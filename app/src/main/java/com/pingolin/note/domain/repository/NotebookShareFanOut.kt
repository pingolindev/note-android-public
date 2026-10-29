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
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ShareNoteRequest
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the web client's notebook auto-fan-out. When a new note lands in
 * a shared notebook, or an existing note moves between notebooks, this
 * helper fans out per-note shares to every recipient on the notebook's
 * standing list.
 *
 * Source of truth for the recipient list is the encrypted metadata item
 * (`item_type=10`) the web client writes. `SyncRepository` pulls it into
 * [SessionManager.getNotebookShares] during sync.
 */
@Singleton
class NotebookShareFanOut @Inject constructor(
    private val noteApi: NoteApi,
    private val noteCrypto: NoteCrypto,
    private val sessionManager: SessionManager,
    private val pinStore: SenderKeyPinStore,
    private val roster: NotebookRoster,
) {
    data class Recipient(
        val email: String,
        val permission: String,
        val publicKey: String?,
        /** Cached recipient user_id (UUID), set when the notebookShares blob
         *  was last filled by a /users/public-keys lookup. Null on legacy
         *  entries → those fan-outs are sent unsigned. */
        val userId: String? = null,
    )

    /**
     * Who a new note in [notebookId] must reach when the author is a MEMBER
     * rather than the owner.
     *
     * The owner has a standing recipient list in their metadata — that is what
     * [recipientsFor] reads. A member has none. What they have is the ROSTER
     * inside the notebook item, which is exactly why the roster is shared at
     * all: a member who cannot read the member list cannot wrap a note for the
     * others, and a note only its author can read is not a contribution to
     * anything.
     *
     * Two things the roster alone gets wrong:
     *  - the OWNER is not in it (it lists who the notebook was shared WITH), so
     *    a member's note would go into someone's notebook that its owner cannot
     *    open. Their key is not in the roster either, so it is looked up.
     *  - YOU are in it. Sealing to yourself is a wasted row and, on the
     *    receiving side, a share from yourself.
     */
    suspend fun rosterRecipientsFor(
        notebookId: String,
        vaultKey: ByteArray,
        ownerEmail: String?,
        myEmail: String?,
    ): List<Recipient> {
        val me = myEmail?.lowercase()
        val out = mutableListOf<Recipient>()
        val seen = mutableSetOf<String>()
        for (m in roster.members(notebookId, vaultKey)) {
            val email = m.email.lowercase()
            if (email == me || !seen.add(email)) continue
            out.add(
                Recipient(
                    email = m.email,
                    // A note share is edit-or-view; `contribute` is a collection
                    // right and on a note row would read as "no write".
                    permission = if (m.permission == "view") "view" else "edit",
                    publicKey = m.sharingPublicKey,
                    userId = m.userId,
                ),
            )
        }
        // The owner comes from the inbox only for a notebook opened through an accepted
        // share, never for one our vault opens: that notebook is ours, with nobody above
        // us, whatever the inbox serves.
        val ours = runCatching { roster.contentKey(notebookId, vaultKey) }.getOrNull()?.also { it.fill(0) } != null
        val ownerAddress = if (ours) null else ownerEmail
        val owner = ownerAddress?.lowercase()
        if (owner != null && owner != me && seen.add(owner)) {
            val entry = runCatching {
                noteApi.lookupPublicKeys(
                    com.pingolin.note.data.remote.dto.PublicKeyLookupRequest(listOf(ownerAddress)),
                ).body()?.data?.users?.firstOrNull()
            }.getOrNull()
            // Dropped rather than sealed to with nothing: a share with no key is
            // a row the recipient can never open, and it would make the note
            // look shared when it is not.
            if (entry?.sharingPublicKey != null) {
                out.add(
                    Recipient(
                        email = ownerAddress,
                        permission = "edit",
                        publicKey = entry.sharingPublicKey,
                        userId = entry.userId,
                    ),
                )
            }
        }
        return out
    }

    fun recipientsFor(notebookId: String?): List<Recipient> {
        if (notebookId == null) return emptyList()
        val json = sessionManager.getNotebookShares() ?: return emptyList()
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray(notebookId) ?: return emptyList()
            List(arr.length()) { idx ->
                val row = arr.getJSONObject(idx)
                Recipient(
                    email = row.optString("email", ""),
                    permission = row.optString("permission", "edit"),
                    publicKey = if (row.has("publicKey") && !row.isNull("publicKey")) {
                        row.getString("publicKey").takeIf { it.isNotBlank() }
                    } else null,
                    userId = if (row.has("userId") && !row.isNull("userId")) {
                        row.getString("userId").takeIf { it.isNotBlank() }
                    } else null,
                )
            }.filter { it.email.isNotBlank() }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to parse notebookShares", e)
            emptyList()
        }
    }

    /** Seal [contentKey] for [recipient] and POST `/items/{noteId}/share`.
     *  [contentKeyGeneration] is the item's CURRENT server content-key
     *  generation: a share-create reuses the current key, so the wrap
     *  signature binds that generation. The driver fetches it once per note
     *  (not per recipient) and threads it in; base 1 when unknown. */
    suspend fun shareToRecipient(
        noteId: String,
        contentKey: ByteArray,
        recipient: Recipient,
        contentKeyGeneration: Long,
    ): Boolean {
        // Send-side pin gate (E2EE): refuse to seal under a directory sharing
        // key that conflicts with this recipient's TOFU pin (server key
        // substitution). We only carry the sharing key on the fan-out path (no
        // signing key), so this DETECTS a mismatch against an existing pin but
        // can't TOFU-create one here — the pin is established at initial share
        // (NotebookShareWriter, which has the signing key) or via the inbox.
        if (recipient.publicKey != null &&
            !pinStore.allowSealToRecipient(recipient.userId, null, recipient.publicKey)
        ) {
            android.util.Log.w(
                TAG,
                // Log the pseudonymous userId, not the email — Log.w survives
                // ProGuard (only d/v/i are stripped).
                "Refusing auto-share to user ${recipient.userId ?: "unknown"}: sharing key differs from pin",
            )
            return false
        }
        // Per-recipient seal + pubkey derive + wrap sign are JNI crypto — this
        // helper is reached from UI-launched (Main) paths (NotebookShareWriter,
        // NoteRepository.createNote's fan-out loop), so keep every JNI hop off
        // Main (parity with ContentKeyRotator). The network POST stays outside.
        val (wrapped, myPubHex, wrapSignature) = withContext(Dispatchers.Default) {
            val sealed = recipient.publicKey?.let {
                try { noteCrypto.sealForRecipient(contentKey, it) } catch (_: Exception) { null }
            }
            val privBytes: ByteArray? = sessionManager.getSharingPrivateKey()?.let { priv ->
                runCatching { android.util.Base64.decode(priv, android.util.Base64.NO_WRAP) }.getOrNull()
            }?.takeIf { it.size == 32 }
            val pubHex = privBytes?.let {
                try { noteCrypto.deriveSharingPublicKey(it) } catch (_: Exception) { null }
            }
            // Sign the wrap with the Ed25519 signing seed, NOT the X25519
            // sharing scalar — a signature made with the sharing scalar never
            // verifies against the directory's signing_public_key. A null here
            // is FATAL to the share (see the fail-closed check after this
            // block), never a fall-through: the server accepts an unsigned
            // wrap, but any recipient already holding a pin for us DROPS it,
            // so sending one manufactures a share that silently never arrives.
            val senderUserId = sessionManager.getUserId()
            val signingSeed: ByteArray? = sessionManager.getSigningPrivateKey()?.let { seed ->
                runCatching { android.util.Base64.decode(seed, android.util.Base64.NO_WRAP) }.getOrNull()
            }?.takeIf { it.size == 32 }
            val sig: String? = if (
                sealed != null
                && signingSeed != null
                && senderUserId != null
                && recipient.userId != null
            ) {
                noteCrypto.signNoteShareWrap(
                    signingSeed = signingSeed,
                    senderUserId = senderUserId,
                    recipientUserId = recipient.userId,
                    itemId = noteId,
                    // A share-create binds the item's CURRENT generation (it
                    // reuses the same content key). coerceIn(1, 2^32-1) so a
                    // Gson-defaulted 0 (or a not-yet-synced note) never signs
                    // under gen 0, AND a server-poisoned negative / >2^32 value
                    // can't wrap through Long.toUInt()'s low-32-bit truncation
                    // back down to 0.
                    contentKeyVersion = contentKeyGeneration.coerceIn(1L, 0xFFFF_FFFFL).toUInt(),
                    wrappedKeyB64 = sealed,
                )
            } else null
            Triple(sealed, pubHex, sig)
        }
        // FAIL CLOSED. We sealed a content key but could not sign the wrap, so
        // the recipient cannot attribute it to us and drops it the moment they
        // hold our pin. Refuse rather than POST a dead letter — the caller
        // reports the failure to the owner. (A recipient with no key yet has
        // nothing sealed to them: that pre-registration invite carries no wrap
        // to sign and is still allowed through.)
        if (wrapped != null && wrapSignature == null) {
            android.util.Log.w(
                TAG,
                "Refusing auto-share to user ${recipient.userId ?: "unknown"}: wrap could not be signed",
            )
            return false
        }
        val resp = try {
            noteApi.shareNote(
                noteId,
                ShareNoteRequest(
                    email = recipient.email,
                    permission = recipient.permission,
                    wrappedKey = wrapped,
                    sharerPublicKey = myPubHex,
                    wrapSignature = wrapSignature,
                ),
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Auto-share call failed: ${e.message}")
            return false
        }
        return resp.isSuccessful
    }

    /** Look up existing shares for (noteId) and delete the one for [email]. */
    suspend fun unshareFromRecipient(noteId: String, email: String): Boolean {
        val list = try { noteApi.listShares(noteId) } catch (_: Exception) { return false }
        if (!list.isSuccessful) return false
        val target = list.body()?.data?.firstOrNull { it.recipientEmail.equals(email, ignoreCase = true) }
            ?: return true // already absent
        val del = try { noteApi.removeShare(target.id) } catch (_: Exception) { return false }
        return del.isSuccessful
    }

    companion object {
        private const val TAG = "NotebookShareFanOut"
    }
}
