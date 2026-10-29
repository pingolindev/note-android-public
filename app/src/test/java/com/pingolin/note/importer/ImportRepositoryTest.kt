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

package com.pingolin.note.importer

import android.content.ContentResolver
import android.net.Uri
import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.collab.NotePayload
import com.pingolin.note.collab.WrappedKeyPair
import com.pingolin.note.importer.model.ImportProgress
import com.pingolin.note.importer.model.ImportSource
import com.pingolin.note.importer.model.ImportedNote
import com.pingolin.note.importer.parser.NoteImportParser
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ImportRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var itemDao: ItemDao

    @MockK
    private lateinit var noteCrypto: NoteCrypto

    @MockK
    private lateinit var contentResolver: ContentResolver

    @MockK
    private lateinit var uri: Uri

    @MockK
    private lateinit var sessionManager: SessionManager

    /** Only touched when restoring a Pingolin backup — the metadata merge runs
     *  before the notes so notebooks exist by the time notes reference them.
     *  Foreign imports never reach it. */
    @MockK
    private lateinit var userMetadata: com.pingolin.note.domain.repository.UserMetadataStore

    private lateinit var repository: ImportRepository

    // 32 zero bytes encoded via the JDK encoder — vaultKeyBytes() requires
    // exactly 32 bytes after decode. We can't use android.util.Base64 at
    // field init time because the static mock is set up in @Before.
    private val testVaultKeyB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32))

    private val testContentKey = ByteArray(32) { 0x42 }

    private val testNotePayload = NotePayload(
        nonceB64 = "import-nonce",
        ciphertextB64 = "encrypted-import",
    )

    private val testWrappedKey = WrappedKeyPair(
        wrappedKeyB64 = "wrapped-key",
        nonceB64 = "wrap-nonce",
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // android.util.Base64 is a stub in JVM unit tests — bridge to JDK Base64.
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        every { android.util.Base64.encodeToString(any<ByteArray>(), any<Int>()) } answers {
            java.util.Base64.getEncoder().withoutPadding().encodeToString(firstArg<ByteArray>())
        }

        every { sessionManager.getMasterKey() } returns testVaultKeyB64
        every { noteCrypto.generateContentKey() } returns testContentKey
        every { noteCrypto.encryptNoteWithContentKeyV2(any(), any(), any()) } returns testNotePayload
        every { noteCrypto.wrapWithVault(any(), any()) } returns testWrappedKey
        coEvery { itemDao.upsertAll(any()) } just Runs

        repository = ImportRepository(itemDao, noteCrypto, sessionManager, userMetadata)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        // `clearAllMocks()` drops the STUBS but leaves the static mock of
        // android.util.Base64 installed, and that mock is JVM-global — so
        // whatever class runs next in this fork inherits an unstubbed Base64.
        // NoteRepositoryTest already unmocks for this reason; match it.
        unmockkAll()
    }

    // =============================================================================
    // importNotes - FILE OPENING TESTS
    // =============================================================================

    @Test
    fun `importNotes_cannotOpenFile_emitsError`() = runTest {
        every { contentResolver.openInputStream(uri) } returns null

        val states = repository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT).toList()

        assertTrue(states.any { it is ImportState.Error })
        val error = states.filterIsInstance<ImportState.Error>().first()
        assertEquals("Cannot open file", error.message)
    }

    // =============================================================================
    // importNotes - ENCRYPTION TESTS
    // =============================================================================

    @Test
    fun `importNotes_encryptsWithPerNoteContentKey`() = runTest {
        val textContent = "Title\nBody content"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(textContent.toByteArray())
        every { contentResolver.getType(uri) } returns "text/plain"

        repository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT).toList()

        verifyOrder {
            noteCrypto.generateContentKey()
            noteCrypto.encryptNoteWithContentKeyV2(any(), any(), any())
            noteCrypto.wrapWithVault(any(), any())
        }
    }

    @Test
    fun `importNotes_persistsWrappedContentKey`() = runTest {
        val textContent = "Title\nBody"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(textContent.toByteArray())
        every { contentResolver.getType(uri) } returns "text/plain"

        val captured = slot<List<ItemEntity>>()
        coEvery { itemDao.upsertAll(capture(captured)) } just Runs

        repository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT).toList()

        val item = captured.captured.single()
        assertEquals("wrapped-key", item.wrappedContentKey)
        assertEquals("wrap-nonce", item.wrappedContentKeyNonce)
    }

    // =============================================================================
    // importNotes - COMPLETION TESTS
    // =============================================================================

    @Test
    fun `importNotes_completionState_containsCorrectCount`() = runTest {
        val textContent = "Title\nBody"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(textContent.toByteArray())
        every { contentResolver.getType(uri) } returns "text/plain"

        val states = repository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT).toList()

        assertTrue(states.any { it is ImportState.Complete })
        val complete = states.filterIsInstance<ImportState.Complete>().first()
        assertEquals(1, complete.count)
    }

    @Test
    fun `importNotes_startsWithIdleState`() = runTest {
        val textContent = "Title\nBody"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(textContent.toByteArray())
        every { contentResolver.getType(uri) } returns "text/plain"

        val states = repository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT).toList()

        assertTrue(states.first() is ImportState.Idle)
    }

    // =============================================================================
    // importNotes - ENTITY CREATION TESTS
    // =============================================================================

    @Test
    fun `importNotes_createsEntityWithCorrectFields`() = runTest {
        val textContent = "My Title\nMy body content"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(textContent.toByteArray())
        every { contentResolver.getType(uri) } returns "text/plain"

        repository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT).toList()

        coVerify {
            itemDao.upsertAll(match { items ->
                items.size == 1 &&
                    items[0].itemType == 1 &&
                    items[0].encryptedContent == "encrypted-import" &&
                    items[0].contentNonce == "import-nonce" &&
                    items[0].isDirty &&
                    items[0].version == 1L
            })
        }
    }

    // =============================================================================
    // importNotes - ZIP DETECTION TESTS
    // =============================================================================

    @Test
    fun `importNotes_plainTextWithZipMime_usesZipParser`() = runTest {
        // Create a minimal zip to avoid parse errors
        val baos = java.io.ByteArrayOutputStream()
        val zos = java.util.zip.ZipOutputStream(baos)
        zos.putNextEntry(java.util.zip.ZipEntry("note.txt"))
        zos.write("Title\nBody".toByteArray())
        zos.closeEntry()
        zos.close()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(baos.toByteArray())
        every { contentResolver.getType(uri) } returns "application/zip"

        val states = repository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT).toList()

        assertTrue(states.any { it is ImportState.Complete })
    }

    @Test
    fun `importNotes_plainTextWithTextMime_usesSingleFileParser`() = runTest {
        val content = "Title\nBody"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(content.toByteArray())
        every { contentResolver.getType(uri) } returns "text/plain"

        val states = repository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT).toList()

        assertTrue(states.any { it is ImportState.Complete })
    }

    // =============================================================================
    // importNotes - BATCH BEHAVIOR TESTS
    // =============================================================================

    @Test
    fun `importNotes_setsUniqueIdsForEachEntity`() = runTest {
        // Use Standard Notes format with multiple notes
        val json = """
            {
                "items": [
                    {"content_type": "Note", "content": {"title": "A", "text": "1"}},
                    {"content_type": "Note", "content": {"title": "B", "text": "2"}}
                ]
            }
        """.trimIndent()
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(json.toByteArray())
        every { contentResolver.getType(uri) } returns "application/json"

        val capturedItems = slot<List<ItemEntity>>()
        coEvery { itemDao.upsertAll(capture(capturedItems)) } just Runs

        repository.importNotes(contentResolver, uri, ImportSource.STANDARD_NOTES).toList()

        val items = capturedItems.captured
        assertEquals(2, items.size)
        assertNotEquals(items[0].id, items[1].id)
    }
}
