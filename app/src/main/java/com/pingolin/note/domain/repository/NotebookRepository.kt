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
import com.pingolin.note.widget.WidgetUpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notebook CRUD.
 *
 * Notebook *sharing* is not handled here — the live path is
 * [NotebookShareWriter] (per-note wrapped content keys). This class only creates,
 * renames and deletes notebooks and moves notes in and out: child notes inherit
 * the notebook's circleId on add, and deleteNotebook revokes any standing shares
 * via NotebookShareWriter for forward secrecy.
 */
@Singleton
class NotebookRepository @Inject constructor(
    private val itemDao: ItemDao,
    private val appContext: android.app.Application,
    private val sessionManager: com.pingolin.note.util.SessionManager,
    private val notebookShareWriter: NotebookShareWriter,
    private val noteCrypto: com.pingolin.note.collab.NoteCrypto,
) {
    /**
     * The vault key as raw bytes, or null if there is no usable one.
     *
     * Same shape as NoteRepository's: `getMasterKey()` hands back base64 and
     * the wrap/unwrap primitives take bytes.
     */
    private fun vaultKeyBytes(): ByteArray? {
        val b64 = sessionManager.getMasterKey() ?: return null
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            if (bytes.size == 32) bytes else null
        } catch (_: Exception) { null }
    }
    /**
     * Every notebook the user has, from BOTH places they can live.
     *
     * Android creates notebooks as item_type=2 rows. Web creates them only
     * inside the item_type=10 user-metadata blob and never writes a type-2 row,
     * so a notebook made on web is invisible to a plain
     * `itemDao.observeNotebooks()`. Android mirrors its own notebooks INTO the
     * metadata (see createNotebook) so web sees those — this is the other half
     * of that bridge, done here so every caller gets the same union.
     *
     * Web-only notebooks are synthesised as ItemEntity rather than modelling a
     * second type, so callers need no changes. They carry no encrypted content
     * because there is no row to decrypt — anything needing the ciphertext
     * (rename, delete, share) still has to go through the type-2 path, which is
     * why they are marked with [WEB_ONLY_MARKER].
     */
    fun observeNotebooks(): Flow<List<ItemEntity>> =
        itemDao.observeNotebooks().map { local ->
            val mine = local.filter { isOurs(it) }
            val localIds = mine.map { it.id }.toSet()
            mine + webOnlyNotebooks().filterNot { it.id in localIds }
        }

    /**
     * Is this notebook row OURS, rather than one shared with us?
     *
     * `/sync/pull` returns items shared TO this user, so a notebook someone
     * shared lands in Room like any other row — and an unfiltered
     * `item_type = 2` query then lists it among the user's own notebooks. It
     * belongs under "Shared Notebooks", where it can be accepted or declined,
     * not silently mixed in with the ones they made.
     *
     * A locally-created row carries an empty `user_id` until a pull fills it
     * in, so blank counts as ours.
     */
    private fun isOurs(notebook: ItemEntity): Boolean {
        if (notebook.userId.isBlank()) return true
        // Compared canonically; without our own id nothing is
        // ours.
        val me = sessionManager.getUserId() ?: return false
        return com.pingolin.note.util.AccountIds.same(notebook.userId, me)
    }

    /** Notebooks that exist only in the web-format metadata blob. */
    private fun webOnlyNotebooks(): List<ItemEntity> {
        val json = sessionManager.getWebNotebooks() ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val nb = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = nb.optString("id").ifBlank { return@mapNotNull null }
                ItemEntity(
                    id = id,
                    userId = sessionManager.getUserId().orEmpty(),
                    itemType = 2,
                    encryptedContent = "",
                    contentNonce = "",
                    decryptedContent = WEB_ONLY_MARKER,
                    title = nb.optString("name").ifBlank { "Untitled" },
                    version = 0,
                    createdAt = java.time.Instant.EPOCH,
                    updatedAt = java.time.Instant.EPOCH,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun observeSharedNotebooks(): Flow<List<ItemEntity>> = itemDao.observeSharedNotebooks()
    fun observeNotebookItems(notebookId: String): Flow<List<ItemEntity>> =
        itemDao.observeNotebookItems(notebookId)

    suspend fun createNotebook(title: String, color: String? = null): ItemEntity {
        val id = UUID.randomUUID().toString()
        val content = NoteContent(
            title = title,
            body = "",
            noteType = "text",
            color = color,
        )
        val json = content.toJson()
        val vaultBytes = vaultKeyBytes()
            ?: throw IllegalStateException("Can't open your notes on this device. Please sign in again.")

        // A PER-NOTEBOOK content key, vault-wrapped — not vault-direct
        // encryption. A notebook encrypted under the vault key itself can only
        // ever be read by its owner, because sharing it would mean sharing the
        // vault. With a content key the notebook is an ordinary shareable item
        // on the same wrap/seal/sign path notes already use, and both clients
        // write the same format.
        //
        // AAD v2 binds the ciphertext to this notebook's id, matching notes
        // here and `encryptWithContentKey(json, key, id)` on web (whose
        // AAD_V2_WRITE_ENABLED is true) — a notebook written without it would
        // decrypt only by web's v1 fallback.
        //
        // Off the Main thread: these are UniFFI/JNI hops that ANR low-end
        // devices, and every caller launches on Main. Same treatment the note
        // paths get (see NoteRepository.createNote); no view is touched here.
        val (encrypted, wrapped) = withContext(Dispatchers.Default) {
            val contentKey = noteCrypto.generateContentKey()
            noteCrypto.encryptNoteWithContentKeyV2(json, contentKey, id) to
                noteCrypto.wrapWithVault(contentKey, vaultBytes)
        }

        val entity = ItemEntity(
            id = id,
            userId = "",
            itemType = 2, // notebook
            encryptedContent = encrypted.ciphertextB64,
            contentNonce = encrypted.nonceB64,
            decryptedContent = json,
            title = title,
            version = 1,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            color = color,
            isDirty = true,
            contentSize = encrypted.ciphertextB64.length,
            wrappedContentKey = wrapped.wrappedKeyB64,
            wrappedContentKeyNonce = wrapped.nonceB64,
        )

        itemDao.upsert(entity)
        // Mirror into the web-format metadata item so the web (which reads
        // notebooks only from item_type=10, not Android's item_type=2) lists
        // this notebook. Best-effort; the item_type=2 row still syncs for
        // other Android devices.
        notebookShareWriter.upsertNotebookInMetadata(id, title)
        WidgetUpdateHelper.notifyDataChanged(appContext)
        return entity
    }

    /**
     * Give every legacy vault-direct notebook a wrapped content key.
     *
     * Notebooks written before [createNotebook] used per-notebook content keys
     * are encrypted under the vault key itself, which leaves
     * `wrapped_content_key` NULL — and a notebook with no wrap has no key to
     * seal to a recipient, so sharing it silently grants nothing. A rename
     * upgrades one too, but renaming is not something a user should have to do
     * to make sharing work.
     *
     * Deliberately conservative:
     *
     *  - **Only notebooks we own.** A row whose `user_id` is someone else's is
     *    skipped: re-encrypting another account's notebook under OUR vault
     *    would put it beyond its owner. Locally-created rows carry an empty
     *    `user_id` until a pull fills it in, so empty counts as ours.
     *  - **Only notebooks we can actually read.** No `decryptedContent` means
     *    the row has not been decrypted yet (or cannot be); the backfill runs
     *    before this and a later cycle picks it up. Re-encrypting content we
     *    never read would destroy it.
     *  - **`updated_at` is left alone.** It is the pull cursor
     *    (`getMaxUpdatedAt`), so bumping it for an internal format change would
     *    move the cursor past server changes we have not seen.
     *
     * The rows are marked dirty and go up on the next drain — which is a PUT,
     * so [NoteRepository.retryDirtyItems] must include item_type 2 or the
     * upgrade never leaves the device.
     */
    suspend fun upgradeLegacyNotebooks(actingUser: String?): Int {
        // Only this account's rows, compared canonically; without
        // its id, none: re-encrypting a row under this vault is
        // for our own.
        val me = com.pingolin.note.util.AccountIds.canonical(actingUser) ?: return 0
        val vaultBytes = vaultKeyBytes() ?: return 0
        var upgraded = 0
        for (notebook in itemDao.getNotebooksWithoutWrap()) {
            if (notebook.userId.isNotEmpty() && !com.pingolin.note.util.AccountIds.same(notebook.userId, me)) continue
            val json = notebook.decryptedContent ?: continue
            if (json == WEB_ONLY_MARKER) continue

            val (encrypted, wrapped) = withContext(Dispatchers.Default) {
                val contentKey = noteCrypto.generateContentKey()
                noteCrypto.encryptNoteWithContentKeyV2(json, contentKey, notebook.id) to
                    noteCrypto.wrapWithVault(contentKey, vaultBytes)
            }
            itemDao.upsert(
                notebook.copy(
                    encryptedContent = encrypted.ciphertextB64,
                    contentNonce = encrypted.nonceB64,
                    contentSize = encrypted.ciphertextB64.length,
                    wrappedContentKey = wrapped.wrappedKeyB64,
                    wrappedContentKeyNonce = wrapped.nonceB64,
                    isDirty = true,
                ),
            )
            upgraded++
        }
        if (upgraded > 0) android.util.Log.i(TAG, "Upgraded $upgraded legacy notebook(s) to a wrapped content key")
        return upgraded
    }

    suspend fun renameNotebook(notebookId: String, newTitle: String) {
        val notebook = itemDao.getById(notebookId) ?: return
        val content = NoteContent(
            title = newTitle,
            body = "",
            noteType = "text",
            color = notebook.color,
        )
        val json = content.toJson()
        val vaultBytes = vaultKeyBytes()
            ?: throw IllegalStateException("Can't open your notes on this device. Please sign in again.")

        // REUSE the notebook's existing content key whenever it can be
        // opened.
        //
        // Rotating on a rename would silently lock out every member:
        // they hold the current key and a rename is not a revocation.
        // Minting happens only where there is genuinely no key to
        // reuse — a notebook written before this format, where there
        // is no member to lock out yet — which is also how those
        // notebooks become shareable without the user having to
        // recreate them.
        //
        // Nor may the body be encrypted vault-direct while any
        // existing wrapped_content_key stays on the row: for a
        // notebook created on web that leaves a wrap which does not
        // open the content. Reads would survive on
        // decryptItemContent's vault-direct fallback, so nothing would
        // look wrong, but the stored key would be a lie and sharing
        // the notebook would seal a useless key to every recipient.
        val existingKey = withContext(Dispatchers.Default) {
            val wrap = notebook.wrappedContentKey
            val wrapNonce = notebook.wrappedContentKeyNonce
            if (wrap != null && wrapNonce != null) {
                noteCrypto.unwrapWithVault(wrap, wrapNonce, vaultBytes)
            } else {
                null
            }
        }
        // A wrap that exists and will not open means this device cannot read
        // the notebook — under a recovered identity, or a key from another era.
        // Minting a fresh one here would overwrite the only pointer back to the
        // real key and drop the roster with it, so refuse rather than "repair".
        if (existingKey == null && notebook.wrappedContentKey != null) {
            android.util.Log.w(
                TAG,
                "Rename skipped for notebook $notebookId: its content key does not open with this vault key",
            )
            return
        }

        // Off Main for the same reason as createNotebook.
        val (encrypted, wrapped) = withContext(Dispatchers.Default) {
            val contentKey = existingKey ?: noteCrypto.generateContentKey()
            noteCrypto.encryptNoteWithContentKeyV2(json, contentKey, notebookId) to
                // Send a wrap only when the key is NEW. Re-sending an unchanged
                // one is pointless, and omitting it leaves the server's COALESCE
                // to keep what is already stored.
                if (existingKey == null) noteCrypto.wrapWithVault(contentKey, vaultBytes) else null
        }

        val updated = notebook.copy(
            title = newTitle,
            encryptedContent = encrypted.ciphertextB64,
            contentNonce = encrypted.nonceB64,
            decryptedContent = json,
            isDirty = true,
            updatedAt = Instant.now(),
            wrappedContentKey = wrapped?.wrappedKeyB64 ?: notebook.wrappedContentKey,
            wrappedContentKeyNonce = wrapped?.nonceB64 ?: notebook.wrappedContentKeyNonce,
        )
        itemDao.upsert(updated)
        notebookShareWriter.upsertNotebookInMetadata(notebookId, newTitle)
        WidgetUpdateHelper.notifyDataChanged(appContext)
    }

    /**
     * Delete a notebook, moving its child notes out (clears their notebook_id).
     *
     * ⚠️ NOT CALLED BY THE APP (`NativeNotebookMovesAreNotWiredTest`), and not
     * to be wired up as it is: the unshare below is best-effort, and with no
     * connection it does nothing while the children are moved out all the same.
     * They would stay shared with the notebook's people.
     */
    suspend fun deleteNotebook(notebookId: String) {
        // Forward secrecy: if this notebook was shared, revoke every
        // recipient and rotate each child note's content key BEFORE the
        // children move out to personal. Otherwise the standing per-note
        // shares — and the recipients' cached inbox content keys — survive
        // the delete and keep decrypting every future edit. Best-effort
        // (no-op for an unshared notebook); runs first so getNotesInNotebook
        // still sees the children.
        notebookShareWriter.unshareNotebookFromAll(notebookId)
        val children = observeNotebookItems(notebookId).first()
        for (child in children) {
            val updated = child.copy(
                notebookId = null,
                circleId = null,
                isDirty = true,
                updatedAt = Instant.now(),
            )
            itemDao.upsert(updated)
        }
        itemDao.softDelete(notebookId, Instant.now().toEpochMilli())
        notebookShareWriter.removeNotebookFromMetadata(notebookId)
        WidgetUpdateHelper.notifyDataChanged(appContext)
    }

    /**
     * ⚠️ NOT CALLED BY THE APP (`NativeNotebookMovesAreNotWiredTest`), and not
     * to be wired up as it is: it changes the notebook on the row and nothing
     * else. A note put in a shared notebook this way is passed on to nobody.
     */
    suspend fun addNoteToNotebook(noteId: String, notebookId: String) {
        val note = itemDao.getById(noteId) ?: return
        val notebook = itemDao.getById(notebookId) ?: return

        val updated = note.copy(
            notebookId = notebookId,
            circleId = notebook.circleId,
            isDirty = true,
            updatedAt = Instant.now(),
        )
        itemDao.upsert(updated)
        WidgetUpdateHelper.notifyDataChanged(appContext)
    }

    /**
     * ⚠️ NOT CALLED BY THE APP (`NativeNotebookMovesAreNotWiredTest`), and not
     * to be wired up as it is: it changes the notebook on the row and nothing
     * else. A note taken out of a shared notebook this way stays shared with
     * that notebook's people, and keeps the key they hold.
     */
    suspend fun removeNoteFromNotebook(noteId: String) {
        val note = itemDao.getById(noteId) ?: return
        val updated = note.copy(
            notebookId = null,
            circleId = null, // back to personal
            isDirty = true,
            updatedAt = Instant.now(),
        )
        itemDao.upsert(updated)
        WidgetUpdateHelper.notifyDataChanged(appContext)
    }

    companion object {
        private const val TAG = "NotebookRepository"

        /** Marks a notebook that exists only in the metadata blob, so callers
         *  can tell it apart from one backed by a real item_type=2 row. */
        const val WEB_ONLY_MARKER = "{\"webOnly\":true}"
    }
}
