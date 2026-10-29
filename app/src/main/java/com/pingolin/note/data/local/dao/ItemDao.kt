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

package com.pingolin.note.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.pingolin.note.data.local.entity.ItemEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ItemDao {

    // Ordering: pinned first, then the user's drag order
    // (sort_order ASC = top), with updated_at DESC as the tiebreaker
    // for rows that have never been dragged (sort_order defaults to 0
    // so untouched rows still feel like "newest first").
    @Query("SELECT * FROM items WHERE item_type = 1 AND deleted = 0 AND is_archived = 0 ORDER BY is_pinned DESC, sort_order ASC, updated_at DESC")
    fun observeActiveNotes(): Flow<List<ItemEntity>>

    /** Paginated active notes for large collections */
    @Query("SELECT * FROM items WHERE item_type = 1 AND deleted = 0 AND is_archived = 0 ORDER BY is_pinned DESC, sort_order ASC, updated_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getActiveNotesPaged(limit: Int, offset: Int): List<ItemEntity>

    /** Count of active notes (for pagination UI) */
    @Query("SELECT COUNT(*) FROM items WHERE item_type = 1 AND deleted = 0 AND is_archived = 0")
    suspend fun countActiveNotes(): Int

    @Query("SELECT * FROM items WHERE item_type = 1 AND deleted = 0 AND is_archived = 1 ORDER BY sort_order ASC, updated_at DESC")
    fun observeArchivedNotes(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE item_type = 1 AND deleted = 1 ORDER BY sort_order ASC, updated_at DESC")
    fun observeTrash(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getById(id: String): ItemEntity?

    /** Batch fetch by id — the sync pull loads a whole page's local rows in
     *  one query instead of an N+1 getById per item. */
    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ItemEntity>)

    /**
     * Insert only the rows whose id is NOT already present, and report how many
     * were actually new.
     *
     * For restoring a backup. A restore carries the notes' original ids, which
     * is what makes restoring the same file twice converge — but it means every
     * row collides with the live note on the second run, and [upsertAll] is
     * REPLACE, so it would overwrite each one with the backup's older content.
     * A restore must never quietly wind a user's newer edits back.
     *
     * Room returns the inserted rowid per item and -1 for each one it ignored,
     * so the caller can tell "restored" from "already had it" without a second
     * query.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsentAll(items: List<ItemEntity>): List<Long>

    /**
     * Upsert a pulled page WITHOUT ever overwriting a row that currently holds
     * un-pushed local changes (is_dirty = 1). The dirty check and the write run
     * in ONE transaction, so an interactive edit that lands between them can't
     * be clobbered — closing the pull-merge TOCTOU where the caller's
     * pre-decrypt snapshot of who-was-dirty has gone stale by write time.
     * Returns the number of rows skipped because they were dirty.
     *
     * The IN() dirty lookup is chunked under SQLite's variable limit (999 on
     * pre-3.32 engines): sync pull pages at ≤100 ids (SyncRepository limit=100),
     * but the undecrypted-backfill batch (getUndecryptedItems) is unbounded.
     */
    @Transaction
    suspend fun upsertAllPreservingDirty(items: List<ItemEntity>): Int {
        if (items.isEmpty()) return 0
        val dirtyIds = HashSet<String>()
        items.chunked(900).forEach { chunk ->
            dirtyIds.addAll(getDirtyIdsAmong(chunk.map { it.id }))
        }
        val safe = items.filterNot { it.id in dirtyIds }
        if (safe.isNotEmpty()) upsertAll(safe)
        return items.size - safe.size
    }

    /** Ids among [ids] whose row currently has un-pushed local changes. */
    @Query("SELECT id FROM items WHERE id IN (:ids) AND is_dirty = 1")
    suspend fun getDirtyIdsAmong(ids: List<String>): List<String>

    /**
     * Confirm a successfully-drained (PUT-accepted) note. Adopts the server
     * [version] and clears is_dirty, but clears ONLY if the row still holds
     * exactly the ciphertext/nonce we PUT ([expectedCiphertext]/[expectedNonce])
     * and has not been soft-deleted meanwhile — i.e. no concurrent edit or
     * delete landed during the PUT round-trip.
     *
     * The version is adopted UNCONDITIONALLY. After the drain's PUT the server
     * row sits at [version]; if a concurrent local edit landed in the PUT window
     * (is_dirty stays 1 because the stored ciphertext moved on), that edit's NEXT
     * PUT must send expectedVersion = [version] so it lands cleanly on the server
     * instead of 409-ing and forking a spurious conflict copy.
     *
     * Returns true when is_dirty was cleared, false when the row moved on and was
     * left dirty for the next drain. The check and the write share one
     * transaction so the boolean faithfully reflects the write.
     */
    @Transaction
    suspend fun confirmDrainedItem(
        id: String,
        version: Long,
        expectedCiphertext: String,
        expectedNonce: String,
    ): Boolean {
        val unchanged = isUnchangedActiveRow(id, expectedCiphertext, expectedNonce)
        adoptVersionClearDirtyIfUnchanged(id, version, expectedCiphertext, expectedNonce)
        return unchanged
    }

    /** True when this device holds any row at all, of any type or state. A sign-in with no
     * saved account id treats rows as another account's: nothing says whose they are. */
    @Query("SELECT EXISTS(SELECT 1 FROM items)")
    suspend fun hasAnyRow(): Boolean

    /** True iff [id] still holds exactly this ciphertext/nonce and is not
     *  soft-deleted — the same predicate the confirm-UPDATE's CASE clears on. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM items WHERE id = :id " +
            "AND encrypted_content = :ciphertext AND content_nonce = :nonce AND deleted = 0)"
    )
    suspend fun isUnchangedActiveRow(id: String, ciphertext: String, nonce: String): Boolean

    /**
     * Adopt [version] on [id] unconditionally; clear is_dirty ONLY when the
     * stored ciphertext/nonce still equal what we PUT and the row is not
     * soft-deleted. Single statement so the adopt + conditional-clear are atomic.
     * `encrypted_content` / `content_nonce` are NON-NULL columns, so a plain `=`
     * compare is correct (no NULL-compare pitfall). The `deleted = 0` guard keeps
     * a concurrent, still-unpushed soft-delete's is_dirty=1 so its tombstone
     * isn't silently dropped from the delete drain (softDelete leaves the
     * ciphertext intact, so a bare ciphertext compare would clear it).
     */
    @Query(
        "UPDATE items SET version = :version, " +
            "is_dirty = CASE WHEN encrypted_content = :ciphertext AND content_nonce = :nonce AND deleted = 0 THEN 0 ELSE is_dirty END " +
            "WHERE id = :id"
    )
    suspend fun adoptVersionClearDirtyIfUnchanged(id: String, version: Long, ciphertext: String, nonce: String): Int

    /**
     * Take the server's version for a row we still owe a write, WITHOUT
     * clearing is_dirty.
     *
     * For a 409 on a NOTEBOOK: the drain PUT was rejected because our
     * expectedVersion was stale, and a notebook is a title rather than a
     * document — forking it into a "(conflict copy)" the way notes do would
     * turn a rename race into a stray item. Adopting the version lets the next
     * drain PUT land, so the later rename wins; staying dirty is what makes
     * that next drain happen at all.
     *
     * Guarded on is_dirty = 1 so this can never resurrect a row the drain
     * already cleared between the PUT and this call.
     */
    @Query("UPDATE items SET version = :version WHERE id = :id AND is_dirty = 1")
    suspend fun adoptServerVersionKeepingDirty(id: String, version: Long): Int

    /**
     * Notebooks stored in the legacy form: encrypted under the vault key
     * itself, with no wrapped content key.
     *
     * They cannot be shared — a share has no key to seal to the recipient — so
     * this is the work list for the upgrade sweep. Small by nature (a user has
     * a handful of notebooks, and each row leaves this set permanently once
     * upgraded), so no paging.
     */
    @Query("SELECT * FROM items WHERE item_type = 2 AND deleted = 0 AND wrapped_content_key IS NULL")
    suspend fun getNotebooksWithoutWrap(): List<ItemEntity>

    /**
     * Persist a content-key ROTATION onto [id]'s local row — the re-keyed
     * ciphertext ([ciphertext]/[nonce]), the plaintext that was actually re-keyed
     * ([decrypted]/[title]), the fresh vault wrap ([wrappedKey]/[wrappedNonce])
     * and the server-bumped [version] — and clear is_dirty, but ONLY when the row
     * still holds exactly the pre-rotation ciphertext/nonce the rotation re-keyed
     * ([expectedCiphertext]/[expectedNonce]) and is neither dirty nor
     * soft-deleted.
     *
     * [decrypted]/[title] are NOT optional bookkeeping: `rotate` decrypts the
     * SERVER copy and re-encrypts THAT plaintext, while the CAS matches on the
     * LOCAL ciphertext. On a clean-but-behind row those two are different
     * documents, so writing only the ciphertext would leave encrypted_content
     * holding E_Knew(P_server) while decrypted_content still holds P_local — a
     * row whose cipher and plain halves disagree, which the next edit (built from
     * decrypted_content) would push out as a silent revert of the other device.
     * Writing the re-keyed plaintext alongside its ciphertext keeps the row
     * internally consistent and adopts the authoritative server document in the
     * same statement.
     *
     * A rotation re-encrypts the SAME plaintext under a NEW key, so the stored
     * ciphertext is expected to still equal the pre-rotation snapshot. If it
     * moved on — a concurrent interactive edit wrote new plaintext C1 and flipped
     * is_dirty=1 while its own PUT hadn't landed — this UPDATE matches no row and
     * is a no-op, leaving that dirty edit intact to drain on its own. A blind
     * full-row upsert of the pre-rotation snapshot would instead revert
     * encrypted_content to the re-keyed OLD body AND clear is_dirty — discarding
     * C1 from the DB and the outbox at once. The `deleted = 0` guard likewise
     * preserves a concurrent soft-delete's is_dirty=1 so its tombstone still
     * drains (a rotation leaves the ciphertext intact, so a bare ciphertext
     * compare alone would clear it). Single statement, so the check and the write
     * are atomic. Returns 1 when the rotation was applied, 0 when it was skipped
     * because the row moved on. Sibling of [adoptVersionClearDirtyIfUnchanged].
     */
    @Query(
        "UPDATE items SET encrypted_content = :ciphertext, content_nonce = :nonce, " +
            "decrypted_content = :decrypted, title = :title, " +
            "wrapped_content_key = :wrappedKey, wrapped_content_key_nonce = :wrappedNonce, " +
            "version = :version, is_dirty = 0 " +
            "WHERE id = :id AND encrypted_content = :expectedCiphertext " +
            "AND content_nonce = :expectedNonce AND is_dirty = 0 AND deleted = 0"
    )
    suspend fun writeRotatedItemIfUnchanged(
        id: String,
        ciphertext: String,
        nonce: String,
        decrypted: String?,
        title: String?,
        wrappedKey: String,
        wrappedNonce: String,
        version: Long,
        expectedCiphertext: String,
        expectedNonce: String,
    ): Int

    /**
     * Write-back for an INTERACTIVE owner edit that has already been pushed
     * (or attempted) via `PUT /items/{id}`: the edited body
     * ([ciphertext]/[nonce]/[decrypted]/[title]) plus its display metadata and
     * the note's vault wrap — but ONLY when the row still holds exactly the
     * ciphertext/nonce AND the wrap the edit was built on
     * ([expectedCiphertext]/[expectedNonce]/[expectedWrappedKey]), and has not
     * been soft-deleted meanwhile.
     *
     * The wrap in the predicate is the security-critical half. A blind full-row
     * upsert of the caller's PRE-push snapshot replays whatever
     * `wrapped_content_key` was read before the network round-trip. If a
     * content-key ROTATION commits inside that window (share revoke, notebook
     * unshare), that puts the RETIRED wrap back on the row and the next drain PUT
     * rides it onto the server through the owner-gated
     * COALESCE(wrapped_content_key) — republishing the note under the very key
     * the revoked recipient still holds, while the survivors re-sealed onto the
     * new key are locked out. `wrapped_content_key IS :expectedWrappedKey` is a
     * null-safe compare (SQLite `IS`), so a legacy wrap-absent note still matches
     * on NULL and can take its first mint.
     *
     * is_dirty is deliberately NOT in the predicate: an offline edit chain
     * legitimately re-edits an already-dirty row, and requiring a clean row there
     * would silently drop the newer keystrokes. A concurrent edit is caught by
     * the ciphertext compare instead (every encrypt draws a fresh nonce), and a
     * concurrent soft-delete by `deleted = 0` — softDelete leaves the ciphertext
     * intact, so the ciphertext compare alone would not see it. Returns 1 when
     * the edit was applied, 0 when the row moved on.
     */
    @Query(
        "UPDATE items SET encrypted_content = :ciphertext, content_nonce = :nonce, " +
            "decrypted_content = :decrypted, title = :title, version = :version, " +
            "updated_at = :updatedAt, color = :color, is_pinned = :isPinned, " +
            "is_archived = :isArchived, is_dirty = :isDirty, content_size = :contentSize, " +
            "notebook_id = :notebookId, wrapped_content_key = :wrappedKey, " +
            "wrapped_content_key_nonce = :wrappedNonce, sort_order = :sortOrder " +
            "WHERE id = :id AND encrypted_content = :expectedCiphertext " +
            "AND content_nonce = :expectedNonce " +
            "AND wrapped_content_key IS :expectedWrappedKey AND deleted = 0"
    )
    suspend fun writeEditedItemIfUnchanged(
        id: String,
        ciphertext: String,
        nonce: String,
        decrypted: String?,
        title: String?,
        version: Long,
        updatedAt: Instant,
        color: String?,
        isPinned: Boolean,
        isArchived: Boolean,
        isDirty: Boolean,
        contentSize: Int,
        notebookId: String?,
        wrappedKey: String?,
        wrappedNonce: String?,
        sortOrder: Int,
        expectedCiphertext: String,
        expectedNonce: String,
        expectedWrappedKey: String?,
    ): Int

    /**
     * [writeEditedItemIfUnchanged] for a note SHARED WITH US. Identical guard,
     * but the wrap columns are not in the SET list at all: the per-note
     * `wrapped_content_key` is the OWNER's recovery wrap and a recipient must
     * never write it (the server enforces the same rule: only the owner's
     * update writes it). Encoding it structurally here means no edit to the
     * recipient branch can introduce the write.
     */
    @Query(
        "UPDATE items SET encrypted_content = :ciphertext, content_nonce = :nonce, " +
            "decrypted_content = :decrypted, title = :title, version = :version, " +
            "updated_at = :updatedAt, color = :color, is_pinned = :isPinned, " +
            "is_archived = :isArchived, is_dirty = :isDirty, content_size = :contentSize, " +
            "notebook_id = :notebookId, sort_order = :sortOrder " +
            "WHERE id = :id AND encrypted_content = :expectedCiphertext " +
            "AND content_nonce = :expectedNonce AND deleted = 0"
    )
    suspend fun writeSharedEditIfUnchanged(
        id: String,
        ciphertext: String,
        nonce: String,
        decrypted: String?,
        title: String?,
        version: Long,
        updatedAt: Instant,
        color: String?,
        isPinned: Boolean,
        isArchived: Boolean,
        isDirty: Boolean,
        contentSize: Int,
        notebookId: String?,
        sortOrder: Int,
        expectedCiphertext: String,
        expectedNonce: String,
    ): Int

    /**
     * Persist the lazy per-note content-key MINT that upgrades a legacy
     * wrap-absent note (the body moves from vault-key encryption to a fresh
     * content key). Writes the re-encrypted body + the new wrap + the version
     * the server acknowledged, but ONLY when the row still holds the
     * ciphertext/nonce/wrap the mint was computed from and is not soft-deleted.
     *
     * The plaintext is unchanged by a mint, so decrypted_content/title are left
     * alone — re-writing them from a pre-network snapshot is exactly what would
     * revert a concurrent edit. is_dirty is not in the predicate for the same
     * reason as [writeEditedItemIfUnchanged]: this runs from the attachment
     * upload path while the editor is open, where the row may already be dirty.
     */
    @Query(
        "UPDATE items SET encrypted_content = :ciphertext, content_nonce = :nonce, " +
            "version = :version, updated_at = :updatedAt, is_dirty = :isDirty, " +
            "content_size = :contentSize, wrapped_content_key = :wrappedKey, " +
            "wrapped_content_key_nonce = :wrappedNonce " +
            "WHERE id = :id AND encrypted_content = :expectedCiphertext " +
            "AND content_nonce = :expectedNonce " +
            "AND wrapped_content_key IS :expectedWrappedKey AND deleted = 0"
    )
    suspend fun writeMintedContentKeyIfUnchanged(
        id: String,
        ciphertext: String,
        nonce: String,
        version: Long,
        updatedAt: Instant,
        isDirty: Boolean,
        contentSize: Int,
        wrappedKey: String,
        wrappedNonce: String,
        expectedCiphertext: String,
        expectedNonce: String,
        expectedWrappedKey: String?,
    ): Int

    /**
     * Reset [id] to the server's copy after a conflict has been forked into a
     * separate "(conflict copy)" note — stored undecrypted + clean so the sync
     * backfill re-derives the plaintext — but ONLY when the row still holds the
     * ciphertext/nonce that were forked ([expectedCiphertext]/[expectedNonce]).
     *
     * The reset spans a `GET /items/{id}`; an interactive edit landing inside
     * that window went into neither the fork (taken before the GET) nor the
     * server copy, and a blind reset would clear its is_dirty and drop it from
     * the outbox as well as the DB. When the row has moved on this no-ops and
     * the newer edit is left dirty to drain — the fork already preserved the
     * older side. Returns 1 when the reset was applied, 0 when it was skipped.
     */
    @Query(
        "UPDATE items SET encrypted_content = :ciphertext, content_nonce = :nonce, " +
            "decrypted_content = NULL, title = NULL, version = :version, " +
            "updated_at = :updatedAt, deleted = :deleted, is_dirty = 0, " +
            "content_size = :contentSize, wrapped_content_key = :wrappedKey, " +
            "wrapped_content_key_nonce = :wrappedNonce " +
            "WHERE id = :id AND encrypted_content = :expectedCiphertext " +
            "AND content_nonce = :expectedNonce"
    )
    suspend fun resetToServerIfUnchanged(
        id: String,
        ciphertext: String,
        nonce: String,
        version: Long,
        updatedAt: Instant,
        deleted: Boolean,
        contentSize: Int,
        wrappedKey: String?,
        wrappedNonce: String?,
        expectedCiphertext: String,
        expectedNonce: String,
    ): Int

    /**
     * Confirm a successfully-PUT user-metadata item (item_type=10): adopt the
     * server [version] + refreshed [updatedAt], cache the [decrypted] JSON and
     * its [ciphertext]/[nonce], and clear is_dirty — but ONLY when the row still
     * holds the ciphertext/nonce our PUT was based on
     * ([expectedCiphertext]/[expectedNonce]) and is neither dirty nor deleted.
     *
     * The metadata item is machine-managed (no interactive editor writes it) and
     * the PUT is server-version-gated, so the primary loss this closes is a
     * concurrent sync pull (or a second mutateMetadata) landing a NEWER metadata
     * copy during our PUT round-trip: a blind full-row upsert of our pre-PUT
     * snapshot would revert that newer local copy to our older content + version.
     * When the row has moved on this UPDATE no-ops (0 rows) and the newer copy is
     * left for the next sync to reconcile — the authoritative state already sits
     * on the server. Sibling of [writeRotatedItemIfUnchanged]; metadata items
     * carry no wrapped_content_key (vault-encrypted directly), so it isn't
     * touched here.
     */
    @Query(
        "UPDATE items SET encrypted_content = :ciphertext, content_nonce = :nonce, " +
            "decrypted_content = :decrypted, version = :version, updated_at = :updatedAt, is_dirty = 0 " +
            "WHERE id = :id AND encrypted_content = :expectedCiphertext " +
            "AND content_nonce = :expectedNonce AND is_dirty = 0 AND deleted = 0"
    )
    suspend fun confirmMetadataWriteIfUnchanged(
        id: String,
        ciphertext: String,
        nonce: String,
        decrypted: String,
        version: Long,
        updatedAt: Instant,
        expectedCiphertext: String,
        expectedNonce: String,
    ): Int

    // Flags the row is_dirty=1 too, so the delete is queued for the server
    // and replayed by retryDeletedItems(). Without it, a soft-delete stays
    // purely local and never reaches the user's other devices.
    @Query("UPDATE items SET deleted = 1, is_dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** Hard-remove a single row. Drops a materialized shared note once
     * its share leaves the inbox (un-share / owner-delete) — a
     * recipient never "trashes" someone else's note, so it's removed
     * outright rather than soft-deleted. */
    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: String)

    // Clears is_dirty: restore runs only after the server restore
    // succeeds, so there is no pending delete left to push for this
    // row.
    @Query("UPDATE items SET deleted = 0, is_dirty = 0, updated_at = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    /** All active (non-deleted) notes for export */
    @Query("SELECT * FROM items WHERE deleted = 0 AND item_type = 1 ORDER BY updated_at DESC")
    suspend fun getAllActiveNotes(): List<ItemEntity>

    // is_dirty = 0: never hard-delete a tombstone whose delete hasn't reached
    // the server yet (device offline for the whole retention window) — purging
    // it locally would silently drop the delete and the note would resurrect
    // on the next pull. retryDeletedItems() clears the flag once pushed.
    @Query("DELETE FROM items WHERE deleted = 1 AND is_dirty = 0 AND updated_at < :cutoff")
    suspend fun purgeOldTrash(cutoff: Long)

    /** Hard-delete every trashed row (counterpart to the server's
     *  DELETE /api/v1/trash). Used by the "Empty trash" action. */
    @Query("DELETE FROM items WHERE deleted = 1")
    suspend fun purgeAllTrash(): Int

    /** Items modified locally that need sync push */
    @Query("SELECT * FROM items WHERE is_dirty = 1")
    suspend fun getDirtyItems(): List<ItemEntity>

    /** Paged dirty items for the create-push. Excludes soft-deleted rows —
     *  those are pending deletes, pushed via [getDeletedDirtyItems], not the
     *  create-only /sync/push (which would otherwise resurrect them). */
    @Query("SELECT * FROM items WHERE is_dirty = 1 AND deleted = 0 ORDER BY updated_at ASC LIMIT :limit OFFSET :offset")
    suspend fun getDirtyItemsPaged(limit: Int, offset: Int): List<ItemEntity>

    /** Soft-deleted rows whose delete hasn't reached the server yet. */
    @Query("SELECT * FROM items WHERE is_dirty = 1 AND deleted = 1")
    suspend fun getDeletedDirtyItems(): List<ItemEntity>

    @Query("UPDATE items SET is_dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirtyFlags(ids: List<String>)

    /**
     * Clear is_dirty on every item in [items] whose row STILL holds exactly the
     * ciphertext/nonce that was sent up, and return the ids actually cleared.
     *
     * The create-push snapshots the dirty page, then spends a network round-trip
     * in `POST /sync/push`. An interactive edit landing in that window writes a
     * new ciphertext and (re-)sets is_dirty; the unconditional [clearDirtyFlags]
     * would clear it anyway, dropping that edit out of the outbox even though the
     * server had only ever seen the older body — the edit would survive only in
     * the local row. Per-row compare-and-swap, all inside one transaction so the
     * returned ids faithfully describe what was written. `deleted = 0` keeps a
     * concurrent soft-delete's pending tombstone dirty (softDelete leaves the
     * ciphertext intact, so the ciphertext compare alone would clear it).
     */
    @Transaction
    suspend fun clearDirtyFlagsIfUnchanged(items: List<ItemEntity>): List<String> {
        val cleared = mutableListOf<String>()
        for (item in items) {
            val rows = clearDirtyIfUnchanged(item.id, item.encryptedContent, item.contentNonce)
            if (rows > 0) cleared += item.id
        }
        return cleared
    }

    /** Single-row half of [clearDirtyFlagsIfUnchanged]. */
    @Query(
        "UPDATE items SET is_dirty = 0 WHERE id = :id " +
            "AND encrypted_content = :ciphertext AND content_nonce = :nonce AND deleted = 0"
    )
    suspend fun clearDirtyIfUnchanged(id: String, ciphertext: String, nonce: String): Int

    /** Substring search over the locally-decrypted copy (LIKE, not FTS) */
    @Query("SELECT * FROM items WHERE item_type = 1 AND deleted = 0 AND (title LIKE '%' || :query || '%' OR decrypted_content LIKE '%' || :query || '%') ORDER BY updated_at DESC")
    suspend fun search(query: String): List<ItemEntity>

    /** Max updated_at for sync cursor */
    @Query("SELECT MAX(updated_at) FROM items")
    suspend fun getMaxUpdatedAt(): Long?

    /** Items that were synced but never decrypted (null title and decryptedContent) */
    @Query("SELECT * FROM items WHERE decrypted_content IS NULL AND encrypted_content IS NOT NULL AND deleted = 0")
    suspend fun getUndecryptedItems(): List<ItemEntity>

    /** How many rows [getUndecryptedItems] would return: the cheap "something is
     *  still locked" test for the foreground re-check (IdentityRecheck). */
    @Query("SELECT COUNT(*) FROM items WHERE decrypted_content IS NULL AND encrypted_content IS NOT NULL AND deleted = 0")
    suspend fun countUndecryptedItems(): Int

    /** Synchronous query for widgets (runs on binder thread in RemoteViewsFactory) */
    @Query("SELECT * FROM items WHERE item_type = 1 AND deleted = 0 AND is_archived = 0 ORDER BY is_pinned DESC, updated_at DESC LIMIT :limit")
    fun getRecentNotesSync(limit: Int = 10): List<ItemEntity>

    /** Synchronous single note query for widgets */
    @Query("SELECT * FROM items WHERE id = :id AND deleted = 0")
    fun getByIdSync(id: String): ItemEntity?

    /** Observe a single item by id — emits after every upsert so editors
     *  can react to rotation-driven wrapped_content_key changes. */
    @Query("SELECT * FROM items WHERE id = :id")
    fun observeById(id: String): Flow<ItemEntity?>

    /**
     * Ids of notebooks that exist as REAL rows here.
     *
     * `items.notebook_id` on the server is `REFERENCES items(id)`, so pushing a
     * note that names a notebook the server does not have is a foreign-key
     * violation — the create fails. Web-only notebooks (NotebookRepository's
     * webOnlyNotebooks) are synthesized for the picker and stored nowhere, so
     * they must never reach the wire as a grouping.
     */
    @Query("SELECT id FROM items WHERE item_type = 2 AND deleted = 0")
    suspend fun getNotebookIds(): List<String>

    /** Get all notebooks (item_type = 2) */
    @Query("SELECT * FROM items WHERE item_type = 2 AND deleted = 0 ORDER BY updated_at DESC")
    fun observeNotebooks(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE notebook_id = :notebookId AND deleted = 0 ORDER BY is_pinned DESC, updated_at DESC")
    fun observeNotebookItems(notebookId: String): Flow<List<ItemEntity>>

    /** Get shared notebooks (have a circle_id set) */
    @Query("SELECT * FROM items WHERE item_type = 2 AND circle_id IS NOT NULL AND deleted = 0 ORDER BY updated_at DESC")
    fun observeSharedNotebooks(): Flow<List<ItemEntity>>

    /** Update circle_id for a notebook and all its children */
    @Query("UPDATE items SET circle_id = :circleId, is_dirty = 1 WHERE notebook_id = :notebookId OR id = :notebookId")
    suspend fun setCircleForNotebook(notebookId: String, circleId: String?)

    /** Every note id this device holds, including trashed ones — a note in
     *  the trash can be restored, so its editor-mode pin must survive.
     *  Feeds the pin GC, which needs the FULL set: a partial one would
     *  collect pins for notes merely absent from it. */
    @Query("SELECT id FROM items WHERE item_type = 1")
    suspend fun getAllNoteIds(): List<String>

    /** The user-metadata items (item_type = 10), newest first. The one that
     * counts is the newest of THIS account's (UserMetadataStore picks it: a
     * row with another owner is never read). It holds notebook definitions,
     * label colors, the notebookShares map and the editor-mode pins that the
     * web + Android clients both read/write. Empty on new users.
     *
     * ORDER BY updated_at DESC because "the single item" is an invariant
     * nothing on this device enforces: the table can hold more than one such
     * row, for example from before the server limited an account to one live
     * row. A bare `LIMIT 1` then returns whichever row the storage engine
     * happens to hand back first, so which copy of the notebooks and share
     * recipients you see is arbitrary and can change between calls.
     * Newest-wins at least makes one row authoritative. */
    @Query("SELECT * FROM items WHERE item_type = 10 AND deleted = 0 ORDER BY updated_at DESC")
    suspend fun getMetadataItems(): List<ItemEntity>

    /** All non-deleted notes that belong to a notebook — used by the
     *  Android notebook share flow to fan out shares to each child. */
    @Query("SELECT * FROM items WHERE item_type = 1 AND deleted = 0 AND notebook_id = :notebookId")
    suspend fun getNotesInNotebook(notebookId: String): List<ItemEntity>
}
