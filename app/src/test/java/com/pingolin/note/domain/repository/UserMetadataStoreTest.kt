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
import com.pingolin.note.collab.NotePayload
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.CreateItemRequest
import com.pingolin.note.data.remote.dto.ItemDto
import com.pingolin.note.data.remote.dto.SyncPullResponse
import com.pingolin.note.data.remote.dto.UpdateItemRequest
import com.pingolin.note.util.SessionManager
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.time.Instant

/**
 * The metadata blob is ONE row per user, rewritten whole, and nothing on the
 * server stops a second row existing. These are the two ways this client can
 * put one there, or lose a write into one:
 *
 *  - two overlapping writes both find no row and both POST (duplicate rows,
 *    after which every client rotates between the copies);
 *  - two overlapping writes each rebuild the whole `editorModes` map from a
 *    snapshot taken before the other landed (lost update, one row).
 *
 * Both need the writes to actually OVERLAP, which is why the fake server
 * suspends inside the request: `runTest`'s scheduler then runs the second
 * writer's fetch while the first is still waiting for its POST, exactly as two
 * taps or a tap racing SyncRepository's `gcPins` would. Sequential writes pass
 * these tests whether or not the writes are serialised, so they could never
 * catch the race.
 *
 * `stored` is the single metadata row. Storage is deliberately dumber than
 * Room: the newest-row query is `ItemDaoMetadataTest`'s job, and a fake that
 * re-implemented it here would only test the fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserMetadataStoreTest {

    /** This account (canonical): the metadata row read and written is its own. */
    private val ME = "00000000-0000-4000-8000-0000000000e1"

    @MockK private lateinit var noteApi: NoteApi
    @MockK private lateinit var itemDao: ItemDao
    @MockK private lateinit var noteCrypto: NoteCrypto
    @MockK private lateinit var sessionManager: SessionManager

    /** The one metadata row the fake server/DAO hold. */
    private var stored: ItemEntity? = null
    private var creates = 0
    private var updates = 0

    /** What SessionManager was told to cache, by key. */
    private val cache = mutableMapOf<String, String>()

    private lateinit var store: UserMetadataStore

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        // Stub android.util.Base64 against the JDK decoder for JVM unit tests.
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        every { sessionManager.getMasterKey() } returns
            java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })

        every { sessionManager.saveWebNotebooks(any()) } answers { cache["notebooks"] = firstArg() }
        every { sessionManager.saveNotebookShares(any()) } answers { cache["shares"] = firstArg() }
        every { sessionManager.saveEditorModes(any()) } answers { cache["editorModes"] = firstArg() }
        every { sessionManager.getEditorModes() } answers { cache["editorModes"] }

        // Passthrough "cipher": encrypted_content IS the plaintext, so a test
        // can read back what was written without any crypto in the way.
        every { noteCrypto.encryptNoteWithContentKey(any(), any()) } answers {
            NotePayload(nonceB64 = "nonce", ciphertextB64 = firstArg())
        }
        every { noteCrypto.decryptNoteWithContentKey(any(), any(), any()) } answers { secondArg() }

        // The fetch suspends as well as the request. A Room read IS a
        // suspension point, and the writer that has already made its decision
        // but not yet published it is the whole hazard: with an instant fetch
        // the scheduler never runs a second writer early enough to observe the
        // pre-write state, and the concurrency tests below pass unfixed.
        coEvery { itemDao.getMetadataItems() } coAnswers { delay(1); listOfNotNull(stored) }
        every { sessionManager.getUserId() } returns ME
        coEvery { itemDao.upsert(any()) } answers { stored = firstArg() }
        // Mirrors the DAO's compare-and-swap: only adopt the PUT's result while
        // the row still holds the ciphertext the PUT was based on.
        coEvery {
            itemDao.confirmMetadataWriteIfUnchanged(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val row = stored
            val expectedCiphertext = arg<String>(6)
            val expectedNonce = arg<String>(7)
            if (row == null || row.encryptedContent != expectedCiphertext || row.contentNonce != expectedNonce) {
                0
            } else {
                stored = row.copy(
                    encryptedContent = arg(1),
                    contentNonce = arg(2),
                    decryptedContent = arg(3),
                    version = arg(4),
                    updatedAt = arg(5),
                )
                1
            }
        }

        // The POST and the PUT both suspend mid-request. That is the whole
        // point: it is the window in which a competing writer runs its own
        // fetch and decides, from what it sees, whether to create a row.
        coEvery { noteApi.createItem(any()) } coAnswers {
            creates++
            val req = firstArg<CreateItemRequest>()
            delay(50)
            Response.success(
                ApiResponse(
                    success = true,
                    data = itemDto("meta-1", req.encryptedContent, req.contentNonce, version = 1),
                ),
            )
        }
        coEvery { noteApi.updateItem(any(), any()) } coAnswers {
            updates++
            val id = firstArg<String>()
            val req = secondArg<UpdateItemRequest>()
            delay(50)
            Response.success(
                ApiResponse(
                    success = true,
                    data = itemDto(id, req.encryptedContent, req.contentNonce, version = req.expectedVersion + 1),
                ),
            )
        }

        store = UserMetadataStore(noteApi, itemDao, noteCrypto, sessionManager)
    }

    @After
    fun teardown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun itemDto(id: String, ciphertext: String, nonce: String, version: Long) = ItemDto(
        id = id,
        userId = ME,
        itemType = 10,
        encryptedContent = ciphertext,
        contentNonce = nonce,
        version = version,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-0${version.coerceAtMost(9)}T00:00:00Z",
        deleted = false,
        circleId = null,
        notebookId = null,
        contentSize = ciphertext.length,
    )

    private fun storedBlob(): JSONObject = JSONObject(stored!!.decryptedContent!!)

    // --- the lock ------------------------------------------------------------

    /**
     * The duplicate-row control. Without a critical section spanning fetch→save
     * both writers see no row and both POST; caching the created row cannot
     * help, because the row id only exists once the POST has RETURNED, which
     * has not happened while the other writer is still in its fetch: two rows,
     * both version 1.
     */
    @Test
    fun `concurrentPinWrites_createOneRow_notTwo`() = runTest {
        val first = launch { store.setPin("note-a", "markdown", "hybrid") }
        val second = launch { store.setPin("note-b", "preview", "hybrid") }
        first.join()
        second.join()

        assertEquals("the second writer must PUT the first writer's row, not POST another", 1, creates)
        assertEquals(1, updates)
    }

    /**
     * The lost-update control, which the lock alone does NOT fix. These writers
     * replace the whole `editorModes` object, so the replacement has to be
     * computed from the blob the critical section fetched — computing it from
     * the SessionManager cache outside the section means the second writer
     * rebuilds the map from a snapshot predating the first and drops its pin.
     */
    @Test
    fun `concurrentPinWrites_keepBothPins`() = runTest {
        val first = launch { store.setPin("note-a", "markdown", "hybrid") }
        val second = launch { store.setPin("note-b", "preview", "hybrid") }
        first.join()
        second.join()

        val pins = storedBlob().getJSONObject("editorModes")
        assertEquals("markdown", pins.optString("note-a"))
        assertEquals("preview", pins.optString("note-b"))
    }

    /** A pin write and a notebook-share write race just as well as two pins,
     *  so `mutate` has to take the same lock `mutatePins` does. */
    @Test
    fun `concurrentPinAndNotebookWrites_createOneRow_notTwo`() = runTest {
        val pin = launch { store.setPin("note-a", "markdown", "hybrid") }
        val notebook = launch {
            store.mutate { root -> root.put("notebooks", org.json.JSONArray().put(JSONObject().put("id", "nb-1"))) }
        }
        pin.join()
        notebook.join()

        assertEquals(1, creates)
        assertEquals(1, updates)
        assertTrue(storedBlob().getJSONObject("editorModes").has("note-a"))
        assertEquals("nb-1", storedBlob().getJSONArray("notebooks").getJSONObject(0).getString("id"))
    }

    /** Nothing changed, so nothing is written — the blob is shared with
     *  notebooks and label colours and a pointless write bumps its version. */
    @Test
    fun `setPin_toTheGlobalMode_writesNothing`() = runTest {
        store.setPin("note-a", "hybrid", "hybrid")

        assertEquals(0, creates)
        assertEquals(0, updates)
    }

    /** The no-op guard has to read the BLOB, not the cache: an un-pin whose pin
     *  exists only in the fetched blob (another device wrote it, this device's
     *  cache is empty) must still be written. */
    @Test
    fun `clearPins_writes_whenThePinsAreOnlyInTheBlob`() = runTest {
        stored = metadataRow("""{"notebooks":[],"editorModes":{"note-a":"markdown"}}""")

        store.clearPins()

        assertEquals(0, storedBlob().getJSONObject("editorModes").length())
        assertEquals(1, updates)
    }

    // --- a list this device cannot read --------------------------------------

    /** After a password reset the list on the server is sealed with the old key until the page
     * rebuilds it, keeping what it cannot open. A write from here that started from an empty
     * list would put that in its place, and the notebooks it named would be gone on every
     * device. Nothing is sent, and the row stays as it was. */
    @Test
    fun `aListThatDoesNotOpen_isNotWrittenOver`() = runTest {
        stored = metadataRow("""{"notebooks":[{"id":"nb-1","name":"Locked"}]}""").copy(encryptedContent = "sealed-with-the-old-key")
        every { noteCrypto.decryptNoteWithContentKey(any(), "sealed-with-the-old-key", any()) } throws IllegalStateException("does not open")

        store.mutate { it.put("acceptedNotebooks", org.json.JSONArray(listOf("nb-2"))) }
        store.setPin("note-a", "markdown", "hybrid")

        assertEquals(0, updates)
        assertEquals(0, creates)
        assertEquals("sealed-with-the-old-key", stored!!.encryptedContent)
    }

    /**
     * The same, the way it comes about after a reset (the test above puts the locked row
     * straight into Room). This device has no row yet; its create is refused, 409
     * `item_metadata_exists` (the account has one); the server's row is adopted, sealed with
     * the old key; and the retry sends nothing: it stays as it was.
     */
    @Test
    fun `afterAReset_theAdoptedListThatDoesNotOpen_isNotWrittenOver`() = runTest {
        stored = null
        rejectCreateWith409()
        serveOnPull(listOf(serverOnlyRow("sealed-with-the-old-key")))
        every { noteCrypto.decryptNoteWithContentKey(any(), "sealed-with-the-old-key", any()) } throws IllegalStateException("does not open")

        store.mutate { it.put("acceptedNotebooks", org.json.JSONArray(listOf("nb-2"))) }

        assertEquals("the create was refused once", 1, creates)
        assertEquals("nothing was written over the adopted list", 0, updates)
        assertEquals("server-meta", stored!!.id)
        assertEquals("sealed-with-the-old-key", stored!!.encryptedContent)
    }

    /** …and one that opens but is not a list is not replaced by an empty one either. */
    @Test
    fun `aListThatOpensButCannotBeRead_isNotWrittenOver`() = runTest {
        stored = metadataRow("not a list")

        store.mutate { it.put("acceptedNotebooks", org.json.JSONArray(listOf("nb-2"))) }

        assertEquals(0, updates)
        assertEquals(0, creates)
    }

    /** With no row at all, a list is started. */
    @Test
    fun `noRowAtAll_startsAList`() = runTest {
        stored = null

        store.mutate { it.put("acceptedNotebooks", org.json.JSONArray(listOf("nb-2"))) }

        assertEquals(1, creates)
        assertEquals("nb-2", storedBlob().getJSONArray("acceptedNotebooks").getString(0))
    }

    // --- the display path ----------------------------------------------------

    /** `refreshCaches` mirrors the row the DAO hands back — which
     *  `ItemDaoMetadataTest` pins down as the newest one — into the three
     *  caches the drawer, the share fan-out and the editor read. */
    @Test
    fun `refreshCaches_populatesCachesFromTheRowTheDaoReturns`() = runTest {
        stored = metadataRow(
            """{"notebooks":[{"id":"nb-newest","name":"Newest"}],""" +
                """"notebookShares":{"nb-newest":[{"email":"a@b.example"}]},""" +
                """"editorModes":{"note-a":"preview"}}""",
        )

        store.refreshCaches()

        assertTrue(cache["notebooks"]!!.contains("nb-newest"))
        assertTrue(cache["shares"]!!.contains("a@b.example"))
        assertEquals("preview", JSONObject(cache["editorModes"]!!).optString("note-a"))
    }

    /** No row means nothing is known, not that everything is empty — clearing
     *  the caches here would blank the drawer on any account whose metadata
     *  hasn't synced yet. */
    @Test
    fun `refreshCaches_withNoMetadataRow_leavesCachesAlone`() = runTest {
        stored = null

        store.refreshCaches()

        verify(exactly = 0) { sessionManager.saveWebNotebooks(any()) }
        verify(exactly = 0) { sessionManager.saveNotebookShares(any()) }
        verify(exactly = 0) { sessionManager.saveEditorModes(any()) }
    }

    private fun metadataRow(json: String) = ItemEntity(
        id = "meta-1",
        userId = ME,
        itemType = 10,
        encryptedContent = json,
        contentNonce = "nonce",
        decryptedContent = json,
        version = 3,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-03-01T00:00:00Z"),
    )

    // --- the server rejecting a duplicate create
    // ----------------------------
    //
    // The server holds at most one live item_type=10 row per user (a
    // partial unique index), so a create raced by another device comes back
    // 409 `item_metadata_exists` instead of quietly making a second row.
    // Unhandled, the notebook item is created and the metadata row stays
    // byte-identical — the write vanishes with no crash and nothing on
    // screen.

    /** Server has a row this device has never seen. */
    private fun serverOnlyRow(json: String) = ItemDto(
        id = "server-meta",
        userId = ME,
        itemType = 10,
        encryptedContent = json,
        contentNonce = "nonce",
        version = 7,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-04-01T00:00:00Z",
        deleted = false,
        circleId = null,
        notebookId = null,
        contentSize = json.length,
    )

    private fun rejectCreateWith409() {
        coEvery { noteApi.createItem(any()) } coAnswers {
            creates++
            delay(50)
            Response.error(
                409,
                """{"success":false,"error_code":"item_metadata_exists"}"""
                    .toResponseBody("application/json".toMediaTypeOrNull()),
            )
        }
    }

    private fun serveOnPull(items: List<ItemDto>) {
        coEvery { noteApi.syncPull(any(), any(), any()) } coAnswers {
            Response.success(
                ApiResponse(success = true, data = SyncPullResponse(items = items, cursor = null, hasMore = false)),
            )
        }
    }

    @Test
    fun `rejectedCreate_adoptsTheServerRow_andRetriesAsAnUpdate`() = runTest {
        val serverBlob = """{"notebooks":[{"id":"nb-from-other-device","name":"Other"}]}"""
        rejectCreateWith409()
        serveOnPull(listOf(serverOnlyRow(serverBlob)))

        store.setPin("note-a", "markdown", "hybrid")

        assertEquals("the rejected create must be retried, not swallowed", 1, updates)
        // Onto the SERVER's row, at its version — not a second create.
        assertEquals(1, creates)
        assertEquals("server-meta", stored!!.id)
        // And the other device's notebook survived: the retry re-applied the
        // pin on top of the blob that was actually there, rather than
        // overwriting it with what this device happened to hold.
        assertEquals("markdown", storedBlob().getJSONObject("editorModes").optString("note-a"))
        assertEquals(
            "nb-from-other-device",
            storedBlob().getJSONArray("notebooks").getJSONObject(0).getString("id"),
        )
    }

    /**
     * NotebookInvites.prune through a write the server refuses as stale. This
     * device holds nb-a and nb-b as accepted; the web has accepted nb-c since, so
     * the server's list is a version on; nb-b is not shared any more. The first
     * PUT is refused (409), the server's row is adopted, and the prune is applied
     * again on top of it: nb-b goes, and nb-c, accepted elsewhere, stays. Writing
     * the whole list kept from this device's copy would erase nb-c.
     */
    @Test
    fun `prune_retriedOverANewerList_keepsAnAcceptanceMadeElsewhere`() = runTest {
        stored = metadataRow("""{"acceptedNotebooks":["nb-a","nb-b"]}""")
        serveOnPull(listOf(itemDto("meta-1", """{"acceptedNotebooks":["nb-a","nb-b","nb-c"]}""", "nonce", version = 4)))
        coEvery { noteApi.updateItem(any(), any()) } coAnswers {
            updates++
            val req = secondArg<UpdateItemRequest>()
            delay(50)
            if (req.expectedVersion != 4L) {
                Response.error(409, """{"success":false,"error":"Version conflict"}""".toResponseBody("application/json".toMediaTypeOrNull()))
            } else {
                Response.success(ApiResponse(success = true, data = itemDto(firstArg(), req.encryptedContent, req.contentNonce, version = 5)))
            }
        }

        NotebookInvites(store, noteApi).prune(setOf("nb-a", "nb-c"))

        assertEquals("refused once as stale, then written over the server's list", 2, updates)
        val accepted = storedBlob().getJSONArray("acceptedNotebooks")
        assertEquals(listOf("nb-a", "nb-c"), (0 until accepted.length()).map { accepted.getString(it) })
    }

    /** Nothing this device read is gone: nothing is written. */
    @Test
    fun `prune_withNothingGone_writesNothing`() = runTest {
        stored = metadataRow("""{"acceptedNotebooks":["nb-a"]}""")
        NotebookInvites(store, noteApi).prune(setOf("nb-a", "nb-z"))
        assertEquals(0, updates)
        assertEquals(0, creates)
    }

    /** A notebook write takes the same path. */
    @Test
    fun `rejectedCreate_notebookWrite_reachesTheServer`() = runTest {
        val serverBlob = """{"notebooks":[{"id":"nb-old","name":"Old"}]}"""
        rejectCreateWith409()
        serveOnPull(listOf(serverOnlyRow(serverBlob)))

        store.mutate { root ->
            root.optJSONArray("notebooks")!!.put(JSONObject().put("id", "nb-new").put("name", "New"))
        }

        assertEquals(1, updates)
        val names = storedBlob().getJSONArray("notebooks")
        assertEquals(2, names.length())
        assertEquals("nb-old", names.getJSONObject(0).getString("id"))
        assertEquals("nb-new", names.getJSONObject(1).getString("id"))
    }

    /** If the row cannot be found the write is still lost — but quietly and
     *  once, not in an unbounded retry loop against the server. */
    @Test
    fun `rejectedCreate_serverRowNotFound_doesNotRetryForever`() = runTest {
        rejectCreateWith409()
        serveOnPull(emptyList())

        store.setPin("note-a", "markdown", "hybrid")

        assertEquals("exactly one create attempt, no retry storm", 1, creates)
        assertEquals(0, updates)
    }

    /** The paired negative: an ordinary successful create must NOT drag in the
     *  recovery pull, or every first-write on a fresh account pays for it. */
    @Test
    fun `successfulCreate_doesNotPullForARowToAdopt`() = runTest {
        store.setPin("note-a", "markdown", "hybrid")

        assertEquals(1, creates)
        coVerify(exactly = 0) { noteApi.syncPull(any(), any(), any()) }
    }

    // --- only this account's metadata row is read ------------------------------------------------

    @Test
    fun `another account's metadata row is never read, however new it is`() = runTest {
        val mine = metadataRow("""{"notebooks":[{"id":"n1","name":"Mine"}]}""")
        val theirs = mine.copy(
            id = "meta-theirs", userId = "00000000-0000-4000-8000-0000000000b2",
            encryptedContent = """{"notebooks":[{"id":"n2","name":"Theirs"}]}""",
            updatedAt = Instant.parse("2026-05-01T00:00:00Z"),
        )
        coEvery { itemDao.getMetadataItems() } returns listOf(theirs, mine.copy(userId = ME.uppercase()))
        assertEquals("""{"notebooks":[{"id":"n1","name":"Mine"}]}""", store.read())
        coEvery { itemDao.getMetadataItems() } returns listOf(theirs)
        assertNull(store.read())
    }
}
