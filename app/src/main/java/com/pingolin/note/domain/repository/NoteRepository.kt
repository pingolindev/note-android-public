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

import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.CreateItemRequest
import com.pingolin.note.data.remote.dto.UpdateItemRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import com.pingolin.note.widget.WidgetUpdateHelper
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Note CRUD operations.
 * Encrypts content client-side before storing locally and pushing to server.
 * Local DB stores both encrypted (for sync) and decrypted (for search/display) forms.
 */
@Singleton
class NoteRepository @Inject constructor(
    private val itemDao: ItemDao,
    private val noteApi: NoteApi,
    private val cryptoManager: CryptoManager,
    private val sessionManager: com.pingolin.note.util.SessionManager,
    private val appContext: android.app.Application,
    private val noteCrypto: com.pingolin.note.collab.NoteCrypto,
    private val notebookShareFanOut: NotebookShareFanOut,
    private val sharesInbox: SharesInboxRepository,
    private val contentKeyRotator: ContentKeyRotator,
    private val retainedContentKeyStore: com.pingolin.note.util.RetainedContentKeyStore,
) {
    fun observeNotes(): Flow<List<ItemEntity>> = itemDao.observeActiveNotes()
    fun observeArchived(): Flow<List<ItemEntity>> = itemDao.observeArchivedNotes()
    fun observeTrash(): Flow<List<ItemEntity>> = itemDao.observeTrash()

    suspend fun getNote(id: String): ItemEntity? = itemDao.getById(id)

    suspend fun search(query: String): List<ItemEntity> = itemDao.search(query)

    /**
     * Create a new note. Generates a per-note content key, wraps it
     * with the vault key, encrypts the note body under the content key.
     */
    suspend fun createNote(content: NoteContent): ItemEntity {
        val id = UUID.randomUUID().toString()
        // Off Main, like the crypto below (these calls ANR the UI on low-end
        // devices): toJson is the more expensive half, serialising and
        // escaping the entire note body.
        val json = withContext(Dispatchers.Default) { content.toJson() }
        val vaultBytes = vaultKeyBytes()
            ?: throw IllegalStateException("Can't open your notes on this device. Please sign in again.")

        // Per-note content key, note-body encrypt and vault-wrap all run off the
        // Main thread — these are UniFFI/JNI crypto calls that ANR the UI on
        // low-end devices. No view is touched here (repository layer).
        val (contentKey, encrypted, wrapped) = withContext(Dispatchers.Default) {
            val ck = noteCrypto.generateContentKey()
            // AAD v2: bind ciphertext to this note's id (see NoteCrypto).
            Triple(
                ck,
                noteCrypto.encryptNoteWithContentKeyV2(json, ck, id),
                noteCrypto.wrapWithVault(ck, vaultBytes),
            )
        }

        val entity = ItemEntity(
            id = id,
            userId = "", // Set by SessionManager
            itemType = 1, // note
            encryptedContent = encrypted.ciphertextB64,
            contentNonce = encrypted.nonceB64,
            decryptedContent = json,
            title = content.title,
            version = 1,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            color = content.color,
            isPinned = content.isPinned,
            isArchived = content.isArchived,
            isDirty = true,
            contentSize = encrypted.ciphertextB64.length,
            wrappedContentKey = wrapped.wrappedKeyB64,
            wrappedContentKeyNonce = wrapped.nonceB64,
            sortOrder = content.sortOrder,
        )

        itemDao.upsert(entity)
        WidgetUpdateHelper.notifyDataChanged(appContext)

        // If the note landed in a shared notebook, fan out per-note shares
        // to every recipient on that notebook's standing list. The content
        // key is still in scope so no vault round-trip is needed.
        if (content.notebookId != null) {
            // The owner's standing list first; a MEMBER has none and falls back
            // to the notebook's ROSTER plus its owner. Without the fallback a
            // member's note is sealed to nobody — a note in a shared notebook
            // that only its author can read, which is not a contribution to
            // anything.
            val recipients = notebookShareFanOut.recipientsFor(content.notebookId).ifEmpty {
                val vaultBytes = vaultKeyBytes()
                if (vaultBytes == null) emptyList() else {
                    // Only when the notebook was shared WITH us, and opened through that accepted
                    // share (the inbox holds its key): for our own notebook an empty standing list
                    // means nobody to share to, and an inbox row naming it is never its owner.
                    val nb = sharesInbox.sharedNotebooksFlow.value
                        .firstOrNull { com.pingolin.note.util.AccountIds.same(it.itemId, content.notebookId) }
                        ?.takeIf { sharesInbox.getContentKey(it.itemId) != null }
                    if (nb == null) emptyList() else runCatching {
                        notebookShareFanOut.rosterRecipientsFor(
                            content.notebookId,
                            vaultBytes,
                            ownerEmail = nb.ownerEmail,
                            myEmail = sessionManager.getEmail(),
                        )
                    }.getOrDefault(emptyList())
                }
            }
            if (recipients.isNotEmpty()) {
                // A share-create binds the note's CURRENT content-key generation.
                // Fetch it ONCE for this note, not per recipient. A brand-new note
                // isn't on the server yet → getItem fails → base 1, which is
                // exactly a fresh note's generation.
                val currentGen = runCatching {
                    noteApi.getItem(entity.id).body()?.data?.contentKeyGeneration
                }.getOrNull() ?: 1L
                for (r in recipients) {
                    val ok = notebookShareFanOut.shareToRecipient(entity.id, contentKey, r, currentGen)
                    if (!ok) android.util.Log.w("NoteRepository", "Auto-share create for note ${entity.id} → recipient ${r.userId ?: "unknown"} failed")
                }
            }
        }
        return entity
    }

    /**
     * ⚠️ NOT CALLED BY THE APP: nothing in main, gms or libre calls it, and
     * `NativeNotebookMovesAreNotWiredTest` fails the build if something does.
     * Notes are edited and moved by the page the app shows (the Note web
     * editor), which carries a move through whenever it is sent (its
     * `notebookMove.ts`).
     *
     * This does not, and is not to be wired up as it is.
     * [reconcileNotebookShares] runs once, straight after the local write,
     * whether or not the PUT reached the server. When it did not, the unshare
     * and the re-key fail with it and nothing does them again:
     * [retryDirtyItems] sends the row later and shares nothing. A note moved
     * out of a shared notebook with no connection would stay with that
     * notebook's people, key and all; one moved in would reach nobody. Before
     * this is called from anywhere, the row has to remember where the server
     * has the note until the move is carried through, as the page's cards do.
     *
     * Update an existing note. Branches:
     * - **Shared-with-us**: uses the inbox-held content key (sealed by the
     *   owner for our pubkey). Does NOT send wrapped_content_key — only the
     *   owner can rotate it; the server leaves it alone when we omit the
     *   field.
     * - **Owned**: unwraps the note's wrapped_content_key with our vault,
     *   re-encrypts. Legacy notes without a wrap generate one on first save
     *   (lazy migration, matches web).
     *
     * Pushes directly via PUT /items/{id}; on transient failure the local row
     * keeps `isDirty = true` so the next sync cycle ([retryDirtyItems])
     * retries.
     */
    suspend fun updateNote(id: String, content: NoteContent): ItemEntity? {
        val firstSnapshot = itemDao.getById(id) ?: return null
        // Off Main, like the crypto below (these calls ANR the UI on low-end
        // devices): toJson is the more expensive half, serialising and
        // escaping the entire note body.
        val json = withContext(Dispatchers.Default) { content.toJson() }
        val vaultBytes = vaultKeyBytes()
            ?: throw IllegalStateException("Can't open your notes on this device. Please sign in again.")

        // Shared-note branch — we're a recipient, not the owner. Use the
        // inbox content key unwrapped at inbox load time. Crucially we
        // don't touch wrapped_content_key; corrupting it here would lock
        // the owner and every other recipient out of their own note.
        // Never for a note of ours, whatever the inbox holds (sharedKeyFor).
        val incomingKey = sharesInbox.sharedKeyFor(firstSnapshot)
        if (incomingKey != null) {
            return updateSharedNote(firstSnapshot, content, json, incomingKey)
        }

        // Owner path. The push + the local write-back straddle a network
        // round-trip, and the write-back is compare-and-swapped against the
        // snapshot it was computed from, so it can legitimately find the row
        // moved on. The one mover worth re-driving is a content-key ROTATION
        // (share revoke / notebook unshare): our edit is sealed under the key
        // that rotation just retired, so re-encrypt it under the NEW key rather
        // than drop it. Bounded, because each re-drive re-reads and can race
        // again.
        var snapshot = firstSnapshot
        var attempt = 0
        var push: OwnedPush
        while (true) {
            attempt++
            push = pushOwnedEdit(id, content, json, snapshot, vaultBytes)
            if (push.applied || !push.rotatedUnderUs || attempt >= MAX_UPDATE_ATTEMPTS) break
            android.util.Log.i(
                "NoteRepository",
                "Content key for $id rotated during save; re-sealing the edit under the new key",
            )
            snapshot = itemDao.getById(id) ?: return null
        }
        if (!push.applied) {
            // The row moved on for a reason we deliberately don't overwrite (a
            // newer interactive edit, a soft-delete, or a rotation we ran out of
            // attempts to follow). Whatever is on the row now is newer than this
            // snapshot and carries its own is_dirty, so report the live row
            // instead of a write we didn't make.
            android.util.Log.w(
                "NoteRepository",
                "Local write-back for $id skipped after $attempt attempt(s); row moved on",
            )
            return itemDao.getById(id)
        }
        WidgetUpdateHelper.notifyDataChanged(appContext)

        reconcileNotebookShares(id, content, snapshot, push.contentKey)
        return push.entity
    }

    /** Result of one owner-path push: the entity we tried to persist, the
     *  content key the body was sealed under (needed by the notebook-share
     *  reconciliation), whether the local write-back actually landed, and — when
     *  it didn't — whether the reason was a content-key rotation committing
     *  under us, the only case worth re-driving. */
    private class OwnedPush(
        val entity: ItemEntity,
        val contentKey: ByteArray,
        val applied: Boolean,
        val rotatedUnderUs: Boolean,
    )

    /**
     * One owner-path attempt: resolve the content key from [existing], encrypt,
     * push, and compare-and-swap the result onto the local row.
     */
    private suspend fun pushOwnedEdit(
        id: String,
        content: NoteContent,
        json: String,
        existing: ItemEntity,
        vaultBytes: ByteArray,
    ): OwnedPush {
        // Capture into locals so the null-checks smart-cast inside the off-Main
        // withContext lambdas below (member properties don't carry a smart-cast
        // into a non-inline lambda).
        val existingWrappedKey = existing.wrappedContentKey
        val existingWrappedNonce = existing.wrappedContentKeyNonce
        // Read OUTSIDE netCatch and defensively: this only decides whether the
        // server-visible grouping is included, and it must never be able to
        // fail the save. Inside netCatch a throw here would be swallowed and
        // read as a failed PUT — the note would silently stay dirty. Empty
        // means "omit the field", i.e. leave the column alone.
        val realNotebookIds = runCatching { itemDao.getNotebookIds().toSet() }.getOrDefault(emptySet())
        var contentKey: ByteArray? = null
        var nextWrappedKey: String? = existingWrappedKey
        var nextWrappedNonce: String? = existingWrappedNonce
        // All UniFFI/JNI crypto runs off the Main thread (avoids ANRs); no view
        // is touched here (repository layer).
        if (existingWrappedKey != null && existingWrappedNonce != null) {
            contentKey = withContext(Dispatchers.Default) {
                noteCrypto.unwrapWithVault(existingWrappedKey, existingWrappedNonce, vaultBytes)
            }
            // A wrap that EXISTS and will not open under this vault
            // key is not a broken key to replace. It is the only
            // pointer to the key every other device and every share
            // recipient reads this note with, and the likeliest
            // reasons it does not open here — a vault key from
            // another era (a password reset, a recovered identity)
            // or a tampered row — say nothing about the note itself.
            // Minting a fresh key and pushing it would re-encrypt
            // the note under a key only this device holds:
            // unreadable everywhere else, for good. Refuse the save
            // instead (the web editor does the same); the note stays
            // as it is on the server and the caller tells the user.
            if (contentKey == null) throw NoteKeyUnavailableException(id)
        }
        if (contentKey == null) {
            // Wrap-absent legacy note (written before per-note content keys):
            // give it one on first save. Nothing else holds a key for it yet,
            // so there is nothing to strand.
            val fresh = withContext(Dispatchers.Default) {
                val ck = noteCrypto.generateContentKey()
                ck to noteCrypto.wrapWithVault(ck, vaultBytes)
            }
            contentKey = fresh.first
            nextWrappedKey = fresh.second.wrappedKeyB64
            nextWrappedNonce = fresh.second.nonceB64
        }
        val resolvedKey = contentKey
        val encrypted = withContext(Dispatchers.Default) {
            noteCrypto.encryptNoteWithContentKeyV2(json, resolvedKey, id)
        }

        // Push eagerly — /sync/push only handles inserts (ON CONFLICT DO NOTHING),
        // so updates to existing notes go through plain PUT /items/{id}. The wrap
        // it carries is either the note's own, unchanged, or the first one a
        // wrap-absent legacy note gets; it never replaces a different wrap.
        val putResp = netCatch {
            noteApi.updateItem(
                id,
                com.pingolin.note.data.remote.dto.UpdateItemRequest(
                    encryptedContent = encrypted.ciphertextB64,
                    contentNonce = encrypted.nonceB64,
                    expectedVersion = existing.version,
                    wrappedContentKey = nextWrappedKey,
                    wrappedContentKeyNonce = nextWrappedNonce,
                    // Keep the server-visible grouping in
                    // step. Sends an explicit null when the
                    // note is in no notebook — the server
                    // reads an ABSENT field as "leave it
                    // alone", so omitting it would strand
                    // the stale grouping — and omits it
                    // entirely for a notebook that is not a
                    // real row, which would be a
                    // foreign-key violation.
                    notebookId = com.pingolin.note.data.remote.dto.notebookIdField(
                        content.notebookId,
                        realNotebookIds,
                    ),
                ),
            )
        }
        // NEVER guess the version forward past a write the server did not
        // acknowledge. `existing.version + 1` is not a neutral placeholder: the
        // rotate endpoint bumps by EXACTLY one, so after a rotation commits
        // inside our PUT window (which makes that PUT 409) the guess lands
        // precisely on the post-rotation version. The next drain then matches
        // the server's optimistic-concurrency check, and the owner-gated
        // COALESCE(wrapped_content_key) swaps the RETIRED wrap back in —
        // republishing the note under the key the revoked recipient still
        // holds, while the survivors re-sealed onto the new key are locked out.
        // Keeping the observed version makes that drain 409 and fork a conflict
        // copy, which is the mechanism the sync design already relies on.
        val bumpedVersion = if (putResp != null && putResp.isSuccessful) {
            putResp.body()?.data?.version ?: (existing.version + 1)
        } else {
            existing.version
        }
        val stillDirty = putResp == null || !putResp.isSuccessful
        // What the local row must still hold for the write-back to be valid.
        val expectedCiphertext = existing.encryptedContent
        val expectedNonce = existing.contentNonce
        val expectedWrappedKey = existingWrappedKey

        val updated = existing.copy(
            encryptedContent = encrypted.ciphertextB64,
            contentNonce = encrypted.nonceB64,
            decryptedContent = json,
            title = content.title,
            version = bumpedVersion,
            updatedAt = Instant.now(),
            color = content.color,
            isPinned = content.isPinned,
            isArchived = content.isArchived,
            isDirty = stillDirty,
            contentSize = encrypted.ciphertextB64.length,
            notebookId = content.notebookId,
            wrappedContentKey = nextWrappedKey,
            wrappedContentKeyNonce = nextWrappedNonce,
            sortOrder = content.sortOrder,
        )

        // Compare-and-swap, not a blind upsert: the wrap in `updated` is a
        // PRE-push snapshot, and replaying it over a wrap that a rotation
        // installed while we were on the network is what puts the retired key
        // back on the row (and, via the next drain, back on the server).
        val applied = itemDao.writeEditedItemIfUnchanged(
            id = id,
            ciphertext = updated.encryptedContent,
            nonce = updated.contentNonce,
            decrypted = updated.decryptedContent,
            title = updated.title,
            version = updated.version,
            updatedAt = updated.updatedAt,
            color = updated.color,
            isPinned = updated.isPinned,
            isArchived = updated.isArchived,
            isDirty = updated.isDirty,
            contentSize = updated.contentSize,
            notebookId = updated.notebookId,
            wrappedKey = updated.wrappedContentKey,
            wrappedNonce = updated.wrappedContentKeyNonce,
            sortOrder = updated.sortOrder,
            expectedCiphertext = expectedCiphertext,
            expectedNonce = expectedNonce,
            expectedWrappedKey = expectedWrappedKey,
        ) > 0

        // Distinguish "a rotation moved the key under us" (re-drivable: re-seal the
        // edit under the new key) from every other mover — a newer interactive
        // edit, a soft-delete, a pulled server copy — which are newer than this
        // snapshot and must be left alone.
        val rotatedUnderUs = !applied && run {
            val live = itemDao.getById(id)
            live != null && !live.deleted && live.wrappedContentKey != expectedWrappedKey
        }
        return OwnedPush(updated, resolvedKey, applied, rotatedUnderUs)
    }

    /**
     * Reconcile notebook-level shares after [id] moved between notebooks.
     * [previous] is the pre-edit row; [contentKey] is the key the note's body is
     * currently sealed under, so newly-added recipients get the live key.
     */
    private suspend fun reconcileNotebookShares(
        id: String,
        content: NoteContent,
        previous: ItemEntity,
        contentKey: ByteArray,
    ) {
        val existing = previous
        // Reconcile notebook-level shares on a notebook change. Only the
        // owner's device runs this — share recipients don't get a chance
        // to move notes between notebooks because updateNote takes the
        // vault path (their per-note content key lives in the inbox store).
        val notebookChanged = content.notebookId != existing.notebookId
        if (notebookChanged) {
            val oldRecipients = notebookShareFanOut.recipientsFor(existing.notebookId)
            val newRecipients = notebookShareFanOut.recipientsFor(content.notebookId)
            val oldEmails = oldRecipients.map { it.email.lowercase() }.toSet()
            val newEmails = newRecipients.map { it.email.lowercase() }.toSet()
            var revokedAnyRecipient = false
            for (r in oldRecipients) {
                if (r.email.lowercase() !in newEmails) {
                    notebookShareFanOut.unshareFromRecipient(id, r.email)
                    revokedAnyRecipient = true
                }
            }
            // A share-create binds the note's CURRENT content-key generation.
            // Fetch it ONCE for this note, and only when there's actually
            // someone new to share to; base 1 on failure.
            val toAdd = newRecipients.filter { it.email.lowercase() !in oldEmails }
            if (toAdd.isNotEmpty()) {
                val currentGen = runCatching {
                    noteApi.getItem(id).body()?.data?.contentKeyGeneration
                }.getOrNull() ?: 1L
                for (r in toAdd) {
                    notebookShareFanOut.shareToRecipient(id, contentKey, r, currentGen)
                }
            }
            // Forward secrecy: the move above reuses the SAME per-note
            // content key, and unshareFromRecipient is a delete-only revoke,
            // so a recipient who lost access still holds a key that decrypts
            // every future edit. If anyone was revoked, rotate the content
            // key — regenerate + re-encrypt + re-wrap for whoever remains
            // (the revoked rows are already deleted, so rotate() re-wraps
            // only survivors, and newly-added recipients get the fresh key
            // too). Mirrors NotebookShareWriter.unshareNotebookFrom. Best-
            // effort: a rotation failure just risks one more future read.
            if (revokedAnyRecipient) {
                val rot = contentKeyRotator.rotate(id)
                if (!rot.rotated) android.util.Log.w(
                    "NoteRepository",
                    "Rotation after notebook-move unshare failed for $id",
                )
            }
        }
    }

    /**
     * Update a note that was shared with us. Uses the inbox content
     * key and omits wrapped_content_key so only the owner can rotate
     * the wrap. On transient server failure (5xx / network) leaves
     * `isDirty = true` for a later retry.
     */
    private suspend fun updateSharedNote(
        existing: ItemEntity,
        content: NoteContent,
        json: String,
        contentKey: ByteArray,
    ): ItemEntity {
        // Off the Main thread (UniFFI/JNI crypto); no view touched here.
        val encrypted = withContext(Dispatchers.Default) {
            noteCrypto.encryptNoteWithContentKeyV2(json, contentKey, existing.id)
        }
        // A materialized share carries a synthesized version (the inbox
        // payload has none, so it's stored as 1). If the owner has
        // revised the note since sharing, the live version is higher and
        // this PUT 409s. That's a stale-version artifact, not a real
        // conflict — refetch the live version and retry once. Re-uses the
        // same ciphertext on retry; only the expectedVersion differs.
        val putResp = retryingOnVersionConflict(
            expectedVersion = existing.version,
            statusOf = { it?.code() },
            liveVersion = {
                netCatch { noteApi.getItem(existing.id) }
                    ?.takeIf { it.isSuccessful }?.body()?.data?.version
            },
            put = { version ->
                netCatch {
                    noteApi.updateItem(
                        existing.id,
                        com.pingolin.note.data.remote.dto.UpdateItemRequest(
                            encryptedContent = encrypted.ciphertextB64,
                            contentNonce = encrypted.nonceB64,
                            expectedVersion = version,
                            // No wrapped_content_key — only owners rotate.
                            wrappedContentKey = null,
                            wrappedContentKeyNonce = null,
                        ),
                    )
                }
            },
        )
        // Same rule as the owner path: only adopt a version the server actually
        // acknowledged. On failure keep the observed one so the drain's PUT is
        // judged against a version we really saw, rather than one fabricated to
        // slide past the server's optimistic-concurrency check.
        val bumpedVersion = if (putResp != null && putResp.isSuccessful) {
            putResp.body()?.data?.version ?: (existing.version + 1)
        } else {
            existing.version
        }
        val stillDirty = putResp == null || !putResp.isSuccessful
        val updated = existing.copy(
            encryptedContent = encrypted.ciphertextB64,
            contentNonce = encrypted.nonceB64,
            decryptedContent = json,
            title = content.title,
            version = bumpedVersion,
            updatedAt = Instant.now(),
            color = content.color,
            isPinned = content.isPinned,
            isArchived = content.isArchived,
            isDirty = stillDirty,
            contentSize = encrypted.ciphertextB64.length,
            notebookId = content.notebookId,
            sortOrder = content.sortOrder,
            // Owner's wrap stays intact; we must not overwrite it.
        )
        // CAS on the pre-push snapshot: the PUT (plus a possible 409
        // refetch + retry) is a long round-trip, and a blind upsert of
        // this snapshot would revert anything that lands inside it — a
        // newer local edit, or the owner's copy arriving through the
        // inbox re-materialization.
        val applied = itemDao.writeSharedEditIfUnchanged(
            id = existing.id,
            ciphertext = updated.encryptedContent,
            nonce = updated.contentNonce,
            decrypted = updated.decryptedContent,
            title = updated.title,
            version = updated.version,
            updatedAt = updated.updatedAt,
            color = updated.color,
            isPinned = updated.isPinned,
            isArchived = updated.isArchived,
            isDirty = updated.isDirty,
            contentSize = updated.contentSize,
            notebookId = updated.notebookId,
            sortOrder = updated.sortOrder,
            expectedCiphertext = existing.encryptedContent,
            expectedNonce = existing.contentNonce,
        ) > 0
        if (!applied) {
            android.util.Log.w(
                "NoteRepository",
                "Shared-note write-back for ${existing.id} skipped; row moved on during the PUT",
            )
            return itemDao.getById(existing.id) ?: updated
        }
        WidgetUpdateHelper.notifyDataChanged(appContext)
        return updated
    }

    /**
     * Ensure [noteId] owns a per-note content key, minting + persisting one
     * (the same lazy migration [updateNote] runs) for a wrap-ABSENT legacy
     * note. Returns the content key the note's body + attachments should be
     * sealed under so a later share recipient — who receives THIS key, never
     * the vault key — can decrypt them.
     *
     * Branches, mirroring the resolve order used elsewhere:
     *  - shared-with-us  → the inbox content key (never rewrap; only the owner
     *    rotates the wrap),
     *  - already-wrapped → the vault-unwrapped content key (no server write),
     *  - wrap-absent     → mint a fresh key, re-encrypt the stored body under
     *    it, wrap with the vault, and persist locally + via PUT /items/{id} —
     *    identical to [updateNote]'s lazy-migration branch (the body currently
     *    lives under the VAULT key, so it MUST be re-encrypted, not just
     *    re-wrapped).
     *
     * A wrap that EXISTS but fails to unwrap (corrupt) is deliberately NOT
     * re-minted here: that would strand existing share recipients on the old
     * key (see [updateNote]'s corrupt-wrap note). Returns null in that case so
     * the caller keeps its existing fallback. Returns null too when there's no
     * vault key, the note is missing, or its plaintext body isn't available.
     */
    suspend fun ensureContentKey(noteId: String): ByteArray? {
        val existing = itemDao.getById(noteId) ?: return null
        // Recipient path: use the inbox-held key; never touch the owner's wrap.
        // Never for a note of ours, whatever the inbox holds (sharedKeyFor).
        sharesInbox.sharedKeyFor(existing)?.let { return it }

        val vaultBytes = vaultKeyBytes() ?: return null

        val wk = existing.wrappedContentKey
        val wn = existing.wrappedContentKeyNonce
        if (wk != null && wn != null) {
            // Already migrated → return the unwrapped content key (no write). A
            // corrupt wrap (unwrap == null) falls through to null: re-minting
            // here would strand share recipients, so leave that to a careful
            // rotation path, not the attachment flow.
            return withContext(Dispatchers.Default) {
                noteCrypto.unwrapWithVault(wk, wn, vaultBytes)
            }
        }

        // Wrap-absent legacy note: mint a fresh content key, re-encrypt the
        // stored body under it, wrap the key with the vault, and persist
        // locally + server. Byte-for-byte the migration updateNote performs.
        val json = existing.decryptedContent ?: return null
        val (contentKey, encrypted, wrapped) = withContext(Dispatchers.Default) {
            val ck = noteCrypto.generateContentKey()
            Triple(
                ck,
                noteCrypto.encryptNoteWithContentKeyV2(json, ck, noteId),
                noteCrypto.wrapWithVault(ck, vaultBytes),
            )
        }
        val putResp = netCatch {
            noteApi.updateItem(
                noteId,
                com.pingolin.note.data.remote.dto.UpdateItemRequest(
                    encryptedContent = encrypted.ciphertextB64,
                    contentNonce = encrypted.nonceB64,
                    expectedVersion = existing.version,
                    wrappedContentKey = wrapped.wrappedKeyB64,
                    wrappedContentKeyNonce = wrapped.nonceB64,
                ),
            )
        }
        val bumpedVersion = if (putResp != null && putResp.isSuccessful) {
            putResp.body()?.data?.version ?: (existing.version + 1)
        } else {
            existing.version
        }
        val stillDirty = putResp == null || !putResp.isSuccessful
        // CAS, not a blind upsert. This runs from the attachment upload
        // path with the editor open, so the user can still be typing
        // during the PUT: a blind write-back of the pre-PUT snapshot would
        // revert those keystrokes' ciphertext and, when the PUT had
        // succeeded, clear is_dirty on top — dropping the edit from the DB
        // and the outbox together. The mint leaves the plaintext
        // unchanged, so decrypted_content/title are not written at all.
        val applied = itemDao.writeMintedContentKeyIfUnchanged(
            id = noteId,
            ciphertext = encrypted.ciphertextB64,
            nonce = encrypted.nonceB64,
            version = bumpedVersion,
            updatedAt = Instant.now(),
            isDirty = stillDirty,
            contentSize = encrypted.ciphertextB64.length,
            wrappedKey = wrapped.wrappedKeyB64,
            wrappedNonce = wrapped.nonceB64,
            expectedCiphertext = existing.encryptedContent,
            expectedNonce = existing.contentNonce,
            expectedWrappedKey = null,
        ) > 0
        if (!applied) {
            // The row moved on during the PUT. The freshly-minted key is still the
            // right one for the caller to seal its attachment under — the server
            // holds it (the PUT carried the wrap) and the next sync brings it down
            // — but the local row is left for whoever moved it.
            android.util.Log.w(
                "NoteRepository",
                "Content-key mint write-back for $noteId skipped; row moved on during the PUT",
            )
            return contentKey
        }
        WidgetUpdateHelper.notifyDataChanged(appContext)
        return contentKey
    }

    private fun vaultKeyBytes(): ByteArray? {
        val b64 = sessionManager.getMasterKey() ?: return null
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            if (bytes.size == 32) bytes else null
        } catch (_: Exception) { null }
    }

    /**
     * `runCatching { ... }.getOrNull()` for a network call, but never swallows
     * coroutine cancellation. `runCatching` catches `CancellationException`
     * too, which makes a cancelled sync look like a failed request and carry
     * on instead of stopping — re-throw it so structured cancellation works.
     */
    private suspend fun <T> netCatch(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    suspend fun deleteNote(id: String) {
        // Soft-delete locally (also flags is_dirty=1) so the note disappears
        // immediately and the delete is queued for the server.
        itemDao.softDelete(id, Instant.now().toEpochMilli())
        // Propagate eagerly, mirroring updateNote's best-effort PUT. A 404
        // means the note was never synced (created offline) or is already
        // gone — treat as done. Any other failure (offline / 5xx) leaves
        // is_dirty=1 for retryDeletedItems() to replay on the next sync.
        val resp = netCatch { noteApi.deleteItem(id) }
        if (resp != null && (resp.isSuccessful || resp.code() == 404)) {
            itemDao.clearDirtyFlags(listOf(id))
        }
        WidgetUpdateHelper.notifyDataChanged(appContext)
    }

    /** Restore a trashed note. */
    suspend fun restoreNote(id: String) {
        noteApi.restoreItem(id)
        itemDao.restore(id, Instant.now().toEpochMilli())
        WidgetUpdateHelper.notifyDataChanged(appContext)
    }

    /**
     * Hard-delete every trashed note for the current user — the
     * "Empty trash" action. Server purges first; on success the local
     * trash rows are removed too. If the server call throws the local
     * trash stays intact so a later retry can sync.
     */
    suspend fun emptyTrash(): Int {
        val resp = noteApi.purgeTrash()
        if (!resp.isSuccessful) {
            error("Server returned ${resp.code()} when purging trash")
        }
        val removed = itemDao.purgeAllTrash()
        WidgetUpdateHelper.notifyDataChanged(appContext)
        return removed
    }

    /** Every active note as a list, for the export path. */
    suspend fun getAllNotes(): List<ItemEntity> = itemDao.getAllActiveNotes()

    /**
     * Replay local-only edits against the server. updateNote PUTs
     * eagerly and leaves is_dirty=true on transient failure; this
     * scans those rows and retries via PUT /items/{id}. Shared notes
     * skip the wrap-with-vault step because the existing row already
     * has the right wrapped_content_key (owner's) and the server
     * accepts edit-permission recipients.
     *
     * Returns the number of rows successfully pushed.
     */
    suspend fun retryDirtyItems(): Int {
        val vaultBytes = vaultKeyBytes() ?: return 0
        // NOTEBOOKS (item_type 2) drain here too, not just notes.
        //
        // /sync/push is create-only (ON CONFLICT DO NOTHING), so an edit to an
        // item the server already has comes back as a conflict and is left
        // dirty for exactly this replay. Filtering to item_type 1 would mean a
        // renamed notebook is rejected by the push, skipped by the retry and
        // stays dirty forever: the item_type=2 row on the server would keep
        // its original title and ciphertext for good. It would look fine on
        // other clients only because the name is mirrored into the
        // item_type=10 metadata blob, which has its own writer and does sync.
        //
        // That also strands the notebook's WRAP: renaming is how a notebook
        // written before per-notebook content keys gets one, and an upgrade
        // that never leaves the device upgrades nothing.
        //
        // item_type 10 stays out — the metadata blob is written by
        // NotebookShareWriter with its own version handling, and a second
        // writer here would race it.
        val dirty = itemDao.getDirtyItems().filter {
            (it.itemType == 1 || it.itemType == 2) && !it.deleted
        }
        // Fetched once for the whole replay rather than per item — see
        // notebookIdField for why a notebook that is not a real row must not
        // reach the wire.
        val realNotebookIds = runCatching { itemDao.getNotebookIds().toSet() }.getOrDefault(emptySet())
        var succeeded = 0
        for (item in dirty) {
            val sharedKey = sharesInbox.sharedKeyFor(item)
            val putResp = netCatch {
                noteApi.updateItem(
                    item.id,
                    com.pingolin.note.data.remote.dto.UpdateItemRequest(
                        encryptedContent = item.encryptedContent,
                        contentNonce = item.contentNonce,
                        expectedVersion = item.version,
                        // Shared notes carry the owner's wrap — never replay
                        // it. Owner rows send their own wrap back for the
                        // COALESCE no-op path.
                        wrappedContentKey = if (sharedKey != null) null else item.wrappedContentKey,
                        wrappedContentKeyNonce = if (sharedKey != null) null else item.wrappedContentKeyNonce,
                        // Owner rows only: the server gates this column on
                        // ownership, so a recipient's value would be ignored,
                        // and sending one is just noise on the wire.
                        notebookId = if (sharedKey != null) null else
                            com.pingolin.note.data.remote.dto.notebookIdField(item.notebookId, realNotebookIds),
                    ),
                )
            }
            when {
                putResp != null && putResp.isSuccessful -> {
                    val bumped = putResp.body()?.data?.version ?: (item.version + 1)
                    // Adopt the server version and clear is_dirty ONLY if the row
                    // still holds the ciphertext we just PUT. A blind full-row
                    // REPLACE of the PRE-PUT snapshot would revert a local edit
                    // made during the PUT window AND drop it from the outbox —
                    // silent loss. The CAS leaves such an edit dirty for the next
                    // drain, while still adopting `bumped` so its next PUT sends
                    // expectedVersion=bumped and lands cleanly instead of
                    // 409-forking.
                    val cleared = itemDao.confirmDrainedItem(
                        id = item.id,
                        version = bumped,
                        expectedCiphertext = item.encryptedContent,
                        expectedNonce = item.contentNonce,
                    )
                    if (!cleared) android.util.Log.d(
                        "NoteRepository",
                        "Drain: content moved on for ${item.id}; left dirty for next drain",
                    )
                    succeeded++
                }
                // Genuine concurrent-edit conflict on a note WE own: the
                // server moved past the version our offline edit was based
                // on. Keep BOTH (Dropbox-style) instead of silently losing
                // either side. Shared notes fall through to stay-dirty —
                // their interactive edit path resolves 409 via refetch+retry.
                // A NOTEBOOK is a title, not a document. resolveConflictKeepBoth
                // forks into a copy hard-coded to `itemType = 1`, so sending a
                // notebook through it would answer a rename race with a stray
                // NOTE called "<notebook> (conflict copy)". Adopt the server's
                // version and stay dirty instead: the next drain PUTs with an
                // expectedVersion that lands, and the later rename wins.
                putResp != null && putResp.code() == 409 && item.itemType == 2 -> {
                    val live = netCatch { noteApi.getItem(item.id) }
                        ?.takeIf { it.isSuccessful }?.body()?.data
                    if (live != null) resolveNotebookConflict(item, live, vaultBytes)
                }
                putResp != null && putResp.code() == 409 && sharedKey == null -> {
                    if (resolveConflictKeepBoth(item, vaultBytes)) succeeded++
                }
                // 403 = the server will NEVER accept this write (view-only
                // share edited before the editor's read-only gate resolved,
                // or access revoked). Give up: clear the flag so the row
                // stops retrying every sync — and, for a shared note, so the
                // next inbox refresh re-materializes the owner's live copy
                // (materializeSharedNotes skips isDirty rows; left dirty, the
                // recipient would be stuck on their un-syncable edit forever).
                putResp != null && putResp.code() == 403 -> {
                    android.util.Log.w(
                        "NoteRepository",
                        "PUT ${item.id} forbidden (403) — dropping un-pushable local edit",
                    )
                    itemDao.clearDirtyFlags(listOf(item.id))
                }
                // On transient/offline failure leave is_dirty=true for retry.
            }
        }
        return succeeded
    }

    /**
     * A notebook PUT came back 409. Usually a version race: adopt the server's
     * version and stay dirty, so the next drain lands.
     *
     * But the server may hold a DIFFERENT wrap than ours: this device gave a
     * legacy wrap-less notebook its first content key (renameNotebook /
     * upgradeLegacyNotebooks) while another client did the same first. Our PUT
     * then carries a replacement for the owner wrap, which the server refuses
     * on a plain PUT — and must, because members already hold the server's
     * key. Adopting the version alone would re-send the same wrap and be
     * refused on every sync, forever. Instead re-seal our pending change under
     * the SERVER's key and adopt its wrap, so the next drain sends the
     * identical wrap. If that key does not open here, drop the local change:
     * the server's copy stands.
     */
    private suspend fun resolveNotebookConflict(
        item: ItemEntity,
        live: com.pingolin.note.data.remote.dto.ItemDto,
        vaultBytes: ByteArray,
    ) {
        val liveWrap = live.wrappedContentKey
        val liveNonce = live.wrappedContentKeyNonce
        val wrapDiffers = liveWrap != null && liveNonce != null &&
            (liveWrap != item.wrappedContentKey || liveNonce != item.wrappedContentKeyNonce)
        if (!wrapDiffers) {
            itemDao.adoptServerVersionKeepingDirty(item.id, live.version)
            return
        }
        val json = item.decryptedContent
        val resealed = if (json == null) null else withContext(Dispatchers.Default) {
            noteCrypto.unwrapWithVault(liveWrap!!, liveNonce!!, vaultBytes)
                ?.let { key -> noteCrypto.encryptNoteWithContentKeyV2(json, key, item.id) }
        }
        if (resealed == null) {
            android.util.Log.w("NoteRepository", "Notebook ${item.id}: the server's key does not open here; dropping the local change")
            itemDao.clearDirtyFlags(listOf(item.id))
            return
        }
        itemDao.writeMintedContentKeyIfUnchanged(
            id = item.id,
            ciphertext = resealed.ciphertextB64,
            nonce = resealed.nonceB64,
            version = live.version,
            // updated_at is the pull cursor; a re-seal is not a server change.
            updatedAt = item.updatedAt,
            isDirty = true,
            contentSize = resealed.ciphertextB64.length,
            wrappedKey = liveWrap!!,
            wrappedNonce = liveNonce!!,
            expectedCiphertext = item.encryptedContent,
            expectedNonce = item.contentNonce,
            expectedWrappedKey = item.wrappedContentKey,
        )
    }

    /**
     * Resolve a concurrent-edit conflict by keeping BOTH versions. Forks our
     * un-pushable local edit into a new note titled "… (conflict copy)" — a
     * fresh id with its own vault-wrapped key, so the create-push lands it
     * cleanly — then resets the original id to the server's current version,
     * stored undecrypted so [SyncRepository]'s backfill pass re-derives it in
     * the same sync. Returns true only if both sides were preserved; on any
     * failure it returns false and the caller leaves the row dirty so nothing
     * is lost and we retry next sync.
     */
    private suspend fun resolveConflictKeepBoth(item: ItemEntity, vaultBytes: ByteArray): Boolean {
        // Fork from the LIVE local row, not the drain snapshot: a concurrent
        // interactive edit that landed AFTER retryDirtyItems snapshotted `item`
        // (during the failed PUT + the getItem refetch below) would otherwise be
        // dropped — the conflict copy would carry the stale pre-edit body. Re-read
        // by id; fall back to the snapshot only if the row vanished (a concurrent
        // hard-delete) so we still preserve something rather than bailing.
        val liveLocal = itemDao.getById(item.id) ?: item
        val localJson = liveLocal.decryptedContent ?: return false
        val live = netCatch { noteApi.getItem(item.id) }
            ?.takeIf { it.isSuccessful }?.body()?.data ?: return false

        // 1. Fork our local edit into a new conflict-copy note.
        val copyJson = withConflictTitle(localJson)
        val copyId = UUID.randomUUID().toString()
        // Off Main, matching every other crypto site in this file: this runs
        // inside retryDirtyItems, which sync() drives from viewModelScope on a
        // foreground refresh — and it seals a FULL note body.
        val (copyKey, copyEnc, copyWrap) = withContext(Dispatchers.Default) {
            val key = noteCrypto.generateContentKey()
            // AAD v2: the fork is a brand-new note; bind to its own id.
            val enc = noteCrypto.encryptNoteWithContentKeyV2(copyJson, key, copyId)
            Triple(key, enc, noteCrypto.wrapWithVault(key, vaultBytes))
        }
        itemDao.upsert(
            ItemEntity(
                id = copyId,
                userId = liveLocal.userId,
                itemType = 1,
                encryptedContent = copyEnc.ciphertextB64,
                contentNonce = copyEnc.nonceB64,
                decryptedContent = copyJson,
                title = titleOf(copyJson),
                version = 1,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                deleted = false,
                circleId = liveLocal.circleId,
                notebookId = liveLocal.notebookId,
                color = liveLocal.color,
                // Match the (unchanged) content JSON so a sync round-trip
                // can't silently flip this back.
                isPinned = liveLocal.isPinned,
                isArchived = liveLocal.isArchived,
                isDirty = true, // new id → created by the next create-push
                contentSize = copyEnc.ciphertextB64.length,
                wrappedContentKey = copyWrap.wrappedKeyB64,
                wrappedContentKeyNonce = copyWrap.nonceB64,
                sortOrder = liveLocal.sortOrder,
            )
        )

        // 2. Accept the server's current version for the original id.
        //    Stored undecrypted + clean; backfillUndecryptedItems()
        //    re-derives the title/body later in this same sync cycle.
        //
        //    CAS on the row we forked from: the getItem above is a network
        //    hop, and an edit landing inside it went into neither the fork
        //    (taken before the call) nor the server copy we're about to
        //    adopt. A blind reset would write isDirty = false over it,
        //    dropping it from the DB and the outbox at once. On a miss the
        //    row keeps its newer, still-dirty edit and the next drain
        //    resolves it; the older side is already safe in the conflict
        //    copy, so nothing is lost either way.
        val reset = itemDao.resetToServerIfUnchanged(
            id = item.id,
            ciphertext = live.encryptedContent,
            nonce = live.contentNonce,
            version = live.version,
            updatedAt = runCatching { Instant.parse(live.updatedAt) }.getOrDefault(Instant.now()),
            deleted = live.deleted,
            contentSize = live.contentSize,
            wrappedKey = live.wrappedContentKey ?: liveLocal.wrappedContentKey,
            wrappedNonce = live.wrappedContentKeyNonce ?: liveLocal.wrappedContentKeyNonce,
            expectedCiphertext = liveLocal.encryptedContent,
            expectedNonce = liveLocal.contentNonce,
        ) > 0
        WidgetUpdateHelper.notifyDataChanged(appContext)
        if (!reset) android.util.Log.w(
            "NoteRepository",
            "Conflict reset for ${item.id} skipped; a newer edit landed during the refetch",
        )
        return reset
    }

    /** Append a "(conflict copy)" marker to the note's title field in its
     *  content JSON, so the forked copy is recognisable in the list. */
    private fun withConflictTitle(json: String): String = try {
        val obj = org.json.JSONObject(json)
        val t = obj.optString("title", "")
        obj.put("title", if (t.isBlank()) "Conflict copy" else "$t (conflict copy)")
        obj.toString()
    } catch (_: Exception) { json }

    private fun titleOf(json: String): String? = try {
        org.json.JSONObject(json).optString("title", "").takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    /**
     * Replay soft-deletes that haven't reached the server yet — an offline
     * delete, or an eager [deleteNote] delete that failed. Scans dirty +
     * deleted rows and DELETEs each server-side, clearing the flag on success
     * (or on 404 — already gone / never synced). Returns the number pushed.
     */
    suspend fun retryDeletedItems(): Int {
        val pending = itemDao.getDeletedDirtyItems()
        var succeeded = 0
        for (item in pending) {
            val resp = netCatch { noteApi.deleteItem(item.id) }
            if (resp != null && (resp.isSuccessful || resp.code() == 404)) {
                itemDao.clearDirtyFlags(listOf(item.id))
                succeeded++
            }
            // On continued failure leave is_dirty=1 for the next retry.
        }
        return succeeded
    }

    companion object {
        /**
         * How many times an owner edit re-drives itself onto a content key that
         * a rotation replaced mid-push. Each re-drive re-reads the row and can
         * race again, so it is bounded rather than a spin; three attempts covers
         * a revoke storm without ever looping on a persistently-contended note.
         */
        private const val MAX_UPDATE_ATTEMPTS = 3

        /**
         * Run [put] with [expectedVersion]; if the result is an HTTP 409
         * (stale optimistic-concurrency version), refetch the live
         * version via [liveVersion] and retry exactly once. A non-409
         * result, a null result (network error → [statusOf] null), or a
         * null live version all return the first result unchanged; a
         * second 409 after the refetch propagates as-is. Pure and
         * generic over the response type so the retry policy is
         * unit-testable without the network.
         */
        suspend fun <T> retryingOnVersionConflict(
            expectedVersion: Long,
            statusOf: (T) -> Int?,
            liveVersion: suspend () -> Long?,
            put: suspend (Long) -> T,
        ): T {
            val first = put(expectedVersion)
            if (statusOf(first) != 409) return first
            val live = liveVersion() ?: return first
            return put(live)
        }
    }
}

/**
 * Parse a locally decrypted note back into [NoteContent] for a
 * mutate-one-field-and-rewrite update, or null when the row has no usable plaintext
 * — a null tells the caller to SKIP the write rather than clobber ciphertext it
 * could not read. Unknown JSON keys ride through [NoteContent.extras] so a rewrite
 * never strips fields another platform wrote (the write-path sibling of the sync
 * pull's preserve-on-decrypt-miss guard). NoteContentRoundTripTest pins this
 * contract.
 */
fun parseNoteContent(note: com.pingolin.note.data.local.entity.ItemEntity): NoteContent? {
    val json = note.decryptedContent
    if (json != null) {
        try {
            val obj = org.json.JSONObject(json)
            return NoteContent(
                title = obj.optString("title", note.title ?: ""),
                body = obj.optString("body", ""),
                noteType = obj.optString("note_type", "text"),
                color = note.color,
                isPinned = note.isPinned,
                isArchived = note.isArchived,
                labels = obj.optJSONArray("labels")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                checklistItems = obj.optJSONArray("checklist_items")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val item = arr.getJSONObject(i)
                        ChecklistItem(
                            text = item.optString("text", ""),
                            checked = item.optBoolean("checked", false),
                        )
                    }
                },
                sortOrder = obj.optInt("sort_order", 0),
                notebookId = note.notebookId,
                editMode = obj.optString("edit_mode", "rich"),
                extras = NoteContent.extrasFrom(obj),
            )
        } catch (_: Exception) {}
    }
    return null
}

/** Decrypted note content structure (matches the JSON stored in encrypted_content) */
data class NoteContent(
    val title: String = "",
    val body: String = "",
    val noteType: String = "text",
    val color: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val labels: List<String> = emptyList(),
    val checklistItems: List<ChecklistItem>? = null,
    val sortOrder: Int = 0,
    val notebookId: String? = null,
    val editMode: String = "markdown",
    /**
     * Keys in the stored JSON that this client doesn't model — written
     * by a newer build or by another platform's client. Preserved verbatim
     * through the parse → mutate → toJson cycle so an edit here never
     * silently strips their fields. Values are org.json types.
     */
    val extras: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): String {
        val sb = StringBuilder("{")
        // The two large fields append in place — see escapeJsonInto.
        sb.append("\"title\":\""); escapeJsonInto(sb, title); sb.append("\",")
        sb.append("\"body\":\""); escapeJsonInto(sb, body); sb.append("\",")
        sb.append("\"note_type\":\"${escapeJson(noteType)}\",")
        color?.let { sb.append("\"color\":\"${escapeJson(it)}\",") }
        sb.append("\"is_pinned\":$isPinned,")
        sb.append("\"is_archived\":$isArchived,")
        sb.append("\"labels\":[${labels.joinToString(",") { "\"${escapeJson(it)}\"" }}],")
        sb.append("\"sort_order\":$sortOrder,")
        sb.append("\"edit_mode\":\"${escapeJson(editMode)}\"")
        notebookId?.let { sb.append(",\"notebook_id\":\"${escapeJson(it)}\"") }
        checklistItems?.let { items ->
            sb.append(",\"checklist_items\":[")
            sb.append(items.joinToString(",") { "{\"text\":\"${escapeJson(it.text)}\",\"checked\":${it.checked}}" })
            sb.append("]")
        }
        if (extras.isNotEmpty()) {
            val obj = org.json.JSONObject()
            for ((key, value) in extras) {
                if (key in KNOWN_KEYS) continue
                obj.put(key, value ?: org.json.JSONObject.NULL)
            }
            val rendered = obj.toString()
            if (rendered.length > 2) {
                sb.append(",")
                sb.append(rendered, 1, rendered.length - 1)
            }
        }
        sb.append("}")
        return sb.toString()
    }

    companion object {
        /** JSON keys owned by the typed fields above. */
        val KNOWN_KEYS = setOf(
            "title", "body", "note_type", "color", "is_pinned", "is_archived",
            "labels", "sort_order", "edit_mode", "notebook_id", "checklist_items",
        )

        /** Collect unknown keys (+ raw org.json values) for round-trip. */
        fun extrasFrom(obj: org.json.JSONObject): Map<String, Any?> {
            var extras: MutableMap<String, Any?>? = null
            for (key in obj.keys()) {
                if (key in KNOWN_KEYS) continue
                val map = extras ?: linkedMapOf<String, Any?>().also { extras = it }
                map[key] = obj.opt(key)
            }
            return extras ?: emptyMap()
        }
    }

    /**
     * Escape straight into [out], without building an intermediate String.
     *
     * Building the JSON through intermediate Strings copies the body four times
     * for one save (the escaped copy, the string template's, the outer
     * builder's and the final toString()). Measured on a 421KB note that is
     * 5.0MB of allocation per save; on a 10MB note it is 121.5MB — on the main
     * thread, against a 256MB heap cap. Appending in place makes it roughly the
     * size of the output.
     */
    private fun escapeJsonInto(out: StringBuilder, s: String) {
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
    }

    private fun escapeJson(s: String): String {
        // Escape the JSON string metacharacters AND every remaining C0 control
        // char (U+0000..U+001F). Handling only \ " \n \r \t is not enough: a raw
        // control char like U+0008 (backspace) or U+000B would pass through
        // verbatim, producing JSON that strict parsers reject inside the E2EE
        // envelope, which renders as a failure on the other clients. Anything
        // else < 0x20 is emitted as a \uXXXX escape.
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c < ' ') {
                        sb.append("\\u")
                        sb.append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        return sb.toString()
    }
}

data class ChecklistItem(val text: String, val checked: Boolean = false)

/**
 * An owned note whose content-key wrap does not open under this device's vault
 * key. Saving it is refused rather than re-keyed: see [NoteRepository]'s owner
 * path. [message] is written for the person, not the log.
 */
class NoteKeyUnavailableException(val noteId: String) : IllegalStateException(
    "This note can't be opened on this device, so saving it here would lock it " +
        "on your other devices. Please sign in again, then edit it.",
)
