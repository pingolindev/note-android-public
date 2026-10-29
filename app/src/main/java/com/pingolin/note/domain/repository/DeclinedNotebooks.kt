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

import com.pingolin.note.util.AccountIds
import java.time.Instant

/**
 * Which shared notes belong to a notebook this account has DECLINED, and are kept away.
 *
 * The rule: "If you decline a share, nothing should show up." The server keeps a declined
 * notebook's later notes away itself when the note's row says which notebook it is in. One whose
 * row does not say arrives like any other share, and only its decrypted content names the
 * notebook. This is the same rule the server applies, by the same two facts, for that one: the
 * notebook was declined, and the share is LATER than the decline. A note shared with the reader
 * before they declined was theirs in its own right, and stays.
 *
 * The web page applies it too, and dismisses such a share on the server (the web editor's
 * inboxPage.ts, `declinedOnTheServersWord` and `sharesOfDeclinedNotebooks`). This native side
 * only keeps the note out of the database the widgets read: nothing here is sent anywhere, and a
 * refresh that does not find the notebook declined brings the note back.
 *
 * Pure, so each rule has a test (DeclinedNotebooksTest).
 */
object DeclinedNotebooks {
    /**
     * The notebooks to treat as declined on the server's word: canonical id → since when.
     *
     * @param said      the first page's `declined_notebooks` (id → time, both as the server wrote
     *                  them), or null when this answer does not say. Then what an earlier answer
     *                  said is kept: null is "not told", never "none".
     * @param offered   the notebooks this answer lists a share of, canonical. One of those is left
     *                  out whatever the declined list says: an owner who removed the reader and
     *                  invited them again leaves, for a moment, a list that can still name the
     *                  notebook beside a row that offers it. The row is the newer fact, and a
     *                  notebook on offer is a question the reader has not answered.
     * @param previous  what an earlier answer said
     */
    fun onTheServersWord(
        said: Map<String, String>?,
        offered: Set<String>,
        previous: Map<String, Instant>,
    ): Map<String, Instant> {
        val declined = if (said == null) previous else buildMap {
            for ((id, at) in said) {
                // An id that is no UUID names no notebook; a time that does not parse would
                // compare as nothing at all, so the entry says nothing usable.
                val notebook = AccountIds.canonical(id) ?: continue
                val since = runCatching { Instant.parse(at) }.getOrNull() ?: continue
                put(notebook, since)
            }
        }
        return declined.filterKeys { it !in offered }
    }

    /**
     * Is a share of a note that says it belongs to [notebookId], made at [sharedAt], kept away?
     *
     * @param notebookId what the note's decrypted content names, in any spelling, or null
     * @param sharedAt   the share's `created_at` as the server wrote it
     */
    fun keepsAway(declined: Map<String, Instant>, notebookId: String?, sharedAt: String?): Boolean {
        if (declined.isEmpty() || notebookId == null || sharedAt == null) return false
        val since = AccountIds.canonical(notebookId)?.let { declined[it] } ?: return false
        // A time that cannot be read decides nothing: the share is shown rather than taken from a
        // reader who may hold it in its own right.
        val shared = runCatching { Instant.parse(sharedAt) }.getOrNull() ?: return false
        return shared.isAfter(since)
    }
}
