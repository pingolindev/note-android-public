/*
 * Copyright (c) 2026 Pingolin LLC
 * This file is part of Pingolin Note.
 *
 * Pingolin Note is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pingolin Note is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pingolin Note. If not, see <https://www.gnu.org/licenses/>.
 */

package com.pingolin.note.domain.repository

import com.pingolin.note.data.remote.api.NoteApi
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which shared notebooks this user has said yes to.
 *
 * Being added to a shared notebook is being enrolled in a place its owner keeps
 * putting notes in — and, with `contribute`, one you can put notes in yourself.
 * That should not happen silently because somebody typed an address, so a
 * shared notebook arrives as an INVITATION and does nothing until accepted.
 *
 * The decision is stored in the user's own encrypted metadata blob under
 * `acceptedNotebooks`, the same key and shape web writes — it is their view of
 * their own account, not the owner's business and not a server column, and
 * keeping it in one place is what stops the same question being asked again on
 * every device.
 */
@Singleton
class NotebookInvites @Inject constructor(
    private val userMetadata: UserMetadataStore,
    private val noteApi: NoteApi,
) {
    companion object {
        /** Must match web's `UserMetadata.acceptedNotebooks`. */
        const val KEY = "acceptedNotebooks"
    }

    /** Notebook ids this user has accepted. */
    suspend fun accepted(): Set<String> {
        val raw = userMetadata.read() ?: return emptySet()
        return runCatching {
            val arr = org.json.JSONObject(raw).optJSONArray(KEY) ?: return emptySet()
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun isAccepted(notebookId: String): Boolean = notebookId in accepted()

    /**
     * Record an acceptance, or forget it again on decline.
     *
     * Forgetting matters: leaving a declined notebook in the list would make a
     * later re-invitation to the same one land already-accepted, with no
     * question asked.
     */
    suspend fun setAccepted(notebookId: String, accepted: Boolean) {
        userMetadata.mutate { meta ->
            val current = meta.optJSONArray(KEY) ?: JSONArray()
            val ids = (0 until current.length())
                .mapNotNull { current.optString(it).takeIf { s -> s.isNotBlank() } }
                .toMutableSet()
            if (accepted) ids.add(notebookId) else ids.remove(notebookId)
            meta.put(KEY, JSONArray(ids.toList()))
        }
    }

    /**
     * Drop acceptances for notebooks that are not shared with us — the owner
     * removed us, or deleted it.
     *
     * Without this a removed-then-re-invited recipient finds the second
     * invitation already accepted.
     *
     * Only the acceptances THIS device read and found unshared are taken off,
     * from the list as it stands when it is written. Writing back a list
     * computed from this device's copy would not do: a write the server refuses
     * as stale (another device had changed the list) is applied again on top of
     * the server's newer list ([UserMetadataStore] `runWrite`), and this
     * device's older list would replace it — erasing an acceptance made on the
     * web in between, so that notebook would be an invitation again on every
     * device.
     */
    suspend fun prune(liveNotebookIds: Set<String>) {
        // Decided OUTSIDE the mutate block (the transform is not a coroutine
        // body, so it cannot re-read the blob), and applied inside it to the
        // blob it is handed.
        val gone = accepted() - liveNotebookIds
        if (gone.isEmpty()) return
        userMetadata.mutate { meta ->
            val current = meta.optJSONArray(KEY) ?: JSONArray()
            val ids = (0 until current.length()).mapNotNull { current.optString(it).takeIf { s -> s.isNotBlank() } }
            meta.put(KEY, JSONArray(ids.filter { it !in gone }))
        }
    }

    // No native decline: the page declines a notebook with POST
    // /api/v1/shares/notebooks/{id}/decline, which the server carries out whole and
    // records as a decline.
}
