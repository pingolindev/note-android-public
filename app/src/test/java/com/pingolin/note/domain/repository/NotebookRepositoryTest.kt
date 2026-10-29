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
import com.pingolin.note.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.collab.NotePayload
import com.pingolin.note.collab.WrappedKeyPair
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NotebookRepositoryTest {

    // Account ids are UUIDs, compared canonically.
    private val ME = "00000000-0000-4000-8000-0000000000a1"
    private val OTHER = "00000000-0000-4000-8000-0000000000b2"

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var itemDao: ItemDao

    @MockK
    private lateinit var noteCrypto: NoteCrypto

    @MockK
    private lateinit var appContext: Application

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK(relaxed = true)
    private lateinit var notebookShareWriter: NotebookShareWriter

    private lateinit var repository: NotebookRepository

    private val testEncryptedData = NotePayload(
        nonceB64 = "notebook-nonce",
        ciphertextB64 = "encrypted-notebook",
    )

    /** 32 bytes, because vaultKeyBytes() rejects anything else. */
    private val vaultKeyB64 =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val contentKey = ByteArray(32) { 9 }

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // android.util.Base64 is a stub in unit tests — replace with JDK Base64,
        // same as NoteRepositoryTest. Without this vaultKeyBytes() decodes to
        // an empty array and every notebook write throws "Can't open your notes on this device".
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }

        every { sessionManager.getMasterKey() } returns vaultKeyB64
        every { noteCrypto.generateContentKey() } returns contentKey
        every { noteCrypto.encryptNoteWithContentKeyV2(any(), any(), any()) } returns testEncryptedData
        every { noteCrypto.wrapWithVault(any(), any()) } returns WrappedKeyPair("wrapped-key", "wrap-nonce")
        every { noteCrypto.unwrapWithVault(any(), any(), any()) } returns contentKey
        coEvery { itemDao.upsert(any()) } just Runs

        mockkObject(com.pingolin.note.widget.WidgetUpdateHelper)
        every { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(any()) } just Runs

        mockkObject(com.pingolin.note.worker.SyncWorker.Companion)
        every { com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(any()) } just Runs

        repository = NotebookRepository(itemDao, appContext, sessionManager, notebookShareWriter, noteCrypto)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    // =============================================================================
    // OBSERVE TESTS
    // =============================================================================

    @Test
    fun `observeNotebooks_delegatesToDao`() {
        every { itemDao.observeNotebooks() } returns flowOf(emptyList())

        repository.observeNotebooks()

        verify { itemDao.observeNotebooks() }
    }

    @Test
    fun `observeSharedNotebooks_delegatesToDao`() {
        every { itemDao.observeSharedNotebooks() } returns flowOf(emptyList())

        repository.observeSharedNotebooks()

        verify { itemDao.observeSharedNotebooks() }
    }

    @Test
    fun `observeNotebookItems_delegatesToDao`() {
        every { itemDao.observeNotebookItems("nb-1") } returns flowOf(emptyList())

        repository.observeNotebookItems("nb-1")

        verify { itemDao.observeNotebookItems("nb-1") }
    }

    // =============================================================================
    // createNotebook TESTS
    // =============================================================================

    @Test
    fun `createNotebook_createsWithCorrectType`() = runTest {
        val result = repository.createNotebook("My Notebook")

        assertEquals(2, result.itemType) // notebook type
        assertEquals("My Notebook", result.title)
        assertEquals("encrypted-notebook", result.encryptedContent)
        assertEquals("notebook-nonce", result.contentNonce)
        assertTrue(result.isDirty)
        assertEquals(1L, result.version)

        coVerify { itemDao.upsert(any()) }
    }

    @Test
    fun `createNotebook_withColor_setsColor`() = runTest {
        val result = repository.createNotebook("Colored", color = "#8B9FFA")

        assertEquals("#8B9FFA", result.color)
    }

    @Test
    fun `createNotebook_withoutColor_nullColor`() = runTest {
        val result = repository.createNotebook("No Color")

        assertNull(result.color)
    }

    @Test
    fun `createNotebook_encryptsWithPersonalKey`() = runTest {
        repository.createNotebook("Test")

        verify { noteCrypto.encryptNoteWithContentKeyV2(any(), any(), any()) }
    }

    @Test
    fun `createNotebook_notifiesWidgets`() = runTest {
        repository.createNotebook("Test")

        verify { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    @Test
    fun `createNotebook_generatesUniqueId`() = runTest {
        val nb1 = repository.createNotebook("Notebook 1")
        val nb2 = repository.createNotebook("Notebook 2")

        assertNotEquals(nb1.id, nb2.id)
    }

    // =============================================================================
    // addNoteToNotebook TESTS
    // =============================================================================

    @Test
    fun `addNoteToNotebook_setsNotebookIdAndCircleId`() = runTest {
        val note = createTestEntity("note-1", itemType = 1)
        val notebook = createTestEntity("nb-1", itemType = 2).copy(circleId = "circle-1")

        coEvery { itemDao.getById("note-1") } returns note
        coEvery { itemDao.getById("nb-1") } returns notebook

        repository.addNoteToNotebook("note-1", "nb-1")

        coVerify {
            itemDao.upsert(match {
                it.id == "note-1" &&
                    it.notebookId == "nb-1" &&
                    it.circleId == "circle-1" &&
                    it.isDirty
            })
        }
    }

    @Test
    fun `addNoteToNotebook_noteNotFound_doesNothing`() = runTest {
        coEvery { itemDao.getById("missing") } returns null
        coEvery { itemDao.getById("nb-1") } returns createTestEntity("nb-1", itemType = 2)

        repository.addNoteToNotebook("missing", "nb-1")

        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `addNoteToNotebook_notebookNotFound_doesNothing`() = runTest {
        coEvery { itemDao.getById("note-1") } returns createTestEntity("note-1", itemType = 1)
        coEvery { itemDao.getById("missing") } returns null

        repository.addNoteToNotebook("note-1", "missing")

        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `addNoteToNotebook_inheritsNullCircleFromNotebook`() = runTest {
        val note = createTestEntity("note-1", itemType = 1)
        val notebook = createTestEntity("nb-1", itemType = 2).copy(circleId = null)

        coEvery { itemDao.getById("note-1") } returns note
        coEvery { itemDao.getById("nb-1") } returns notebook

        repository.addNoteToNotebook("note-1", "nb-1")

        coVerify {
            itemDao.upsert(match {
                it.circleId == null && it.notebookId == "nb-1"
            })
        }
    }

    // =============================================================================
    // removeNoteFromNotebook TESTS
    // =============================================================================

    @Test
    fun `removeNoteFromNotebook_clearsNotebookAndCircle`() = runTest {
        val note = createTestEntity("note-1", itemType = 1).copy(
            notebookId = "nb-1",
            circleId = "circle-1",
        )
        coEvery { itemDao.getById("note-1") } returns note

        repository.removeNoteFromNotebook("note-1")

        coVerify {
            itemDao.upsert(match {
                it.id == "note-1" &&
                    it.notebookId == null &&
                    it.circleId == null &&
                    it.isDirty
            })
        }
    }

    @Test
    fun `removeNoteFromNotebook_noteNotFound_doesNothing`() = runTest {
        coEvery { itemDao.getById("missing") } returns null

        repository.removeNoteFromNotebook("missing")

        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `removeNoteFromNotebook_notifiesWidgets`() = runTest {
        val note = createTestEntity("note-1", itemType = 1).copy(notebookId = "nb-1")
        coEvery { itemDao.getById("note-1") } returns note

        repository.removeNoteFromNotebook("note-1")

        verify { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    private fun createTestEntity(id: String, itemType: Int = 1) = ItemEntity(
        id = id,
        userId = ME,
        itemType = itemType,
        encryptedContent = "encrypted",
        contentNonce = "nonce",
        decryptedContent = "{}",
        title = "Test",
        version = 1,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        isDirty = false,
    )

    // =============================================================================
    // createNotebook encrypts + rename respects vault wrap +
    // delete cascades children out of the notebook.
    // =============================================================================

    @Test
    fun `createNotebook_noMasterKey_throws`() = runTest {
        every { sessionManager.getMasterKey() } returns null

        try {
            repository.createNotebook("Unauthenticated")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Please sign in again"))
        }
    }

    @Test
    fun `createNotebook_wrapsTheContentKeyWithTheVaultKey`() = runTest {
        repository.createNotebook("My Notebook")

        // The vault key does not encrypt the notebook; it WRAPS the
        // notebook's own content key. Confirm the decoded vault bytes
        // are what the wrap is made against.
        verify {
            noteCrypto.wrapWithVault(
                contentKey,
                match { it.size == 32 && it[0] == 0.toByte() && it[31] == 31.toByte() },
            )
        }
    }

    @Test
    fun `createNotebook_storesCiphertextAndNonceFromCryptoManager`() = runTest {
        val r = repository.createNotebook("Work")
        assertEquals("encrypted-notebook", r.encryptedContent)
        assertEquals("notebook-nonce", r.contentNonce)
        assertEquals("encrypted-notebook".length, r.contentSize)
    }

    @Test
    fun `renameNotebook_existingId_writesEncryptedPayload`() = runTest {
        val existing = createTestEntity("nb-1", itemType = 2)
        coEvery { itemDao.getById("nb-1") } returns existing

        repository.renameNotebook("nb-1", "New Title")

        coVerify {
            itemDao.upsert(match<ItemEntity> {
                it.id == "nb-1" &&
                    it.title == "New Title" &&
                    it.encryptedContent == "encrypted-notebook" &&
                    it.contentNonce == "notebook-nonce" &&
                    it.isDirty
            })
        }
    }

    @Test
    fun `renameNotebook_missingNotebook_isNoop`() = runTest {
        coEvery { itemDao.getById("missing") } returns null

        repository.renameNotebook("missing", "Nope")

        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `renameNotebook_noVaultKey_throws`() = runTest {
        coEvery { itemDao.getById("nb-1") } returns createTestEntity("nb-1", itemType = 2)
        every { sessionManager.getMasterKey() } returns null

        try {
            repository.renameNotebook("nb-1", "x")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Please sign in again"))
        }
    }

    @Test
    fun `deleteNotebook_cascadesChildrenOutOfNotebook`() = runTest {
        val notebook = createTestEntity("nb-1", itemType = 2)
        val child1 = createTestEntity("n-1", itemType = 1).copy(notebookId = "nb-1", circleId = "c")
        val child2 = createTestEntity("n-2", itemType = 1).copy(notebookId = "nb-1", circleId = "c")
        every { itemDao.observeNotebookItems("nb-1") } returns flowOf(listOf(child1, child2))
        coEvery { itemDao.softDelete("nb-1", any()) } just Runs

        repository.deleteNotebook("nb-1")

        // Children should be upserted with notebookId = null + circleId = null + dirty.
        coVerify {
            itemDao.upsert(match<ItemEntity> {
                it.id == "n-1" && it.notebookId == null && it.circleId == null && it.isDirty
            })
        }
        coVerify {
            itemDao.upsert(match<ItemEntity> {
                it.id == "n-2" && it.notebookId == null && it.circleId == null && it.isDirty
            })
        }
        // The notebook itself is soft-deleted.
        coVerify { itemDao.softDelete("nb-1", any()) }
    }

    @Test
    fun `deleteNotebook_emptyNotebook_onlySoftDeletes`() = runTest {
        every { itemDao.observeNotebookItems("nb-empty") } returns flowOf(emptyList())
        coEvery { itemDao.softDelete("nb-empty", any()) } just Runs

        repository.deleteNotebook("nb-empty")

        coVerify(exactly = 0) { itemDao.upsert(any()) }
        coVerify { itemDao.softDelete("nb-empty", any()) }
    }

    @Test
    fun `deleteNotebook_notifiesWidgets`() = runTest {
        every { itemDao.observeNotebookItems(any()) } returns flowOf(emptyList())
        coEvery { itemDao.softDelete(any(), any()) } just Runs

        repository.deleteNotebook("nb-1")

        verify { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
    }

    // =============================================================================
    // CONTENT-KEY FORMAT — a notebook must be shareable, which means its content
    // is under its OWN key, vault-wrapped, never under the vault key itself.
    // =============================================================================

    /**
     * Encrypting vault-direct leaves `wrapped_content_key` NULL, and a
     * notebook with no wrap has no key to seal to a recipient: the web editor
     * finds no content key for such a notebook, and sharing silently does
     * nothing.
     */
    @Test
    fun `createNotebook_storesAVaultWrappedContentKey`() = runTest {
        val r = repository.createNotebook("Recipes")

        assertEquals("wrapped-key", r.wrappedContentKey)
        assertEquals("wrap-nonce", r.wrappedContentKeyNonce)
    }

    /** AAD v2 binds the ciphertext to the notebook's own id — the same binding
     *  web writes. An unbound one is readable only by a v1 fallback. */
    @Test
    fun `createNotebook_bindsCiphertextToItsOwnId`() = runTest {
        val r = repository.createNotebook("Recipes")

        verify { noteCrypto.encryptNoteWithContentKeyV2(any(), contentKey, r.id) }
    }

    /**
     * Rotating a notebook's key on rename would lock out every member: they
     * hold the current key, and renaming is not a revocation.
     */
    @Test
    fun `renameNotebook_reusesTheExistingContentKey`() = runTest {
        coEvery { itemDao.getById("nb-1") } returns createTestEntity("nb-1", itemType = 2)
            .copy(wrappedContentKey = "wrapped-key", wrappedContentKeyNonce = "wrap-nonce")

        repository.renameNotebook("nb-1", "Dinners")

        verify(exactly = 0) { noteCrypto.generateContentKey() }
        verify { noteCrypto.encryptNoteWithContentKeyV2(any(), contentKey, "nb-1") }
    }

    /** An unchanged key needs no new wrap, and the stored one must survive. */
    @Test
    fun `renameNotebook_keepsTheStoredWrapWhenTheKeyIsUnchanged`() = runTest {
        coEvery { itemDao.getById("nb-1") } returns createTestEntity("nb-1", itemType = 2)
            .copy(wrappedContentKey = "wrapped-key", wrappedContentKeyNonce = "wrap-nonce")

        repository.renameNotebook("nb-1", "Dinners")

        verify(exactly = 0) { noteCrypto.wrapWithVault(any(), any()) }
        coVerify {
            itemDao.upsert(match<ItemEntity> {
                it.wrappedContentKey == "wrapped-key" && it.wrappedContentKeyNonce == "wrap-nonce"
            })
        }
    }

    /**
     * A notebook written by an older version has no wrap and no members,
     * so minting locks nobody out — and it is how those notebooks become
     * shareable without the user recreating them.
     */
    @Test
    fun `renameNotebook_upgradesALegacyVaultDirectNotebook`() = runTest {
        coEvery { itemDao.getById("nb-1") } returns createTestEntity("nb-1", itemType = 2)

        repository.renameNotebook("nb-1", "Dinners")

        verify(exactly = 1) { noteCrypto.generateContentKey() }
        coVerify {
            itemDao.upsert(match<ItemEntity> {
                it.wrappedContentKey == "wrapped-key" && it.wrappedContentKeyNonce == "wrap-nonce"
            })
        }
    }

    /**
     * A wrap that will not open means this device cannot read the notebook --
     * a recovered identity, or a key from another era. Minting a fresh key here
     * would overwrite the only pointer back to the real one and take the roster
     * with it, so the rename is refused instead of "repaired".
     */
    @Test
    fun `renameNotebook_refusesWhenTheStoredKeyDoesNotOpen`() = runTest {
        coEvery { itemDao.getById("nb-1") } returns createTestEntity("nb-1", itemType = 2)
            .copy(wrappedContentKey = "wrapped-key", wrappedContentKeyNonce = "wrap-nonce")
        every { noteCrypto.unwrapWithVault(any(), any(), any()) } returns null

        repository.renameNotebook("nb-1", "Dinners")

        verify(exactly = 0) { noteCrypto.generateContentKey() }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    // =============================================================================
    // LEGACY UPGRADE SWEEP — a notebook with no wrap cannot be shared; the sweep
    // gives it one without the user having to rename it.
    // =============================================================================

    private fun legacyNotebook(id: String, userId: String = ME) = ItemEntity(
        id = id,
        userId = userId,
        itemType = 2,
        encryptedContent = "vault-direct-ct",
        contentNonce = "vault-direct-nonce",
        decryptedContent = """{"title":"Old","edit_mode":"markdown"}""",
        title = "Old",
        version = 3,
        createdAt = Instant.now(),
        updatedAt = Instant.parse("2020-01-01T00:00:00Z"),
        isDirty = false,
        wrappedContentKey = null,
        wrappedContentKeyNonce = null,
    )

    @Test
    fun `upgradeLegacyNotebooks_givesAWrappedContentKeyAndMarksDirty`() = runTest {
        coEvery { itemDao.getNotebooksWithoutWrap() } returns listOf(legacyNotebook("nb-1"))

        assertEquals(1, repository.upgradeLegacyNotebooks(ME))

        coVerify {
            itemDao.upsert(match<ItemEntity> {
                it.id == "nb-1" &&
                    it.wrappedContentKey == "wrapped-key" &&
                    it.wrappedContentKeyNonce == "wrap-nonce" &&
                    it.encryptedContent == "encrypted-notebook" &&
                    it.isDirty
            })
        }
    }

    /** The upgrade re-encrypts the SAME document — it is a format change, not
     *  an edit, so nothing in the content may be rewritten. */
    @Test
    fun `upgradeLegacyNotebooks_reEncryptsTheExistingContentUnchanged`() = runTest {
        coEvery { itemDao.getNotebooksWithoutWrap() } returns listOf(legacyNotebook("nb-1"))

        repository.upgradeLegacyNotebooks(ME)

        verify {
            noteCrypto.encryptNoteWithContentKeyV2(
                """{"title":"Old","edit_mode":"markdown"}""", contentKey, "nb-1",
            )
        }
    }

    /**
     * updated_at is the pull cursor (getMaxUpdatedAt). Bumping it for an
     * internal format change would move the cursor past server changes this
     * device has never seen.
     */
    @Test
    fun `upgradeLegacyNotebooks_doesNotTouchUpdatedAt`() = runTest {
        val legacy = legacyNotebook("nb-1")
        coEvery { itemDao.getNotebooksWithoutWrap() } returns listOf(legacy)

        repository.upgradeLegacyNotebooks(ME)

        coVerify { itemDao.upsert(match<ItemEntity> { it.updatedAt == legacy.updatedAt }) }
    }

    /**
     * Re-encrypting someone else's notebook under OUR vault key would put it
     * beyond its owner.
     */
    @Test
    fun `upgradeLegacyNotebooks_skipsNotebooksOwnedBySomeoneElse`() = runTest {
        coEvery { itemDao.getNotebooksWithoutWrap() } returns
            listOf(legacyNotebook("nb-1", userId = OTHER))

        assertEquals(0, repository.upgradeLegacyNotebooks(ME))

        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    /**
     * Without our own id nothing is re-encrypted: the owner check would
     * otherwise let every row through, other people's included.
     */
    @Test
    fun `upgradeLegacyNotebooks_withNoOwnIdUpgradesNothing`() = runTest {
        coEvery { itemDao.getNotebooksWithoutWrap() } returns
            listOf(legacyNotebook("nb-1", userId = OTHER), legacyNotebook("nb-2", userId = ""))

        assertEquals(0, repository.upgradeLegacyNotebooks(null))
        assertEquals(0, repository.upgradeLegacyNotebooks("not-an-id"))

        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    /** Our own row under another spelling of our id is ours; another's is not. */
    @Test
    fun `upgradeLegacyNotebooks_comparesTheOwnerCanonically`() = runTest {
        coEvery { itemDao.getNotebooksWithoutWrap() } returns
            listOf(legacyNotebook("nb-1", userId = ME.replace("-", "").uppercase()), legacyNotebook("nb-2", userId = "{$OTHER}"))

        assertEquals(1, repository.upgradeLegacyNotebooks(ME.uppercase()))

        coVerify(exactly = 1) { itemDao.upsert(match<ItemEntity> { it.id == "nb-1" }) }
        coVerify(exactly = 0) { itemDao.upsert(match<ItemEntity> { it.id == "nb-2" }) }
    }

    /** A locally-created row carries an empty user_id until a pull fills it. */
    @Test
    fun `upgradeLegacyNotebooks_treatsAnEmptyOwnerAsOurs`() = runTest {
        coEvery { itemDao.getNotebooksWithoutWrap() } returns
            listOf(legacyNotebook("nb-1", userId = ""))

        assertEquals(1, repository.upgradeLegacyNotebooks(ME))
    }

    /**
     * No decrypted content means the row has not been decrypted yet, or cannot
     * be. Re-encrypting content we never read would destroy it.
     */
    @Test
    fun `upgradeLegacyNotebooks_skipsWhatItCannotRead`() = runTest {
        coEvery { itemDao.getNotebooksWithoutWrap() } returns
            listOf(legacyNotebook("nb-1").copy(decryptedContent = null))

        assertEquals(0, repository.upgradeLegacyNotebooks(ME))

        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    /** A metadata-blob-only notebook has no row to re-encrypt. */
    @Test
    fun `upgradeLegacyNotebooks_skipsWebOnlyPlaceholders`() = runTest {
        coEvery { itemDao.getNotebooksWithoutWrap() } returns
            listOf(legacyNotebook("nb-1").copy(
                decryptedContent = NotebookRepository.WEB_ONLY_MARKER))

        assertEquals(0, repository.upgradeLegacyNotebooks(ME))
    }

    /** Idempotent by construction: an upgraded row has a wrap, so the query
     *  that feeds this sweep stops returning it. */
    @Test
    fun `upgradeLegacyNotebooks_doesNothingWhenThereIsNothingLeft`() = runTest {
        coEvery { itemDao.getNotebooksWithoutWrap() } returns emptyList()

        assertEquals(0, repository.upgradeLegacyNotebooks(ME))

        verify(exactly = 0) { noteCrypto.generateContentKey() }
    }

    @Test
    fun `upgradeLegacyNotebooks_noVaultKey_isANoop`() = runTest {
        every { sessionManager.getMasterKey() } returns null
        coEvery { itemDao.getNotebooksWithoutWrap() } returns listOf(legacyNotebook("nb-1"))

        assertEquals(0, repository.upgradeLegacyNotebooks(ME))

        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    // =============================================================================
    // A notebook shared WITH us is not one of OURS
    // =============================================================================

    /**
     * `/sync/pull` returns items shared TO this user, so a shared notebook lands
     * in Room like any other row and an unfiltered `item_type = 2` query lists
     * it among the user's own. It belongs under "Shared Notebooks", where it can
     * be accepted or declined — not silently mixed in with the ones they made.
     */
    @Test
    fun `observeNotebooks_excludesNotebooksOwnedBySomeoneElse`() = runTest {
        every { sessionManager.getUserId() } returns ME
        every { sessionManager.getWebNotebooks() } returns null
        every { itemDao.observeNotebooks() } returns flowOf(listOf(
            createTestEntity("mine", itemType = 2).copy(userId = ME),
            createTestEntity("theirs", itemType = 2).copy(userId = OTHER),
        ))

        val out = repository.observeNotebooks().first()

        assertEquals(listOf("mine"), out.map { it.id })
    }

    /** A locally-created row carries an empty user_id until a pull fills it in. */
    @Test
    fun `observeNotebooks_keepsALocallyCreatedNotebookWithNoOwnerYet`() = runTest {
        every { sessionManager.getUserId() } returns ME
        every { sessionManager.getWebNotebooks() } returns null
        every { itemDao.observeNotebooks() } returns flowOf(listOf(
            createTestEntity("fresh", itemType = 2).copy(userId = ""),
        ))

        assertEquals(listOf("fresh"), repository.observeNotebooks().first().map { it.id })
    }

    /** Without our own id nothing that names an owner is ours: only a
     * locally-created row, which names none yet, is shown. */
    @Test
    fun `observeNotebooks_showsNoOwnedRowWhenTheUserIdIsUnknown`() = runTest {
        every { sessionManager.getUserId() } returns null
        every { sessionManager.getWebNotebooks() } returns null
        every { itemDao.observeNotebooks() } returns flowOf(listOf(
            createTestEntity("a", itemType = 2).copy(userId = OTHER),
            createTestEntity("fresh", itemType = 2).copy(userId = ""),
        ))

        assertEquals(listOf("fresh"), repository.observeNotebooks().first().map { it.id })
    }

    /** Our own rows under another spelling of our id are ours. */
    @Test
    fun `observeNotebooks_comparesTheOwnerCanonically`() = runTest {
        every { sessionManager.getUserId() } returns ME
        every { sessionManager.getWebNotebooks() } returns null
        every { itemDao.observeNotebooks() } returns flowOf(listOf(
            createTestEntity("mine", itemType = 2).copy(userId = ME.uppercase()),
            createTestEntity("theirs", itemType = 2).copy(userId = OTHER.replace("-", "")),
        ))

        assertEquals(listOf("mine"), repository.observeNotebooks().first().map { it.id })
    }
}
