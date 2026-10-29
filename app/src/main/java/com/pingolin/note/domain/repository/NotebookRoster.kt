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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.UpdateItemRequest
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The member list inside a shared notebook.
 *
 * ## Why the roster is IN the notebook, not in the owner's metadata
 *
 * A member who cannot read the member list cannot wrap a new note for the
 * others, and a note only its author can read is not a contribution to
 * anything. So the roster travels with the notebook object, sealed to every
 * member — that is the whole reason the notebook is shared as an ITEM rather
 * than existing only as a name in the owner's metadata blob.
 *
 * ## Why this writes JSON rather than a data class
 *
 * The notebook document is written by three clients on different release
 * trains, and every write replaces the whole thing. Re-encoding it from a
 * fixed-shape Kotlin object would silently delete any field this version does
 * not model (such as `edit_mode` and `color`). Merging at the JSON level
 * changes `members` and leaves everything else byte-identical.
 */
@Singleton
class NotebookRoster @Inject constructor(
    private val noteApi: NoteApi,
    private val itemDao: ItemDao,
    private val noteCrypto: NoteCrypto,
) {
    data class Member(
        val userId: String?,
        val email: String,
        val sharingPublicKey: String?,
        val signingPublicKey: String?,
        /** `view`, `edit` or `contribute` — what this member may do. */
        val permission: String,
    )

    /** The notebook's content key, or null if this device cannot open it. */
    suspend fun contentKey(notebookId: String, vaultKey: ByteArray): ByteArray? =
        itemDao.getById(notebookId)?.let { contentKeyFor(it, vaultKey) }

    /** Everyone currently listed in [notebookId]'s roster. */
    suspend fun members(notebookId: String, vaultKey: ByteArray): List<Member> {
        val doc = decryptedDocument(notebookId, vaultKey) ?: return emptyList()
        return membersFrom(doc)
    }

    /**
     * Add or replace a member, then write the notebook back under the SAME
     * content key.
     *
     * The key is reused, never rotated: members hold the current one and adding
     * somebody is not a reason to lock the others out.
     *
     * Replace-not-append, so re-sharing to change a permission does not leave
     * two entries for one person with the last one silently winning.
     */
    suspend fun addMember(notebookId: String, vaultKey: ByteArray, member: Member): Boolean {
        val nb = itemDao.getById(notebookId) ?: return false
        val doc = decryptedDocument(notebookId, vaultKey) ?: return false
        val key = contentKeyFor(nb, vaultKey) ?: return false

        val kept = membersFrom(doc).filterNot { it.email.equals(member.email, ignoreCase = true) }
        return write(notebookId, nb.version, doc, kept + member, key)
    }

    /**
     * Drop a member.
     *
     * The roster is what every OTHER member reads to seal a new note to
     * everyone; leaving a removed person in it means the next note anyone
     * writes is sealed straight back to them.
     */
    suspend fun removeMember(notebookId: String, vaultKey: ByteArray, email: String): Boolean {
        val nb = itemDao.getById(notebookId) ?: return false
        val doc = decryptedDocument(notebookId, vaultKey) ?: return false
        val key = contentKeyFor(nb, vaultKey) ?: return false

        val kept = membersFrom(doc).filterNot { it.email.equals(email, ignoreCase = true) }
        if (kept.size == membersFrom(doc).size) return true // already absent
        return write(notebookId, nb.version, doc, kept, key)
    }

    private suspend fun contentKeyFor(
        nb: com.pingolin.note.data.local.entity.ItemEntity,
        vaultKey: ByteArray,
    ): ByteArray? {
        val wrap = nb.wrappedContentKey ?: return null
        val nonce = nb.wrappedContentKeyNonce ?: return null
        // Off Main: reached from shareNotebookWith, which the UI
        // launches on its lifecycleScope.
        return withContext(Dispatchers.Default) { noteCrypto.unwrapWithVault(wrap, nonce, vaultKey) }
    }

    private suspend fun decryptedDocument(notebookId: String, vaultKey: ByteArray): JSONObject? {
        val nb = itemDao.getById(notebookId) ?: return null
        val key = contentKeyFor(nb, vaultKey) ?: return null
        val plain = withContext(Dispatchers.Default) {
            noteCrypto.decryptNoteWithContentKeyV2(
                nb.contentNonce, nb.encryptedContent, key, notebookId,
            )
        } ?: return null
        return runCatching { JSONObject(plain) }.getOrNull()
    }

    private suspend fun write(
        notebookId: String,
        version: Long,
        doc: JSONObject,
        members: List<Member>,
        contentKey: ByteArray,
    ): Boolean {
        val arr = JSONArray()
        for (m in members) {
            arr.put(
                JSONObject().apply {
                    put("userId", m.userId ?: JSONObject.NULL)
                    put("email", m.email)
                    put("sharingPublicKey", m.sharingPublicKey ?: JSONObject.NULL)
                    put("signingPublicKey", m.signingPublicKey ?: JSONObject.NULL)
                    put("permission", m.permission)
                },
            )
        }
        // Merge, not rebuild: everything this version does not model stays.
        doc.put("members", arr)

        val enc = withContext(Dispatchers.Default) {
            noteCrypto.encryptNoteWithContentKeyV2(doc.toString(), contentKey, notebookId)
        }
        val resp = runCatching {
            noteApi.updateItem(
                notebookId,
                UpdateItemRequest(
                    encryptedContent = enc.ciphertextB64,
                    contentNonce = enc.nonceB64,
                    expectedVersion = version,
                ),
            )
        }.getOrNull() ?: return false
        if (!resp.isSuccessful) {
            android.util.Log.w(
                "NotebookRoster",
                "Roster write for $notebookId failed (${resp.code()})",
            )
            return false
        }
        val bumped = resp.body()?.data?.version ?: (version + 1)
        itemDao.getById(notebookId)?.let { current ->
            itemDao.upsert(
                current.copy(
                    encryptedContent = enc.ciphertextB64,
                    contentNonce = enc.nonceB64,
                    decryptedContent = doc.toString(),
                    version = bumped,
                    isDirty = false,
                ),
            )
        }
        return true
    }

    companion object {
        /**
         * What the NOTEBOOK ITEM itself is shared with — `view`, or `contribute`
         * for a member who may add and remove notes.
         *
         * **Never `edit`.** The server grants write access on the item to
         * `permission = 'edit'` exactly, so an edit-share on the notebook item
         * would let the recipient rewrite the ROSTER and invite whoever they
         * liked. A member's ability to edit note CONTENT rides on the note
         * shares instead.
         */
        fun itemSharePermission(permission: String): String =
            if (permission == "contribute") "contribute" else "view"

        /** Members recorded in a decrypted notebook document. Tolerant: this
         *  parses a document another client wrote, possibly a newer one. */
        fun membersFrom(doc: JSONObject): List<Member> {
            val arr = doc.optJSONArray("members") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val email = o.optString("email").ifBlank { return@mapNotNull null }
                Member(
                    userId = o.optString("userId").ifBlank { null },
                    email = email,
                    // Filtered rather than trusted: a member with no sharing key
                    // cannot be sealed to, and keeping them would make "shared
                    // with N people" a claim the crypto cannot honour.
                    sharingPublicKey = o.optString("sharingPublicKey").ifBlank { null },
                    signingPublicKey = o.optString("signingPublicKey").ifBlank { null },
                    permission = o.optString("permission").ifBlank { "view" },
                )
            }.filter { it.sharingPublicKey != null }
        }
    }
}
