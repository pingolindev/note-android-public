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
import android.app.Application
import com.pingolin.note.domain.QuotaExceededException
import com.pingolin.note.util.AccountIds
import com.pingolin.note.util.SessionManager
import com.pingolin.note.widget.WidgetUpdateHelper
import org.json.JSONObject
import uniffi.pingolin_mobile.CryptoManager
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coordinates delta sync between local Room DB and the note server.
 *
 * Sync protocol:
 * 1. Pull: GET /sync/pull?since=<cursor> → decrypt → upsert local
 * 2. Push: collect dirty items → encrypt → POST /sync/push → clear dirty flags
 * 3. Conflicts: server returns its version; we keep both and let user reconcile
 */
@Singleton
class SyncRepository @Inject constructor(
    private val previousIdentityLoader: PreviousIdentityLoader,
    private val noteApi: NoteApi,
    private val itemDao: ItemDao,
    private val cryptoManager: CryptoManager,
    private val appContext: Application,
    private val sessionManager: SessionManager,
    private val noteCrypto: com.pingolin.note.collab.NoteCrypto,
    private val sharesInbox: SharesInboxRepository,
    private val noteRepository: javax.inject.Provider<NoteRepository>,
    // Provider, like noteRepository above: NotebookRepository reaches back into
    // this graph, and the indirection is what keeps that from being a cycle.
    private val notebookRepository: javax.inject.Provider<NotebookRepository>,
    private val reminderScheduler: ReminderScheduler,
    private val notebookInvites: javax.inject.Provider<NotebookInvites>,
    private val userMetadata: UserMetadataStore,
) {
    /** What [SharesInboxRepository.rowsRemoved] read when the widgets were last told of removed rows. */
    private val widgetsToldAtRemoved = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Tell the home-screen widgets when an inbox refresh has taken rows OUT of the database.
     * Shares that were withdrawn, dismissed or declined leave it with no pulled item to announce
     * them, and the widgets would go on showing their titles ("If you decline a share, nothing
     * should show up").
     *
     * Against a count that only grows, so that a removal is announced once whichever sync made
     * it, and none is lost when two syncs overlap or when the one that removed the rows stopped
     * before it came here.
     */
    private fun tellWidgetsOfRemovedRows() {
        val removed = sharesInbox.rowsRemoved
        if (widgetsToldAtRemoved.getAndSet(removed) == removed) return
        try {
            WidgetUpdateHelper.notifyDataChanged(appContext)
        } catch (e: Exception) {
            // The widgets missing an update must not cost the sync that follows.
            android.util.Log.w(TAG, "Telling the widgets of removed rows failed: ${e.message}")
        }
    }

    /**
     * Full sync cycle: push local changes, then pull remote changes.
     * Also backfills any already-synced items that failed decryption.
     */
    suspend fun sync(): SyncResult {
        android.util.Log.i(TAG, "Sync starting...")
        // Acting account for this whole cycle, captured before any network
        // fetch. A page pulled for this user must never be written into a
        // DIFFERENT account's DB if an account switch / logout lands mid-sync
        // (see the guard at the pull upsert). Pairs with SyncWorker.cancelAllSync
        // in the account-switch wipe, which cooperatively cancels this worker.
        val actingUser = sessionManager.getUserId()
        // Refresh the shares inbox before pulling — items shared with us
        // need the inbox's unwrapped content keys to decrypt. Best-effort:
        // if the inbox is unreachable we still try the vault path below.
        try {
            val unwrapped = sharesInbox.refresh()
            android.util.Log.d(TAG, "Inbox refreshed: $unwrapped content keys available")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Inbox refresh failed: ${e.message}")
        } finally {
            // Here, and not at the end of the cycle: the push and the pull below
            // can fail (a free account over its limit fails the push on every
            // sync), and the rows are already gone. In a `finally`, so that a sync
            // CANCELLED after its refresh removed rows tells the widgets too; it
            // does not suspend.
            tellWidgetsOfRemovedRows()
        }
        // Forget acceptances for notebooks that are not shared with us —
        // the owner removed us, or deleted it. Left behind, a later
        // re-invitation to the same notebook would arrive already
        // accepted and never ask.
        //
        // Only when THIS refresh applied the server's list, and against
        // every notebook that list named
        // (SharesInboxRepository.listedNotebookIds). Not against
        // sharedNotebooksFlow, which is the empty list in a cold process
        // until a refresh succeeds: one failed inbox request in a
        // background sync would strike every acceptance, in the metadata
        // every device shares, and each shared notebook would go back to
        // "new" on all of them — and the web, which holds a notebook's
        // notes until its invitation is accepted, would take those notes
        // off the screen too.
        try {
            sharesInbox.listedNotebookIds?.let { notebookInvites.get().prune(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Pruning notebook acceptances failed: ${e.message}")
        }

        val pushResult = pushDirtyItems()
        android.util.Log.i(TAG, "Push complete: $pushResult items pushed")
        // Replay offline edits via the eager-PUT path. /sync/push is a
        // create-only endpoint; any note that already exists on the
        // server needs PUT /items/{id} to persist edits. retryDirtyItems
        // walks the local dirty queue and re-tries through the correct
        // per-note branch (shared-note skips wrapped_content_key).
        val retried = try {
            noteRepository.get().retryDirtyItems()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "retryDirtyItems failed: ${e.message}")
            0
        }
        if (retried > 0) android.util.Log.i(TAG, "Replayed $retried pending updates")
        // Replay pending deletes (offline delete, or a failed eager delete).
        // After create+update push so the row exists server-side before we
        // delete it, and before pull so the tombstone goes up first.
        val deleted = try {
            noteRepository.get().retryDeletedItems()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "retryDeletedItems failed: ${e.message}")
            0
        }
        if (deleted > 0) android.util.Log.i(TAG, "Replayed $deleted pending deletes")
        val pullResult = pullRemoteChanges(actingUser)
        android.util.Log.i(TAG, "Pull complete: ${pullResult.itemCount} items pulled")
        val backfilled = backfillUndecryptedItems(actingUser)
        if (backfilled > 0) android.util.Log.i(TAG, "Backfilled $backfilled items")

        // The pull may have brought the account's ANSWER to an invitation (the acceptances are
        // in its metadata), which the refresh above could not know yet: read the inbox once
        // more when that changes what is held back, so an accepted notebook's notes arrive
        // with this sync and not the next (SharesInboxRepository.refreshIfAnswersChanged).
        val answered = try {
            sharesInbox.refreshIfAnswersChanged()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Inbox re-read after the pull failed: ${e.message}")
            false
        } finally {
            tellWidgetsOfRemovedRows()
        }

        // Give any legacy vault-direct notebook a wrapped content key, so it
        // can be shared. AFTER the backfill on purpose: the sweep only touches
        // notebooks it can actually read, and the backfill is what decrypts a
        // freshly-pulled one. The upgraded rows are left dirty and go up on the
        // next cycle's drain rather than forcing another push pass here.
        try {
            notebookRepository.get().upgradeLegacyNotebooks(actingUser)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Legacy notebook upgrade failed: ${e.message}")
        }

        // Rows the inbox refresh took OUT were announced right after it (tellWidgetsOfRemovedRows).
        // Rows a second reading of the inbox may have ADDED are announced here.
        if (pullResult.itemCount > 0 || backfilled > 0 || answered) {
            WidgetUpdateHelper.notifyDataChanged(appContext)
        }

        // Collect editor-mode pins whose note is gone, so the map in the
        // metadata blob cannot grow forever. Only after a pull that got this
        // far, and never against an empty id set — an empty local database is
        // what a freshly-installed device looks like before its first page
        // lands, and treating that as "no notes exist" would wipe every pin
        // the user has on every other device.
        runCatching {
            val liveIds = itemDao.getAllNoteIds().toSet()
            if (liveIds.isNotEmpty()) userMetadata.gcPins(liveIds)
        }.onFailure { android.util.Log.w(TAG, "Editor-mode pin GC skipped: ${it.message}") }

        // Mirror the server's reminders into the local table (the source
        // the boot path re-arms alarms from) and keep device alarms in step
        // — including the web-created reminders this device would otherwise
        // never fire locally. Quiet on failure; the next sync retries and
        // the previous reconciliation's alarms stay armed meanwhile.
        runCatching { reminderScheduler.refresh() }
            .onFailure { android.util.Log.w(TAG, "Reminder refresh skipped: ${it.message}") }

        return SyncResult(
            pushed = pushResult,
            pulled = pullResult.itemCount,
            conflicts = pullResult.conflicts,
        )
    }

    /**
     * Push local pending changes to the server WITHOUT pulling. Used before a
     * logout wipe so unsynced offline edits aren't destroyed by clearAllTables().
     * Mirrors the PUSH half of [sync] — create-push + eager-PUT edit replay +
     * delete replay — minus the inbox refresh, pull and backfill. Best-effort per
     * stage (a stage failure never blocks the others); returns items pushed.
     */
    suspend fun pushPendingChanges(): Int {
        val created = pushDirtyItems()
        val edited = try {
            noteRepository.get().retryDirtyItems()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "pushPendingChanges: retryDirtyItems failed: ${e.message}")
            0
        }
        val deleted = try {
            noteRepository.get().retryDeletedItems()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "pushPendingChanges: retryDeletedItems failed: ${e.message}")
            0
        }
        return created + edited + deleted
    }

    /**
     * True if any local item is unsynced — a pending create/edit (is_dirty, not
     * deleted) or a pending delete (is_dirty + deleted). Guards the account-switch
     * wipe from silently destroying offline work when we can't flush it (offline):
     * unlike explicit logout, a switch shows no "may be lost" warning. Cheap: a
     * LIMIT 1 probe plus the (small) pending-delete set.
     */
    suspend fun hasUnsyncedChanges(): Boolean = withContext(Dispatchers.IO) {
        itemDao.getDirtyItemsPaged(1, 0).isNotEmpty() ||
            itemDao.getDeletedDirtyItems().isNotEmpty()
    }

    /** True when this device holds any row. A sign-in with no saved account id counts it as an
     * account switch: rows nobody's id names are wiped before another account's are saved. */
    suspend fun hasAnyLocalRows(): Boolean = withContext(Dispatchers.IO) { itemDao.hasAnyRow() }

    companion object {
        private const val TAG = "NoteSync"
        // Hard cap on /sync/pull pages per cycle. hasMore + the keyset cursor come
        // from the server, so a hostile or buggy server that always returns
        // hasMore=true could otherwise drive this loop — and its per-page Room
        // writes — unboundedly, filling the device disk. 10k pages x 100 items = 1M
        // items, comfortably above any real vault; hitting it means the server is
        // misbehaving, so bail loudly and let the next cycle resume from the cursor.
        private const val MAX_SYNC_PAGES = 10_000
    }

    private suspend fun pushDirtyItems(): Int {
        var totalPushed = 0
        var offset = 0
        val pageSize = 100

        while (true) {
            val dirtyItems = itemDao.getDirtyItemsPaged(pageSize, offset)
            android.util.Log.d(TAG, "Push: found ${dirtyItems.size} dirty items (offset=$offset)")
            if (dirtyItems.isEmpty()) break

            // Never send a SHARED-WITH-ME note through the
            // create-only /sync/push: it already exists (owned by
            // the sharer) and the server 403s the whole BATCH for
            // it, poisoning the push and blocking the recipient's
            // OWN new notes from syncing. A dirty shared note is
            // an edit; it goes up via retryDirtyItems() (PUT,
            // per-item) instead. sync() refreshes the inbox
            // first, so sharedKeyFor reliably identifies shared
            // items — and never one of ours, whatever the inbox
            // holds.
            val ownItems = dirtyItems.filter { sharesInbox.sharedKeyFor(it) == null }
            if (ownItems.isEmpty()) {
                // Whole page was shared-with-me; page past it and continue.
                offset += dirtyItems.size
                if (dirtyItems.size < pageSize) break
                continue
            }

            // Notebooks that actually exist as rows. A note naming any other
            // notebook must go up WITHOUT the grouping: the server's
            // items.notebook_id is REFERENCES items(id), so a dangling id is a
            // foreign-key violation that fails the whole batch — and web-only
            // notebooks (NotebookRepository.webOnlyNotebooks) are synthesized
            // for the picker and exist on no server. The note still carries its
            // notebook inside its encrypted content either way; only the
            // server-visible grouping is withheld until the notebook is real.
            // Defensive: this only decides whether the server-visible grouping
            // rides along, and must never be able to fail the whole push.
            // Empty means "send no grouping", which is always safe.
            val realNotebookIds = runCatching { itemDao.getNotebookIds().toSet() }.getOrDefault(emptySet())

            val requests = ownItems.map { item ->
                CreateItemRequest(
                    id = item.id,
                    itemType = item.itemType,
                    encryptedContent = item.encryptedContent,
                    contentNonce = item.contentNonce,
                    circleId = item.circleId,
                    notebookId = item.notebookId?.takeIf { it in realNotebookIds },
                    wrappedContentKey = item.wrappedContentKey,
                    wrappedContentKeyNonce = item.wrappedContentKeyNonce,
                )
            }

            val response = noteApi.syncPush(requests)
            android.util.Log.d(TAG, "Push response: ${response.code()}")
            if (response.code() == 402) {
                // This app's line, never the error body (JSON,
                // and the server's words).
                throw QuotaExceededException(QuotaExceededException.NOTE_LIMIT_TEXT)
            }
            if (!response.isSuccessful) {
                // Transient failure (5xx etc.) — keep everything dirty so the
                // next sync retries instead of silently dropping changes.
                android.util.Log.w(TAG, "Push batch failed (${response.code()}); leaving ${dirtyItems.size} items dirty")
                break
            }
            val body = response.body()?.data ?: break
            totalPushed += body.created

            // /sync/push is create-only (ON CONFLICT DO NOTHING): items the
            // server reports as conflicts already exist, so their edits were
            // NOT applied here. Keep those dirty so retryDirtyItems() updates
            // them via PUT /items/{id}; only clear the freshly-created ones.
            // Clearing the whole batch unconditionally would drop offline edits
            // that the retry then never sees.
            val conflictIds = body.conflicts.map { it.id }.toHashSet()
            val created = ownItems.filterNot { conflictIds.contains(it.id) }
            // Compare-and-swap the flag clear against the ciphertext we actually
            // sent: this page is a snapshot taken before the POST's network
            // round-trip, so an interactive edit landing inside that window must
            // stay dirty. See ItemDao.clearDirtyFlagsIfUnchanged.
            val clearedIds =
                if (created.isNotEmpty()) itemDao.clearDirtyFlagsIfUnchanged(created) else emptyList()
            // Page past everything we left dirty this page (conflicts, the skipped
            // shared items, and any row whose CAS was refused) so the next page
            // advances; cleared items drop out of the dirty set.
            offset += dirtyItems.size - clearedIds.size

            if (dirtyItems.size < pageSize) break
        }

        return totalPushed
    }

    private suspend fun pullRemoteChanges(actingUser: String?): PullResult {
        val cursorMs = itemDao.getMaxUpdatedAt() ?: 0L
        val cursor = Instant.ofEpochMilli(cursorMs).toString()

        var totalItems = 0
        var conflicts = 0
        var currentCursor = cursor
        // Tie-breaker paired with currentCursor (the compound (updated_at, id)
        // keyset cursor). Null on the first page — the session cursor is derived
        // from getMaxUpdatedAt() (millisecond-truncated), so a plain
        // `updated_at > since` already re-fetches the boundary group idempotently.
        // Within the loop we thread the exact server cursor_id so a group of
        // same-updated_at items straddling a 100-item page boundary is not
        // skipped between pages.
        var currentCursorId: String? = null
        var hasMore = true
        var pagesFetched = 0

        android.util.Log.d(TAG, "Pull: cursor=$currentCursor")

        while (hasMore) {
            // Break loudly rather than follow a server that never stops claiming
            // hasMore (see MAX_SYNC_PAGES); the cursor has already advanced, so a
            // later cycle resumes from where we stopped.
            if (pagesFetched >= MAX_SYNC_PAGES) {
                android.util.Log.w(TAG, "Pull: reached MAX_SYNC_PAGES ($MAX_SYNC_PAGES); stopping to bound disk writes")
                break
            }
            pagesFetched++
            val response = noteApi.syncPull(since = currentCursor, sinceId = currentCursorId, limit = 100)
            android.util.Log.d(TAG, "Pull response: ${response.code()}, success=${response.isSuccessful}")
            if (!response.isSuccessful) {
                android.util.Log.e(TAG, "Pull failed: ${response.code()} ${response.errorBody()?.string()}")
                break
            }

            val body = response.body()?.data ?: break
            android.util.Log.d(TAG, "Pull page: ${body.items.size} items, hasMore=${body.hasMore}, cursor=${body.cursor}")

            // Decrypt and collect all items for this page. The per-item
            // decrypt is JNI crypto + JSON parsing; a foreground refresh
            // drives sync() from viewModelScope (Main), so running it inline
            // blocks the UI thread for the whole page and ANRs on a large
            // pull. Do the decrypt/parse on Dispatchers.Default; the batched
            // Room read below and the upsertAll keep Room's own dispatcher.
            // Make any RECOVERED pre-reset identity available before decrypting.
            // Without this the pull succeeds and every older note arrives as an
            // undecryptable row. Hopped to Default: the FIRST call imports every
            // recovered identity through the SDK (keyed unseals), and even the
            // latched steady state is one UniFFI crossing per page — neither
            // belongs on Main, which is where viewModelScope-driven sync runs.
            withContext(Dispatchers.Default) { previousIdentityLoader.ensureLoaded() }

            val vaultKey = sessionManager.getMasterKey()
            // One batched lookup for the page instead of an N+1 getById per
            // item. Feeds the dirty-guard and the defensive plaintext merge
            // below.
            val localById = itemDao.getByIds(body.items.map { it.id })
                .associateBy { it.id }
            val entities = withContext(Dispatchers.Default) {
                val page = mutableListOf<ItemEntity>()
                for (itemDto in body.items) {
                    // Dirty-guard: never let a pulled copy overwrite a local row
                    // that still has un-pushed changes. Pull runs AFTER push +
                    // retryDirtyItems + retryDeletedItems in sync(), so a row
                    // that's still is_dirty here means its local edit/delete
                    // hasn't reached the server (offline mid-sync, a transient
                    // failure, or a version conflict). Applying the remote copy
                    // would silently discard that local change — skip it and let
                    // the next push send it up.
                    val local = localById[itemDto.id]
                    if (local != null && local.isDirty) {
                        android.util.Log.d(TAG, "Pull: keeping local unsynced changes for ${itemDto.id}")
                        continue
                    }

                    // A note the inbox keeps away is not written here either
                    // (SharesInboxRepository.isKeptAway: its notebook was DECLINED, or its
                    // invitation is not answered yet): the server serves it in a pull like any
                    // note with a live share. Never one of this account's own items, whatever
                    // a share names.
                    if (!AccountIds.same(itemDto.userId, actingUser) && sharesInbox.isKeptAway(itemDto.id)) {
                        continue
                    }

                    val decryptedContent = if (vaultKey != null) {
                        decryptItemContent(
                            itemDto.encryptedContent,
                            itemDto.contentNonce,
                            vaultKey,
                            itemDto.wrappedContentKey,
                            itemDto.wrappedContentKeyNonce,
                            itemDto.id,
                        )
                    } else {
                        null
                    }

                    // An item that is NOT this account's, that nothing here
                    // opens, and that has no row yet, gets none. The inbox
                    // hands out a shared note's key only once it has
                    // checked the share and found the note is not one to
                    // hold back, and writes the row itself then. A row
                    // written here without that key has no title: the
                    // widgets would show it as "Untitled", for a note of an
                    // invitation nobody has answered or for a share the
                    // inbox refused.
                    if (decryptedContent == null && local == null && !AccountIds.same(itemDto.userId, actingUser)) {
                        continue
                    }

                    // Metadata items (item_type=10 — the notebook list, share
                    // recipients and editor-mode pins) are NOT unpacked here.
                    // See the refresh below the upsert.

                    val parsed = decryptedContent?.let { parseContentJson(it) }
                    // Defensive: if the server didn't return the wrapped
                    // content key (or returned a value we can't unwrap with
                    // our vault), decryption is null and parsed fields go
                    // null. Preserve the locally-cached plaintext rather
                    // than overwriting it with empty data — otherwise a
                    // user's own freshly-created note shows as "Untitled"
                    // with no body after the immediate sync round-trip.
                    val existing = if (decryptedContent == null) local else null
                    val mergedDecrypted = decryptedContent ?: existing?.decryptedContent
                    val mergedParsed = mergedDecrypted?.let { parseContentJson(it) }
                    val title = (parsed?.optString("title", "")?.takeIf { it.isNotEmpty() })
                        ?: mergedParsed?.optString("title", "")?.takeIf { it.isNotEmpty() }
                    val color = (parsed?.optString("color", "")?.takeIf { it.isNotEmpty() })
                        ?: mergedParsed?.optString("color", "")?.takeIf { it.isNotEmpty() }
                    val isPinned = parsed?.optBoolean("is_pinned", false)
                        ?: mergedParsed?.optBoolean("is_pinned", false) ?: false
                    val isArchived = parsed?.optBoolean("is_archived", false)
                        ?: mergedParsed?.optBoolean("is_archived", false) ?: false
                    val sortOrder = parsed?.optInt("sort_order", 0)
                        ?: mergedParsed?.optInt("sort_order", 0) ?: 0

                    page.add(ItemEntity(
                        id = itemDto.id,
                        userId = itemDto.userId,
                        itemType = itemDto.itemType,
                        encryptedContent = itemDto.encryptedContent,
                        contentNonce = itemDto.contentNonce,
                        decryptedContent = mergedDecrypted,
                        title = title,
                        version = itemDto.version,
                        createdAt = Instant.parse(itemDto.createdAt),
                        updatedAt = Instant.parse(itemDto.updatedAt),
                        deleted = itemDto.deleted,
                        circleId = itemDto.circleId,
                        notebookId = itemDto.notebookId,
                        contentSize = itemDto.contentSize,
                        sharedByMe = itemDto.sharedByMe,
                        color = color,
                        isPinned = isPinned,
                        isArchived = isArchived,
                        isDirty = false,
                        // Same defensive merge: if the server dropped the
                        // wrapped key on a round-trip, keep what we have
                        // locally so we can still decrypt next time.
                        wrappedContentKey = itemDto.wrappedContentKey ?: existing?.wrappedContentKey,
                        wrappedContentKeyNonce = itemDto.wrappedContentKeyNonce ?: existing?.wrappedContentKeyNonce,
                        sortOrder = sortOrder,
                    ))
                }
                page
            }

            // Cross-account guard: if the signed-in user changed while this
            // page was being fetched + decrypted (account switch or logout
            // mid-sync), discard it — writing it now would land the outgoing
            // account's plaintext in the incoming account's DB. The wipe also
            // cancels this worker, but WorkManager cancellation is cooperative,
            // so re-check the acting user right at the write.
            if (sessionManager.getUserId() != actingUser) {
                android.util.Log.w(TAG, "Pull: acting user changed mid-sync; discarding page and stopping")
                break
            }

            // Batch upsert the whole page, but never clobber a row that was
            // dirtied by an interactive edit between the localById snapshot above
            // and this write. The in-loop localById guard is a cheap first pass
            // (and feeds the plaintext merge); upsertAllPreservingDirty re-checks
            // is_dirty in the SAME transaction as the write, closing that TOCTOU.
            if (entities.isNotEmpty()) {
                val skipped = itemDao.upsertAllPreservingDirty(entities)
                if (skipped > 0) android.util.Log.d(TAG, "Pull: skipped $skipped rows dirtied during page decrypt")
                totalItems += entities.size - skipped
            }

            // The cached notebook list, the fan-out's share recipients
            // and the editor-mode pins are re-derived from the NEWEST
            // metadata row in Room, once this page has landed — not
            // from the row itself as the decrypt loop met it. There is
            // supposed to be one item_type=10 row per user but the
            // local table does not enforce it, and applying every row
            // the loop meets would let the LAST one in the page decide
            // what the user sees: right only as long as the server
            // serves the page oldest-first, and with any other ordering
            // the app would silently show an arbitrary copy.
            //
            // Deleted metadata rows count too: a tombstone for the
            // newest row changes which row is newest-and-live, so the
            // caches have to be re-derived after it lands.
            if (body.items.any { it.itemType == 10 }) {
                try {
                    // Decrypts the metadata blob through the SDK — off Main.
                    withContext(Dispatchers.Default) { userMetadata.refreshCaches() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Metadata cache refresh skipped: ${e.message}")
                }
            }

            hasMore = body.hasMore
            currentCursor = body.cursor ?: break
            // Advance the tie-breaker in lockstep with the cursor so the next
            // page resumes exactly after this page's last item.
            currentCursorId = body.cursorId
        }

        return PullResult(totalItems, conflicts)
    }

    /**
     * Re-decrypt items that were stored without their plaintext: rows holding
     * encrypted_content but a null decrypted_content/title, e.g. pulled while
     * the vault key or the sharing inbox's content key wasn't available yet.
     *
     * This is also what heals a library that synced BEFORE the user recovered a
     * pre-reset identity in the account portal. The pull cannot do it: its
     * cursor is `getMaxUpdatedAt()`, so the server only ever re-sends items
     * NEWER than what we hold and those rows are never offered again. Nothing
     * needs re-downloading though — a failed decrypt keeps the whole ciphertext
     * (encrypted_content/content_nonce/wrapped_content_key are written from the
     * pulled row regardless of whether it opened), so the repair is local.
     */
    private suspend fun backfillUndecryptedItems(actingUser: String?): Int {
        val vaultKey = sessionManager.getMasterKey() ?: return 0
        val undecrypted = itemDao.getUndecryptedItems()
        if (undecrypted.isEmpty()) return 0

        // Only now, with rows that actually need repairing, make sure any
        // RECOVERED pre-reset identity is available. The pull loads it too, but
        // only from inside its page loop — an offline or 5xx pull breaks out
        // before that, and this pass would then re-try the same rows with the
        // same one key that already failed. Latched in the loader, so this costs
        // a field read once it has run. Hopped for the same reason as the pull's
        // copy: the first call is a batch of keyed unseals, and Main is the
        // caller here whenever a foreground refresh drives sync().
        withContext(Dispatchers.Default) { previousIdentityLoader.ensureLoaded() }

        // Decrypt off Main, same as the pull: sync() can run on viewModelScope
        // (Main) and this batch of JNI decrypts + JSON parses is unbounded.
        val updated = withContext(Dispatchers.Default) {
            val out = mutableListOf<ItemEntity>()
            for (item in undecrypted) {
                // A note the inbox keeps away (its notebook was declined, or its invitation is
                // not answered) gets no title here either: its row should not be there at
                // all, and one whose removal failed must not be filled in instead.
                if (!AccountIds.same(item.userId, actingUser) && sharesInbox.isKeptAway(item.id)) continue
                val decryptedContent = decryptItemContent(
                    item.encryptedContent,
                    item.contentNonce,
                    vaultKey,
                    item.wrappedContentKey,
                    item.wrappedContentKeyNonce,
                    item.id,
                ) ?: continue
                val parsed = parseContentJson(decryptedContent)
                out.add(item.copy(
                    decryptedContent = decryptedContent,
                    title = parsed?.optString("title", "")?.takeIf { it.isNotEmpty() },
                    color = parsed?.optString("color", "")?.takeIf { it.isNotEmpty() },
                    isPinned = parsed?.optBoolean("is_pinned", false) ?: false,
                    isArchived = parsed?.optBoolean("is_archived", false) ?: false,
                    sortOrder = parsed?.optInt("sort_order", item.sortOrder) ?: item.sortOrder,
                ))
            }
            out
        }

        var skipped = 0
        if (updated.isNotEmpty()) {
            // Cross-account guard, as at the pull upsert: an account switch can
            // land between reading the undecrypted rows and writing their
            // plaintext back, and worker cancellation is only cooperative — so
            // re-check the acting user captured at sync start right before the
            // write. Never persist this account's plaintext into another's DB.
            if (sessionManager.getUserId() != actingUser) {
                android.util.Log.w(TAG, "Backfill: acting user changed mid-sync; discarding")
                return 0
            }
            // Same TOCTOU as the pull: an interactive edit can dirty a row between
            // getUndecryptedItems() above and this write. Preserve it rather than
            // overwriting the edit with the (older) backfilled plaintext.
            skipped = itemDao.upsertAllPreservingDirty(updated)
            if (skipped > 0) android.util.Log.d(TAG, "Backfill: skipped $skipped rows dirtied during decrypt")
        }
        return updated.size - skipped
    }

    /**
     * Decrypt item content. Tries three paths in order:
     *   1. Owned note with wrapped_content_key → unwrap with vault.
     *   2. Note shared with us → content key came from the inbox's
     *      sealed-box open, keyed by item id.
     *   3. Legacy note predating per-note content keys → direct decrypt
     *      under the vault key.
     *
     * Paths 1 and 3 both fall back to the vault key of any RECOVERED pre-reset
     * era after the current one fails; path 2 does not, because a share's
     * content key is sealed to the recipient's CURRENT key by the sender and
     * has nothing to do with the recipient's own reset history.
     */
    private fun decryptItemContent(
        ciphertext: String,
        nonce: String,
        vaultKeyB64: String,
        wrappedContentKey: String?,
        wrappedContentKeyNonce: String?,
        itemId: String? = null,
    ): String? {
        if (wrappedContentKey != null && wrappedContentKeyNonce != null) {
            fun tryVault(vaultB64: String): String? {
                val vaultBytes = try {
                    android.util.Base64.decode(vaultB64, android.util.Base64.NO_WRAP)
                } catch (_: Exception) { null }
                if (vaultBytes == null || vaultBytes.size != 32) return null
                val contentKey = noteCrypto.unwrapWithVault(wrappedContentKey, wrappedContentKeyNonce, vaultBytes)
                    ?: return null
                // AAD v2: bind to the item id when known; v2 decrypt
                // falls back to v1 so legacy ciphertext still reads.
                return if (itemId != null) {
                    noteCrypto.decryptNoteWithContentKeyV2(nonce, ciphertext, contentKey, itemId)
                } else {
                    noteCrypto.decryptNoteWithContentKey(nonce, ciphertext, contentKey)
                }
            }

            tryVault(vaultKeyB64)?.let { return it }

            // Then any identity RECOVERED from before a password reset. Note's
            // vault key is derived from the account keypair, so a reset makes
            // every older note fail the unwrap above — this is what turns a
            // library of unreadable notes back into a library.
            //
            // CURRENT KEY FIRST, always: this loop runs only after it failed.
            for (previousVault in previousIdentityLoader.vaultKeys()) {
                tryVault(previousVault)?.let { return it }
            }
        }
        if (itemId != null) {
            val sharedKey = sharesInbox.getContentKey(itemId)
            if (sharedKey != null) {
                val plain = noteCrypto.decryptNoteWithContentKeyV2(nonce, ciphertext, sharedKey, itemId)
                if (plain != null) return plain
            }
        }
        fun tryDirect(vaultB64: String): String? = try {
            val encData = uniffi.pingolin_mobile.EncryptedData(nonce = nonce, ciphertext = ciphertext)
            cryptoManager.decryptNote(encData, vaultB64)
        } catch (e: Exception) {
            null
        }

        tryDirect(vaultKeyB64)?.let { return it }

        // A legacy note is encrypted under the vault key ITSELF, so a
        // password reset takes it out exactly like the wrapped-key
        // notes above; trying the current key alone would leave a
        // recovered user with a library that opens except for its
        // oldest notes. Same order and same reasoning as the
        // wrapped-key loop: current key first, recovered eras only
        // after it has failed.
        for (previousVault in previousIdentityLoader.vaultKeys()) {
            tryDirect(previousVault)?.let { return it }
        }
        return null
    }

    private fun parseContentJson(json: String): JSONObject? {
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            null
        }
    }

    data class SyncResult(val pushed: Int, val pulled: Int, val conflicts: Int)
    private data class PullResult(val itemCount: Int, val conflicts: Int)
}
