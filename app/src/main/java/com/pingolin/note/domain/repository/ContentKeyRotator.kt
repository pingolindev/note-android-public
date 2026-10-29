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

import com.pingolin.note.collab.AttachmentBlobFormat
import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.collab.NotePayload
import com.pingolin.note.collab.WrappedKeyPair
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.PublicKeyLookupRequest
import com.pingolin.note.data.remote.dto.PresignUploadRequest
import com.pingolin.note.data.remote.dto.RekeyAttachmentRequest
import com.pingolin.note.data.remote.dto.RotateShareEntry
import com.pingolin.note.data.remote.dto.RotateSharesRequest
import com.pingolin.note.util.RetainedContentKeyStore
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import javax.inject.Inject
import javax.inject.Singleton

/** Per-rotation ceilings on the INLINE attachment re-key. A rotation is driven
 * from a Main dispatcher coroutine, so an unbounded re-encrypt+upload of every
 * blob on a note with many/large attachments would stall the user's action.
 * Anything skipped past these caps stays decryptable for the OWNER via the
 * retained old content key ([RetainedContentKeyStore]); only cross-recipient
 * forward access to those specific blobs is left out of that rotation. */
private const val MAX_REKEY_ATTACHMENTS = 20
private const val MAX_REKEY_TOTAL_BYTES = 50L * 1024 * 1024
private const val MAX_REKEY_ATTACHMENT_BYTES = 26_214_400L // 25 MiB, matches the editor's per-file cap

/**
 * Content-key rotation. After a share is revoked, the removed recipient still
 * holds the *old* per-note content key; rotating to a fresh key means every
 * ciphertext written from here on is opaque to them, while remaining recipients
 * transparently follow along via an upserted `wrapped_key`.
 *
 * Steps:
 *   1. Load the note and unwrap its current content key with the vault.
 *   2. Decrypt the server ciphertext with the old key.
 *   3. Generate a fresh content key, re-encrypt the plaintext.
 *   4. Wrap the new key under the owner's vault.
 *   5. List remaining share rows, look up each recipient's X25519 pubkey and
 *      re-seal the new key per recipient.
 *   6. POST /items/{id}/rotate-shares with the new ciphertext, the new
 *      wrapped_content_key, the share deletions and the re-sealed wraps — one
 *      server transaction. rotate-shares is the only route that may REPLACE an
 *      existing owner wrap; a plain PUT /items/{id} that tries is refused.
 *
 * ATTACHMENTS (forward secrecy): rotation re-encrypts the note BODY and also
 * re-keys the attachment blobs onto the new content key — after the rotate-shares
 * commit, each blob is downloaded, decrypted with the old key, re-encrypted under
 * the new key, uploaded to a fresh presigned object, and the row repointed +
 * metadata swapped via `PUT /attachments/{id}`. This is best-effort and bounded
 * (see [MAX_REKEY_ATTACHMENTS]/[MAX_REKEY_TOTAL_BYTES]); the retired vault-wrapped
 * content key is STILL stashed in [RetainedContentKeyStore] as the owner's
 * fallback for any blob not re-keyed — a removed recipient never held that vault
 * wrap, so retaining it costs them no forward access. The re-key probes
 * decrypt-under-new-key first, so it is idempotent across interrupted runs.
 */
@Singleton
class ContentKeyRotator @Inject constructor(
    private val noteApi: NoteApi,
    private val itemDao: ItemDao,
    private val noteCrypto: NoteCrypto,
    private val sessionManager: SessionManager,
    private val pinStore: SenderKeyPinStore,
    private val retainedContentKeyStore: RetainedContentKeyStore,
    private val okHttpClient: OkHttpClient,
) {
    data class RotationResult(
        val rotated: Boolean,
        val remainingCount: Int,
        val reWrapFailures: List<String>,
        /** True when the backend also dropped a public share_link row
         *  as part of the atomic transaction. Lets the editor surface
         *  "your public link was revoked" to the owner. */
        val revokedShareLink: Boolean = false,
        /** Server-bumped item version after a successful rotate, or null when
         *  the rotation didn't commit. Lets a caller that layers its own item
         *  upsert on top (NoteRepository's re-mint branch) adopt the authoritative
         *  post-rotate version instead of guessing existing.version + 1. */
        val newVersion: Long? = null,
        /** True when the LOCAL row was actually re-written with the rotated body
         *  and wrap. False when the server re-key committed but the local
         *  compare-and-swap was skipped because the row had moved on. A caller
         *  that layers its own CAS-guarded write on top needs this to know which
         *  ciphertext the row now holds — guessing costs it its own write. */
        val localApplied: Boolean = false,
    )

    /**
     * Rotate [noteId]'s content key and optionally revoke share rows in
     * [removeShareIds]. Single-transaction server call via the
     * `/rotate-shares` endpoint — delete + rewrap + ciphertext swap land
     * together so concurrent revokes can't leave stale wraps.
     */
    suspend fun rotate(noteId: String, removeShareIds: List<String> = emptyList()): RotationResult {
        val failures = mutableListOf<String>()
        val note = itemDao.getById(noteId) ?: return RotationResult(false, 0, failures)
        val wrappedKey = note.wrappedContentKey
        val wrappedNonce = note.wrappedContentKeyNonce
        if (wrappedKey == null || wrappedNonce == null) {
            return RotationResult(false, 0, failures)
        }
        val vaultB64 = sessionManager.getMasterKey() ?: return RotationResult(false, 0, failures)
        val vaultBytes = try {
            android.util.Base64.decode(vaultB64, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { return RotationResult(false, 0, failures) }
        if (vaultBytes.size != 32) return RotationResult(false, 0, failures)

        // JNI crypto is CPU-bound; callers (notebook delete/unshare, looped per
        // child note) drive rotate() from Main, so keep every JNI hop off it.
        // Network calls (getItem/listShares/lookupPublicKeys) stay outside these
        // blocks on the caller's dispatcher.
        val oldKey = withContext(Dispatchers.Default) {
            noteCrypto.unwrapWithVault(wrappedKey, wrappedNonce, vaultBytes)
        } ?: return RotationResult(false, 0, failures)

        // Pull the fresh server copy so we rotate on the latest version.
        val serverResp = runCatching { noteApi.getItem(noteId) }.getOrNull()
        val serverItem = serverResp?.body()?.data ?: return RotationResult(false, 0, failures)

        // Decrypt with the old key, mint a fresh key, re-encrypt + re-wrap.
        val prepared = withContext(Dispatchers.Default) {
            val plaintext = noteCrypto.decryptNoteWithContentKeyV2(
                serverItem.contentNonce,
                serverItem.encryptedContent,
                oldKey,
                noteId,
            ) ?: return@withContext null
            val freshKey = noteCrypto.generateContentKey()
            Prepared(
                key = freshKey,
                ciphertext = noteCrypto.encryptNoteWithContentKeyV2(plaintext, freshKey, noteId),
                wrapped = noteCrypto.wrapWithVault(freshKey, vaultBytes),
                plaintext = plaintext,
            )
        } ?: return RotationResult(false, 0, failures)

        // Hand off to the shared re-key path: it re-seals the survivors onto the
        // new key, swaps the ciphertext/wrap atomically, and — because oldKey is
        // available here (we just unwrapped it) — forward-secrecy re-keys the
        // attachment blobs too.
        //
        // The plaintext travels with the ciphertext: it came from the SERVER copy
        // (decrypted above), while the local write-back's compare-and-swap matches
        // on the LOCAL ciphertext. On a clean-but-behind row those are two
        // different documents, so persisting only the re-keyed ciphertext would
        // leave encrypted_content and decrypted_content describing different
        // notes.
        return rotateWithKey(
            noteId = noteId,
            newKey = prepared.key,
            newWrapped = prepared.wrapped,
            newCiphertext = prepared.ciphertext,
            newPlaintext = prepared.plaintext,
            newTitle = titleOf(prepared.plaintext),
            oldKey = oldKey,
            expectedVersion = note.version,
            removeShareIds = removeShareIds,
        )
    }

    /** Freshly re-keyed material for one rotation: the new content key, the body
     *  re-encrypted under it, its vault wrap, and the plaintext that was
     *  re-encrypted (needed so the local row's cipher and plain halves stay in
     *  agreement — see [ItemDao.writeRotatedItemIfUnchanged]). */
    private data class Prepared(
        val key: ByteArray,
        val ciphertext: NotePayload,
        val wrapped: WrappedKeyPair,
        val plaintext: String,
    )

    /** Title field of a note-content JSON document, or null when absent/blank
     *  or the document isn't parseable. Mirrors NoteRepository.titleOf. */
    private fun titleOf(json: String): String? = try {
        org.json.JSONObject(json).optString("title", "").takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    /**
     * Rotate [noteId] onto an ALREADY-MINTED [newKey] — with its [newWrapped]
     * vault wrap and the note body already re-encrypted as [newCiphertext]. Re-
     * seals every surviving share recipient onto [newKey] via the atomic
     * `/rotate-shares` endpoint, then (when [oldKey] != null) forward-secrecy
     * re-keys the note's attachment blobs from [oldKey] to [newKey].
     *
     * Two callers:
     *  - [rotate] (unshare / notebook-move): passes the vault-unwrapped current
     *    content key as [oldKey], so attachments re-key too.
     *  - [NoteRepository]'s corrupt-wrap RE-MINT branch: the old content key is
     *    unrecoverable (its vault wrap won't unwrap), so it passes oldKey = null
     *    — survivors still follow the fresh BODY key (the point of calling this
     *    instead of a plain item PUT), but the old-key-sealed attachment blobs
     *    genuinely can't be re-encrypted (an honest limit; the retained wrap is
     *    equally useless there, since it's the same corrupt wrap).
     */
    suspend fun rotateWithKey(
        noteId: String,
        newKey: ByteArray,
        newWrapped: WrappedKeyPair,
        newCiphertext: NotePayload,
        newPlaintext: String,
        newTitle: String?,
        oldKey: ByteArray?,
        expectedVersion: Long,
        removeShareIds: List<String> = emptyList(),
    ): RotationResult {
        val failures = mutableListOf<String>()
        val note = itemDao.getById(noteId) ?: return RotationResult(false, 0, failures)

        // Current (pre-rotation) content-key generation — the authoritative base
        // for the wrap signature (rotate-shares bumps it by exactly +1). Re-read
        // from the server; if it's unreachable, default 0 (→ sign under gen 1,
        // matching the recipient's unwrap_or(1)) and let the rotate PUT below be
        // the call that actually fails when the server is down.
        val serverGen = runCatching {
            noteApi.getItem(noteId).body()?.data?.contentKeyGeneration
        }.getOrNull() ?: 0L

        // Build the rewraps for every share that survives the rotation.
        // Missing pubkeys are recorded as failures and excluded — the
        // backend endpoint requires a wrapped_key per remaining recipient.
        val sharesResp = runCatching { noteApi.listShares(noteId) }.getOrNull()
        val all = sharesResp?.body()?.data ?: emptyList()
        val removeSet = removeShareIds.toSet()
        // Only people who HOLD the key are given the new one. A row with no key is
        // someone invited before they had an account, and their invitation is finished
        // by its own road, notebook first. Re-sealed here, the first re-key after they
        // signed up would hand them the note by itself, with no invitation, to whatever
        // account the directory named for the address at that moment (the server
        // refuses that too). Their row stays as it is, and that is not a failure of
        // this re-key.
        val remaining = all.filter { it.id !in removeSet && it.hasWrap != false }
        val updateShares = mutableListOf<RotateShareEntry>()
        if (remaining.isNotEmpty()) {
            val lookup = runCatching {
                noteApi.lookupPublicKeys(PublicKeyLookupRequest(remaining.map { it.recipientEmail }))
            }.getOrNull()
            val pubByEmail = lookup?.body()?.data?.users
                ?.filter { it.sharingPublicKey != null }
                ?.associate { it.email.lowercase() to it.sharingPublicKey!! }
                ?: emptyMap()

            // Index the lookup by email → (sharingPub, userId, signingPub) so
            // sealing and signing both come out of the same fan-out call.
            data class RecipientKeys(val sharingPub: String, val userId: String?, val signingPub: String?)
            val recipientByEmail: Map<String, RecipientKeys> = lookup?.body()?.data?.users
                ?.filter { it.sharingPublicKey != null }
                ?.associate { entry ->
                    entry.email.lowercase() to RecipientKeys(
                        sharingPub = entry.sharingPublicKey!!,
                        userId = entry.userId,
                        signingPub = entry.signingPublicKey,
                    )
                } ?: emptyMap()

            // Sign rotated wraps with the REAL Ed25519 signing seed, loaded at
            // login from encrypted_signing_private_key. It must NOT be the
            // X25519 sharing scalar: that is a different curve and never
            // verifies against the directory's signing_public_key. Null
            // (→ unsigned) until the seed is provisioned.
            val signingSeed: ByteArray? = sessionManager.getSigningPrivateKey()
                ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
                ?.takeIf { it.size == 32 }
            val senderUserId: String? = sessionManager.getUserId()

            // Per-recipient seal + sign are JNI crypto — run the fan-out off the
            // caller's dispatcher and merge the results back afterwards.
            val sealResults = withContext(Dispatchers.Default) {
                val entries = mutableListOf<RotateShareEntry>()
                val failed = mutableListOf<String>()
                for (s in remaining) {
                    val keys = recipientByEmail[s.recipientEmail.lowercase()]
                    // Send-side pin gate (E2EE): never re-seal the rotated key
                    // under a directory sharing key that conflicts with this
                    // recipient's TOFU pin — a server key substitution is worst
                    // at rotation, where every survivor is re-wrapped. Refuse
                    // (record as a failure, don't seal); TOFU-pin on first use.
                    if (keys != null &&
                        !pinStore.allowSealToRecipient(keys.userId, keys.signingPub, keys.sharingPub)
                    ) {
                        android.util.Log.w(
                            "ContentKeyRotator",
                            // Log the pseudonymous userId, not the email — Log.w
                            // survives ProGuard (only d/v/i are stripped).
                            "Refusing rewrap to user ${keys.userId ?: "unknown"}: sharing key differs from pin",
                        )
                        failed += s.recipientEmail
                        continue
                    }
                    val sealed = keys?.sharingPub?.let {
                        try { noteCrypto.sealForRecipient(newKey, it) } catch (_: Exception) { null }
                    }
                    if (sealed != null) {
                        val sig: String? = if (
                            signingSeed != null
                            && senderUserId != null
                            && keys.userId != null
                        ) {
                            noteCrypto.signNoteShareWrap(
                                signingSeed = signingSeed,
                                senderUserId = senderUserId,
                                recipientUserId = keys.userId,
                                itemId = noteId,
                                // A rotate signs the re-sealed wraps under
                                // CURRENT + 1: the server bumps
                                // content_key_generation by exactly 1 in
                                // rotate-shares (the ONLY bump path), so that is
                                // the generation the recipient will read them at.
                                // serverGen is the FRESH pre-rotation copy's
                                // generation, so it is authoritative. A Gson-
                                // defaulted or unreachable-server 0 becomes 1,
                                // matching the recipient's unwrap_or(1).
                                // Clamp the server-supplied base BEFORE +1 and
                                // the UInt cast: Kotlin's Long.toUInt() keeps only
                                // the low 32 bits, so a poisoned -1 or 2^32 would
                                // wrap to gen 0 and make us sign the newest wrap
                                // under effective gen 0 (which the reader exempts
                                // from the rollback floor). 0 is a legitimate base
                                // for a legacy signer, so floor at 0; cap at
                                // 2^32-2 so the +1 still fits in u32.
                                contentKeyVersion =
                                    (serverGen.coerceIn(0L, 0xFFFF_FFFEL) + 1L).toUInt(),
                                wrappedKeyB64 = sealed,
                            )
                        } else null
                        if (sig == null) {
                            // FAIL CLOSED: an unsigned rewrap silently cuts this
                            // recipient off at the next rotation — they have
                            // shared with us before, so they hold our pin and
                            // drop anything unsigned. Report it as a re-wrap
                            // failure so the owner is told to fix their keys and
                            // re-share, instead of rotating them into the dark.
                            android.util.Log.w(
                                "ContentKeyRotator",
                                "Refusing unsigned rewrap for share ${s.id} during rotation",
                            )
                            failed += s.recipientEmail
                        } else {
                            entries += RotateShareEntry(
                                shareId = s.id,
                                wrappedKey = sealed,
                                wrapSignature = sig,
                            )
                        }
                    } else {
                        failed += s.recipientEmail
                    }
                }
                entries to failed
            }
            updateShares += sealResults.first
            failures += sealResults.second
        }

        // Availability: retain the RETIRED vault-wrapped content key BEFORE the
        // rotate PUT so a process death can't orphan this note's pre-rotation
        // attachments while the inline re-key below hasn't run/finished. The blobs
        // are re-encrypted onto the new key AFTER the commit (rekeyAttachments), so
        // until that lands the OWNER's decrypt still needs to fall back to the old
        // wrap; a removed recipient never held this vault wrap, so retaining it
        // grants them no forward access. If we retained only AFTER a PUT success, a
        // crash in that window would leave the server holding the new wrap while the
        // old one was never stashed → unrecoverable blobs. retain is idempotent +
        // deduped on (wrap, nonce) per RetainedContentKeyStore, so a retain that
        // precedes a PUT that then fails (or that the re-mint path already made) is a
        // harmless no-op. Uses the note row's CURRENT wrap — the OLD one, since the
        // local upsert to newWrapped happens below.
        val oldWrappedKey = note.wrappedContentKey
        val oldWrappedNonce = note.wrappedContentKeyNonce
        if (oldWrappedKey != null && oldWrappedNonce != null) {
            retainedContentKeyStore.retain(noteId, oldWrappedKey, oldWrappedNonce)
        }

        val put = runCatching {
            noteApi.rotateShares(
                noteId,
                RotateSharesRequest(
                    encryptedContent = newCiphertext.ciphertextB64,
                    contentNonce = newCiphertext.nonceB64,
                    wrappedContentKey = newWrapped.wrappedKeyB64,
                    wrappedContentKeyNonce = newWrapped.nonceB64,
                    expectedVersion = expectedVersion,
                    removeShareIds = removeShareIds,
                    updateShares = updateShares,
                ),
            )
        }.getOrNull()
        if (put == null || !put.isSuccessful) {
            return RotationResult(false, remaining.size, failures)
        }
        val body = put.body()?.data
        val bumpedVersion = body?.version ?: (note.version + 1)
        val revokedLink = body?.revokedShareLink == true
        // Persist the re-keyed body + fresh wrap + adopt the bumped version and
        // clear is_dirty — but ONLY if the local row still holds the exact pre-
        // rotation ciphertext we just re-keyed and is neither dirty nor soft-
        // deleted. A blind full-row upsert of the pre-rotation `note` snapshot
        // would silently DISCARD a concurrent interactive edit landing during the
        // long rotate round-trip (per-recipient re-seal + the rotate-shares PUT):
        // such an edit writes new plaintext into encrypted_content and flips
        // is_dirty=1 because its own /items PUT hasn't landed, and the upsert would
        // both revert encrypted_content to the re-keyed OLD body AND clear
        // is_dirty — dropping the edit from the DB and the outbox at once.
        // writeRotatedItemIfUnchanged no-ops (0 rows) instead, leaving that dirty
        // row untouched to drain on its own: its next PUT 409-forks into a conflict
        // copy via resolveConflictKeepBoth, so the edit survives and is never
        // re-keyed under the OLD body. The server re-key already committed above,
        // so RotationResult still reports rotated=true regardless. The retired wrap
        // was already stashed (retain, above) BEFORE the PUT, so it survives a
        // crash between the server commit and this local overwrite.
        val localApplied = itemDao.writeRotatedItemIfUnchanged(
            id = noteId,
            ciphertext = newCiphertext.ciphertextB64,
            nonce = newCiphertext.nonceB64,
            decrypted = newPlaintext,
            title = newTitle,
            wrappedKey = newWrapped.wrappedKeyB64,
            wrappedNonce = newWrapped.nonceB64,
            version = bumpedVersion,
            expectedCiphertext = note.encryptedContent,
            expectedNonce = note.contentNonce,
        )
        if (localApplied == 0) android.util.Log.d(
            "ContentKeyRotator",
            "Rotate: local row for $noteId moved on during the rotate round-trip; " +
                "left dirty for the next drain (server re-key already committed)",
        )

        // Forward-secrecy re-key of the attachment blobs onto the new key.
        // Only possible when we hold the OLD key (unshare / notebook-move
        // path); the re-mint path passes oldKey = null and cannot. Runs
        // AFTER the item commit and is fully best-effort — a failure here
        // never unwinds the rotation the survivors already depend on.
        if (oldKey != null) {
            runCatching { rekeyAttachments(noteId, oldKey, newKey) }
                .onFailure {
                    android.util.Log.w("ContentKeyRotator", "attachment re-key pass failed for $noteId", it)
                }
        }
        return RotationResult(
            true, remaining.size, failures, revokedLink,
            newVersion = bumpedVersion,
            localApplied = localApplied > 0,
        )
    }

    /**
     * Best-effort forward-secrecy re-key of [noteId]'s attachment blobs from
     * [oldKey] to [newKey]. Each blob in R2 is sealed under the OLD content key, so
     * a survivor/recipient holding only the new key can't open it until it's re-
     * encrypted + repointed. Per attachment: download → PROBE decrypt under the NEW
     * key (skip if it already succeeds — stateless idempotency across interrupted
     * runs) → decrypt body + metadata under the OLD key → re-encrypt both under the
     * new key → upload to a fresh presigned object → `PUT /attachments/{id}` to
     * repoint the row + swap metadata. Bounded by [MAX_REKEY_ATTACHMENTS] /
     * [MAX_REKEY_TOTAL_BYTES]; anything past the cap (or that fails a step) stays
     * owner-decryptable via the retained old content key. Crypto runs on Default,
     * network on IO — this is invoked from a Main-dispatcher rotation.
     */
    private suspend fun rekeyAttachments(noteId: String, oldKey: ByteArray, newKey: ByteArray) {
        val listResp = runCatching { noteApi.listAttachments(noteId) }.getOrNull() ?: return
        if (!listResp.isSuccessful) return
        val attachments = listResp.body()?.data ?: return
        if (attachments.isEmpty()) return

        var rekeyed = 0
        var bytesRekeyed = 0L
        var capped = false
        for (att in attachments) {
            if (rekeyed >= MAX_REKEY_ATTACHMENTS || bytesRekeyed >= MAX_REKEY_TOTAL_BYTES) {
                capped = true
                break
            }
            try {
                // 1. Download the current (old-key-sealed) blob with a bounded read
                //    (the attachment host is untrusted in the E2EE model).
                val encBlob = withContext(Dispatchers.IO) {
                    val dl = runCatching { noteApi.downloadAttachment(att.id) }.getOrNull()
                    if (dl == null || !dl.isSuccessful) null
                    else dl.body()?.let { readCapped(it, MAX_REKEY_ATTACHMENT_BYTES) }
                } ?: continue

                // 2. Idempotency probe + re-encrypt (all JNI crypto, off-Main).
                //    Returns null to SKIP: either the blob already decrypts under
                //    the new key (a prior run re-keyed it), or the old-key body/meta
                //    decrypt failed (sealed under an even older key / inconsistent —
                //    leave it for the retained-key fallback rather than write a
                //    mismatched blob+metadata pair).
                val prepared = withContext(Dispatchers.Default) {
                    val alreadyNew = openAttachmentBlob(newKey, encBlob)
                    if (alreadyNew != null) return@withContext null
                    val plain = openAttachmentBlob(oldKey, encBlob) ?: return@withContext null
                    val metaJson = noteCrypto.decryptNoteWithContentKeyV2(att.metaNonce, att.encryptedMeta, oldKey, noteId)
                        ?: return@withContext null
                    val newBlob = resealLike(encBlob, newKey, plain) ?: return@withContext null
                    val newMeta = noteCrypto.encryptNoteWithContentKeyV2(metaJson, newKey, noteId)
                    Triple(newBlob, newMeta.ciphertextB64, newMeta.nonceB64)
                } ?: continue
                val (newBlob, newMetaCt, newMetaNonce) = prepared

                // 3. Upload the re-encrypted blob to a fresh presigned object.
                val presign = runCatching {
                    noteApi.presignUpload(PresignUploadRequest(sizeBytes = newBlob.size))
                }.getOrNull() ?: continue
                if (!presign.isSuccessful) continue
                val presigned = presign.body()?.data ?: continue
                val uploaded = withContext(Dispatchers.IO) {
                    val reqBody = newBlob.toRequestBody("application/octet-stream".toMediaType())
                    val req = Request.Builder().url(presigned.url).put(reqBody).build()
                    runCatching { okHttpClient.newCall(req).execute().use { it.isSuccessful } }
                        .getOrDefault(false)
                }
                if (!uploaded) continue

                // 4. Atomically repoint the row + swap metadata onto the new object.
                val swap = runCatching {
                    noteApi.rekeyAttachment(
                        att.id,
                        RekeyAttachmentRequest(
                            r2Key = presigned.objectKey,
                            encryptedMeta = newMetaCt,
                            metaNonce = newMetaNonce,
                            sizeBytes = newBlob.size,
                        ),
                    )
                }.getOrNull()
                if (swap != null && swap.isSuccessful) {
                    rekeyed++
                    bytesRekeyed += newBlob.size.toLong()
                } else {
                    android.util.Log.w("ContentKeyRotator", "attachment re-key PUT failed for ${att.id}")
                }
            } catch (e: Exception) {
                android.util.Log.w("ContentKeyRotator", "attachment re-key failed for ${att.id}", e)
            }
        }
        if (capped) {
            android.util.Log.w(
                "ContentKeyRotator",
                "attachment re-key capped for note $noteId after $rekeyed blob(s) / $bytesRekeyed bytes — " +
                    "remaining stay owner-decryptable via the retained old key",
            )
        }
    }

    /**
     * Open an attachment blob in either format. The web editor and new Android
     * uploads write the chunked v2 stream; v1 remains readable for older
     * Android blobs. Both must be read: a v2 blob that failed both probes would
     * be silently left under the old key, so the removed recipient would keep
     * forward access to it and survivors would never get it.
     */
    private fun openAttachmentBlob(key: ByteArray, blob: ByteArray): ByteArray? =
        if (AttachmentBlobFormat.isV2(blob)) {
            AttachmentBlobFormat.decryptV2(key, blob)
        } else {
            try { uniffi.collab.decryptCollabFrame(key, blob) } catch (_: Exception) { null }
        }

    /** Re-seal in the format the blob arrived in, so a re-key never changes
     *  which clients can read it. */
    private fun resealLike(source: ByteArray, key: ByteArray, plain: ByteArray): ByteArray? =
        if (AttachmentBlobFormat.isV2(source)) {
            try { AttachmentBlobFormat.encryptV2(key, plain) } catch (_: Exception) { null }
        } else {
            try { uniffi.collab.encryptCollabFrame(key, plain) } catch (_: Exception) { null }
        }

    /** Read [body] fully but abort (null) once it exceeds [cap] bytes — never
     *  buffer an unbounded / length-unknown (chunked) response from the untrusted
     *  attachment host. Mirrors AttachmentUploader's pre-read size cap. */
    private fun readCapped(body: ResponseBody, cap: Long): ByteArray? = body.use { b ->
        if (b.contentLength() > cap) return@use null
        val input = b.byteStream()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            total += n
            if (total > cap) return@use null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
}
