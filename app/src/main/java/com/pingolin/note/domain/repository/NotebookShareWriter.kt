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

import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.CreateItemRequest
import com.pingolin.note.data.remote.dto.UpdateItemRequest
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write-side of notebook sharing on Android. Mirrors the web client's
 * share flow when the target is a notebook:
 *   1. Look up the recipient's X25519 public key.
 *   2. Seal the notebook's existing notes' content keys to the recipient
 *      and POST a share row per note.
 *   3. Update the `notebookShares` map in the encrypted user-metadata
 *      item so *future* notes added to the notebook auto-fan-out.
 */
@Singleton
class NotebookShareWriter @Inject constructor(
    private val noteApi: NoteApi,
    private val itemDao: ItemDao,
    private val noteCrypto: NoteCrypto,
    private val sessionManager: SessionManager,
    private val fanOut: NotebookShareFanOut,
    private val rotator: ContentKeyRotator,
    private val pinStore: SenderKeyPinStore,
    private val userMetadata: UserMetadataStore,
    private val roster: NotebookRoster,
) {
    data class ShareOutcome(
        val shared: Int,
        val skipped: Int,
        val failedEmail: String? = null,
        val errorMessage: String? = null,
    )

    /** Share every note in [notebookId] with [email]. Updates
     * notebookShares metadata so future note additions auto-fan-out.
     * Caller provides the notebook display name for metadata consistency
     * if the notebook is Android-native (itemType=2) and missing from the
     * web metadata map. */
    suspend fun shareNotebookWith(
        notebookId: String,
        notebookName: String?,
        email: String,
        permission: String,
    ): ShareOutcome {
        val vaultB64 = sessionManager.getMasterKey()
            ?: return ShareOutcome(0, 0, email, "Not signed in")
        val vaultBytes = try {
            android.util.Base64.decode(vaultB64, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { null }
        if (vaultBytes == null || vaultBytes.size != 32) {
            return ShareOutcome(0, 0, email, "Can't open your notes on this device. Please sign in again.")
        }

        // 1. Pubkey lookup — ok if missing (backend still accepts the share
        //    and queues an invite; recipient just can't decrypt until they
        //    re-provision).
        val lookup = runCatching {
            noteApi.lookupPublicKeys(
                com.pingolin.note.data.remote.dto.PublicKeyLookupRequest(listOf(email)),
            )
        }.getOrNull()
        val lookupEntry = lookup?.body()?.data?.users?.firstOrNull()
        val recipientPubKey = lookupEntry?.sharingPublicKey
        val recipientUserId = lookupEntry?.userId
        val recipientSigningKey = lookupEntry?.signingPublicKey

        // Send-side pin gate (E2EE): TOFU-pin the recipient's directory
        // keys on first share (we hold the signing key here); refuse
        // the whole notebook share if the directory sharing key does
        // not match an existing pin (server key substitution). The
        // per-note fan-out re-checks via the pinned userId below.
        if (recipientPubKey != null &&
            !pinStore.allowSealToRecipient(recipientUserId, recipientSigningKey, recipientPubKey)
        ) {
            return ShareOutcome(0, 0, email, "Confirm it's really $email before sharing.")
        }

        // 2. Fan out to each existing child note.
        val children = itemDao.getNotesInNotebook(notebookId)
        var shared = 0
        var skipped = 0
        for (note in children) {
            val wrappedKey = note.wrappedContentKey
            val wrappedNonce = note.wrappedContentKeyNonce
            if (wrappedKey == null || wrappedNonce == null) {
                // Legacy note — owner must re-save once before it becomes
                // shareable. Skip rather than fail the whole notebook.
                skipped++
                continue
            }
            // JNI crypto — shareNotebookWith is launched from the UI (Main), so
            // keep the vault unwrap off it (parity with ContentKeyRotator).
            val contentKey = withContext(Dispatchers.Default) {
                noteCrypto.unwrapWithVault(wrappedKey, wrappedNonce, vaultBytes)
            }
            if (contentKey == null) { skipped++; continue }
            // A share-create reuses the note's current content key, so the wrap
            // signature binds that key's CURRENT generation. This loop is over
            // NOTES (one recipient each), so fetch per note; base 1 on failure.
            val currentGen = runCatching {
                noteApi.getItem(note.id).body()?.data?.contentKeyGeneration
            }.getOrNull() ?: 1L
            val ok = fanOut.shareToRecipient(
                note.id,
                contentKey,
                NotebookShareFanOut.Recipient(email, permission, recipientPubKey, recipientUserId),
                currentGen,
            )
            if (ok) shared++ else skipped++
        }

        // 3. Share the NOTEBOOK OBJECT itself, and record the recipient in
        //    its roster.
        //
        //    Without this, sharing a notebook would produce only loose notes
        //    in the recipient's "Shared with me": no entry under Shared
        //    Notebooks, nothing to accept or decline, and — because the
        //    roster is what a member reads to seal a NEW note to everyone —
        //    no way for them to contribute to it either.
        //
        //    Roster FIRST: a notebook that is shared but whose roster never
        //    landed is the shape where joining grants nothing.
        //
        //    Non-fatal. The per-note shares above are what make the notes
        //    readable, and failing the whole action because the collection
        //    object did not go through would be the worse outcome.
        run {
            val objectPermission = NotebookRoster.itemSharePermission(permission)
            val added = roster.addMember(
                notebookId,
                vaultBytes,
                NotebookRoster.Member(
                    userId = recipientUserId,
                    email = email,
                    sharingPublicKey = recipientPubKey,
                    signingPublicKey = recipientSigningKey,
                    permission = permission,
                ),
            )
            if (!added) {
                android.util.Log.w("NotebookShareWriter", "Roster for $notebookId not written; sharing the object anyway")
            }
            val key = roster.contentKey(notebookId, vaultBytes)
            if (key == null) {
                // A notebook with no wrapped content key predates per-notebook
                // keys; the upgrade sweep gives it one on a later sync.
                android.util.Log.w("NotebookShareWriter", "Notebook $notebookId has no content key; object not shared")
            } else {
                val gen = runCatching {
                    noteApi.getItem(notebookId).body()?.data?.contentKeyGeneration
                }.getOrNull() ?: 1L
                val ok = fanOut.shareToRecipient(
                    notebookId,
                    key,
                    NotebookShareFanOut.Recipient(
                        email, objectPermission, recipientPubKey, recipientUserId,
                    ),
                    gen,
                )
                if (!ok) android.util.Log.w("NotebookShareWriter", "Notebook object $notebookId was not shared")
            }
        }

        // 4. Update the notebookShares JSON in SessionManager + push the
        //    metadata item to the server so other devices + the web pick
        //    it up on their next sync. Persist the recipient's user_id so
        //    auto-fan-out to future notes can sign the wrap + pass the pin gate.
        updateNotebookSharesMetadata(notebookId, notebookName, email, permission, recipientPubKey, recipientUserId)

        return ShareOutcome(shared, skipped, failedEmail = null)
    }

    /** Remove [email] from every share row in [notebookId] and strip it
     *  from the notebookShares metadata. */
    suspend fun unshareNotebookFrom(notebookId: String, email: String): ShareOutcome {
        val children = itemDao.getNotesInNotebook(notebookId)
        var removed = 0
        var skipped = 0
        for (note in children) {
            val ok = fanOut.unshareFromRecipient(note.id, email)
            if (ok) {
                removed++
                // Rotate the content key on every note the recipient lost
                // access to. Without rotation they'd still decrypt future
                // edits since the old content key was already in their
                // hands. Best-effort: a rotation failure just means the
                // removed user might read one more future edit.
                val rot = rotator.rotate(note.id)
                if (!rot.rotated) android.util.Log.w(
                    "NotebookShareWriter",
                    "Rotation after unshare failed for ${note.id}",
                )
            } else {
                skipped++
            }
        }
        // The NOTEBOOK OBJECT, which the per-note loop above never touches.
        //
        // Two things, and removal is not removal without both. Drop them from
        // the ROSTER — it is what every other member reads to seal a new note
        // to everyone, so leaving them in it means the next note anyone writes
        // is sealed straight back to them. And revoke their share on the
        // notebook itself, or they keep receiving its name, its member list and
        // every later update to it.
        //
        // Best-effort and reported, never fatal: the per-note revocations above
        // are what actually cut off the content.
        val vaultBytes = sessionManager.getMasterKey()?.let { b64 ->
            runCatching { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) }
                .getOrNull()?.takeIf { it.size == 32 }
        }
        if (vaultBytes != null) {
            if (!roster.removeMember(notebookId, vaultBytes, email)) {
                android.util.Log.w(
                    "NotebookShareWriter",
                    "Roster for $notebookId still lists $email; other members may re-share to them",
                )
            }
        }
        if (!fanOut.unshareFromRecipient(notebookId, email)) {
            android.util.Log.w(
                "NotebookShareWriter",
                "Notebook object $notebookId is still shared with $email",
            )
        }
        // Rotate the notebook's own key: they held the one that opens
        // its name and roster, so every later roster update would
        // otherwise be written under a key they have. Once removed
        // they cannot FETCH it, which is what stops them; this is the
        // defence-in-depth behind that.
        val rot = rotator.rotate(notebookId)
        if (!rot.rotated) android.util.Log.w(
            "NotebookShareWriter",
            "Rotation after unshare failed for notebook $notebookId",
        )

        updateNotebookSharesMetadata(notebookId, null, email, null, null, remove = true)
        return ShareOutcome(removed, skipped)
    }

    /** Tear down EVERY share on [notebookId]'s notes with forward secrecy —
     *  used when a shared notebook is deleted. Each child moves out to
     *  personal, so its standing per-note shares must be revoked AND the
     *  content key rotated; a delete-only revoke would leave every former
     *  recipient's cached inbox key decrypting all future edits. One
     *  rotation per note: all of its share rows are passed as
     *  removeShareIds, so rotate() deletes them and re-keys with no
     *  survivors to re-wrap. Best-effort + offline-tolerant (a failed
     *  rotation just risks a former recipient reading one more edit); the
     *  caller proceeds with the local delete regardless. */
    suspend fun unshareNotebookFromAll(notebookId: String) {
        val children = itemDao.getNotesInNotebook(notebookId)
        for (note in children) {
            val shares = runCatching { noteApi.listShares(note.id) }
                .getOrNull()?.body()?.data ?: continue
            if (shares.isEmpty()) continue
            val rot = rotator.rotate(note.id, removeShareIds = shares.map { it.id })
            if (!rot.rotated) android.util.Log.w(
                "NotebookShareWriter",
                "Rotation on notebook-delete unshare failed for ${note.id}",
            )
        }
    }

    /**
     * Add (or rename) an Android-native notebook (item_type=2) in the
     * web-format user-metadata item (item_type=10) so the web client lists
     * it. Android keeps notebooks as their own items, but the web only reads
     * this metadata blob — without this, Android-created notebooks never
     * appear on the web. Best-effort.
     */
    suspend fun upsertNotebookInMetadata(notebookId: String, name: String) {
        mutateMetadata { root ->
            val arr = root.optJSONArray("notebooks") ?: JSONArray().also { root.put("notebooks", it) }
            var found = false
            for (i in 0 until arr.length()) {
                val nb = arr.getJSONObject(i)
                if (nb.optString("id") == notebookId) { nb.put("name", name); found = true; break }
            }
            if (!found) arr.put(JSONObject().apply { put("id", notebookId); put("name", name) })
        }
    }

    /** Remove a notebook (and any standing shares) from the web-format
     *  metadata. Called when an Android notebook is deleted. Best-effort. */
    suspend fun removeNotebookFromMetadata(notebookId: String) {
        mutateMetadata { root ->
            root.optJSONArray("notebooks")?.let { arr ->
                val kept = JSONArray()
                for (i in 0 until arr.length()) {
                    val nb = arr.getJSONObject(i)
                    if (nb.optString("id") != notebookId) kept.put(nb)
                }
                root.put("notebooks", kept)
            }
            root.optJSONObject("notebookShares")?.remove(notebookId)
        }
    }

    /**
     * Update the per-notebook recipient list in the metadata (fan-out
     * source of truth), and ensure the notebook is named in `notebooks`.
     */
    private suspend fun updateNotebookSharesMetadata(
        notebookId: String,
        notebookName: String?,
        email: String,
        permission: String?,
        publicKey: String?,
        userId: String? = null,
        remove: Boolean = false,
    ) {
        mutateMetadata { root ->
            val shares = root.optJSONObject("notebookShares") ?: JSONObject()
            val listForNotebook = shares.optJSONArray(notebookId) ?: JSONArray()
            val emailLower = email.lowercase()

            val filtered = JSONArray()
            for (i in 0 until listForNotebook.length()) {
                val rowItem = listForNotebook.getJSONObject(i)
                if (rowItem.optString("email", "").lowercase() != emailLower) filtered.put(rowItem)
            }
            if (!remove && permission != null) {
                filtered.put(JSONObject().apply {
                    put("email", email)
                    put("permission", permission)
                    if (publicKey != null) put("publicKey", publicKey)
                    // Persist the recipient's user_id (same field the web client
                    // writes) so auto-fan-out to future notes can bind
                    // sender+recipient ids into the wrap signature AND pass the
                    // send-side pin gate (keyed by userId). Without it, fan-out
                    // sends unsigned wraps that the receiver drops.
                    if (userId != null) put("userId", userId)
                })
            }
            if (filtered.length() == 0) shares.remove(notebookId) else shares.put(notebookId, filtered)
            root.put("notebookShares", shares)

            // Ensure the notebook is named in root.notebooks too (web render).
            if (notebookName != null) {
                val arr = root.optJSONArray("notebooks") ?: JSONArray().also { root.put("notebooks", it) }
                var found = false
                for (i in 0 until arr.length()) {
                    if (arr.getJSONObject(i).optString("id") == notebookId) { found = true; break }
                }
                if (!found) arr.put(JSONObject().apply { put("id", notebookId); put("name", notebookName) })
            }
        }
    }

    /** The one read-modify-write for the shared user-metadata blob; see
     *  [UserMetadataStore]. Kept as a private shim so the transforms above read
     *  unchanged. */
    private suspend fun mutateMetadata(transform: (JSONObject) -> Unit) =
        userMetadata.mutate(transform)
}
