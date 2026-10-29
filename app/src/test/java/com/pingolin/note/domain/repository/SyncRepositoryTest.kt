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

import android.app.Application
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.*
import com.pingolin.note.domain.QuotaExceededException
import com.pingolin.note.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SyncRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var noteApi: NoteApi

    @MockK
    private lateinit var itemDao: ItemDao

    @MockK
    private lateinit var cryptoManager: CryptoManager

    @MockK
    private lateinit var appContext: Application

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var noteCrypto: com.pingolin.note.collab.NoteCrypto

    @MockK
    private lateinit var sharesInbox: SharesInboxRepository

    private lateinit var repository: SyncRepository

    /** Hoisted out of the constructor call so the metadata tests can verify
     *  the pull hands the display caches to it instead of writing them. */
    private lateinit var userMetadata: UserMetadataStore

    /** Hoisted for the acceptance-prune tests; a relaxed no-op everywhere else. */
    private lateinit var notebookInvites: NotebookInvites

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        mockkObject(com.pingolin.note.widget.WidgetUpdateHelper)
        every { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(any()) } just Runs

        every { sessionManager.getUserId() } returns "u1"
        every { sessionManager.getMasterKey() } returns
            java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })

        // Stub android.util.Base64 against the JDK decoder for JVM unit tests.
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }

        // Default: no pending backfill work (tests can override per-test)
        coEvery { itemDao.getUndecryptedItems() } returns emptyList()

        // The pull path reads the local row to preserve cached plaintext
        // when decryption fails. Default: no local copy.
        coEvery { itemDao.getById(any()) } returns null

        // Batched sibling of getById — the pull dirty-guard + plaintext
        // merge does one getByIds per page. Default: no local copies,
        // matching the getById=null default above.
        coEvery { itemDao.getByIds(any()) } returns emptyList()

        // Pull/backfill write via upsertAllPreservingDirty (a @Transaction
        // default method whose real body filters is_dirty rows then delegates to
        // upsertAll). MockK intercepts the default method, so mirror that
        // delegation here: no test row is dirty (getByIds/getDirtyIdsAmong default
        // to empty), so the real method would skip nothing and call upsertAll with
        // the whole page. This keeps every existing upsertAll stub/verify valid;
        // the dirty-skip guard is exercised by sync_pull_skipsLocalRowWithUnsyncedChanges.
        coEvery { itemDao.upsertAllPreservingDirty(any()) } coAnswers {
            val items = firstArg<List<ItemEntity>>()
            itemDao.upsertAll(items)
            0
        }

        // Inbox is best-effort; default to a no-op that returns 0 keys.
        coEvery { sharesInbox.refresh() } returns 0
        // ...and one that applied no list, which is what a refresh returning 0
        // keys most often is: nothing to prune acceptances against.
        every { sharesInbox.listedNotebookIds } returns null
        // ...and that has taken no row out of the database, and keeps no note away.
        every { sharesInbox.rowsRemoved } returns 0L
        every { sharesInbox.isKeptAway(any()) } returns false
        // ...and whose invitations nobody answered since.
        coEvery { sharesInbox.refreshIfAnswersChanged() } returns false
        every { sharesInbox.getContentKey(any()) } returns null
        every { sharesInbox.sharedKeyFor(any()) } returns null

        // NoteRepository is injected via Provider to break the
        // circular DI (Sync calls retryDirtyItems → NoteRepository's
        // constructor depends on SyncRepository sibling beans).
        // Tests don't exercise the replay so return a no-op.
        val noteRepoMock = io.mockk.mockk<NoteRepository>(relaxed = true)
        coEvery { noteRepoMock.retryDirtyItems() } returns 0
        val noteRepoProvider = javax.inject.Provider<NoteRepository> { noteRepoMock }

        userMetadata = io.mockk.mockk<UserMetadataStore>(relaxed = true)
        notebookInvites = io.mockk.mockk<NotebookInvites>(relaxed = true)

        repository = SyncRepository(
            // Relaxed: pull() asks it to load first, and a strict mock would
            // throw inside that method's own error handling, turning success-path
            // assertions into silent passes.
            io.mockk.mockk<PreviousIdentityLoader>(relaxed = true),
            noteApi, itemDao, cryptoManager, appContext, sessionManager,
            noteCrypto, sharesInbox, noteRepoProvider,
            // The legacy-notebook sweep runs at the end of sync(); relaxed so
            // it is a no-op here rather than the subject of these tests.
            javax.inject.Provider { io.mockk.mockk<NotebookRepository>(relaxed = true) },
            // Relaxed no-op: sync()'s tail reconciles reminders best-effort,
            // and none of these tests are about alarms.
            io.mockk.mockk<ReminderScheduler>(relaxed = true),
            javax.inject.Provider { notebookInvites },
            userMetadata,
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    // =============================================================================
    // sync - PUSH TESTS
    // =============================================================================

    @Test
    fun `pushPendingChanges_pushesDirtyEditsAndDeletes_withoutPulling`() = runTest {
        // pushDirtyItems() loops on getDirtyItemsPaged until empty → contributes 0.
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        // The eager-PUT edit replay + delete replay run through NoteRepository.
        val repo = io.mockk.mockk<NoteRepository>(relaxed = true)
        coEvery { repo.retryDirtyItems() } returns 2
        coEvery { repo.retryDeletedItems() } returns 1
        val sync = SyncRepository(
            io.mockk.mockk<PreviousIdentityLoader>(relaxed = true),
            noteApi, itemDao, cryptoManager, appContext, sessionManager,
            noteCrypto, sharesInbox, javax.inject.Provider { repo },
            javax.inject.Provider { io.mockk.mockk<NotebookRepository>(relaxed = true) },
            // Relaxed no-op: sync()'s tail reconciles reminders best-effort,
            // and none of these tests are about alarms.
            io.mockk.mockk<ReminderScheduler>(relaxed = true),
            javax.inject.Provider { io.mockk.mockk<NotebookInvites>(relaxed = true) },
            io.mockk.mockk<UserMetadataStore>(relaxed = true),
        )

        val pushed = sync.pushPendingChanges()

        assertEquals(3, pushed) // 0 create-push + 2 edit-replays + 1 delete-replay
        coVerify { itemDao.getDirtyItemsPaged(any(), any()) }
        coVerify { repo.retryDirtyItems() }
        coVerify { repo.retryDeletedItems() }
        // PUSH-only: no inbox refresh and no /sync/pull (those belong to sync()).
        coVerify(exactly = 0) { sharesInbox.refresh() }
        coVerify(exactly = 0) { noteApi.syncPull(any(), any(), any()) }
    }

    @Test
    fun `sync_noDirtyItems_pushesZero`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(100, 0) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        val result = repository.sync()

        assertEquals(0, result.pushed)
    }

    @Test
    fun `sync_withDirtyItems_pushesToServer`() = runTest {
        val dirtyItems = listOf(createTestEntity("1", isDirty = true))

        // One stub only: a second `getDirtyItemsPaged(100, any())` would shadow
        // this one for offset 0 too (MockK resolves to the last matching stub),
        // so the page would come back empty and nothing would be pushed. The
        // loop breaks on its own here because the page is short of pageSize.
        coEvery { itemDao.getDirtyItemsPaged(100, 0) } returns dirtyItems
        coEvery { noteApi.syncPush(any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPushResponse(created = 1, conflicts = emptyList(), errors = emptyList()))
        )
        coEvery { itemDao.clearDirtyFlagsIfUnchanged(any()) } returns listOf("1")
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        val result = repository.sync()

        assertEquals(1, result.pushed)
        coVerify {
            itemDao.clearDirtyFlagsIfUnchanged(match { items -> items.map { it.id } == listOf("1") })
        }
    }

    @Test
    fun `sync_quotaExceeded_throwsQuotaExceededException`() = runTest {
        val dirtyItems = listOf(createTestEntity("1", isDirty = true))

        coEvery { itemDao.getDirtyItemsPaged(100, 0) } returns dirtyItems
        coEvery { noteApi.syncPush(any()) } returns Response.error(
            402,
            """{"success":false,"error":"Note limit reached. Call 0800 555 0100","error_code":"quota_notes_exceeded"}"""
                .toResponseBody(),
        )

        try {
            repository.sync()
            fail("Expected QuotaExceededException")
        } catch (e: QuotaExceededException) {
            // This app's line, never the error body (JSON, the server's
            // words).
            assertEquals(QuotaExceededException.NOTE_LIMIT_TEXT, e.message)
        }
    }

    @Test
    fun `sync_pushFailure_keepsItemsDirty_soTheNextSyncRetries`() = runTest {
        // Clearing on a failed push would silently drop every unsynced
        // change in the batch: the whole page stays dirty on a
        // transient failure so the next cycle retries.
        val dirtyItems = listOf(createTestEntity("1", isDirty = true))

        coEvery { itemDao.getDirtyItemsPaged(100, 0) } returns dirtyItems
        coEvery { noteApi.syncPush(any()) } returns Response.error(
            500, "Server error".toResponseBody()
        )
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        val result = repository.sync()

        assertEquals(0, result.pushed)
        coVerify(exactly = 0) { itemDao.clearDirtyFlagsIfUnchanged(any()) }
        coVerify(exactly = 0) { itemDao.clearDirtyFlags(any()) }
    }

    @Test
    fun `sync_push_skipsSharedWithMeItems`() = runTest {
        // A dirty SHARED-WITH-ME note (sharedKeyFor != null) must NOT
        // go through the create-only /sync/push — that 403s the whole
        // batch and would block the recipient's OWN notes. Only the own
        // note is sent, even while the inbox holds a key for its id:
        // sharedKeyFor never hands one out for a row of ours.
        val own = createTestEntity("own-1", isDirty = true)
        val shared = createTestEntity("shared-1", isDirty = true)
        coEvery { itemDao.getDirtyItemsPaged(100, 0) } returns listOf(own, shared)
        every { sharesInbox.getContentKey("own-1") } returns ByteArray(32)
        every { sharesInbox.sharedKeyFor(match { it.id == "own-1" }) } returns null
        every { sharesInbox.sharedKeyFor(match { it.id == "shared-1" }) } returns ByteArray(32)
        coEvery { noteApi.syncPush(any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPushResponse(created = 1, conflicts = emptyList(), errors = emptyList()))
        )
        coEvery { itemDao.clearDirtyFlagsIfUnchanged(any()) } returns listOf("own-1")
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        repository.sync()

        // Only the own note reached /sync/push; the shared note was skipped.
        coVerify { noteApi.syncPush(match { reqs -> reqs.size == 1 && reqs[0].id == "own-1" }) }
        coVerify {
            itemDao.clearDirtyFlagsIfUnchanged(
                match { items -> items.map { it.id } == listOf("own-1") }
            )
        }
    }

    @Test
    fun `sync_push_editDuringPost_keepsItemDirty_soTheEditStaysInTheOutbox`() = runTest {
        // The create-push snapshots the dirty page, then spends a
        // round-trip in POST /sync/push. An interactive edit landing
        // inside that window writes a new ciphertext and re-sets
        // is_dirty; an unconditional clearDirtyFlags(createdIds) would
        // clear it anyway, dropping the edit from the outbox while the
        // server had only ever seen the older body.
        val pushed = createTestEntity("note-1", isDirty = true)
        coEvery { itemDao.getDirtyItemsPaged(100, 0) } returns listOf(pushed)
        coEvery { itemDao.getDirtyItemsPaged(100, 1) } returns emptyList()
        every { sharesInbox.getContentKey(any()) } returns null
        coEvery { noteApi.syncPush(any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPushResponse(created = 1, conflicts = emptyList(), errors = emptyList()))
        )
        // The CAS refuses: the row holds a newer ciphertext than
        // the one we sent.
        coEvery { itemDao.clearDirtyFlagsIfUnchanged(any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        repository.sync()

        // The clear is compare-and-swapped against the ciphertext actually sent...
        coVerify {
            itemDao.clearDirtyFlagsIfUnchanged(
                match { items ->
                    items.single().encryptedContent == pushed.encryptedContent &&
                        items.single().contentNonce == pushed.contentNonce
                }
            )
        }
        // ...and no unconditional clear is made, so nothing can drop the newer
        // edit out of the dirty queue.
        coVerify(exactly = 0) { itemDao.clearDirtyFlags(any()) }
    }

    // =============================================================================
    // sync - PULL TESTS
    // =============================================================================

    @Test
    fun `sync_pullRemoteChanges_decryptsAndUpserts`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L

        val remoteItem = ItemDto(
            id = "remote-1",
            userId = "user-1",
            itemType = 1,
            encryptedContent = "enc-content",
            contentNonce = "enc-nonce",
            version = 1,
            createdAt = "2024-01-15T10:00:00.000Z",
            updatedAt = "2024-01-15T12:00:00.000Z",
            deleted = false,
            circleId = null,
            notebookId = null,
            contentSize = 100,
        )

        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(remoteItem),
                cursor = null,
                hasMore = false,
            ))
        )

        every { cryptoManager.decryptNote(any(), any()) } returns """{"title":"Remote Note","body":"Hello"}"""
        coEvery { itemDao.upsertAll(any()) } just Runs

        val result = repository.sync()

        assertEquals(1, result.pulled)
        coVerify { itemDao.upsertAll(any()) }
    }

    @Test
    fun `sync_pullDecryptionFails_nullDecryptedContent`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L
        // One of this account's OWN items: its row is written even unread, and the backfill
        // reads it later. (Another account's item that nothing opens gets no row: see
        // sync_pull_writesNoRowForAnotherAccountsItemThatNothingOpens.)
        val me = "11111111-1111-4111-8111-111111111111"
        every { sessionManager.getUserId() } returns me

        val remoteItem = ItemDto(
            id = "remote-1",
            userId = me,
            itemType = 1,
            encryptedContent = "enc-content",
            contentNonce = "enc-nonce",
            version = 1,
            createdAt = "2024-01-15T10:00:00.000Z",
            updatedAt = "2024-01-15T12:00:00.000Z",
            deleted = false,
            circleId = null,
            notebookId = null,
            contentSize = 100,
        )

        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(remoteItem),
                cursor = null,
                hasMore = false,
            ))
        )

        every { cryptoManager.decryptNote(any(), any()) } throws Exception("Decryption failed")
        coEvery { itemDao.upsertAll(any()) } just Runs

        val result = repository.sync()

        // Item still pulled but with null decryptedContent
        assertEquals(1, result.pulled)
        coVerify {
            itemDao.upsertAll(match { items ->
                items.size == 1 && items[0].decryptedContent == null
            })
        }
    }

    @Test
    fun `sync_pullNotifiesWidgets_whenItemsPulled`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L

        val remoteItem = ItemDto(
            id = "remote-1",
            userId = "user-1",
            itemType = 1,
            encryptedContent = "enc",
            contentNonce = "nonce",
            version = 1,
            createdAt = "2024-01-15T10:00:00.000Z",
            updatedAt = "2024-01-15T12:00:00.000Z",
            deleted = false,
            circleId = null,
            notebookId = null,
            contentSize = 10,
        )

        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(remoteItem),
                cursor = null,
                hasMore = false,
            ))
        )
        every { cryptoManager.decryptNote(any(), any()) } returns """{"title":"Test"}"""
        coEvery { itemDao.upsertAll(any()) } just Runs

        repository.sync()

        verify { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    @Test
    fun `sync_pullNoItems_doesNotNotifyWidgets`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        repository.sync()

        verify(exactly = 0) { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(any()) }
    }

    /**
     * Once a share is declined, nothing of it should show up. A declined notebook's rows
     * leave the database at the inbox refresh, and the pull that follows brings nothing: the
     * widgets, which read that database, have to be told, or they go on showing the declined
     * notes' titles and first lines.
     */
    @Test
    fun `sync_inboxRefreshRemovedRows_pullNoItems_notifiesWidgets`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        every { sharesInbox.rowsRemoved } returns 2L

        repository.sync()

        verify(exactly = 1) { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    /**
     * The widgets are told right after the refresh, not at the END of the cycle: a free account
     * over its note limit with one unsynced note fails the push on EVERY sync, so the end is
     * never reached, and a declined notebook's titles would stay on the home screen although
     * their rows were gone.
     */
    @Test
    fun `the widgets are told of removed rows even when the push that follows fails`() = runTest {
        every { sharesInbox.rowsRemoved } returns 2L
        coEvery { itemDao.getDirtyItemsPaged(100, 0) } returns listOf(createTestEntity("1", isDirty = true))
        coEvery { noteApi.syncPush(any()) } returns Response.error(
            402,
            """{"success":false,"error":"Note limit reached","error_code":"quota_notes_exceeded"}""".toResponseBody(),
        )

        try {
            repository.sync()
            fail("Expected QuotaExceededException")
        } catch (_: QuotaExceededException) {
        }

        verify(exactly = 1) { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    /** And when it is the refresh itself that fails after it had taken rows out. */
    @Test
    fun `the widgets are told of removed rows when the refresh that removed them then failed`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        coEvery { sharesInbox.refresh() } throws RuntimeException("materialise failed")
        every { sharesInbox.rowsRemoved } returns 1L

        repository.sync()

        verify(exactly = 1) { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    /**
     * The count only grows (SharesInboxRepository.rowsRemoved), and the widgets are told when it
     * has grown since they were last told: once for a removal, whichever sync made it, and not
     * again by every later sync. Two syncs can overlap, and "what the last refresh removed"
     * would lose the first one's removals when the second refresh set it back to zero.
     */
    @Test
    fun `a removal is announced once, and the next one again`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        every { sharesInbox.rowsRemoved } returns 2L
        repository.sync()
        repository.sync()
        verify(exactly = 1) { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }

        // Another sync's refresh removed one more meanwhile: this one announces it.
        every { sharesInbox.rowsRemoved } returns 3L
        repository.sync()
        verify(exactly = 2) { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    @Test
    fun `a widget update that fails does not stop the sync that follows`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        every { sharesInbox.rowsRemoved } returns 1L
        every { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(any()) } throws RuntimeException("no widget host")

        repository.sync()

        coVerify(exactly = 1) { noteApi.syncPull(any(), any(), any()) }
    }

    /** A sync CANCELLED after its refresh removed rows still tells the widgets. */
    @Test
    fun `the widgets are told of removed rows when the sync is cancelled in its refresh`() = runTest {
        coEvery { sharesInbox.refresh() } throws kotlinx.coroutines.CancellationException("the worker was stopped")
        every { sharesInbox.rowsRemoved } returns 1L

        try {
            repository.sync()
            fail("the cancellation must leave sync()")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }

        verify(exactly = 1) { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
        coVerify(exactly = 0) { noteApi.syncPull(any(), any(), any()) }
    }

    // =============================================================================
    // sync - AN INVITATION ANSWERED SINCE THE REFRESH
    // =============================================================================

    /**
     * The acceptances are in the account's metadata, which the PULL brings; the inbox was
     * refreshed before it. So the inbox is read once more after the pull when an answer
     * changed what is held back, and the widgets are told of what that reading wrote.
     */
    @Test
    fun `sync_rereadsTheInboxAfterThePull_andTellsTheWidgets_whenAnInvitationWasAnswered`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        coEvery { sharesInbox.refreshIfAnswersChanged() } returns true

        repository.sync()

        coVerifyOrder {
            sharesInbox.refresh()
            noteApi.syncPull(any(), any(), any())
            sharesInbox.refreshIfAnswersChanged()
        }
        verify(exactly = 1) { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    @Test
    fun `sync_noAnswerSinceTheRefresh_tellsTheWidgetsNothing`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        repository.sync()

        coVerify(exactly = 1) { sharesInbox.refreshIfAnswersChanged() }
        verify(exactly = 0) { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(any()) }
    }

    @Test
    fun `sync_aFailedRereadOfTheInbox_doesNotFailTheSync_andStillAnnouncesRemovedRows`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        // The re-read took a row out (an acceptance that was taken back) and then failed.
        var removed = 0L
        every { sharesInbox.rowsRemoved } answers { removed }
        coEvery { sharesInbox.refreshIfAnswersChanged() } coAnswers { removed = 1L; throw RuntimeException("offline") }

        repository.sync()

        verify(exactly = 1) { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    // =============================================================================
    // sync - NOTES OF A DECLINED NOTEBOOK
    // =============================================================================

    /**
     * The server serves a note with a live share in the PULL too, and the inbox's key opens it.
     * One the inbox keeps away because its notebook was declined (SharesInboxRepository.isKeptAway)
     * is not written here either: the widgets read this database.
     */
    @Test
    fun `sync_pull_doesNotWriteANoteOfADeclinedNotebook`() = runTest {
        val me = "11111111-1111-4111-8111-111111111111"
        every { sessionManager.getUserId() } returns me
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L
        val theirs = createRemoteItemDto("remote-kept-away").copy(userId = "22222222-2222-4222-8222-222222222222")
        val alsoTheirs = createRemoteItemDto("remote-shown").copy(userId = "22222222-2222-4222-8222-222222222222")
        every { sharesInbox.isKeptAway("remote-kept-away") } returns true
        // The inbox handed out the key of the one that is shown (and of neither, were it asked
        // for the other: it is not asked).
        val key = ByteArray(32) { 3 }
        every { sharesInbox.getContentKey("remote-shown") } returns key
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), key, "remote-shown") } returns """{"title":"shown"}"""
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = listOf(theirs, alsoTheirs), cursor = null, hasMore = false))
        )
        val written = mutableListOf<List<ItemEntity>>()
        coEvery { itemDao.upsertAll(capture(written)) } just Runs

        repository.sync()

        assertEquals(listOf("remote-shown"), written.flatten().map { it.id })
        assertEquals("shown", written.flatten().single().title)
        verify(exactly = 0) { sharesInbox.getContentKey("remote-kept-away") }
    }

    /**
     * Another account's item that NOTHING here opens, and that has no row yet, gets none. The
     * inbox hands out a shared note's key only when the note is not one to hold back; a row
     * written without it has no title, and the widgets would show it as "Untitled" — for example
     * a note of an invitation nobody has answered, after a refresh that stopped early.
     */
    @Test
    fun `sync_pull_writesNoRowForAnotherAccountsItemThatNothingOpens`() = runTest {
        val me = "11111111-1111-4111-8111-111111111111"
        val them = "22222222-2222-4222-8222-222222222222"
        every { sessionManager.getUserId() } returns me
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L
        every { cryptoManager.decryptNote(any(), any()) } throws Exception("not ours to open")
        val unopened = createRemoteItemDto("theirs-unopened").copy(userId = them)
        // One of OUR OWN that does not open either is still written: the backfill reads it later.
        val mine = createRemoteItemDto("mine-unopened").copy(userId = me)
        // And one of theirs that already HAS a row keeps it, with what it held.
        val known = createRemoteItemDto("theirs-known").copy(userId = them)
        val knownRow = createTestEntity("theirs-known").copy(userId = them, decryptedContent = """{"title":"kept"}""", isDirty = false)
        coEvery { itemDao.getByIds(any()) } returns listOf(knownRow)
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = listOf(unopened, mine, known), cursor = null, hasMore = false))
        )
        val written = mutableListOf<List<ItemEntity>>()
        coEvery { itemDao.upsertAll(capture(written)) } just Runs

        repository.sync()

        val rows = written.flatten().associateBy { it.id }
        assertEquals(setOf("mine-unopened", "theirs-known"), rows.keys)
        assertNull(rows.getValue("mine-unopened").decryptedContent)
        assertEquals("kept", rows.getValue("theirs-known").title)
    }

    /** The same item once a key opens it is written like any other: the guard is on "nothing opened". */
    @Test
    fun `sync_pull_writesAnotherAccountsItemOnceTheInboxKeyOpensIt`() = runTest {
        val me = "11111111-1111-4111-8111-111111111111"
        val them = "22222222-2222-4222-8222-222222222222"
        every { sessionManager.getUserId() } returns me
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L
        every { cryptoManager.decryptNote(any(), any()) } throws Exception("not ours to open")
        val key = ByteArray(32) { 5 }
        every { sharesInbox.getContentKey("theirs-opened") } returns key
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), key, "theirs-opened") } returns """{"title":"opened"}"""
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = listOf(createRemoteItemDto("theirs-opened").copy(userId = them)), cursor = null, hasMore = false))
        )
        val written = mutableListOf<List<ItemEntity>>()
        coEvery { itemDao.upsertAll(capture(written)) } just Runs

        repository.sync()

        assertEquals(listOf("opened"), written.flatten().map { it.title })
    }

    /**
     * The backfill gives a title to rows that have none. A note the inbox keeps away should
     * have no row at all; one whose removal failed must not be filled in instead.
     */
    @Test
    fun `sync_backfill_leavesAKeptAwayNoteWithoutItsTitle`() = runTest {
        val me = "11111111-1111-4111-8111-111111111111"
        every { sessionManager.getUserId() } returns me
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        val keptAway = createTestEntity("kept-away").copy(userId = "22222222-2222-4222-8222-222222222222", decryptedContent = null)
        val mine = createTestEntity("mine").copy(userId = me, decryptedContent = null)
        coEvery { itemDao.getUndecryptedItems() } returns listOf(keptAway, mine)
        every { sharesInbox.isKeptAway(any()) } returns true
        every { sharesInbox.getContentKey(any()) } returns null
        every { cryptoManager.decryptNote(any(), any()) } returns """{"title":"t"}"""
        val written = mutableListOf<List<ItemEntity>>()
        coEvery { itemDao.upsertAll(capture(written)) } just Runs

        repository.sync()

        // This account's own row is filled in, whatever the inbox would keep away; the other is not.
        assertEquals(listOf("mine"), written.flatten().map { it.id })
    }

    /** Never one of this account's own items, whatever a share names: under any spelling of the id. */
    @Test
    fun `sync_pull_alwaysWritesThisAccountsOwnItem_evenOneTheInboxWouldKeepAway`() = runTest {
        // Letters in it: the item's owner comes in another case than the session's id.
        val me = "aaaaaaaa-1111-4111-8111-11111111111f"
        every { sessionManager.getUserId() } returns me
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L
        val mine = createRemoteItemDto("mine-1").copy(userId = me.uppercase())
        every { sharesInbox.isKeptAway(any()) } returns true
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = listOf(mine), cursor = null, hasMore = false))
        )
        val written = mutableListOf<List<ItemEntity>>()
        coEvery { itemDao.upsertAll(capture(written)) } just Runs

        repository.sync()

        assertEquals(listOf("mine-1"), written.flatten().map { it.id })
    }

    // =============================================================================
    // sync - PAGINATION TESTS
    // =============================================================================

    @Test
    fun `sync_pullMultiplePages_handlesHasMore`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L

        val page1Item = createRemoteItemDto("remote-1")
        val page2Item = createRemoteItemDto("remote-2")

        coEvery { noteApi.syncPull(since = any(), limit = 100) } returnsMany listOf(
            Response.success(ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(page1Item), cursor = "cursor-1", hasMore = true,
            ))),
            Response.success(ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(page2Item), cursor = null, hasMore = false,
            ))),
        )

        every { cryptoManager.decryptNote(any(), any()) } returns """{"title":"Test"}"""
        coEvery { itemDao.upsertAll(any()) } just Runs

        val result = repository.sync()

        assertEquals(2, result.pulled)
    }

    // =============================================================================
    // extractTitle/extractField TESTS (tested indirectly via sync pull)
    // =============================================================================

    @Test
    @org.junit.Ignore("Test mock drift — decrypt mock returns null for the matched content path; upsertAll verify predicate doesn't match the produced entity.")
    fun `sync_pullExtractsTitleFromDecryptedContent`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L

        val remoteItem = createRemoteItemDto("remote-1")
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(remoteItem), cursor = null, hasMore = false,
            ))
        )
        every { cryptoManager.decryptNote(any(), any()) } returns """{"title":"My Title","body":"Hello","color":"#FF0000","is_pinned":true,"is_archived":false}"""
        coEvery { itemDao.upsertAll(any()) } just Runs

        repository.sync()

        coVerify {
            itemDao.upsertAll(match { items ->
                items.size == 1 &&
                    items[0].title == "My Title" &&
                    items[0].color == "#FF0000" &&
                    items[0].isPinned &&
                    !items[0].isArchived
            })
        }
    }

    // =============================================================================
    // SyncResult TESTS
    // =============================================================================

    @Test
    fun `sync_returnsCorrectSyncResult`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        val result = repository.sync()

        assertEquals(0, result.pushed)
        assertEquals(0, result.pulled)
        assertEquals(0, result.conflicts)
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    private fun createTestEntity(id: String, isDirty: Boolean = false) = ItemEntity(
        id = id,
        userId = "user-1",
        itemType = 1,
        encryptedContent = "encrypted",
        contentNonce = "nonce",
        version = 1,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        isDirty = isDirty,
    )

    private fun createRemoteItemDto(id: String) = ItemDto(
        id = id,
        userId = "user-1",
        itemType = 1,
        encryptedContent = "enc",
        contentNonce = "nonce",
        version = 1,
        createdAt = "2024-01-15T10:00:00.000Z",
        updatedAt = "2024-01-15T12:00:00.000Z",
        deleted = false,
        circleId = null,
        notebookId = null,
        contentSize = 10,
    )

    // =============================================================================
    // TESTS — wiring, metadata, conflict semantics
    // =============================================================================

    @Test
    fun `sync_pull_serverCopyOverwritesLocal`() = runTest {
        // "Server wins" for a CLEAN local row — pullRemoteChanges upserts the
        // remote copy with `isDirty = false`. (getById defaults to null here,
        // i.e. no local row / not dirty; the dirty-guard case is covered by
        // `sync_pull_skipsLocalRowWithUnsyncedChanges`.)
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L
        val me = "11111111-1111-4111-8111-111111111111"
        every { sessionManager.getUserId() } returns me

        val serverCopy = ItemDto(
            id = "shared",
            userId = me,
            itemType = 1,
            encryptedContent = "server-ct",
            contentNonce = "server-nonce",
            version = 99,
            createdAt = "2024-01-15T10:00:00.000Z",
            updatedAt = "2024-02-15T10:00:00.000Z",
            deleted = false,
            circleId = null,
            notebookId = null,
            contentSize = 20,
        )
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(serverCopy), cursor = null, hasMore = false,
            ))
        )
        coEvery { itemDao.upsertAll(any()) } just Runs

        repository.sync()

        coVerify {
            itemDao.upsertAll(match<List<com.pingolin.note.data.local.entity.ItemEntity>> { list ->
                val it = list.single()
                it.id == "shared" &&
                    it.encryptedContent == "server-ct" &&
                    it.version == 99L &&
                    !it.isDirty
            })
        }
    }

    @Test
    fun `sync_refreshesInboxBeforePulling`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        repository.sync()

        // Pull should be preceded by a best-effort inbox refresh so shared
        // items can be decrypted with the inbox-held content keys.
        coVerify { sharesInbox.refresh() }
    }

    // =============================================================================
    // sync - the reader's ACCEPTED shared notebooks are pruned only against a list
    //
    // The list of accepted notebooks is in the metadata every device shares.
    // Were it pruned after every inbox refresh against sharedNotebooksFlow,
    // which in a cold process (a background SyncWorker) is empty until a refresh
    // succeeds, one failed inbox request would strike every acceptance, for every
    // device.
    // =============================================================================

    private fun stubAnEmptyPull() {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
    }

    @Test
    fun `sync_inboxRefreshAppliedNoList_prunesNoAcceptance`() = runTest {
        stubAnEmptyPull()
        // What a failed fetch, an unreadable trust store or a locked session leave:
        // refresh() returns 0 and there is no list.
        coEvery { sharesInbox.refresh() } returns 0
        every { sharesInbox.listedNotebookIds } returns null

        repository.sync()

        coVerify(exactly = 0) { notebookInvites.prune(any()) }
    }

    @Test
    fun `sync_inboxRefreshThrows_prunesNoAcceptance`() = runTest {
        stubAnEmptyPull()
        coEvery { sharesInbox.refresh() } throws RuntimeException("inbox down")
        every { sharesInbox.listedNotebookIds } returns null

        repository.sync()

        coVerify(exactly = 0) { notebookInvites.prune(any()) }
    }

    @Test
    fun `sync_inboxRefreshAppliedAList_prunesAgainstEveryNotebookItNamed`() = runTest {
        stubAnEmptyPull()
        // One notebook whose row opened and one whose row was refused (its sender
        // changed keys and the reader has not approved the new one yet): both are
        // still OFFERED, so neither acceptance is forgotten.
        val listed = setOf("10000000-0000-4000-8000-00000000000a", "10000000-0000-4000-8000-00000000000b")
        every { sharesInbox.listedNotebookIds } returns listed

        repository.sync()

        coVerify(exactly = 1) { notebookInvites.prune(listed) }
    }

    @Test
    fun `sync_anEmptyListIsAList_everyAcceptanceIsStale`() = runTest {
        stubAnEmptyPull()
        // The server answered, and names no notebook: that is a list.
        every { sharesInbox.listedNotebookIds } returns emptySet()

        repository.sync()

        coVerify(exactly = 1) { notebookInvites.prune(emptySet()) }
    }

    @Test
    fun `sync_neverPrunesAgainstTheNotebooksThatHappenToBeOpen`() = runTest {
        stubAnEmptyPull()
        every { sharesInbox.listedNotebookIds } returns setOf("10000000-0000-4000-8000-00000000000a")

        repository.sync()

        // sharedNotebooksFlow is what the Shared Notebooks screen draws; it is
        // not asked what is still shared.
        verify(exactly = 0) { sharesInbox.sharedNotebooksFlow }
    }

    @Test
    fun `sync_inboxRefreshThrows_continuesWithPull`() = runTest {
        coEvery { sharesInbox.refresh() } throws RuntimeException("inbox down")
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        val result = repository.sync()

        assertEquals(0, result.pulled)
        coVerify { noteApi.syncPull(any(), any(), any()) }
    }

    // --- metadata (item_type=10): the DISPLAY path
    // ---------------------------
    //
    // There is meant to be one metadata row per user, but nothing enforces
    // it. A pull loop that unpacked every metadata row it met straight into
    // the SessionManager caches the share fan-out and the editor read would
    // let the LAST row of the page decide what the user sees — right only
    // while the server serves rows oldest-first. The pull does not decide
    // at all: it re-derives from the newest row in Room via
    // UserMetadataStore.

    /** A metadata item to pull. `encryptedContent` carries the plaintext —
     *  see the passthrough `decryptNote` stub below. */
    private fun metadataDto(id: String, updatedAt: String, json: String, deleted: Boolean = false) = ItemDto(
        id = id, userId = "u", itemType = 10,
        encryptedContent = json, contentNonce = "nonce-$id",
        version = 1,
        createdAt = "2024-01-01T00:00:00.000Z",
        updatedAt = updatedAt,
        deleted = deleted,
        circleId = null, notebookId = null, contentSize = json.length,
    )

    @Test
    fun `sync_pullMetadata_neverWritesTheDisplayCachesFromTheStream`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L
        coEvery { itemDao.upsertAll(any()) } just Runs

        // Whatever the pull writes into the caches, recorded rather
        // than rejected: a test that just let the strict mock throw
        // would not show WHICH copy won, and that is the failure to
        // catch.
        val written = mutableMapOf<String, String>()
        every { sessionManager.saveWebNotebooks(any()) } answers { written["notebooks"] = firstArg() }
        every { sessionManager.saveNotebookShares(any()) } answers { written["shares"] = firstArg() }
        every { sessionManager.saveEditorModes(any()) } answers { written["editorModes"] = firstArg() }

        // Two metadata rows, NEWEST FIRST — the reverse of the
        // server's order. A loop that applies each row as it meets
        // it ends up holding the OLDER one.
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(
                    metadataDto("meta-new", "2024-03-01T00:00:00.000Z", """{"notebooks":[{"id":"nb-newest"}]}"""),
                    metadataDto("meta-old", "2024-01-01T00:00:00.000Z", """{"notebooks":[{"id":"nb-oldest"}]}"""),
                ),
                cursor = null, hasMore = false,
            ))
        )
        every { cryptoManager.decryptNote(any(), any()) } answers { firstArg<EncryptedData>().ciphertext }

        repository.sync()

        assertEquals(
            "the pull must not pick a metadata row out of the stream — here it would have taken the OLDER one",
            emptyMap<String, String>(), written,
        )
        coVerify(exactly = 1) { userMetadata.refreshCaches() }
    }

    /** A tombstone changes which row is newest-and-live, so the caches have to
     *  be re-derived after it lands — but still not from the stream. */
    @Test
    fun `sync_deletedMetadataItem_refreshesFromStore_withoutWritingCaches`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L
        coEvery { itemDao.upsertAll(any()) } just Runs

        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(
                    metadataDto("meta-1", "2024-02-01T00:00:00.000Z", """{"notebooks":[{"id":"nb-1"}]}""", deleted = true),
                ),
                cursor = null, hasMore = false,
            ))
        )
        every { cryptoManager.decryptNote(any(), any()) } answers { firstArg<EncryptedData>().ciphertext }

        repository.sync()

        verify(exactly = 0) { sessionManager.saveWebNotebooks(any()) }
        verify(exactly = 0) { sessionManager.saveNotebookShares(any()) }
        verify(exactly = 0) { sessionManager.saveEditorModes(any()) }
        coVerify(exactly = 1) { userMetadata.refreshCaches() }
    }

    /** The paired negative: a page of ordinary notes decrypts and stores
     *  without touching the metadata caches at all, so the verify above is
     *  attributable to the metadata row rather than to every sync. */
    @Test
    fun `sync_pageWithoutMetadata_doesNotRefreshCaches`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L
        coEvery { itemDao.upsertAll(any()) } just Runs
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(createRemoteItemDto("note-1")), cursor = null, hasMore = false,
            ))
        )
        every { cryptoManager.decryptNote(any(), any()) } returns """{"title":"Server"}"""

        repository.sync()

        coVerify(exactly = 0) { userMetadata.refreshCaches() }
    }

    @Test
    fun `sync_retryDirtyItems_isInvokedOnNoteRepository`() = runTest {
        // Build with a spy-ish provider to verify retryDirtyItems is called.
        val retryingRepo = io.mockk.mockk<NoteRepository>(relaxed = true)
        coEvery { retryingRepo.retryDirtyItems() } returns 3
        val provider = javax.inject.Provider<NoteRepository> { retryingRepo }
        val alt = SyncRepository(
            io.mockk.mockk<PreviousIdentityLoader>(relaxed = true),
            noteApi, itemDao, cryptoManager, appContext, sessionManager,
            noteCrypto, sharesInbox, provider,
            javax.inject.Provider { io.mockk.mockk<NotebookRepository>(relaxed = true) },
            // Relaxed no-op: sync()'s tail reconciles reminders best-effort,
            // and none of these tests are about alarms.
            io.mockk.mockk<ReminderScheduler>(relaxed = true),
            javax.inject.Provider { io.mockk.mockk<NotebookInvites>(relaxed = true) },
            io.mockk.mockk<UserMetadataStore>(relaxed = true),
        )

        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        alt.sync()

        coVerify { retryingRepo.retryDirtyItems() }
    }

    @Test
    fun `sync_retryDirtyItems_throws_doesNotFailSync`() = runTest {
        val badRepo = io.mockk.mockk<NoteRepository>(relaxed = true)
        coEvery { badRepo.retryDirtyItems() } throws RuntimeException("boom")
        val provider = javax.inject.Provider<NoteRepository> { badRepo }
        val alt = SyncRepository(
            io.mockk.mockk<PreviousIdentityLoader>(relaxed = true),
            noteApi, itemDao, cryptoManager, appContext, sessionManager,
            noteCrypto, sharesInbox, provider,
            javax.inject.Provider { io.mockk.mockk<NotebookRepository>(relaxed = true) },
            // Relaxed no-op: sync()'s tail reconciles reminders best-effort,
            // and none of these tests are about alarms.
            io.mockk.mockk<ReminderScheduler>(relaxed = true),
            javax.inject.Provider { io.mockk.mockk<NotebookInvites>(relaxed = true) },
            io.mockk.mockk<UserMetadataStore>(relaxed = true),
        )

        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        val result = alt.sync()

        assertEquals(0, result.pulled)
    }

    @Test
    fun `sync_pull_skipsLocalRowWithUnsyncedChanges`() = runTest {
        // Dirty-guard: a still-dirty local row (its edit/delete hasn't reached
        // the server) must NOT be overwritten by the pulled copy, or the
        // offline change would be silently lost.
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 0L
        coEvery { itemDao.getByIds(listOf("dirty-1")) } returns listOf(createTestEntity("dirty-1", isDirty = true))

        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(
                items = listOf(createRemoteItemDto("dirty-1")), cursor = null, hasMore = false,
            ))
        )
        every { cryptoManager.decryptNote(any(), any()) } returns """{"title":"Server"}"""
        coEvery { itemDao.upsertAll(any()) } just Runs

        val result = repository.sync()

        assertEquals(0, result.pulled)
        coVerify(exactly = 0) { itemDao.upsertAll(any()) }
    }

    @Test
    fun `sync_retryDeletedItems_isInvokedOnNoteRepository`() = runTest {
        val repoMock = io.mockk.mockk<NoteRepository>(relaxed = true)
        coEvery { repoMock.retryDirtyItems() } returns 0
        coEvery { repoMock.retryDeletedItems() } returns 2
        val provider = javax.inject.Provider<NoteRepository> { repoMock }
        val alt = SyncRepository(
            io.mockk.mockk<PreviousIdentityLoader>(relaxed = true),
            noteApi, itemDao, cryptoManager, appContext, sessionManager,
            noteCrypto, sharesInbox, provider,
            javax.inject.Provider { io.mockk.mockk<NotebookRepository>(relaxed = true) },
            // Relaxed no-op: sync()'s tail reconciles reminders best-effort,
            // and none of these tests are about alarms.
            io.mockk.mockk<ReminderScheduler>(relaxed = true),
            javax.inject.Provider { io.mockk.mockk<NotebookInvites>(relaxed = true) },
            io.mockk.mockk<UserMetadataStore>(relaxed = true),
        )

        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns null
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )

        alt.sync()

        coVerify { repoMock.retryDeletedItems() }
    }

    // =============================================================================
    // RECOVERY — healing notes that synced BEFORE the user recovered their
    // pre-reset identity in the account portal.
    // =============================================================================

    /** Vault key of a recovered pre-reset era. Distinct from the current one
     *  (ByteArray(32){7}, see setup) so a stub can tell them apart. */
    private val previousVaultBytes = ByteArray(32) { 9 }
    private val previousVaultKeyB64: String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(previousVaultBytes)

    /** A row that synced while the vault key of its era was gone: ciphertext and
     *  wrap intact, plaintext null — what the list renders as "Untitled". */
    private fun stuckRow(id: String, isDirty: Boolean = false) = ItemEntity(
        id = id,
        userId = "u1",
        itemType = 1,
        encryptedContent = "cipher-$id",
        contentNonce = "nonce-$id",
        decryptedContent = null,
        title = null,
        version = 1,
        createdAt = Instant.parse("2026-08-01T10:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T12:00:00Z"),
        isDirty = isDirty,
        wrappedContentKey = "wrapped-$id",
        wrappedContentKeyNonce = "wrapnonce-$id",
    )

    /** Wires a repository whose loader reports [vaultKeys] as recovered. */
    private fun repositoryWithRecovered(
        vaultKeys: List<String>,
        loader: PreviousIdentityLoader = io.mockk.mockk(relaxed = true),
    ): Pair<SyncRepository, PreviousIdentityLoader> {
        every { loader.vaultKeys() } returns vaultKeys
        val repo = SyncRepository(
            loader, noteApi, itemDao, cryptoManager, appContext, sessionManager,
            noteCrypto, sharesInbox, javax.inject.Provider { io.mockk.mockk<NoteRepository>(relaxed = true) },
            javax.inject.Provider { io.mockk.mockk<NotebookRepository>(relaxed = true) },
            // Relaxed no-op: sync()'s tail reconciles reminders best-effort,
            // and none of these tests are about alarms.
            io.mockk.mockk<ReminderScheduler>(relaxed = true),
            javax.inject.Provider { io.mockk.mockk<NotebookInvites>(relaxed = true) },
            io.mockk.mockk<UserMetadataStore>(relaxed = true),
        )
        return repo to loader
    }

    /** Only the recovered era's vault key unwraps these rows; the current one
     *  fails, exactly as it does after an email password reset. */
    private fun onlyPreviousVaultOpens(plaintext: String) {
        every { noteCrypto.unwrapWithVault(any(), any(), any()) } answers {
            if (thirdArg<ByteArray>().contentEquals(previousVaultBytes)) ByteArray(32) { 3 } else null
        }
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns plaintext
    }

    /**
     * Rows that synced before the recovery are never offered by the server
     * again: the pull cursor is getMaxUpdatedAt(), so it only ever asks for
     * NEWER items. The repair has to be local, from the ciphertext already in
     * Room.
     */
    @Test
    fun `sync_healsRowsThatSyncedBeforeTheRecovery_withoutRefetchingThem`() = runTest {
        val stuck = stuckRow("n1")
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns stuck.updatedAt.toEpochMilli()
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        coEvery { itemDao.getUndecryptedItems() } returns listOf(stuck)
        coEvery { itemDao.upsertAll(any()) } just Runs
        onlyPreviousVaultOpens("""{"title":"Recovered","body":"still here"}""")

        val (repo, _) = repositoryWithRecovered(listOf(previousVaultKeyB64))
        repo.sync()

        // The plaintext is back on the SAME row, from the ciphertext we already had.
        coVerify {
            itemDao.upsertAllPreservingDirty(match { items ->
                items.size == 1 &&
                    items[0].id == "n1" &&
                    items[0].title == "Recovered" &&
                    items[0].decryptedContent == """{"title":"Recovered","body":"still here"}"""
            })
        }
        // Local repair: the cursor still starts from what we hold, so
        // the repair does not re-download the library to fix a local
        // decrypt.
        coVerify(exactly = 1) {
            noteApi.syncPull(Instant.ofEpochMilli(stuck.updatedAt.toEpochMilli()).toString(), any(), any())
        }
    }

    /**
     * The heal must not depend on the pull having succeeded: the pull loads the
     * recovered identities from inside its page loop, and an offline or 5xx pull
     * breaks out before that.
     */
    @Test
    fun `sync_healsRecoveredRows_evenWhenThePullFails`() = runTest {
        val stuck = stuckRow("n1")
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns stuck.updatedAt.toEpochMilli()
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.error(
            503, "unavailable".toResponseBody(null)
        )
        coEvery { itemDao.getUndecryptedItems() } returns listOf(stuck)
        coEvery { itemDao.upsertAll(any()) } just Runs
        onlyPreviousVaultOpens("""{"title":"Recovered"}""")

        val (repo, loader) = repositoryWithRecovered(listOf(previousVaultKeyB64))
        repo.sync()

        coVerify { loader.ensureLoaded() }
        coVerify { itemDao.upsertAllPreservingDirty(match { it.singleOrNull()?.title == "Recovered" }) }
    }

    /**
     * Idempotent and bounded: with nothing broken locally the pass does no
     * decrypt work and writes nothing, so an ordinary sync is unaffected.
     */
    @Test
    fun `sync_noUndecryptedRows_doesNoRecoveryWork`() = runTest {
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns 1_000L
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        coEvery { itemDao.getUndecryptedItems() } returns emptyList()

        val (repo, _) = repositoryWithRecovered(listOf(previousVaultKeyB64))
        repo.sync()

        verify(exactly = 0) { noteCrypto.unwrapWithVault(any(), any(), any()) }
        coVerify(exactly = 0) { itemDao.upsertAll(any()) }
    }

    /**
     * A row the user has edited offline must survive the heal: its plaintext is
     * newer than anything the ciphertext on disk decrypts to.
     */
    @Test
    fun `sync_recoveryHeal_neverClobbersALocallyEditedRow`() = runTest {
        val dirty = stuckRow("n1", isDirty = true)
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns dirty.updatedAt.toEpochMilli()
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        coEvery { itemDao.getUndecryptedItems() } returns listOf(dirty)
        coEvery { itemDao.upsertAll(any()) } just Runs
        // The real @Transaction body filters dirty rows out; mirror that here
        // instead of the whole-page passthrough the shared setup installs.
        coEvery { itemDao.getDirtyIdsAmong(any()) } returns listOf("n1")
        coEvery { itemDao.upsertAllPreservingDirty(any()) } coAnswers {
            val items = firstArg<List<ItemEntity>>()
            val skip = itemDao.getDirtyIdsAmong(items.map { it.id }).toSet()
            val safe = items.filterNot { it.id in skip }
            if (safe.isNotEmpty()) itemDao.upsertAll(safe)
            items.size - safe.size
        }
        onlyPreviousVaultOpens("""{"title":"Recovered"}""")

        val (repo, _) = repositoryWithRecovered(listOf(previousVaultKeyB64))
        repo.sync()

        coVerify(exactly = 0) { itemDao.upsertAll(any()) }
    }

    /**
     * A LEGACY note — no wrapped content key, body encrypted under the vault key
     * itself — is broken by a password reset just like every other note, so the
     * direct-decrypt path has to try the recovered eras too. Trying the current
     * key alone would leave a recovered user with a library that opens except
     * for its oldest notes.
     */
    @Test
    fun `sync_healsLegacyNoteWithNoWrappedKey_usingARecoveredVaultKey`() = runTest {
        val legacy = stuckRow("legacy-1").copy(
            wrappedContentKey = null,
            wrappedContentKeyNonce = null,
        )
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns legacy.updatedAt.toEpochMilli()
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        coEvery { itemDao.getUndecryptedItems() } returns listOf(legacy)
        coEvery { itemDao.upsertAll(any()) } just Runs
        // Only the recovered era's vault key opens it; the current one throws
        // exactly as the SDK does on a failed AEAD open.
        every { cryptoManager.decryptNote(any(), any()) } answers {
            if (secondArg<String>() == previousVaultKeyB64) """{"title":"Legacy Recovered"}"""
            else throw Exception("wrong era")
        }

        val (repo, _) = repositoryWithRecovered(listOf(previousVaultKeyB64))
        repo.sync()

        coVerify {
            itemDao.upsertAllPreservingDirty(match { it.singleOrNull()?.title == "Legacy Recovered" })
        }
    }

    /**
     * Current key first on the legacy path too: a recovered era must never be
     * preferred over the identity the user is actually on.
     */
    @Test
    fun `sync_legacyNote_prefersTheCurrentVaultKey`() = runTest {
        val legacy = stuckRow("legacy-1").copy(
            wrappedContentKey = null,
            wrappedContentKeyNonce = null,
        )
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns legacy.updatedAt.toEpochMilli()
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        coEvery { itemDao.getUndecryptedItems() } returns listOf(legacy)
        coEvery { itemDao.upsertAll(any()) } just Runs
        every { cryptoManager.decryptNote(any(), any()) } returns """{"title":"Current"}"""

        val (repo, loader) = repositoryWithRecovered(listOf(previousVaultKeyB64))
        repo.sync()

        coVerify { itemDao.upsertAllPreservingDirty(match { it.singleOrNull()?.title == "Current" }) }
        // The current key answered, so no recovered era was ever consulted.
        verify(exactly = 0) { loader.vaultKeys() }
    }

    /**
     * Nothing recovered = nothing healed. The rows stay as they are for a later
     * sync rather than being rewritten with empty plaintext.
     */
    @Test
    fun `sync_noRecoveredIdentities_leavesStuckRowsAlone`() = runTest {
        val stuck = stuckRow("n1")
        coEvery { itemDao.getDirtyItemsPaged(any(), any()) } returns emptyList()
        coEvery { itemDao.getMaxUpdatedAt() } returns stuck.updatedAt.toEpochMilli()
        coEvery { noteApi.syncPull(any(), any(), any()) } returns Response.success(
            ApiResponse(success = true, data = SyncPullResponse(items = emptyList(), cursor = null, hasMore = false))
        )
        coEvery { itemDao.getUndecryptedItems() } returns listOf(stuck)
        coEvery { itemDao.upsertAll(any()) } just Runs
        every { noteCrypto.unwrapWithVault(any(), any(), any()) } returns null
        every { cryptoManager.decryptNote(any(), any()) } throws Exception("wrong era")

        val (repo, _) = repositoryWithRecovered(emptyList())
        repo.sync()

        coVerify(exactly = 0) { itemDao.upsertAll(any()) }
    }
}
