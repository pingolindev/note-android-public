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
import com.pingolin.note.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NoteRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var itemDao: ItemDao

    @MockK
    private lateinit var noteApi: NoteApi

    @MockK
    private lateinit var cryptoManager: CryptoManager

    @MockK
    private lateinit var appContext: Application

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var noteCrypto: com.pingolin.note.collab.NoteCrypto

    @MockK
    private lateinit var notebookShareFanOut: NotebookShareFanOut

    @MockK
    private lateinit var sharesInbox: SharesInboxRepository

    @MockK
    private lateinit var contentKeyRotator: ContentKeyRotator

    private lateinit var repository: NoteRepository

    private val testEncryptedData = EncryptedData(
        ciphertext = "encrypted-content",
        nonce = "test-nonce",
    )
    private val testContentKey = ByteArray(32) { it.toByte() }
    private val testVaultKeyB64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // Default mocks for encryption + vault key retrieval
        every { sessionManager.getMasterKey() } returns testVaultKeyB64
        every { cryptoManager.encryptNote(any(), any()) } returns testEncryptedData
        // Stub the per-note crypto — tests don't need real crypto output,
        // they check that the repository populates entity fields correctly
        // and marks items dirty.
        every { noteCrypto.generateContentKey() } returns testContentKey
        every { noteCrypto.wrapWithVault(any(), any()) } returns
            com.pingolin.note.collab.WrappedKeyPair("wrapped-key-b64", "wrap-nonce-b64")
        every { noteCrypto.unwrapWithVault(any(), any(), any()) } returns testContentKey
        every { noteCrypto.encryptNoteWithContentKey(any(), any()) } returns
            com.pingolin.note.collab.NotePayload("test-nonce", "encrypted-content")
        every { noteCrypto.decryptNoteWithContentKey(any(), any(), any()) } returns "{}"
        // AAD v2 note-content path (createNote/updateNote call these).
        every { noteCrypto.encryptNoteWithContentKeyV2(any(), any(), any()) } returns
            com.pingolin.note.collab.NotePayload("test-nonce", "encrypted-content")
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns "{}"
        // Notebook-move forward-secrecy rotation (only fires when a recipient
        // is revoked; stubbed so those tests don't hit an unmocked call).
        coEvery { contentKeyRotator.rotate(any(), any()) } returns
            ContentKeyRotator.RotationResult(true, 0, emptyList())

        // Stub WidgetUpdateHelper object (Kotlin `object` → use mockkObject, not mockkStatic)
        mockkObject(com.pingolin.note.widget.WidgetUpdateHelper)
        every { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(any()) } just Runs
        // android.util.Base64 is a stub in unit tests — replace with JDK Base64.
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        every { android.util.Base64.encodeToString(any<ByteArray>(), any<Int>()) } answers {
            java.util.Base64.getEncoder().withoutPadding().encodeToString(firstArg<ByteArray>())
        }

        coEvery { itemDao.upsert(any()) } just Runs
        // The local write-backs are compare-and-swaps, not blind upserts.
        // Default them to "applied" (1 row); the refused path (0) is exercised
        // explicitly by the race tests below.
        coEvery {
            itemDao.writeEditedItemIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns 1
        coEvery {
            itemDao.writeSharedEditIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns 1
        coEvery {
            itemDao.writeMintedContentKeyIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns 1
        coEvery {
            itemDao.resetToServerIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns 1
        every { notebookShareFanOut.recipientsFor(any()) } returns emptyList()
        coEvery { notebookShareFanOut.shareToRecipient(any(), any(), any(), any()) } returns true
        coEvery { notebookShareFanOut.unshareFromRecipient(any(), any()) } returns true
        // Default: no inbox entries — exercises the owner branch of
        // updateNote. Tests that want to exercise the shared-note
        // branch override with `every { sharesInbox.sharedKeyFor(row) } returns bytes`
        // (every sealing use asks sharedKeyFor; getContentKey only says
        // whether the inbox opened a notebook).
        every { sharesInbox.getContentKey(any()) } returns null
        every { sharesInbox.sharedKeyFor(any()) } returns null

        repository = NoteRepository(
            itemDao, noteApi, cryptoManager, sessionManager, appContext,
            noteCrypto, notebookShareFanOut, sharesInbox, contentKeyRotator,
            io.mockk.mockk<com.pingolin.note.util.RetainedContentKeyStore>(relaxed = true),
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    // =============================================================================
    // observeNotes TESTS
    // =============================================================================

    @Test
    fun `observeNotes_delegatesToDao`() {
        val items = listOf(createTestEntity("1"), createTestEntity("2"))
        every { itemDao.observeActiveNotes() } returns flowOf(items)

        val flow = repository.observeNotes()
        assertNotNull(flow)
        verify { itemDao.observeActiveNotes() }
    }

    @Test
    fun `observeArchived_delegatesToDao`() {
        every { itemDao.observeArchivedNotes() } returns flowOf(emptyList())

        val flow = repository.observeArchived()
        assertNotNull(flow)
        verify { itemDao.observeArchivedNotes() }
    }

    @Test
    fun `observeTrash_delegatesToDao`() {
        every { itemDao.observeTrash() } returns flowOf(emptyList())

        val flow = repository.observeTrash()
        assertNotNull(flow)
        verify { itemDao.observeTrash() }
    }

    // =============================================================================
    // getNote TESTS
    // =============================================================================

    @Test
    fun `getNote_existingId_returnsEntity`() = runTest {
        val entity = createTestEntity("note-1")
        coEvery { itemDao.getById("note-1") } returns entity

        val result = repository.getNote("note-1")

        assertNotNull(result)
        assertEquals("note-1", result?.id)
    }

    @Test
    fun `getNote_nonExistentId_returnsNull`() = runTest {
        coEvery { itemDao.getById("missing") } returns null

        val result = repository.getNote("missing")

        assertNull(result)
    }

    // =============================================================================
    // search TESTS
    // =============================================================================

    @Test
    fun `search_delegatesToDao`() = runTest {
        val results = listOf(createTestEntity("1"))
        coEvery { itemDao.search("query") } returns results

        val result = repository.search("query")

        assertEquals(1, result.size)
        coVerify { itemDao.search("query") }
    }

    // =============================================================================
    // createNote TESTS
    // =============================================================================

    @Test
    fun `createNote_encrypsContent_andStoresLocally`() = runTest {
        val content = NoteContent(title = "Test", body = "Hello", color = "#FF0000")

        val result = repository.createNote(content)

        assertNotNull(result)
        assertEquals("Test", result.title)
        assertEquals("encrypted-content", result.encryptedContent)
        assertEquals("test-nonce", result.contentNonce)
        assertEquals(1, result.itemType) // note type
        assertEquals(1L, result.version)
        assertTrue(result.isDirty)
        assertFalse(result.isArchived)
        assertEquals("#FF0000", result.color)

        coVerify { itemDao.upsert(any()) }
        verify { noteCrypto.encryptNoteWithContentKeyV2(any(), any(), any()) }
    }

    @Test
    fun `createNote_encryptsWithPersonalKey`() = runTest {
        val content = NoteContent(title = "Test", body = "Body")

        repository.createNote(content)

        verify { noteCrypto.encryptNoteWithContentKeyV2(any(), any(), any()) }
    }

    @Test
    fun `createNote_generatesUniqueId`() = runTest {
        val content = NoteContent(title = "Test", body = "Body")

        val result1 = repository.createNote(content)
        val result2 = repository.createNote(content)

        assertNotEquals(result1.id, result2.id)
    }

    @Test
    fun `createNote_notifiesWidgetUpdate`() = runTest {
        val content = NoteContent(title = "Test", body = "Body")

        repository.createNote(content)

        verify { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    @Test
    fun `createNote_setsContentSize`() = runTest {
        val content = NoteContent(title = "Test", body = "Body")

        val result = repository.createNote(content)

        assertEquals(testEncryptedData.ciphertext.length, result.contentSize)
    }

    @Test
    fun `createNote_pinnedNote_setsPinnedFlag`() = runTest {
        val content = NoteContent(title = "Test", body = "Body", isPinned = true)

        val result = repository.createNote(content)

        assertTrue(result.isPinned)
    }

    // =============================================================================
    // updateNote TESTS
    // =============================================================================

    @Test
    fun `updateNote_existingNote_updatesAndReencrypts`() = runTest {
        val existing = createTestEntity("note-1")
        coEvery { itemDao.getById("note-1") } returns existing

        val newContent = NoteContent(
            title = "Updated Title",
            body = "Updated Body",
            color = "#00FF00",
        )

        val result = repository.updateNote("note-1", newContent)

        assertNotNull(result)
        assertEquals("Updated Title", result?.title)
        // The PUT is unstubbed here, so it fails: the version stays where it was
        // last observed rather than being fabricated forward (see
        // updateNote_pushRejected_keepsObservedVersion).
        assertEquals(existing.version, result?.version)
        assertTrue(result!!.isDirty)
        assertEquals("encrypted-content", result.encryptedContent)
        assertEquals("#00FF00", result.color)

        // The write-back is the CAS, never the blind full-row upsert.
        coVerify {
            itemDao.writeEditedItemIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    /**
     * A wrap that exists but does not open under this vault key (another
     * era's vault key, a tampered row) must NOT be replaced by a freshly
     * minted key: that re-encrypts the note under a key only this device
     * holds. The save is refused and nothing reaches the server.
     */
    @Test
    fun `updateNote_wrapThatDoesNotOpen_refusesTheSave_andWritesNothing`() = runTest {
        val existing = createTestEntity("note-1").copy(
            wrappedContentKey = "wrap-from-another-era",
            wrappedContentKeyNonce = "wrap-nonce",
        )
        coEvery { itemDao.getById("note-1") } returns existing
        every { noteCrypto.unwrapWithVault("wrap-from-another-era", "wrap-nonce", any()) } returns null

        val thrown = runCatching { repository.updateNote("note-1", NoteContent(title = "Edited")) }.exceptionOrNull()

        assertTrue(thrown is NoteKeyUnavailableException)
        verify(exactly = 0) { noteCrypto.generateContentKey() }
        coVerify(exactly = 0) { noteApi.updateItem(any(), any()) }
        coVerify(exactly = 0) { contentKeyRotator.rotateWithKey(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            itemDao.writeEditedItemIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    @Test
    fun `updateNote_nonExistentNote_returnsNull`() = runTest {
        coEvery { itemDao.getById("missing") } returns null

        val result = repository.updateNote("missing", NoteContent(title = "Test"))

        assertNull(result)
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `updateNote_usesExistingCircleId`() = runTest {
        val existing = createTestEntity("note-1").copy(circleId = "circle-abc")
        coEvery { itemDao.getById("note-1") } returns existing

        repository.updateNote("note-1", NoteContent(title = "Updated"))

        verify { noteCrypto.encryptNoteWithContentKeyV2(any(), any(), any()) }
    }

    @Test
    fun `updateNote_nullCircleId_usesPersonal`() = runTest {
        val existing = createTestEntity("note-1").copy(circleId = null)
        coEvery { itemDao.getById("note-1") } returns existing

        repository.updateNote("note-1", NoteContent(title = "Updated"))

        verify { noteCrypto.encryptNoteWithContentKeyV2(any(), any(), any()) }
    }

    @Test
    fun `updateNote_adoptsServerVersion_whenPushAccepted`() = runTest {
        val existing = createTestEntity("note-1").copy(version = 5)
        coEvery { itemDao.getById("note-1") } returns existing
        coEvery { noteApi.updateItem(any(), any()) } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(
                success = true,
                data = itemDto("note-1", version = 6L),
            )
        )

        val result = repository.updateNote("note-1", NoteContent(title = "Updated"))

        assertEquals(6L, result?.version)
        assertFalse(result!!.isDirty)
    }

    @Test
    fun `updateNote_preservesArchiveState`() = runTest {
        val existing = createTestEntity("note-1")
        coEvery { itemDao.getById("note-1") } returns existing

        val content = NoteContent(title = "Updated", isArchived = true)
        val result = repository.updateNote("note-1", content)

        assertTrue(result!!.isArchived)
    }

    // =============================================================================
    // deleteNote TESTS
    // =============================================================================

    @Test
    fun `deleteNote_callsSoftDelete`() = runTest {
        coEvery { itemDao.softDelete(any(), any()) } just Runs
        coEvery { noteApi.deleteItem(any()) } returns retrofit2.Response.success(Unit)
        coEvery { itemDao.clearDirtyFlags(any()) } just Runs

        repository.deleteNote("note-1")

        coVerify { itemDao.softDelete("note-1", any()) }
    }

    @Test
    fun `deleteNote_notifiesWidgetUpdate`() = runTest {
        coEvery { itemDao.softDelete(any(), any()) } just Runs
        coEvery { noteApi.deleteItem(any()) } returns retrofit2.Response.success(Unit)
        coEvery { itemDao.clearDirtyFlags(any()) } just Runs

        repository.deleteNote("note-1")

        verify { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    @Test
    fun `deleteNote_serverAccepts_clearsDirty`() = runTest {
        // Eager delete succeeds → the queued tombstone is cleared immediately.
        coEvery { itemDao.softDelete(any(), any()) } just Runs
        coEvery { noteApi.deleteItem("note-1") } returns retrofit2.Response.success(Unit)
        coEvery { itemDao.clearDirtyFlags(any()) } just Runs

        repository.deleteNote("note-1")

        coVerify { noteApi.deleteItem("note-1") }
        coVerify { itemDao.clearDirtyFlags(listOf("note-1")) }
    }

    @Test
    fun `deleteNote_offline_leavesDirtyForRetry`() = runTest {
        // Offline: the eager DELETE throws; softDelete already flagged the
        // row is_dirty=1, so it must NOT be cleared — retryDeletedItems
        // will replay it on reconnect.
        coEvery { itemDao.softDelete(any(), any()) } just Runs
        coEvery { noteApi.deleteItem(any()) } throws java.io.IOException("offline")
        coEvery { itemDao.clearDirtyFlags(any()) } just Runs

        repository.deleteNote("note-1")

        coVerify { itemDao.softDelete("note-1", any()) }
        coVerify(exactly = 0) { itemDao.clearDirtyFlags(any()) }
    }

    @Test
    fun `retryDeletedItems_pushesPendingDeletes_andClearsDirty`() = runTest {
        val tombstone = ItemEntity(
            id = "gone-1",
            userId = "user-1",
            itemType = 1,
            encryptedContent = "enc",
            contentNonce = "nonce",
            version = 2,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            deleted = true,
            isDirty = true,
        )
        coEvery { itemDao.getDeletedDirtyItems() } returns listOf(tombstone)
        coEvery { noteApi.deleteItem("gone-1") } returns retrofit2.Response.success(Unit)
        coEvery { itemDao.clearDirtyFlags(any()) } just Runs

        val pushed = repository.retryDeletedItems()

        assertEquals(1, pushed)
        coVerify { noteApi.deleteItem("gone-1") }
        coVerify { itemDao.clearDirtyFlags(listOf("gone-1")) }
    }

    @Test
    fun `retryDirtyItems_forbidden_dropsUnpushableEdit`() = runTest {
        // 403 = the server will never accept this write (e.g. a view-only
        // share edited before the read-only gate resolved). The row must stop
        // retrying: clear dirty so the inbox can re-materialize the owner's
        // copy, and don't count it as pushed.
        val item = ItemEntity(
            id = "ro-1",
            userId = "owner",
            itemType = 1,
            encryptedContent = "ct",
            contentNonce = "n",
            decryptedContent = "{}",
            version = 1,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            deleted = false,
            isDirty = true,
        )
        coEvery { itemDao.getDirtyItems() } returns listOf(item)
        every { sharesInbox.sharedKeyFor(match { it.id == "ro-1" }) } returns ByteArray(32)
        coEvery { noteApi.updateItem("ro-1", any()) } returns
            retrofit2.Response.error(403, "forbidden".toResponseBody())
        coEvery { itemDao.clearDirtyFlags(any()) } just Runs

        val pushed = repository.retryDirtyItems()

        assertEquals(0, pushed)
        coVerify { itemDao.clearDirtyFlags(listOf("ro-1")) }
    }

    @Test
    fun `retryDirtyItems_ownedNoteSuccess_confirmsViaCasNotBlindUpsert`() = runTest {
        // A successful drain PUT must NOT blind full-row REPLACE the
        // pre-PUT snapshot (which reverts + drops a concurrent edit
        // made during the PUT window). It confirms via the
        // ciphertext-CAS, adopting the server version and clearing
        // dirty only if the row is still unchanged.
        val localEdit = ItemEntity(
            id = "note-9",
            userId = "u",
            itemType = 1,
            encryptedContent = "snap-ct",
            contentNonce = "snap-nonce",
            decryptedContent = """{"title":"Note","body":"edit"}""",
            title = "Note",
            version = 4,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            deleted = false,
            isDirty = true,
            wrappedContentKey = "wk",
            wrappedContentKeyNonce = "wn",
        )
        coEvery { itemDao.getDirtyItems() } returns listOf(localEdit)
        every { sharesInbox.sharedKeyFor(any()) } returns null
        coEvery { noteApi.updateItem("note-9", any()) } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.ItemDto(
                    id = "note-9", userId = "u", itemType = 1,
                    encryptedContent = "snap-ct", contentNonce = "snap-nonce",
                    version = 5L, createdAt = "2024-01-15T10:00:00.000Z",
                    updatedAt = "2024-02-15T10:00:00.000Z", deleted = false,
                    circleId = null, notebookId = null, contentSize = 20,
                    wrappedContentKey = "wk", wrappedContentKeyNonce = "wn",
                ),
            )
        )
        coEvery { itemDao.confirmDrainedItem(any(), any(), any(), any()) } returns true

        val pushed = repository.retryDirtyItems()

        assertEquals(1, pushed)
        // Confirmed via the CAS with the snapshot ciphertext/nonce and the
        // server's bumped version — never a blind upsert of the drained row.
        coVerify { itemDao.confirmDrainedItem("note-9", 5L, "snap-ct", "snap-nonce") }
        coVerify(exactly = 0) { itemDao.upsert(match { it.id == "note-9" }) }
    }

    @Test
    fun `retryDirtyItems_ownedNoteVersionConflict_keepsBothCopies`() = runTest {
        val localEdit = ItemEntity(
            id = "note-1",
            userId = "u",
            itemType = 1,
            encryptedContent = "local-ct",
            contentNonce = "local-nonce",
            decryptedContent = """{"title":"Groceries","body":"my offline edit"}""",
            title = "Groceries",
            version = 2,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            deleted = false,
            isDirty = true,
            wrappedContentKey = "wk",
            wrappedContentKeyNonce = "wn",
        )
        coEvery { itemDao.getDirtyItems() } returns listOf(localEdit)
        every { sharesInbox.sharedKeyFor(any()) } returns null
        // resolveConflictKeepBoth re-reads the LIVE local row at fork time so a
        // concurrent edit isn't lost; no concurrent edit here → return the same row.
        coEvery { itemDao.getById("note-1") } returns localEdit

        // PUT conflicts — the server moved past our base version.
        coEvery { noteApi.updateItem("note-1", any()) } returns
            retrofit2.Response.error(409, "conflict".toResponseBody())

        // The server's current copy = the other device's edit.
        coEvery { noteApi.getItem("note-1") } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.ItemDto(
                    id = "note-1", userId = "u", itemType = 1,
                    encryptedContent = "server-ct", contentNonce = "server-nonce",
                    version = 7L, createdAt = "2024-01-15T10:00:00.000Z",
                    updatedAt = "2024-02-15T10:00:00.000Z", deleted = false,
                    circleId = null, notebookId = null, contentSize = 20,
                    wrappedContentKey = "swk", wrappedContentKeyNonce = "swn",
                ),
            )
        )

        val upserts = mutableListOf<ItemEntity>()
        coEvery { itemDao.upsert(capture(upserts)) } just Runs

        val pushed = repository.retryDirtyItems()

        assertEquals(1, pushed)

        // Our offline edit is preserved as a forked "(conflict copy)" note:
        // a fresh id, dirty so the next create-push lands it.
        val copy = upserts.firstOrNull { it.id != "note-1" }
        assertNotNull("expected a forked conflict copy", copy)
        assertTrue(copy!!.title!!.contains("(conflict copy)"))
        assertTrue(copy.isDirty)
        assertEquals(1L, copy.version)

        // The original id is reset to the server's version, clean + undecrypted
        // (the backfill pass re-derives title/body this same sync) — via a
        // compare-and-swap on the row it forked from, never a blind upsert. Only
        // the conflict copy goes through upsert; it has a brand-new id.
        assertTrue("only the fork may be a blind upsert", upserts.all { it.id != "note-1" })
        coVerify {
            itemDao.resetToServerIfUnchanged(
                id = "note-1",
                ciphertext = "server-ct",
                nonce = "server-nonce",
                version = 7L,
                updatedAt = any(),
                deleted = false,
                contentSize = 20,
                wrappedKey = "swk",
                wrappedNonce = "swn",
                // Guarded on the row that was forked: an edit
                // landing during the getItem above went into
                // neither side, and a blind upsert would clear
                // its is_dirty and drop it from the outbox.
                expectedCiphertext = "local-ct",
                expectedNonce = "local-nonce",
            )
        }
    }

    // =============================================================================
    // NoteContent TESTS
    // =============================================================================

    @Test
    fun `noteContent_toJson_containsAllFields`() {
        val content = NoteContent(
            title = "Test Title",
            body = "Test body",
            noteType = "text",
            color = "#FF0000",
            isPinned = true,
            isArchived = false,
            labels = listOf("work", "important"),
        )

        val json = content.toJson()

        assertTrue(json.contains("\"title\":\"Test Title\""))
        assertTrue(json.contains("\"body\":\"Test body\""))
        assertTrue(json.contains("\"note_type\":\"text\""))
        assertTrue(json.contains("\"color\":\"#FF0000\""))
        assertTrue(json.contains("\"is_pinned\":true"))
        assertTrue(json.contains("\"is_archived\":false"))
        assertTrue(json.contains("\"work\""))
        assertTrue(json.contains("\"important\""))
    }

    @Test
    fun `noteContent_toJson_escapesSpecialCharacters`() {
        val content = NoteContent(
            title = "Title with \"quotes\"",
            body = "Body with\nnewlines\tand tabs\\backslash",
        )

        val json = content.toJson()

        assertTrue(json.contains("\\\"quotes\\\""))
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("\\t"))
        assertTrue(json.contains("\\\\"))
    }

    @Test
    fun `noteContent_toJson_withChecklistItems`() {
        val content = NoteContent(
            title = "Checklist",
            body = "",
            checklistItems = listOf(
                ChecklistItem("Item 1", false),
                ChecklistItem("Item 2", true),
            ),
        )

        val json = content.toJson()

        assertTrue(json.contains("\"checklist_items\""))
        assertTrue(json.contains("\"text\":\"Item 1\""))
        assertTrue(json.contains("\"checked\":false"))
        assertTrue(json.contains("\"text\":\"Item 2\""))
        assertTrue(json.contains("\"checked\":true"))
    }

    @Test
    fun `noteContent_toJson_nullColor_omitsColor`() {
        val content = NoteContent(title = "No Color", body = "")

        val json = content.toJson()

        assertFalse(json.contains("\"color\""))
    }

    @Test
    fun `noteContent_toJson_nullChecklistItems_omitsField`() {
        val content = NoteContent(title = "Text Note", body = "")

        val json = content.toJson()

        assertFalse(json.contains("\"checklist_items\""))
    }

    @Test
    fun `noteContent_toJson_emptyLabels_emptyArray`() {
        val content = NoteContent(title = "No Labels", body = "")

        val json = content.toJson()

        assertTrue(json.contains("\"labels\":[]"))
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    private fun createTestEntity(id: String) = ItemEntity(
        id = id,
        userId = "user-1",
        itemType = 1,
        encryptedContent = "encrypted",
        contentNonce = "nonce",
        decryptedContent = "{}",
        title = "Test",
        version = 1,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        isDirty = false,
    )

    private fun itemDto(id: String, version: Long) =
        com.pingolin.note.data.remote.dto.ItemDto(
            id = id,
            userId = "user-1",
            itemType = 1,
            encryptedContent = "encrypted-content",
            contentNonce = "test-nonce",
            version = version,
            createdAt = "2024-01-01T00:00:00.000Z",
            updatedAt = "2024-01-01T00:00:00.000Z",
            deleted = false,
            circleId = null,
            notebookId = null,
            contentSize = 10,
        )

    // =============================================================================
    // updateNote TESTS — CONTENT-KEY ROTATION RACE (forward secrecy).
    //
    // A rotation (share revoke / notebook unshare) can commit on the server
    // while an interactive save is on the network. updateNote's snapshot of
    // wrapped_content_key is then the RETIRED wrap, and the server's rotate
    // bumps version by EXACTLY one — so a write-back that guesses
    // `existing.version + 1` lands precisely on the post-rotation version, the
    // next drain PUT satisfies the server's CAS, and the owner-gated
    // COALESCE(wrapped_content_key) puts the retired wrap back on the row.
    // That republishes the note under the key the REVOKED recipient still
    // holds while the re-sealed survivors are locked out.
    // =============================================================================

    /** Args of every writeEditedItemIfUnchanged call, in declaration order. */
    private fun captureEditWrites(): MutableList<List<Any?>> {
        val writes = mutableListOf<List<Any?>>()
        coEvery {
            itemDao.writeEditedItemIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } answers {
            writes += invocation.args
            1
        }
        return writes
    }

    private val idxVersion = 5
    private val idxWrappedKey = 13
    private val idxExpectedWrappedKey = 18

    @Test
    fun `updateNote_pushRejected_keepsObservedVersion_notTheRotationBump`() = runTest {
        // The rotation won the race, so our PUT is refused with 409.
        val existing = createTestEntity("note-1").copy(
            version = 5,
            wrappedContentKey = "retired-wrap",
            wrappedContentKeyNonce = "retired-nonce",
        )
        coEvery { itemDao.getById("note-1") } returns existing
        coEvery { noteApi.updateItem(any(), any()) } returns
            retrofit2.Response.error(409, "conflict".toResponseBody())
        val writes = captureEditWrites()

        val result = repository.updateNote("note-1", NoteContent(title = "Edited"))

        // 6 is the post-rotation version. Writing it would make the next drain's
        // PUT match the server's optimistic-concurrency check and carry the
        // retired wrap back up through COALESCE. Keeping 5 makes that drain 409
        // and fork a conflict copy instead — the mechanism sync already relies on.
        assertEquals(1, writes.size)
        assertEquals(5L, writes[0][idxVersion])
        assertEquals(5L, result?.version)
        assertTrue("a refused push must leave the row dirty", result!!.isDirty)
    }

    @Test
    fun `updateNote_rowMovedOnDuringPush_neverBlindUpserts`() = runTest {
        val existing = createTestEntity("note-1").copy(
            version = 5,
            wrappedContentKey = "wrap-A",
            wrappedContentKeyNonce = "wrap-A-nonce",
        )
        coEvery { itemDao.getById("note-1") } returns existing
        coEvery { noteApi.updateItem(any(), any()) } returns
            retrofit2.Response.error(409, "conflict".toResponseBody())
        // The CAS refuses: a newer interactive edit landed during the PUT. The
        // wrap is unchanged, so this is NOT a rotation and must not be re-driven.
        coEvery {
            itemDao.writeEditedItemIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns 0

        repository.updateNote("note-1", NoteContent(title = "Edited"))

        // No fallback to a blind full-row upsert: it would drop the newer edit.
        coVerify(exactly = 0) { itemDao.upsert(any()) }
        // And exactly one attempt — an unchanged wrap is not a rotation.
        coVerify(exactly = 1) {
            itemDao.writeEditedItemIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    @Test
    fun `updateNote_rotationCommittedDuringPush_reSealsEditUnderNewWrap`() = runTest {
        val retiredKey = ByteArray(32) { 1 }
        val rotatedKey = ByteArray(32) { 2 }
        val preRotation = createTestEntity("note-1").copy(
            version = 5,
            encryptedContent = "pre-rotation-ct",
            wrappedContentKey = "retired-wrap",
            wrappedContentKeyNonce = "retired-nonce",
        )
        // What the row looks like after the rotation's own CAS write lands.
        val postRotation = preRotation.copy(
            version = 6,
            encryptedContent = "rotated-ct",
            contentNonce = "rotated-nonce",
            wrappedContentKey = "fresh-wrap",
            wrappedContentKeyNonce = "fresh-nonce",
        )
        coEvery { itemDao.getById("note-1") } returnsMany
            listOf(preRotation, postRotation, postRotation, postRotation)
        // Each wrap unwraps to its own content key, so the key the edit is
        // sealed under is observable.
        every { noteCrypto.unwrapWithVault(any(), any(), any()) } answers {
            if (firstArg<String>() == "fresh-wrap") rotatedKey else retiredKey
        }
        // First push loses to the rotation (409); the re-drive lands.
        coEvery { noteApi.updateItem(any(), any()) } returnsMany listOf(
            retrofit2.Response.error(409, "conflict".toResponseBody()),
            retrofit2.Response.success(
                com.pingolin.note.data.remote.dto.ApiResponse(
                    success = true,
                    data = itemDto("note-1", version = 7L),
                )
            ),
        )
        val writes = mutableListOf<List<Any?>>()
        coEvery {
            itemDao.writeEditedItemIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } answers {
            writes += invocation.args
            // The CAS only matches once the expected wrap is the one on the row.
            if (invocation.args[idxExpectedWrappedKey] == "fresh-wrap") 1 else 0
        }

        val result = repository.updateNote("note-1", NoteContent(title = "Edited"))

        // Two attempts: the first was refused because the row had rotated.
        assertEquals(2, writes.size)
        // The refused attempt was guarded on the retired wrap, so it could never
        // have written it — this is the guard, not an accident of ordering.
        assertEquals("retired-wrap", writes[0][idxExpectedWrappedKey])
        // The attempt that LANDED carries the fresh wrap, never the retired one.
        assertEquals("fresh-wrap", writes[1][idxWrappedKey])
        assertEquals("fresh-wrap", writes[1][idxExpectedWrappedKey])
        assertEquals(7L, writes[1][idxVersion])
        // The user's edit survived and is sealed under the ROTATED content key,
        // so the revoked recipient cannot read it.
        verify {
            noteCrypto.encryptNoteWithContentKeyV2(
                any(), match<ByteArray> { it.contentEquals(rotatedKey) }, any(),
            )
        }
        assertEquals("fresh-wrap", result?.wrappedContentKey)
        assertFalse(result!!.isDirty)
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    // =============================================================================
    // updateNote() shared-note branch TESTS.
    //
    // When sharesInbox.sharedKeyFor(row) returns a non-null byte array the
    // repo is updating a note that was shared WITH this account (not owned).
    // It must:
    //   1. Re-encrypt under the inbox content key.
    //   2. Send PUT /items/{id} WITHOUT wrapped_content_key fields
    //      (only the owner rotates the wrap).
    //   3. Leave the local row's wrapped_content_key untouched.
    // =============================================================================

    @Test
    fun `updateNote_sharedNote_skipsWrappedContentKey`() = runTest {
        val existing = createTestEntity("shared-1").copy(
            wrappedContentKey = "owners-wrap",
            wrappedContentKeyNonce = "owners-nonce",
        )
        coEvery { itemDao.getById("shared-1") } returns existing
        every { sharesInbox.sharedKeyFor(match { it.id == "shared-1" }) } returns testContentKey
        coEvery {
            noteApi.updateItem(any(), any())
        } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.ItemDto(
                    id = "shared-1",
                    userId = "u",
                    itemType = 1,
                    encryptedContent = "encrypted-content",
                    contentNonce = "test-nonce",
                    version = 5L,
                    createdAt = "2024-01-01T00:00:00.000Z",
                    updatedAt = "2024-01-01T00:00:00.000Z",
                    deleted = false,
                    circleId = null,
                    notebookId = null,
                    contentSize = 10,
                ),
            )
        )

        val updateCaptor = slot<com.pingolin.note.data.remote.dto.UpdateItemRequest>()
        coEvery { noteApi.updateItem("shared-1", capture(updateCaptor)) } returns
            retrofit2.Response.success(
                com.pingolin.note.data.remote.dto.ApiResponse(
                    success = true,
                    data = com.pingolin.note.data.remote.dto.ItemDto(
                        id = "shared-1",
                        userId = "u",
                        itemType = 1,
                        encryptedContent = "encrypted-content",
                        contentNonce = "test-nonce",
                        version = 5L,
                        createdAt = "2024-01-01T00:00:00.000Z",
                        updatedAt = "2024-01-01T00:00:00.000Z",
                        deleted = false,
                        circleId = null,
                        notebookId = null,
                        contentSize = 10,
                    ),
                )
            )

        val result = repository.updateNote("shared-1", NoteContent(title = "Edited shared", body = "new"))

        assertNotNull(result)
        assertNull("wrappedContentKey must be null in PUT request (owner rotates it)", updateCaptor.captured.wrappedContentKey)
        assertNull("wrappedContentKeyNonce must be null in PUT request", updateCaptor.captured.wrappedContentKeyNonce)
        // Local row KEEPS the owner's wrap — the recipient write-back has no wrap
        // columns at all, so there is no code path that could overwrite it.
        assertEquals("owners-wrap", result!!.wrappedContentKey)
        assertEquals("owners-nonce", result.wrappedContentKeyNonce)
        coVerify {
            itemDao.writeSharedEditIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `updateNote_sharedNote_usesInboxContentKeyForEncryption`() = runTest {
        val existing = createTestEntity("shared-1")
        coEvery { itemDao.getById("shared-1") } returns existing
        every { sharesInbox.sharedKeyFor(match { it.id == "shared-1" }) } returns testContentKey
        coEvery { noteApi.updateItem(any(), any()) } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.ItemDto(
                    id = "shared-1",
                    userId = "u",
                    itemType = 1,
                    encryptedContent = "encrypted-content",
                    contentNonce = "test-nonce",
                    version = 2L,
                    createdAt = "2024-01-01T00:00:00.000Z",
                    updatedAt = "2024-01-01T00:00:00.000Z",
                    deleted = false,
                    circleId = null,
                    notebookId = null,
                    contentSize = 10,
                ),
            )
        )

        repository.updateNote("shared-1", NoteContent(title = "Edited", body = "new"))

        // encryptNoteWithContentKey should be called with the inbox's content key.
        verify {
            noteCrypto.encryptNoteWithContentKeyV2(any(), match<ByteArray> { it.contentEquals(testContentKey) }, any())
        }
        // The shared-note branch must NOT touch the vault (no
        // wrapWithVault / unwrapWithVault on the rewrap path).
        verify(exactly = 0) { noteCrypto.wrapWithVault(any(), any()) }
        verify(exactly = 0) { noteCrypto.unwrapWithVault(any(), any(), any()) }
    }

    @Test
    fun `updateNote_sharedNote_putFails_leavesDirtyTrue`() = runTest {
        val existing = createTestEntity("shared-1")
        coEvery { itemDao.getById("shared-1") } returns existing
        every { sharesInbox.sharedKeyFor(match { it.id == "shared-1" }) } returns testContentKey
        coEvery { noteApi.updateItem(any(), any()) } throws RuntimeException("network")

        val result = repository.updateNote("shared-1", NoteContent(title = "Edited"))

        assertNotNull(result)
        assertTrue("isDirty must stay true on transient PUT failure", result!!.isDirty)
    }

    // =============================================================================
    // NOTEBOOKS DRAIN TOO — /sync/push is create-only, so a renamed notebook is
    // rejected there and this replay is its ONLY route to the server.
    // =============================================================================

    private fun dirtyNotebook(id: String, version: Long = 1L) = ItemEntity(
        id = id,
        userId = "u",
        itemType = 2,
        encryptedContent = "nb-ct",
        contentNonce = "nb-nonce",
        decryptedContent = """{"title":"Renamed"}""",
        title = "Renamed",
        version = version,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        deleted = false,
        isDirty = true,
        wrappedContentKey = "wk",
        wrappedContentKeyNonce = "wn",
    )

    private fun itemDto(id: String, version: Long, itemType: Int = 2) =
        com.pingolin.note.data.remote.dto.ItemDto(
            id = id, userId = "u", itemType = itemType,
            encryptedContent = "nb-ct", contentNonce = "nb-nonce",
            version = version, createdAt = "2024-01-15T10:00:00.000Z",
            updatedAt = "2024-02-15T10:00:00.000Z", deleted = false,
            circleId = null, notebookId = null, contentSize = 5,
            wrappedContentKey = "wk", wrappedContentKeyNonce = "wn",
        )

    // =============================================================================
    // A note is sealed with the key that opened it: a note this account owns
    // never goes under a share's key, even while the inbox holds one for its id
    // (a share admitted before the account's own row was pulled). Every sealing
    // use asks sharesInbox.sharedKeyFor(row), never getContentKey(id).
    // =============================================================================

    /** The key the inbox holds for the id: not the fixtures' content key, so a seal under it shows. */
    private val inboxKey = ByteArray(32) { 9 }

    @Test
    fun `updateNote_ourNoteTheInboxAlsoHoldsAKeyFor_sealsItAsOurs`() = runTest {
        val existing = createTestEntity("own-1")
        coEvery { itemDao.getById("own-1") } returns existing
        every { sharesInbox.getContentKey("own-1") } returns inboxKey

        repository.updateNote("own-1", NoteContent(title = "Mine", body = "private"))

        verify(exactly = 0) {
            noteCrypto.encryptNoteWithContentKeyV2(any(), match<ByteArray> { it.contentEquals(inboxKey) }, any())
        }
        coVerify(exactly = 0) {
            itemDao.writeSharedEditIfUnchanged(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    @Test
    fun `ensureContentKey_ourNoteTheInboxAlsoHoldsAKeyFor_returnsOurKey`() = runTest {
        val ours = ByteArray(32) { 4 }
        coEvery { itemDao.getById("own-1") } returns createTestEntity("own-1").copy(
            wrappedContentKey = "wk", wrappedContentKeyNonce = "wn",
        )
        every { sharesInbox.getContentKey("own-1") } returns inboxKey
        every { noteCrypto.unwrapWithVault("wk", "wn", any()) } returns ours

        assertTrue(repository.ensureContentKey("own-1").contentEquals(ours))

        every { sharesInbox.sharedKeyFor(match { it.id == "own-1" }) } returns inboxKey
        assertTrue("another owner's row takes the inbox's key", repository.ensureContentKey("own-1").contentEquals(inboxKey))
    }

    @Test
    fun `retryDirtyItems_ourNoteTheInboxAlsoHoldsAKeyFor_sendsItsWrap`() = runTest {
        val dirty = createTestEntity("own-1").copy(
            isDirty = true, wrappedContentKey = "wk", wrappedContentKeyNonce = "wn",
        )
        coEvery { itemDao.getDirtyItems() } returns listOf(dirty)
        every { sharesInbox.getContentKey("own-1") } returns inboxKey
        val put = slot<com.pingolin.note.data.remote.dto.UpdateItemRequest>()
        coEvery { noteApi.updateItem("own-1", capture(put)) } returns
            retrofit2.Response.error(500, "down".toResponseBody())

        repository.retryDirtyItems()

        assertEquals("wk", put.captured.wrappedContentKey)
        assertEquals("wn", put.captured.wrappedContentKeyNonce)
    }

    /**
     * Notebooks drain too: filtered to item_type 1, a renamed notebook would
     * be rejected by the create-only push, skipped here, and stay dirty
     * forever — the server's row keeping its original title and, worse, its
     * original wrap.
     */
    @Test
    fun `retryDirtyItems_renamedNotebook_isPushed`() = runTest {
        coEvery { itemDao.getDirtyItems() } returns listOf(dirtyNotebook("nb-1"))
        every { sharesInbox.sharedKeyFor(any()) } returns null
        coEvery { noteApi.updateItem("nb-1", any()) } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(success = true, data = itemDto("nb-1", 2L)),
        )
        coEvery { itemDao.confirmDrainedItem(any(), any(), any(), any()) } returns true

        val pushed = repository.retryDirtyItems()

        assertEquals(1, pushed)
        coVerify { noteApi.updateItem("nb-1", any()) }
    }

    /** The notebook's own wrap rides along, which is how a legacy vault-direct
     *  notebook upgraded by a rename actually becomes shareable. */
    @Test
    fun `retryDirtyItems_notebook_sendsItsWrap`() = runTest {
        coEvery { itemDao.getDirtyItems() } returns listOf(dirtyNotebook("nb-1"))
        every { sharesInbox.sharedKeyFor(any()) } returns null
        coEvery { noteApi.updateItem("nb-1", any()) } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(success = true, data = itemDto("nb-1", 2L)),
        )
        coEvery { itemDao.confirmDrainedItem(any(), any(), any(), any()) } returns true

        repository.retryDirtyItems()

        coVerify {
            noteApi.updateItem("nb-1", match { it.wrappedContentKey == "wk" && it.wrappedContentKeyNonce == "wn" })
        }
    }

    /**
     * resolveConflictKeepBoth forks into a copy hard-coded to `itemType = 1`,
     * so routing a notebook through it would answer a rename race with a stray
     * NOTE called "… (conflict copy)".
     */
    @Test
    fun `retryDirtyItems_notebookVersionConflict_adoptsVersionInsteadOfForking`() = runTest {
        coEvery { itemDao.getDirtyItems() } returns listOf(dirtyNotebook("nb-1", version = 1L))
        every { sharesInbox.sharedKeyFor(any()) } returns null
        coEvery { noteApi.updateItem("nb-1", any()) } returns
            retrofit2.Response.error(409, "conflict".toResponseBody())
        coEvery { noteApi.getItem("nb-1") } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(success = true, data = itemDto("nb-1", 7L)),
        )
        coEvery { itemDao.adoptServerVersionKeepingDirty(any(), any()) } returns 1

        repository.retryDirtyItems()

        // Adopted the server version, still dirty, and NO conflict-copy created.
        coVerify { itemDao.adoptServerVersionKeepingDirty("nb-1", 7L) }
        coVerify(exactly = 0) { itemDao.upsert(match { it.id != "nb-1" }) }
    }

    /**
     * The server holds a different wrap than the one this device minted for a
     * legacy notebook, and refuses to have it replaced on a plain PUT. Adopting
     * the version alone would re-send the same wrap and be refused forever; the
     * pending change is re-sealed under the SERVER's key instead, and the
     * server's wrap adopted, so the next drain sends an identical wrap.
     */
    @Test
    fun `retryDirtyItems_notebookWithAnotherClientsWrap_resealsUnderTheServerKey`() = runTest {
        coEvery { itemDao.getDirtyItems() } returns listOf(dirtyNotebook("nb-1", version = 1L).copy(
            wrappedContentKey = "local-wk", wrappedContentKeyNonce = "local-wn",
        ))
        every { sharesInbox.sharedKeyFor(any()) } returns null
        coEvery { noteApi.updateItem("nb-1", any()) } returns
            retrofit2.Response.error(409, "refused".toResponseBody())
        coEvery { noteApi.getItem("nb-1") } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(success = true, data = itemDto("nb-1", 7L, itemType = 2)),
        )
        val serverKey = ByteArray(32) { 5 }
        every { noteCrypto.unwrapWithVault("wk", "wn", any()) } returns serverKey
        every { noteCrypto.encryptNoteWithContentKeyV2("""{"title":"Renamed"}""", serverKey, "nb-1") } returns
            com.pingolin.note.collab.NotePayload("resealed-nonce", "resealed-ct")
        coEvery {
            itemDao.writeMintedContentKeyIfUnchanged(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns 1

        repository.retryDirtyItems()

        coVerify {
            itemDao.writeMintedContentKeyIfUnchanged(
                id = "nb-1", ciphertext = "resealed-ct", nonce = "resealed-nonce", version = 7L,
                updatedAt = any(), isDirty = true, contentSize = any(),
                wrappedKey = "wk", wrappedNonce = "wn",
                expectedCiphertext = "nb-ct", expectedNonce = "nb-nonce", expectedWrappedKey = "local-wk",
            )
        }
        coVerify(exactly = 0) { itemDao.adoptServerVersionKeepingDirty(any(), any()) }
    }

    @Test
    fun `retryDirtyItems_notebookWhoseServerKeyDoesNotOpen_dropsTheLocalChange`() = runTest {
        coEvery { itemDao.getDirtyItems() } returns listOf(dirtyNotebook("nb-1").copy(
            wrappedContentKey = "local-wk", wrappedContentKeyNonce = "local-wn",
        ))
        every { sharesInbox.sharedKeyFor(any()) } returns null
        coEvery { noteApi.updateItem("nb-1", any()) } returns
            retrofit2.Response.error(409, "refused".toResponseBody())
        coEvery { noteApi.getItem("nb-1") } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(success = true, data = itemDto("nb-1", 7L, itemType = 2)),
        )
        every { noteCrypto.unwrapWithVault("wk", "wn", any()) } returns null
        coEvery { itemDao.clearDirtyFlags(any()) } just Runs

        repository.retryDirtyItems()

        coVerify { itemDao.clearDirtyFlags(listOf("nb-1")) }
        coVerify(exactly = 0) {
            itemDao.writeMintedContentKeyIfUnchanged(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    /** The metadata blob has its own writer (NotebookShareWriter) with its own
     *  version handling; a second writer here would race it. */
    @Test
    fun `retryDirtyItems_skipsTheMetadataBlob`() = runTest {
        coEvery { itemDao.getDirtyItems() } returns listOf(
            dirtyNotebook("meta-1").copy(itemType = 10),
        )
        every { sharesInbox.sharedKeyFor(any()) } returns null

        val pushed = repository.retryDirtyItems()

        assertEquals(0, pushed)
        coVerify(exactly = 0) { noteApi.updateItem("meta-1", any()) }
    }

    // --- the member fan-out: an owner only through an accepted share ------------------

    private val sharedNotebook = "4a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private fun inboxLists(notebook: String) {
        every { sharesInbox.sharedNotebooksFlow } returns kotlinx.coroutines.flow.MutableStateFlow(
            listOf(SharesInboxRepository.SharedNotebook("s1", notebook, "Trips", "owner@x.y", "contribute")),
        )
        coEvery { notebookShareFanOut.rosterRecipientsFor(any(), any(), any(), any()) } returns emptyList()
        every { sessionManager.getEmail() } returns "me@x.y"
    }

    @Test
    fun `createNote_inANotebookTheInboxListsButDidNotOpen_asksNoRoster`() = runTest {
        inboxLists(sharedNotebook)
        every { sharesInbox.getContentKey(sharedNotebook) } returns null
        repository.createNote(NoteContent(title = "t", notebookId = sharedNotebook))
        coVerify(exactly = 0) { notebookShareFanOut.rosterRecipientsFor(any(), any(), any(), any()) }
    }

    @Test
    fun `createNote_inANotebookOpenedThroughTheShare_asksTheRosterWithItsOwner`() = runTest {
        inboxLists(sharedNotebook)
        every { sharesInbox.getContentKey(sharedNotebook) } returns ByteArray(32) { 3 }
        repository.createNote(NoteContent(title = "t", notebookId = sharedNotebook.uppercase()))
        coVerify(exactly = 1) {
            notebookShareFanOut.rosterRecipientsFor(sharedNotebook.uppercase(), any(), "owner@x.y", any())
        }
    }
}
