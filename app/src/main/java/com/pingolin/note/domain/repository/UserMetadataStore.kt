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
import com.pingolin.note.util.EditorModePins
import com.pingolin.note.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single read-modify-write for the encrypted user-metadata item
 * (item_type=10) — the one per-user blob that carries the notebook list, label
 * colours, notebook share recipients and per-note editor-mode pins.
 *
 * WHY ONE PLACE. Every client rewrites the WHOLE blob, so a writer that
 * rebuilds it from the fields it happens to model deletes everything it does
 * not. Mutating the parsed `JSONObject` in place is what makes android safe
 * for fields it has never heard of, and that guarantee is only worth anything
 * if there is exactly one writer holding it; a second copy of this logic is
 * the obvious way for two writers to drift apart.
 *
 * The compare-and-swap on confirm and the duplicate-POST avoidance below are
 * load-bearing.
 */
@Singleton
class UserMetadataStore @Inject constructor(
    private val noteApi: NoteApi,
    private val itemDao: ItemDao,
    private val noteCrypto: NoteCrypto,
    private val sessionManager: SessionManager,
) {
    /**
     * Serialises the whole read-modify-write below — fetch AND save, not just
     * the save.
     *
     * [mutateLocked] POSTs a NEW row whenever the fetch handed it none, so the
     * choice between PUT and POST is made from what THAT fetch saw: two calls
     * that overlap both see "no row", both create one, and from then on every
     * client rotates between the two copies at random. Caching the created row
     * (the `else` branch's `itemDao.upsert`) cannot close that — the row only
     * exists once the POST has RETURNED, which by definition has not happened
     * while the competing writer is still inside its own fetch (two fresh
     * accounts end up with two rows, both at version 1). What closes it is a
     * lock spanning fetch→save, as the iOS app has too. This is that lock.
     *
     * EVERY writer must come through it — a pin write interleaving with a
     * notebook-share write duplicates just as well as two pin writes do — which
     * is why [mutateLocked] is private and reachable only via [mutate] or
     * [mutatePins], and why [refreshCaches] takes it too.
     *
     * Not reentrant (a kotlinx `Mutex` never is): nothing inside a transform may
     * call back into this class.
     *
     * Scope is this process. As a @Singleton it covers every writer on the
     * device; two DEVICES writing at once still duplicate, and that needs the
     * server-side partial unique index rather than anything here.
     */
    private val writeLock = Mutex()

    /** The newest metadata row that is THIS account's: another owner's row — a shared or
     * served one — is never read as ours. None without a known account. */
    private suspend fun ownMetadataItem(): com.pingolin.note.data.local.entity.ItemEntity? {
        val me = sessionManager.getUserId() ?: return null
        return itemDao.getMetadataItems().firstOrNull { com.pingolin.note.util.AccountIds.same(it.userId, me) }
    }

    /** The blob as it stands, decrypted, or null when there is nothing to read
     *  (no vault key, no item, or it will not decrypt).
     *
     *  "The blob" is the newest-by-`updated_at` row of THIS account's ([ownMetadataItem]), so
     *  this — and therefore [refreshCaches] and every write below — is
     *  newest-wins even when the account has collected duplicate rows. */
    suspend fun read(): String? {
        val vaultBytes = sessionManager.getMasterKey()?.let {
            try { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
        } ?: return null
        if (vaultBytes.size != 32) return null
        return ownMetadataItem()?.let { decryptMetadata(it, vaultBytes) }
    }

    /**
     * Re-derive the in-memory display caches — notebooks, notebook share
     * recipients and editor-mode pins — from the NEWEST metadata row this
     * device holds.
     *
     * This is the display path. Applying the three `save*` setters for EVERY
     * item_type=10 row in a pulled page would let whichever duplicate row the
     * loop met LAST win — correct only as long as the server serves the page
     * oldest-first, and no better than taking the FIRST row of that stream.
     * Reading back through [read] makes newest-wins explicit and independent of
     * both the stream order and the page boundaries.
     *
     * Takes [writeLock] so it cannot land between a [mutateLocked] refreshing
     * the caches optimistically and that same call writing its row back to
     * Room: in that window the newest row is still the PRE-write one, and
     * reading it would revert what the user just did.
     */
    suspend fun refreshCaches() {
        writeLock.withLock {
            val json = read() ?: return
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return
            applyCaches(root)
        }
    }

    /**
     * Read-modify-write the encrypted user-metadata item (item_type=10):
     * decrypt (or start from default), apply [transform] to the JSON root,
     * refresh the in-memory caches, and push it. PUT if it exists, else POST;
     * either way the returned item is stored locally with its new version so
     * a later write updates it (avoids a stale-version 409 or a duplicate
     * metadata item). Best-effort.
     *
     * Serialised against every other writer by [writeLock]; see there for why
     * the critical section has to span the fetch as well as the save.
     */
    suspend fun mutate(transform: (JSONObject) -> Unit) =
        runWrite { root -> transform(root); true }

    /**
     * One read-modify-write, retried once if the server tells us this account
     * already has a metadata row we did not know about.
     *
     * The server holds at most one live item_type=10 row per user (a partial
     * unique index), so the POST branch below does not quietly create a second
     * row: it comes back 409 `item_metadata_exists`. A client that ignores the
     * 409 throws the user's notebook / share / pin away, with no crash and
     * nothing on screen — the notebook item is created and the metadata row
     * stays byte-identical, so the write simply vanishes. So the rejection is
     * recovered from, not swallowed.
     *
     * [adoptServerMetadataRow] runs OUTSIDE the lock: it is a paged network
     * scan, and holding the section across it would stall every other writer
     * for no reason — it only teaches Room about a row the server already has.
     * Another writer slipping in between the two attempts is harmless; every
     * transform here means "apply this change to whatever blob you are handed",
     * so the retry re-applies on top of whatever landed.
     */
    private suspend fun runWrite(transform: (JSONObject) -> Boolean) {
        if (!writeLock.withLock { mutateLocked(transform) }) return
        if (!adoptServerMetadataRow()) return
        // Exactly one retry. If the second attempt is rejected the same way,
        // stop: another writer is creating rows faster than we can adopt them,
        // and looping would spin against the server rather than converge.
        writeLock.withLock { mutateLocked(transform) }
    }

    /**
     * The body of [mutate]. Callers MUST already hold [writeLock].
     *
     * [transform] returns false for "nothing changed", which skips the encrypt
     * and the round-trip — the guard has to be in here rather than at the call
     * site, because "nothing changed" is only meaningful against the blob this
     * critical section fetched.
     *
     * Returns true when the write could not be applied because the server
     * already holds a metadata row this device has never seen — the caller
     * should adopt it and come back. False in every other case, including
     * success and every ordinary failure (this is still best-effort).
     */
    private suspend fun mutateLocked(transform: (JSONObject) -> Boolean): Boolean {
        val vaultBytes = sessionManager.getMasterKey()?.let {
            try { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
        } ?: return false
        if (vaultBytes.size != 32) return false

        val metaItem = ownMetadataItem()
        // A list this device cannot read is never written over: nothing is sent. After a
        // password reset the list on the server is sealed with the old key until the page
        // rebuilds it, keeping what it cannot open (the web editor's `lockedListRow`);
        // starting from an empty list here would put an empty one in its place, and the
        // notebooks it named would be gone on every device. With no row at all, a new
        // list is started.
        val baseJson = if (metaItem == null) defaultMetadata() else decryptMetadata(metaItem, vaultBytes) ?: run {
            android.util.Log.w(TAG, "The notebook list does not open with this account's key; not writing over it")
            return false
        }
        val root = try {
            JSONObject(baseJson)
        } catch (_: Exception) {
            if (metaItem != null) {
                android.util.Log.w(TAG, "The notebook list opens but cannot be read; not writing over it")
                return false
            }
            JSONObject(defaultMetadata())
        }

        if (!transform(root)) return false

        // Refresh in-memory caches so the notebook list / fan-out reflect the
        // change before the next sync pull.
        applyCaches(root)

        val jsonOut = root.toString()
        // Same choke-point rule as decryptMetadata: the seal is SDK work and
        // this path is reached from pin/notebook writes on Main.
        val encrypted = withContext(Dispatchers.Default) {
            runCatching { noteCrypto.encryptNoteWithContentKey(jsonOut, vaultBytes) }.getOrNull()
        } ?: return false

        if (metaItem != null) {
            val response = runCatching {
                noteApi.updateItem(
                    metaItem.id,
                    UpdateItemRequest(
                        encryptedContent = encrypted.ciphertextB64,
                        contentNonce = encrypted.nonceB64,
                        expectedVersion = metaItem.version,
                    ),
                )
            }.getOrNull() ?: return false

            // A 409 here means our `expectedVersion` was stale:
            // another client changed the row between our fetch and
            // this PUT. Ask [runWrite] to re-pull and replay,
            // exactly as the create branch does for
            // `item_metadata_exists`.
            //
            // Falling through to `return false` would lose the
            // change: a 409 makes `body()` null, so the `.let` below
            // is skipped, and the user's notebook / pin / share
            // recipient is gone with no crash and nothing on screen.
            // With the retry the result composes ONTO the other
            // client's blob rather than replacing it; the cost is
            // the pull plus the replayed PUT (about 1.3-1.7s
            // tap-to-commit against ~870ms uncontended).
            //
            // "The next sync reconciles it" is true of the ROW and
            // false of the user's change: the sync brings the row up
            // to date and the edit is not in it.
            if (response.code() == 409) return true

            response.body()?.data?.let { dto ->
                    // Keep the local copy + version current so the next write
                    // doesn't PUT with a stale expectedVersion (409) — but as a
                    // compare-and-swap, not a blind upsert of the pre-PUT
                    // `metaItem` snapshot, which would revert a newer metadata
                    // copy that landed during the round-trip. See
                    // ItemDao.confirmMetadataWriteIfUnchanged.
                    itemDao.confirmMetadataWriteIfUnchanged(
                        id = metaItem.id,
                        ciphertext = encrypted.ciphertextB64,
                        nonce = encrypted.nonceB64,
                        decrypted = jsonOut,
                        version = dto.version,
                        updatedAt = java.time.Instant.parse(dto.updatedAt),
                        expectedCiphertext = metaItem.encryptedContent,
                        expectedNonce = metaItem.contentNonce,
                    )
            }
        } else {
            val response = runCatching {
                noteApi.createItem(
                    CreateItemRequest(
                        itemType = 10,
                        encryptedContent = encrypted.ciphertextB64,
                        contentNonce = encrypted.nonceB64,
                    ),
                )
            }.getOrNull() ?: return false

            val dto = response.body()?.data
            if (dto != null) {
                // Cache the new metadata item locally so a rapid second
                // write updates it instead of POSTing a duplicate.
                runCatching {
                    itemDao.upsert(
                        com.pingolin.note.data.local.entity.ItemEntity(
                            id = dto.id,
                            userId = dto.userId,
                            itemType = 10,
                            encryptedContent = encrypted.ciphertextB64,
                            contentNonce = encrypted.nonceB64,
                            decryptedContent = jsonOut,
                            title = null,
                            version = dto.version,
                            createdAt = java.time.Instant.parse(dto.createdAt),
                            updatedAt = java.time.Instant.parse(dto.updatedAt),
                            isDirty = false,
                            contentSize = dto.contentSize,
                        ),
                    )
                }
                return false
            }

            // 409 means the account already has a live metadata row (the
            // server's partial unique index) — another device created it, or
            // our own pull has not reached it. Ask [runWrite] to fetch that row
            // and replay this write onto it; returning quietly here is what
            // loses the user's notebook. This create sends no client-supplied
            // `id`, and `item_already_exists` is the only other 409 the
            // endpoint can raise, so a 409 here has exactly one meaning.
            return response.code() == 409
        }
        // The PUT branch. A stale-version 409 here is a different animal — the
        // row exists and we already know about it — so the next sync reconciles
        // it and there is nothing to adopt.
        return false
    }

    /**
     * Fetch the metadata row the server says this account already has, and
     * store it so the retry in [runWrite] takes the PUT branch.
     *
     * There is no "get my metadata item" endpoint, so this walks /sync/pull the
     * way the sync loop does. Bounded by [MAX_ADOPT_PAGES] rather than run to
     * exhaustion: it is a recovery path, and a client that pages forever
     * because the server keeps claiming `hasMore` is worse than one that gives
     * up and lets the next ordinary sync land the row.
     *
     * `lastOrNull` because the server serves a page in ascending `updated_at`
     * order, so the last metadata row in it is the newest — the same
     * newest-wins rule [read] applies. Only the ciphertext is stored, not a
     * decrypted copy: [decryptMetadata] decrypts from the row's own
     * ciphertext + nonce, so the retry needs nothing more.
     */
    private suspend fun adoptServerMetadataRow(): Boolean {
        var since = java.time.Instant.EPOCH.toString()
        var sinceId: String? = null
        repeat(MAX_ADOPT_PAGES) {
            val body = runCatching { noteApi.syncPull(since = since, sinceId = sinceId, limit = 100) }
                .getOrNull()?.body()?.data ?: return false
            val meta = body.items.lastOrNull { it.itemType == 10 && !it.deleted }
            if (meta != null) {
                runCatching {
                    itemDao.upsert(
                        com.pingolin.note.data.local.entity.ItemEntity(
                            id = meta.id,
                            userId = meta.userId,
                            itemType = 10,
                            encryptedContent = meta.encryptedContent,
                            contentNonce = meta.contentNonce,
                            decryptedContent = null,
                            title = null,
                            version = meta.version,
                            createdAt = java.time.Instant.parse(meta.createdAt),
                            updatedAt = java.time.Instant.parse(meta.updatedAt),
                            isDirty = false,
                            contentSize = meta.contentSize,
                        ),
                    )
                }.getOrElse { return false }
                return true
            }
            if (!body.hasMore) return false
            since = body.cursor ?: return false
            sinceId = body.cursorId
        }
        return false
    }

    /** Mirror the fields the UI reads out of [root] into the SessionManager
     *  caches. The single place that writes them, so a write and a sync land
     *  the same three keys from the same shape of blob. */
    private fun applyCaches(root: JSONObject) {
        root.optJSONObject("notebookShares")?.let { sessionManager.saveNotebookShares(it.toString()) }
        root.optJSONArray("notebooks")?.let { sessionManager.saveWebNotebooks(it.toString()) }
        // Unconditionally, unlike the two above: the pin map is the one field
        // whose EMPTY state is meaningful. "Reset all notes to the default"
        // removes every key, and an `optJSONObject(...)?.let` would skip the
        // cache refresh for exactly that case and leave the stale pins on screen.
        sessionManager.saveEditorModes(
            (root.optJSONObject("editorModes") ?: JSONObject()).toString(),
        )
    }

    private suspend fun decryptMetadata(
        meta: com.pingolin.note.data.local.entity.ItemEntity,
        vaultBytes: ByteArray,
    ): String? = withContext(Dispatchers.Default) {
        // The metadata item is encrypted directly under the vault key, not
        // under a per-item content key — matching what the web client writes.
        //
        // Hopped here, at the choke point, rather than at the five call paths
        // (gcPins on each pull, read() from the export, setPin from the
        // editor): each runs a full SDK decrypt of the metadata blob, which
        // must not run on Main.
        try {
            noteCrypto.decryptNoteWithContentKey(meta.contentNonce, meta.encryptedContent, vaultBytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultMetadata(): String =
        "{\"notebooks\":[],\"labelColors\":{},\"notebookShares\":{}}"

    // --- Per-note editor mode pins ---

    /** The pins as last synced or written. Reads the cache rather than the
     *  blob so the editor can resolve a mode without a decrypt on every open. */
    fun pins(): Map<String, String> =
        EditorModePins.sanitize(
            runCatching { JSONObject(sessionManager.getEditorModes() ?: "{}") }.getOrNull(),
        )

    /**
     * Read-modify-write the pin map, with [transform] fed the pins out of the
     * blob this critical section just fetched — NOT out of [pins].
     *
     * That is as load-bearing as the lock itself. These writers replace the
     * WHOLE `editorModes` object, so computing the replacement from the
     * SessionManager cache outside the section means a write that lands while
     * you were deciding gets rebuilt over and thrown away: two pins set in
     * quick succession, and the first one is gone. The iOS app takes its map
     * from the fetched metadata for the same reason.
     *
     * Returning null means "no change", which skips the round-trip: this blob
     * is shared with notebooks and label colours, and a pointless write would
     * bump its version and race another device for nothing.
     */
    private suspend fun mutatePins(transform: (Map<String, String>) -> Map<String, String>?) {
        runWrite { root ->
            val next = transform(EditorModePins.sanitize(root.optJSONObject("editorModes")))
                ?: return@runWrite false
            root.put("editorModes", EditorModePins.toJson(next))
            true
        }
    }

    /** Pin [noteId] to [mode], or un-pin it when [mode] is the current
     *  [global]. */
    suspend fun setPin(noteId: String, mode: String, global: String) =
        mutatePins { current ->
            EditorModePins.withMode(current, noteId, mode, global).takeIf { it != current }
        }

    /** Forget every pin. */
    suspend fun clearPins() =
        mutatePins { current -> EditorModePins.cleared(current).takeIf { it != current } }

    /** Drop pins whose note is gone. Takes the FULL local note id set — a
     *  partial one would collect pins for notes merely absent from it, which
     *  is silent data loss rather than housekeeping. */
    suspend fun gcPins(liveNoteIds: Set<String>) =
        mutatePins { current -> EditorModePins.gc(current, liveNoteIds).takeIf { it != current } }

    private companion object {
        const val TAG = "UserMetadataStore"

        /** Pages [adoptServerMetadataRow] will walk before giving up. 100 items
         *  a page, so 20 covers 2000 items; beyond that the next ordinary sync
         *  is the better fix. */
        const val MAX_ADOPT_PAGES = 20
    }
}
