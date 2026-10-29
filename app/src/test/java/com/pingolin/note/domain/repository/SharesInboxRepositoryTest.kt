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
import com.pingolin.note.collab.WrapVerify
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.InboxShareDto
import com.pingolin.note.data.remote.dto.PublicKeyLookupEntry
import com.pingolin.note.data.remote.dto.PublicKeyLookupResponse
import com.pingolin.note.util.NoteGenFloorStore
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response as RetrofitResponse
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SharesInboxRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @MockK
    private lateinit var noteApi: NoteApi

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var noteCrypto: NoteCrypto

    @MockK
    private lateinit var itemDao: ItemDao

    @MockK
    private lateinit var pinStore: SenderKeyPinStore

    @MockK
    private lateinit var genFloorStore: NoteGenFloorStore

    @MockK
    private lateinit var previousIdentities: PreviousIdentityLoader

    private lateinit var repository: SharesInboxRepository

    /** The account's own list of accepted notebooks (NotebookInvites.accepted), as a test sets it. */
    private var acceptedNotebooks: Set<String> = emptySet()
    /** What this device remembers of that list (SessionManager), as the repository stores it. */
    private var rememberedAccepted: Set<String> = emptySet()
    /** What the session store holds as "on offer at the last read to the end": null until one is stored. */
    private var offeredStored: Set<String>? = null
    private val notebookInvites: NotebookInvites = mockk()

    private val testPrivB64 =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })
    private val testContentKey = ByteArray(32) { 1 }

    /** Recipient (us). The `inbox()` helper's sender ownerId is `…0001`. */
    private val myUserId = "00000000-0000-0000-0000-000000000002"

    // Item ids are UUIDs: the inbox drops a share whose item id isn't one.
    private val ITEM_1 = "10000000-0000-4000-8000-000000000001"
    private val ITEM_2 = "10000000-0000-4000-8000-000000000002"
    private val ITEM_GOOD = "10000000-0000-4000-8000-00000000000a"
    private val ITEM_BAD = "10000000-0000-4000-8000-00000000000b"
    private val ITEM_GONE = "10000000-0000-4000-8000-00000000000c"
    private val NB_1 = "20000000-0000-4000-8000-000000000001"
    private val NEVER_SEEN = "10000000-0000-4000-8000-0000000000ff"

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // android.util.Base64 is stubbed in JVM unit tests — wire JDK decoder.
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }

        // refresh() needs our own id (canonical) before it fetches
        // anything: without one it stops. Unpinned senders below
        // still take the first-use path, so the verification branch
        // runs only where a test pins a sender.
        every { sessionManager.getUserId() } returns myUserId

        // Materialization defaults: no local row, decrypt yields nothing,
        // upsert is a no-op. Tests that exercise materialization override
        // these. Keeps the key-unwrap-only tests from tripping on the
        // Room write path.
        coEvery { itemDao.getById(any()) } returns null
        coEvery { itemDao.upsert(any()) } just Runs
        coEvery { itemDao.deleteById(any()) } just Runs
        every { noteCrypto.decryptNoteWithContentKey(any(), any(), any()) } returns null
        // Materialize uses the AAD v2 decrypt (item_id-bound).
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns null

        // Un-share/delete reconcile defaults: no prior inbox set, persist
        // is a no-op. Tests that exercise removal override getInboxShareIds.
        every { sessionManager.getInboxShareIds() } returns emptySet()
        every { sessionManager.setInboxShareIds(any()) } just Runs
        // Default: our own email unknown → key-change alarms record without a
        // safety number (self directory keys can't be looked up). Tests that
        // exercise the SAS override this.
        every { sessionManager.getEmail() } returns null

        // TOFU sender-key pin store: unpinned by default (first-use path) so the loop skips
        // pinned-key verification and takes the pin-on-unwrap branch. Tests that exercise
        // verification/alarm override getPinned.
        // Every trust-store call of a refresh names the account the refresh is for. Stubbed
        // for THAT account only: a call that names none finds no answer and fails its test.
        every { pinStore.ensureReadable(myUserId) } just Runs
        every { pinStore.getPinned(any(), myUserId) } returns null
        every { pinStore.pin(any(), any(), any(), myUserId) } returns SenderKeyPinStore.PinState.NewPinning

        // Generation rollback floor: baseline 0 (TOFU) so any verified
        // generation is accepted; bump is a no-op. Tests exercising a rollback
        // override floor().
        every { genFloorStore.ensureReadable(myUserId) } just Runs
        every { genFloorStore.floor(any(), myUserId) } returns 0L
        every { genFloorStore.bump(any(), any()) } just Runs
        // Item owners: nothing bound yet; accepting a share is a no-op here.
        every { genFloorStore.owner(any(), myUserId) } returns null
        every { genFloorStore.accept(any(), any(), any()) } just Runs
        every { genFloorStore.acceptUnsaved(any(), any(), any(), myUserId) } just Runs
        every { genFloorStore.saveAccepted(myUserId) } returns true

        // Own rows: none in Room, and no vault key to open one with.
        coEvery { itemDao.getByIds(any()) } returns emptyList()
        every { sessionManager.getMasterKey() } returns null
        every { previousIdentities.vaultKeys() } returns emptyList()

        // The shared notebooks this account has said yes to: none listed in its metadata and
        // none remembered on this device, unless a test says so.
        acceptedNotebooks = emptySet()
        rememberedAccepted = emptySet()
        offeredStored = null
        coEvery { notebookInvites.accepted() } answers { acceptedNotebooks }
        every { sessionManager.getSeenAcceptedNotebooks() } answers { rememberedAccepted }
        every { sessionManager.setSeenAcceptedNotebooks(any()) } answers { rememberedAccepted = firstArg() }
        every { sessionManager.getOfferedNotebooks() } answers { offeredStored }
        every { sessionManager.getOffersKnownAsOf() } returns null
        every { sessionManager.setOffersKnownAsOf(any()) } returns Unit
        every { sessionManager.setOfferedNotebooks(any()) } answers { offeredStored = firstArg() }
        // The notes held for an invitation, kept across restarts.
        var heldStored: Set<String> = emptySet()
        every { sessionManager.getHeldForAnInvitation() } answers { heldStored }
        every { sessionManager.setHeldForAnInvitation(any()) } answers { heldStored = firstArg() }

        repository = SharesInboxRepository(
            noteApi, sessionManager, noteCrypto, itemDao, pinStore, genFloorStore, previousIdentities,
            javax.inject.Provider { notebookInvites },
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    // =============================================================================
    // refresh TESTS
    // =============================================================================

    @Test
    fun `refresh_noPrivateKey_returnsZero`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns null

        val n = repository.refresh()

        assertEquals(0, n)
        // Nothing fetched, whatever it would have been asked with.
        coVerify(exactly = 0) { noteApi.listInbox(any(), any(), any()) }
    }

    @Test
    fun `refresh_invalidPrivateKeySize_returnsZero`() = runTest {
        // 16-byte instead of 32-byte key — should bail out.
        val shortKey = java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(ByteArray(16) { 0 })
        every { sessionManager.getSharingPrivateKey() } returns shortKey

        val n = repository.refresh()

        assertEquals(0, n)
    }

    @Test
    fun `refresh_inboxFetchFails_returnsZero`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } throws RuntimeException("network")

        val n = repository.refresh()

        assertEquals(0, n)
    }

    @Test
    fun `refresh_inboxNotSuccessful_returnsZero`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.error(
            500, okhttp3.ResponseBody.Companion.create(null, "err"),
        )

        val n = repository.refresh()

        assertEquals(0, n)
    }

    // =============================================================================
    // listedNotebookIds: what the acceptance prune in SyncRepository runs
    // against
    // =============================================================================

    @Test
    fun `listedNotebookIds_isNull_beforeAnyRefresh`() {
        assertNull(repository.listedNotebookIds)
    }

    @Test
    fun `listedNotebookIds_isNull_afterAFetchThatFailed`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } throws RuntimeException("network")

        repository.refresh()

        assertNull(repository.listedNotebookIds)
    }

    @Test
    fun `listedNotebookIds_isNull_afterAnAnswerThatWasNotSuccessful`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.error(
            500, okhttp3.ResponseBody.Companion.create(null, "err"),
        )

        repository.refresh()

        assertNull(repository.listedNotebookIds)
    }

    @Test
    fun `listedNotebookIds_isNull_whenTheSessionHoldsNoKey`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns null

        repository.refresh()

        assertNull(repository.listedNotebookIds)
    }

    @Test
    fun `listedNotebookIds_isEmpty_notNull_whenTheServerNamesNothing`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(emptyList()))
        )

        repository.refresh()

        // The server answered: that is a list, and an acceptance of anything is stale.
        assertEquals(emptySet<String>(), repository.listedNotebookIds)
    }

    @Test
    fun `listedNotebookIds_namesANotebookWhoseRowWasRefused`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getUserId() } returns myUserId
        // A pinned sender whose wrap arrives with no signature: the row is
        // refused, exactly as in refresh_pinnedSender_strippedSignature_dropped.
        val pinnedSigning = "aa".repeat(32)
        every { pinStore.getPinned("00000000-0000-0000-0000-000000000001", myUserId) } returns
            SenderKeyPinStore.PinRecord(pinnedSigning, "cc".repeat(32), 0L)
        val notebook = inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@b.c", sig = null, itemType = 2)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(notebook)))
        )
        stubLookup("a@b.c", signing = pinnedSigning, sharing = "cc".repeat(32))
        every { noteCrypto.openSeal(any(), any()) } returns testContentKey

        repository.refresh()

        // Not opened, and not among the notebooks the screen draws...
        assertTrue(repository.sharedNotebooksFlow.value.isEmpty())
        verify(exactly = 0) { noteCrypto.openSeal(any(), any()) }
        // ...but still OFFERED: the reader's acceptance of it must outlive the refusal.
        assertEquals(setOf(ITEM_1), repository.listedNotebookIds)
    }

    @Test
    fun `listedNotebookIds_namesNotebooksOnly`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val note = inbox("share-1", ITEM_1, wrapped = "sealed-b64", ownerEmail = "a@b.c")
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(note)))
        )
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        repository.refresh()

        assertEquals(emptySet<String>(), repository.listedNotebookIds)
    }

    /** [n] rows as the server would send them, numbered from [from]: row [notebookAt] is a
     *  notebook, the rest notes, and every id is distinct across pages. */
    private fun page(n: Int, from: Int = 0, notebookAt: Int = 0) = (from until from + n).map { i ->
        val tail = i.toString(16).padStart(12, '0')
        inbox("5a5a5a5a-0000-4000-8000-$tail", "6b6b6b6b-0000-4000-8000-$tail", wrapped = "sealed-b64", ownerEmail = "a@b.c",
            itemType = if (i == notebookAt) 2 else 1)
    }
    private fun shareIdOf(i: Int) = "5a5a5a5a-0000-4000-8000-${i.toString(16).padStart(12, '0')}"
    private fun itemIdOf(i: Int) = "6b6b6b6b-0000-4000-8000-${i.toString(16).padStart(12, '0')}"
    private val PAGE = SharesInboxRepository.INBOX_PAGE_LIMIT
    /** The cursor a client sends after a page whose last row is number [last]: its created_at and share_id. */
    private fun stubPageAfter(last: Int, rows: List<InboxShareDto>?) {
        if (rows == null) coEvery { noteApi.listInbox("2024-01-01T00:00:00Z", shareIdOf(last)) } throws RuntimeException("network")
        else coEvery { noteApi.listInbox("2024-01-01T00:00:00Z", shareIdOf(last)) } returns
            RetrofitResponse.success(ApiResponse(success = true, data = bareList(rows)))
    }

    @Test
    fun `listedNotebookIds_isAList_whenTheAnswerIsOneRowShortOfThePage`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(page(SharesInboxRepository.INBOX_PAGE_LIMIT - 1)))
        )
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        repository.refresh()

        assertEquals(setOf("6b6b6b6b-0000-4000-8000-000000000000"), repository.listedNotebookIds)
    }

    @Test
    fun `refresh_readsTheInboxToItsEnd_fromTheLastRowsTimeAndId`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(page(PAGE, notebookAt = -1)))
        )
        // The notebook's own row is the OLDEST: on the second page, which the refresh must
        // ask for.
        stubPageAfter(PAGE - 1, page(3, from = PAGE, notebookAt = PAGE + 2))
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        val n = repository.refresh()

        assertEquals(PAGE + 3, n)
        assertEquals(setOf(itemIdOf(PAGE + 2)), repository.listedNotebookIds)
        coVerify(exactly = 1) { noteApi.listInbox(null, null) }
        coVerify(exactly = 1) { noteApi.listInbox("2024-01-01T00:00:00Z", shareIdOf(PAGE - 1)) }
    }

    @Test
    fun `refresh_anInboxOfExactlyOnePage_takesOneMoreRequest_andIsAList`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(page(PAGE)))
        )
        stubPageAfter(PAGE - 1, emptyList())
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        repository.refresh()

        assertEquals(setOf(itemIdOf(0)), repository.listedNotebookIds)
    }

    @Test
    fun `listedNotebookIds_isNull_whenALaterPageCannotBeRead`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(page(PAGE)))
        )
        stubPageAfter(PAGE - 1, null)
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        val n = repository.refresh()

        // What was read is used: its shares are opened...
        assertEquals(PAGE, n)
        // ...but it may be the newest 500 of more, so it is not a list to prune against.
        assertNull(repository.listedNotebookIds)
    }

    @Test
    fun `refresh_aServerThatSendsTheSamePageAgain_isAskedOnceMore_andThatIsNotAList`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        // A server that does not know the cursor answers every request with the first page.
        coEvery { noteApi.listInbox(any(), any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(page(PAGE)))
        )
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        val n = repository.refresh()

        assertEquals(PAGE, n)                       // nothing is counted twice
        assertNull(repository.listedNotebookIds)
        coVerify(exactly = 2) { noteApi.listInbox(any(), any()) }
    }

    @Test
    fun `refresh_stopsAtItsPageLimit_onAnInboxThatNeverEnds`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        var asked = 0
        coEvery { noteApi.listInbox(any(), any()) } answers {
            val from = asked++ * PAGE
            RetrofitResponse.success(ApiResponse(success = true, data = bareList(page(PAGE, from = from, notebookAt = -1))))
        }
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        repository.refresh()

        assertEquals(SharesInboxRepository.INBOX_MAX_PAGES, asked)
        assertNull(repository.listedNotebookIds)
    }

    @Test
    fun `theInboxPageIs500Rows_theServersCap`() {
        assertEquals(500, SharesInboxRepository.INBOX_PAGE_LIMIT)
    }

    // ---- a server that SAYS whether more follows (has_more) ----

    /** The request asks for a page answer, on the first page and on every later one. */
    @Test
    fun `refresh_asksForAPageAnswer`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox(any(), any(), any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(emptyList(), hasMore = false))
        )

        repository.refresh()

        coVerify(exactly = 1) { noteApi.listInbox(null, null, true) }
    }

    /** Nobody asks for an empty page to learn that a full one was the last. */
    @Test
    fun `refresh_aFullPageTheServerSaysIsTheEnd_isOneRequest_andAList`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox(any(), any(), any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(page(PAGE), hasMore = false))
        )
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        val n = repository.refresh()

        assertEquals(PAGE, n)
        assertEquals(setOf(itemIdOf(0)), repository.listedNotebookIds)
        coVerify(exactly = 1) { noteApi.listInbox(any(), any(), any()) }
    }

    /** A page SHORTER than this app's copy of the page size, with `has_more` set: the next page is
     * followed all the same.
     */
    @Test
    fun `refresh_aShortPageTheServerSaysHasMore_isFollowed_fromTheLastRowsTimeAndId`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(page(3, notebookAt = -1), hasMore = true))
        )
        // The notebook's own row is the OLDEST, on the second page.
        coEvery { noteApi.listInbox("2024-01-01T00:00:00Z", shareIdOf(2)) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(page(2, from = 3, notebookAt = 4), hasMore = false))
        )
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        val n = repository.refresh()

        assertEquals(5, n)
        assertEquals(setOf(itemIdOf(4)), repository.listedNotebookIds)
        coVerify(exactly = 1) { noteApi.listInbox("2024-01-01T00:00:00Z", shareIdOf(2)) }
    }

    /** Not an empty inbox: an empty inbox read to its end removes every share's row. */
    @Test
    fun `refresh_anAnswerInNeitherShape_isARefreshThatFailed_andRemovesNothing`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        for (text in listOf("""{"shares":[]}""", """{"has_more":false}""", "{}", "7", """{"shares":[],"has_more":"no"}""")) {
            coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
                ApiResponse(success = true, data = com.google.gson.JsonParser.parseString(text))
            )

            repository.refresh()

            assertNull(text, repository.listedNotebookIds)
        }
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
        verify(exactly = 0) { sessionManager.setInboxShareIds(any()) }
    }

    @Test
    fun `refresh_aLaterAnswerInNeitherShape_leavesWhatWasRead_andNoList`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        // A row that WOULD be removed if this were taken for the whole inbox (with
        // no row here the check below could not fail).
        coEvery { itemDao.getById(ITEM_GONE) } returns shareRow(ITEM_GONE)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(page(2), hasMore = true))
        )
        coEvery { noteApi.listInbox("2024-01-01T00:00:00Z", shareIdOf(1)) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = com.google.gson.JsonParser.parseString("""{"shares":[]}"""))
        )
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        val n = repository.refresh()

        assertEquals(2, n)
        assertNull(repository.listedNotebookIds)
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
        verify { sessionManager.setInboxShareIds(match { ITEM_GONE in it }) }
    }

    @Test
    fun `refresh_aServerThatSaysMoreFollowsForEver_isLeftAtThePageLimit`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        var asked = 0
        coEvery { noteApi.listInbox(any(), any(), any()) } answers {
            val from = asked++
            RetrofitResponse.success(ApiResponse(success = true, data = inboxPage(page(1, from = from, notebookAt = -1), hasMore = true)))
        }
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        repository.refresh()

        assertEquals(SharesInboxRepository.INBOX_MAX_PAGES, asked)
        assertNull(repository.listedNotebookIds)
    }

    /** The server's own bytes through the repository, not this app's idea of them (InboxWire.kt). */
    @Test
    fun `refresh_readsTheServersPageAsItIsOnTheWire`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val type = object : com.google.gson.reflect.TypeToken<ApiResponse<com.google.gson.JsonElement>>() {}.type
        val first: ApiResponse<com.google.gson.JsonElement> = com.google.gson.Gson().fromJson(PAGE_ON_THE_WIRE, type)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(first)
        // It says more follows: the next page is asked for with the row's own time and id.
        coEvery { noteApi.listInbox("2026-10-07T01:02:03.456789Z", "5a5a5a5a-0000-4000-8000-000000000001") } returns
            RetrofitResponse.success(ApiResponse(success = true, data = inboxPage(emptyList(), hasMore = false)))

        repository.refresh()

        assertEquals(setOf("6b6b6b6b-0000-4000-8000-000000000001"), repository.listedNotebookIds)
        coVerify(exactly = 1) { noteApi.listInbox("2026-10-07T01:02:03.456789Z", "5a5a5a5a-0000-4000-8000-000000000001") }
    }

    @Test
    fun `listedNotebookIds_isNullAgain_whenTheNextRefreshFails`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(emptyList()))
        )
        repository.refresh()
        assertNotNull(repository.listedNotebookIds)

        // The list a refresh applied is that refresh's. A later one that fails
        // has none, whatever the one before it had.
        coEvery { noteApi.listInbox() } throws RuntimeException("network")
        repository.refresh()

        assertNull(repository.listedNotebookIds)
    }

    @Test
    fun `listedNotebookIds_isNull_afterAWipe`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(emptyList()))
        )
        repository.refresh()
        assertNotNull(repository.listedNotebookIds)

        repository.wipe()

        assertNull(repository.listedNotebookIds)
    }

    @Test
    fun `refresh_unwrapsSealedKey_populatesContentKeys`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64

        val share = inbox("share-1", ITEM_1, wrapped = "sealed-b64", ownerEmail = "a@b.c")
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        val n = repository.refresh()

        assertEquals(1, n)
        assertNotNull(repository.getContentKey(ITEM_1))
        verify { sessionManager.setInboxShareIds(setOf(ITEM_1)) }
    }

    @Test
    fun `refresh_nullWrappedKey_skipped`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val share = inbox("s1", ITEM_1, wrapped = null, ownerEmail = null)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )

        val n = repository.refresh()

        // wrapped_key missing → content key not unwrapped (so count = 0)
        // but the item is still in the live inbox set.
        assertEquals(0, n)
        assertNull(repository.getContentKey(ITEM_1))
        verify { sessionManager.setInboxShareIds(setOf(ITEM_1)) }
    }

    @Test
    fun `refresh_openSealThrows_skippedQuietly`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val share = inbox("s", ITEM_1, wrapped = "bad-seal", ownerEmail = null)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        every { noteCrypto.openSeal(any(), any()) } throws RuntimeException("bad nonce")

        val n = repository.refresh()

        assertEquals(0, n)
        assertNull(repository.getContentKey(ITEM_1))
    }

    @Test
    fun `refresh_wrongKeySize_dropped`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val share = inbox("s", ITEM_1, wrapped = "sealed", ownerEmail = null)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        every { noteCrypto.openSeal(any(), any()) } returns ByteArray(16) // wrong size

        val n = repository.refresh()

        assertEquals(0, n)
        assertNull(repository.getContentKey(ITEM_1))
    }

    // =============================================================================
    // materialize-into-Room TESTS (shared notes render without depending on the
    // /sync/pull watermark path)
    // =============================================================================

    @Test
    fun `refresh_materializesSharedNoteIntoRoom`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val share = inbox("s", ITEM_1, wrapped = "sealed", ownerEmail = "alice@x.y")
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        val plaintext = """{"title":"Groceries","body":"milk","is_pinned":true,"sort_order":3}"""
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2("nonce", "enc", testContentKey, ITEM_1) } returns plaintext

        repository.refresh()

        val slot = slot<ItemEntity>()
        coVerify(exactly = 1) { itemDao.upsert(capture(slot)) }
        val e = slot.captured
        assertEquals(ITEM_1, e.id)
        // The decrypted plaintext is stored verbatim — this is the crux of
        // materializing (shared note content lands in Room from the
        // inbox). Title/pinned/sortOrder are parsed via org.json, which
        // returns defaults under this JVM runner (isReturnDefaultValues);
        // that extraction is the same path SyncRepository uses, so it's
        // not asserted here.
        assertEquals(plaintext, e.decryptedContent)
        assertEquals(1, e.itemType) // note
        // owned-by-sharer (mirrors /sync/pull), not dirty, no vault wrap
        assertEquals("00000000-0000-0000-0000-000000000001", e.userId)
        assertFalse(e.isDirty)
        assertNull(e.wrappedContentKey)
        assertNull(e.wrappedContentKeyNonce)
    }

    @Test
    fun `refresh_dirtyLocalRow_notClobbered`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val share = inbox("s", ITEM_1, wrapped = "sealed", ownerEmail = "alice@x.y")
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns
            """{"title":"Owner copy"}"""
        // Recipient has unpushed local edits to this shared note.
        coEvery { itemDao.getById(ITEM_1) } returns dirtyRow(ITEM_1)

        repository.refresh()

        // Content key still available for the edit/sync path…
        assertNotNull(repository.getContentKey(ITEM_1))
        // …but the owner's copy must NOT overwrite the dirty local row.
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `refresh_decryptFails_noUpsert`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val share = inbox("s", ITEM_1, wrapped = "sealed", ownerEmail = null)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKey(any(), any(), any()) } returns null

        val n = repository.refresh()

        // Key was unwrapped (count=1) but nothing decryptable → no row.
        assertEquals(1, n)
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    // =============================================================================
    // TOFU sender-key pinning: verify against the PINNED key, never the
    // server-supplied one; pin on first use.
    // =============================================================================

    @Test
    fun `refresh_pinnedSender_verifiesAgainstPinnedKey_notServerKey`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getUserId() } returns myUserId

        val pinnedSigning = "aa".repeat(32)   // the key we trust (TOFU)
        val serverSigning = "bb".repeat(32)   // a substituted key the server returns
        every { pinStore.getPinned("00000000-0000-0000-0000-000000000001", myUserId) } returns
            SenderKeyPinStore.PinRecord(pinnedSigning, "cc".repeat(32), 0L)

        val share = inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@b.c", sig = "ff".repeat(64))
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        stubLookup("a@b.c", signing = serverSigning, sharing = "dd".repeat(32))
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) } returns WrapVerify(true, 1u)

        repository.refresh()

        // CRUX: verification MUST use the PINNED signing key…
        verify { noteCrypto.verifyNoteShareWrapGen(eq(pinnedSigning), any(), any(), any(), any(), any(), any()) }
        // …and NEVER the server-returned one (else a malicious server could
        // forge a "verified" share with an attacker key + matching signature).
        verify(exactly = 0) { noteCrypto.verifyNoteShareWrapGen(eq(serverSigning), any(), any(), any(), any(), any(), any()) }
        // A pinned sender is never silently re-pinned on a normal share.
        verify(exactly = 0) { pinStore.pin(any(), any(), any(), any()) }
    }

    @Test
    fun `refresh_firstUse_noPin_tofuPinsAfterUnwrap_andSkipsVerify`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getUserId() } returns myUserId
        every { pinStore.getPinned(any(), myUserId) } returns null // unpinned (first use)

        val serverSigning = "bb".repeat(32)
        val serverSharing = "dd".repeat(32)
        val share = inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@b.c", sig = "ff".repeat(64))
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        stubLookup("a@b.c", signing = serverSigning, sharing = serverSharing)
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey

        repository.refresh()

        // No pin yet → NEVER verify against a server-supplied key (proves nothing).
        verify(exactly = 0) { noteCrypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) }
        // …and TOFU-pin the sender's directory keys after the successful unwrap.
        verify { pinStore.pin("00000000-0000-0000-0000-000000000001", serverSigning, serverSharing, myUserId) }
    }

    // =============================================================================
    // GRADUATED FAIL-CLOSED (matching the shared crypto SDK's key store):
    // once a sender is PINNED, a stripped or invalid wrap signature drops the
    // share entirely (no unwrap, no materialize, not visible). GRACE only for
    // an unpinned first-use sender.
    // =============================================================================

    @Test
    fun `refresh_pinnedSender_strippedSignature_dropped`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getUserId() } returns myUserId

        // We already hold this sender's pin, so a legit wrap from them would
        // carry a signature — but this one has NONE (a relay stripped it).
        val pinnedSigning = "aa".repeat(32)
        every { pinStore.getPinned("00000000-0000-0000-0000-000000000001", myUserId) } returns
            SenderKeyPinStore.PinRecord(pinnedSigning, "cc".repeat(32), 0L)

        val share = inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@b.c", sig = null)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        stubLookup("a@b.c", signing = pinnedSigning, sharing = "cc".repeat(32))
        // Stub the unwrap so that IF the drop failed and we reached it, the
        // content key would populate and the assertions below would catch it.
        every { noteCrypto.openSeal(any(), any()) } returns testContentKey

        val n = repository.refresh()

        // Dropped: no content key, not visible, never unwrapped, not materialized.
        assertEquals(0, n)
        assertNull(repository.getContentKey(ITEM_1))
        verify { sessionManager.setInboxShareIds(emptySet()) }
        verify(exactly = 0) { noteCrypto.openSeal(any(), any()) }
        // A missing signature is rejected before we ever call verify.
        verify(exactly = 0) { noteCrypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `refresh_pinnedSender_invalidSignature_dropped`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getUserId() } returns myUserId

        val pinnedSigning = "aa".repeat(32)
        every { pinStore.getPinned("00000000-0000-0000-0000-000000000001", myUserId) } returns
            SenderKeyPinStore.PinRecord(pinnedSigning, "cc".repeat(32), 0L)

        // Signature present, but it does NOT verify against the pinned key —
        // forged / tampered / server-substituted. Fail closed.
        val share = inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@b.c", sig = "ff".repeat(64))
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        stubLookup("a@b.c", signing = pinnedSigning, sharing = "cc".repeat(32))
        every { noteCrypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) } returns WrapVerify(false, 0u)
        every { noteCrypto.openSeal(any(), any()) } returns testContentKey

        val n = repository.refresh()

        // Verified against the PINNED key, it failed, and the share was dropped.
        verify { noteCrypto.verifyNoteShareWrapGen(eq(pinnedSigning), any(), any(), any(), any(), any(), any()) }
        assertEquals(0, n)
        assertNull(repository.getContentKey(ITEM_1))
        verify { sessionManager.setInboxShareIds(emptySet()) }
        verify(exactly = 0) { noteCrypto.openSeal(any(), any()) }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `refresh_pinnedSender_malformedSignature_dropped_validStillMaterializes`() = runTest {
        // Fail-CLOSED on an unparseable signature. verifyNoteShareWrapGen
        // returns null (NOT a verified=false result) when the SDK can't
        // PARSE the signature — DefaultNoteCrypto maps note-collab-sdk's
        // Err(bad-hex / wrong-length) to null. The signature is the one
        // attacker-controlled input in the pinned branch (the pubkey is
        // our pinned one), so a malformed sig from a PINNED sender is a
        // malicious server bypassing verification and MUST be dropped,
        // exactly like an invalid one. A check written as `if (ok ==
        // false)` would let a null slip through, and the attacker's wrap
        // would be opened and materialized as coming from the trusted
        // pinned sender. This test drives BOTH shares through one inbox:
        // the malformed-sig one is dropped, the validly-signed one still
        // materializes.
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getUserId() } returns myUserId

        val pinnedSigning = "aa".repeat(32)
        every { pinStore.getPinned("00000000-0000-0000-0000-000000000001", myUserId) } returns
            SenderKeyPinStore.PinRecord(pinnedSigning, "cc".repeat(32), 0L)

        val malformedSig = "zz"            // non-hex, wrong length → SDK Err → null
        val validSig = "ff".repeat(64)
        val bad = inbox("s-bad", ITEM_BAD, wrapped = "sealed-bad", ownerEmail = "a@b.c", sig = malformedSig)
        val good = inbox("s-good", ITEM_GOOD, wrapped = "sealed-good", ownerEmail = "a@b.c", sig = validSig)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(bad, good)))
        )
        stubLookup("a@b.c", signing = pinnedSigning, sharing = "cc".repeat(32))

        // Malformed signature → wrapper can't parse it → null (the exact value
        // DefaultNoteCrypto yields for the SDK's Err on that input).
        every {
            noteCrypto.verifyNoteShareWrapGen(any(), eq(malformedSig), any(), any(), any(), any(), any())
        } returns null
        // Valid signature → verifies against the pinned key.
        every {
            noteCrypto.verifyNoteShareWrapGen(any(), eq(validSig), any(), any(), any(), any(), any())
        } returns WrapVerify(true, 1u)
        every { noteCrypto.openSeal("sealed-good", any()) } returns testContentKey
        every {
            noteCrypto.decryptNoteWithContentKeyV2("nonce", "enc", testContentKey, ITEM_GOOD)
        } returns """{"title":"ok"}"""
        // If the malformed share slipped through, this stub would let it unwrap
        // and the assertions below would catch the leak.
        every { noteCrypto.openSeal("sealed-bad", any()) } returns testContentKey

        val n = repository.refresh()

        // The malformed-signature share is DROPPED: not visible, no content key,
        // never unwrapped, never materialized into Room.
        assertNull(repository.getContentKey(ITEM_BAD))
        verify { sessionManager.setInboxShareIds(match { ITEM_BAD !in it }) }
        verify(exactly = 0) { noteCrypto.openSeal("sealed-bad", any()) }
        coVerify(exactly = 0) { itemDao.upsert(match { it.id == ITEM_BAD }) }

        // …while the validly-signed share from the SAME pinned sender still
        // materializes normally.
        assertEquals(1, n)
        assertNotNull(repository.getContentKey(ITEM_GOOD))
        verify { sessionManager.setInboxShareIds(match { ITEM_GOOD in it }) }
        coVerify(exactly = 1) { itemDao.upsert(match { it.id == ITEM_GOOD }) }
    }

    @Test
    fun `refresh_firstUse_noPin_unsignedWrap_stillMaterializesAndPins`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getUserId() } returns myUserId
        every { pinStore.getPinned(any(), myUserId) } returns null // first use — unpinned

        // No pin AND no signature: this is the genuine first-use/legacy grace
        // path — we still unwrap, show it, and TOFU-pin the observed key so
        // subsequent shares from this sender ARE held to the higher bar.
        val serverSigning = "bb".repeat(32)
        val serverSharing = "dd".repeat(32)
        val share = inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@b.c", sig = null)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        stubLookup("a@b.c", signing = serverSigning, sharing = serverSharing)
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey

        val n = repository.refresh()

        // Materialized (content key present, visible) and never verified.
        assertEquals(1, n)
        assertNotNull(repository.getContentKey(ITEM_1))
        verify { sessionManager.setInboxShareIds(setOf(ITEM_1)) }
        verify(exactly = 0) { noteCrypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) }
        // …and TOFU-pinned on the successful unwrap.
        verify { pinStore.pin("00000000-0000-0000-0000-000000000001", serverSigning, serverSharing, myUserId) }
    }

    // =============================================================================
    // un-share / delete propagation
    // =============================================================================

    @Test
    fun `sharesToRemove returns ids that left the inbox`() {
        assertEquals(
            setOf("gone"),
            SharesInboxRepository.sharesToRemove(
                previous = setOf("keep", "gone"),
                live = setOf("keep", "new"),
            ),
        )
    }

    @Test
    fun `sharesToRemove is empty on first run (no prior set)`() {
        assertTrue(
            SharesInboxRepository.sharesToRemove(emptySet(), setOf("a", "b")).isEmpty(),
        )
    }

    @Test
    fun `refresh removes the Room row of a share that left the inbox`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        // Last refresh saw item-1 + item-gone; this inbox returns only item-1.
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_1, ITEM_GONE)
        val share = inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@b.c")
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        // The vanished share's materialized row: its sender's, clean, no wrap of ours.
        coEvery { itemDao.getById(ITEM_GONE) } returns ItemEntity(
            id = ITEM_GONE, userId = "30000000-0000-4000-8000-000000000003", itemType = 1,
            encryptedContent = "enc", contentNonce = "nonce",
            createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )

        repository.refresh()

        // The vanished share's materialized row is hard-deleted…
        coVerify(exactly = 1) { itemDao.deleteById(ITEM_GONE) }
        coVerify(exactly = 0) { itemDao.deleteById(ITEM_1) }
        // …and the live inbox set is persisted for the next diff.
        verify { sessionManager.setInboxShareIds(setOf(ITEM_1)) }
    }

    /**
     * The server answers with at most 500 rows, newest first. A share missing from a FULL
     * page has not left, it is past the cut: its row stays, and its id is kept for the next
     * answer that is the whole list.
     */
    @Test
    fun `refresh removes nothing when the inbox could not be read to its end`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        val full = page(SharesInboxRepository.INBOX_PAGE_LIMIT)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(ApiResponse(success = true, data = bareList(full)))
        stubPageAfter(PAGE - 1, null)
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey
        coEvery { itemDao.getById(ITEM_GONE) } returns ItemEntity(
            id = ITEM_GONE, userId = "30000000-0000-4000-8000-000000000003", itemType = 1,
            encryptedContent = "enc", contentNonce = "nonce",
            createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )

        repository.refresh()

        coVerify(exactly = 0) { itemDao.deleteById(any()) }
        // What was known before is kept beside this page's ids.
        verify { sessionManager.setInboxShareIds(match { ITEM_GONE in it && it.size == SharesInboxRepository.INBOX_PAGE_LIMIT + 1 }) }
    }

    @Test
    fun `refresh removes what left once the whole inbox has been read, over two pages`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE, itemIdOf(PAGE + 1))
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(page(PAGE)))
        )
        stubPageAfter(PAGE - 1, page(2, from = PAGE))
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey
        coEvery { itemDao.getById(ITEM_GONE) } returns ItemEntity(
            id = ITEM_GONE, userId = "30000000-0000-4000-8000-000000000003", itemType = 1,
            encryptedContent = "enc", contentNonce = "nonce",
            createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )

        repository.refresh()

        // The share that really left goes; the one on the second page, which a
        // one-page reading took for gone, stays.
        coVerify(exactly = 1) { itemDao.deleteById(ITEM_GONE) }
        coVerify(exactly = 0) { itemDao.deleteById(itemIdOf(PAGE + 1)) }
        verify { sessionManager.setInboxShareIds(match { ITEM_GONE !in it && itemIdOf(PAGE + 1) in it && it.size == PAGE + 2 }) }
    }

    @Test
    fun `refresh still removes what left when the answer is one row short of the page`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(page(SharesInboxRepository.INBOX_PAGE_LIMIT - 1)))
        )
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey
        coEvery { itemDao.getById(ITEM_GONE) } returns ItemEntity(
            id = ITEM_GONE, userId = "30000000-0000-4000-8000-000000000003", itemType = 1,
            encryptedContent = "enc", contentNonce = "nonce",
            createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )

        repository.refresh()

        coVerify(exactly = 1) { itemDao.deleteById(ITEM_GONE) }
        verify { sessionManager.setInboxShareIds(match { ITEM_GONE !in it }) }
    }

    @Test
    fun `refresh deletes nothing when the inbox is unchanged`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_1)
        val share = inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@b.c")
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        // A row that a removal WOULD take (with none here, the checks below could not fail).
        coEvery { itemDao.getById(ITEM_1) } returns shareRow(ITEM_1, owner = "00000000-0000-0000-0000-000000000001")

        repository.refresh()

        coVerify(exactly = 0) { itemDao.deleteById(any()) }
        assertEquals(0L, repository.rowsRemoved)
    }

    /** A known share that left the inbox and has no row here any more is done with: forgotten. */
    @Test
    fun `a share that left and has no row here is forgotten, not kept for another try`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(emptyList(), hasMore = false))
        )
        coEvery { itemDao.getById(ITEM_GONE) } returns null

        repository.refresh()

        verify { sessionManager.setInboxShareIds(emptySet()) }
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
    }

    /**
     * Once a share is declined, nothing of it should show up. A declined notebook's shares
     * leave the inbox, their rows leave the database, and the home-screen widgets read
     * that database: the sync has to learn that rows went (SyncRepository.sync).
     */
    @Test
    fun `refresh counts the rows it took out, and only those`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val kept = "10000000-0000-4000-8000-0000000000aa"       // dirty: an unsynced edit stays
        val failing = "10000000-0000-4000-8000-0000000000bb"    // the delete throws
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_1, ITEM_GONE, kept, failing)
        val share = inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@b.c")
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(listOf(share), hasMore = false))
        )
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        fun row(id: String, dirty: Boolean = false) = ItemEntity(
            id = id, userId = "30000000-0000-4000-8000-000000000003", itemType = 1,
            encryptedContent = "enc", contentNonce = "nonce", isDirty = dirty,
            createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )
        coEvery { itemDao.getById(ITEM_GONE) } returns row(ITEM_GONE)
        coEvery { itemDao.getById(kept) } returns row(kept, dirty = true)
        coEvery { itemDao.getById(failing) } returns row(failing)
        coEvery { itemDao.deleteById(failing) } throws RuntimeException("disk")

        repository.refresh()

        coVerify(exactly = 1) { itemDao.deleteById(ITEM_GONE) }
        assertEquals(1L, repository.rowsRemoved)
        // The row whose delete failed is still in the database, so its id stays in the
        // stored set and the next refresh tries again; forgotten, the row (and its title on
        // the widget) would stay for good. The removed one and the kept one are done with.
        verify { sessionManager.setInboxShareIds(setOf(ITEM_1, failing)) }
    }

    /** ...and the next refresh does try again, and then forgets it. */
    @Test
    fun `a row whose removal failed is removed by the next refresh`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val stored = slot<Set<String>>()
        every { sessionManager.setInboxShareIds(capture(stored)) } just Runs
        every { sessionManager.getInboxShareIds() } answers { if (stored.isCaptured) stored.captured else setOf(ITEM_GONE) }
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(emptyList(), hasMore = false))
        )
        coEvery { itemDao.getById(ITEM_GONE) } returns shareRow(ITEM_GONE)
        coEvery { itemDao.deleteById(ITEM_GONE) } throws RuntimeException("disk")

        repository.refresh()
        assertEquals(setOf(ITEM_GONE), stored.captured)
        assertEquals(0L, repository.rowsRemoved)

        coEvery { itemDao.deleteById(ITEM_GONE) } just Runs
        repository.refresh()

        assertEquals(emptySet<String>(), stored.captured)
        assertEquals(1L, repository.rowsRemoved)
    }

    /** A cancellation is not a failed delete: the removal loop stops on it rather than
     * logging it and carrying on.
     */
    @Test
    fun `a refresh that is cancelled while removing rows stops there, and forgets none of the ids`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(emptyList(), hasMore = false))
        )
        coEvery { itemDao.getById(ITEM_GONE) } returns shareRow(ITEM_GONE)
        coEvery { itemDao.deleteById(ITEM_GONE) } throws kotlinx.coroutines.CancellationException("the sync was stopped")

        try {
            repository.refresh()
            fail("the cancellation must leave refresh()")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }

        verify(exactly = 0) { sessionManager.setInboxShareIds(any()) }
    }

    /**
     * The count only grows: SyncRepository compares it with what it read when it last told the
     * widgets. "What the LAST refresh removed" would be set back to zero by any later refresh,
     * one that could not even start included, and two syncs can overlap.
     */
    @Test
    fun `the count only grows - a later refresh that removes nothing leaves it`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(emptyList(), hasMore = false))
        )
        coEvery { itemDao.getById(ITEM_GONE) } returns shareRow(ITEM_GONE)
        repository.refresh()
        assertEquals(1L, repository.rowsRemoved)

        // The next refresh cannot even start (no key).
        every { sessionManager.getSharingPrivateKey() } returns null
        repository.refresh()
        assertEquals(1L, repository.rowsRemoved)

        // And one that removes another row adds to it.
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        repository.refresh()
        assertEquals(2L, repository.rowsRemoved)
    }

    // =============================================================================
    // AN ANSWER THAT IS NOT ONE
    // =============================================================================

    /**
     * A 2xx answer whose envelope says `success: false` must not be read for its `data`:
     * `{"success":false,"data":[]}` taken for an empty inbox read to its end would prune every
     * acceptance and remove every known share's row.
     */
    @Test
    fun `an answer that says it failed is not an inbox, whatever it carries`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        coEvery { itemDao.getById(ITEM_GONE) } returns shareRow(ITEM_GONE)
        for (data in listOf(bareList(emptyList()), inboxPage(emptyList(), hasMore = false))) {
            coEvery { noteApi.listInbox() } returns RetrofitResponse.success(ApiResponse(success = false, data = data))

            assertEquals(0, repository.refresh())

            assertNull(repository.listedNotebookIds)
        }
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
        verify(exactly = 0) { sessionManager.setInboxShareIds(any()) }

        // The control: the same answer saying it SUCCEEDED is an empty inbox read to its end.
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(emptyList(), hasMore = false))
        )
        repository.refresh()
        assertEquals(emptySet<String>(), repository.listedNotebookIds)
        coVerify(exactly = 1) { itemDao.deleteById(ITEM_GONE) }
    }

    @Test
    fun `a later page that says it failed ends the reading there, incomplete`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        coEvery { itemDao.getById(ITEM_GONE) } returns shareRow(ITEM_GONE)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(page(2), hasMore = true))
        )
        coEvery { noteApi.listInbox("2024-01-01T00:00:00Z", shareIdOf(1)) } returns RetrofitResponse.success(
            ApiResponse(success = false, data = inboxPage(emptyList(), hasMore = false))
        )
        every { noteCrypto.openSeal("sealed-b64", any()) } returns testContentKey

        assertEquals(2, repository.refresh())

        assertNull(repository.listedNotebookIds)
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
    }

    /**
     * The server reads the parameter by this name (`page_info`, beside `before` and `before_id`).
     * Under any other name it answers with the bare list, and this app would silently go back to
     * guessing the inbox's end from the length of a page. The names are part of the server's API.
     */
    @Test
    fun `the inbox is asked for by the server's own parameter names, and always as a page`() = runTest {
        val method = NoteApi::class.java.declaredMethods.single { it.name == "listInbox" }
        val names = method.parameterAnnotations.mapNotNull { on ->
            on.filterIsInstance<retrofit2.http.Query>().singleOrNull()?.value
        }
        assertEquals(listOf("before", "before_id", "page_info"), names)

        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox(any(), any(), any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(emptyList(), hasMore = false))
        )
        repository.refresh()
        coVerify(exactly = 1) { noteApi.listInbox(null, null, true) }
    }

    /** A page like this ends the reading: taking the cursor from it before the emptiness check
     * would throw out of refresh().
     */
    @Test
    fun `a page that says more follows and brings nothing ends the reading, incomplete`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        coEvery { itemDao.getById(ITEM_GONE) } returns shareRow(ITEM_GONE)
        coEvery { noteApi.listInbox(any(), any(), any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(emptyList(), hasMore = true))
        )

        assertEquals(0, repository.refresh())

        assertNull(repository.listedNotebookIds)
        coVerify(exactly = 1) { noteApi.listInbox(any(), any(), any()) }
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
    }

    // =============================================================================
    // NOTES OF A DECLINED NOTEBOOK
    // =============================================================================
    //
    // Once a share is declined, nothing of it should show up. The server keeps a declined
    // notebook's later notes away when the note's row names its notebook. One whose row does
    // not arrives like any other share, and must not be written to the database the
    // home-screen widgets read. The rule is DeclinedNotebooks; these are the refresh keeping
    // it.

    private val DECLINED_AT = "2026-10-07T10:00:00.000000Z"
    private val AFTER_THE_DECLINE = "2026-10-07T10:05:00.000000Z"
    private val BEFORE_THE_DECLINE = "2026-10-07T09:55:00.000000Z"

    /**
     * An inbox of two notes: "inside" (ITEM_1) says it is in NB_1 and was shared at [sharedAt];
     * "outside" (ITEM_2) is in no notebook. [declined] is what the first page says was declined
     * (null: it does not say). Returns what the refresh writes to the database.
     */
    private fun inboxWithANoteOf(
        declined: Map<String, String>?,
        sharedAt: String = AFTER_THE_DECLINE,
        notebookInContent: String = NB_1,
        more: List<InboxShareDto> = emptyList(),
    ): MutableList<ItemEntity> {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val rows = listOf(
            inbox("inside", ITEM_1, wrapped = "sealed", ownerEmail = "a@x.y").copy(createdAt = sharedAt),
            inbox("outside", ITEM_2, wrapped = "sealed", ownerEmail = "a@x.y").copy(createdAt = AFTER_THE_DECLINE),
        ) + more
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(ApiResponse(
            success = true,
            data = if (declined == null) inboxPage(rows, hasMore = false) else inboxPage(rows, hasMore = false, declined = declined),
        ))
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), NB_1) } returns """{"title":"Recipes"}"""
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), ITEM_1) } returns
            """{"title":"in","notebook_id":"$notebookInContent"}"""
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), ITEM_2) } returns """{"title":"out"}"""
        val written = mutableListOf<ItemEntity>()
        coEvery { itemDao.upsert(capture(written)) } just Runs
        return written
    }

    @Test
    fun `a note shared after its notebook was declined is not written to the database`() = runTest {
        val written = inboxWithANoteOf(declined = mapOf(NB_1 to DECLINED_AT))

        repository.refresh()

        assertEquals(listOf(ITEM_2), written.map { it.id })
        assertTrue(repository.isKeptAway(ITEM_1))
        // Under any spelling of the id (this one has no letter whose case could differ).
        assertTrue(repository.isKeptAway("{$ITEM_1}"))
        assertTrue(repository.isKeptAway(ITEM_1.replace("-", "")))
        assertFalse(repository.isKeptAway(ITEM_2))
        assertFalse(repository.isKeptAway("not-an-id"))
    }

    /** The row was put there by an older version, or by a refresh the server did not tell: it
     * goes, and is counted.
     */
    @Test
    fun `and its row is taken out when an earlier refresh put it there`() = runTest {
        inboxWithANoteOf(declined = mapOf(NB_1 to DECLINED_AT))
        coEvery { itemDao.getById(ITEM_1) } returns shareRow(ITEM_1)

        repository.refresh()

        coVerify(exactly = 1) { itemDao.deleteById(ITEM_1) }
        coVerify(exactly = 0) { itemDao.deleteById(ITEM_2) }
        assertEquals(1L, repository.rowsRemoved)
    }

    /** The same shape rule as any removal: an unsynced edit stays, and so does anything of this account's. */
    @Test
    fun `but never a row with an unsynced edit, nor one of this account's own`() = runTest {
        val written = inboxWithANoteOf(declined = mapOf(NB_1 to DECLINED_AT))
        coEvery { itemDao.getById(ITEM_1) } returns shareRow(ITEM_1).copy(isDirty = true)
        repository.refresh()
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
        assertEquals(0L, repository.rowsRemoved)
        // Not written over either.
        assertEquals(listOf(ITEM_2), written.map { it.id })
    }

    /**
     * What the reader already held when they declined was shared with them by itself. Its owner
     * filing it into that notebook later takes nothing away (the server's rule, and the web's).
     */
    @Test
    fun `a note shared before the decline is the reader's in its own right, and is written`() = runTest {
        val written = inboxWithANoteOf(declined = mapOf(NB_1 to DECLINED_AT), sharedAt = BEFORE_THE_DECLINE)
        coEvery { itemDao.getById(ITEM_1) } returns shareRow(ITEM_1, owner = "00000000-0000-0000-0000-000000000001")

        repository.refresh()

        assertEquals(setOf(ITEM_1, ITEM_2), written.map { it.id }.toSet())
        assertFalse(repository.isKeptAway(ITEM_1))
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
    }

    @Test
    fun `the note's notebook is found under any spelling of its id`() = runTest {
        val written = inboxWithANoteOf(declined = mapOf("{$NB_1}" to DECLINED_AT), notebookInContent = NB_1.replace("-", ""))

        repository.refresh()

        assertEquals(listOf(ITEM_2), written.map { it.id })
    }

    /** The row is the newer fact: on offer again, the notebook is a question still unanswered. */
    @Test
    fun `a notebook the same answer offers is not declined, and its notes are written`() = runTest {
        // Offered again and ACCEPTED: the declined list does not count, and nothing
        // holds the note back. (Offered and unanswered, it is held: see the next
        // section.)
        acceptedNotebooks = setOf(NB_1)
        val written = inboxWithANoteOf(
            declined = mapOf(NB_1 to DECLINED_AT),
            more = listOf(inbox("nbshare", NB_1, wrapped = "sealed", ownerEmail = "a@x.y", itemType = 2)),
        )

        repository.refresh()

        assertEquals(setOf(ITEM_1, ITEM_2), written.map { it.id }.toSet())
        assertFalse(repository.isKeptAway(ITEM_1))
    }

    /** Null is "not told", never "none": an older server, or the server's own lookup failing. */
    @Test
    fun `an answer that does not say keeps what the one before said`() = runTest {
        inboxWithANoteOf(declined = mapOf(NB_1 to DECLINED_AT))
        repository.refresh()

        val written = inboxWithANoteOf(declined = null)
        repository.refresh()

        assertEquals(listOf(ITEM_2), written.map { it.id })
        assertTrue(repository.isKeptAway(ITEM_1))
    }

    @Test
    fun `an answer that says nothing is declined any more brings the note back`() = runTest {
        inboxWithANoteOf(declined = mapOf(NB_1 to DECLINED_AT))
        repository.refresh()
        assertTrue(repository.isKeptAway(ITEM_1))

        val written = inboxWithANoteOf(declined = emptyMap())
        repository.refresh()

        assertEquals(setOf(ITEM_1, ITEM_2), written.map { it.id }.toSet())
        assertFalse(repository.isKeptAway(ITEM_1))
    }

    @Test
    fun `nothing is kept away when the server names no declined notebook, or another one`() = runTest {
        // Their rows are here, so a removal COULD happen (with none, the last check could not fail).
        coEvery { itemDao.getById(ITEM_1) } returns shareRow(ITEM_1, owner = "00000000-0000-0000-0000-000000000001")
        coEvery { itemDao.getById(ITEM_2) } returns shareRow(ITEM_2, owner = "00000000-0000-0000-0000-000000000001")
        for (declined in listOf(null, emptyMap(), mapOf("20000000-0000-4000-8000-0000000000ee" to DECLINED_AT))) {
            val written = inboxWithANoteOf(declined = declined)
            repository.refresh()
            assertEquals("declined=$declined", setOf(ITEM_1, ITEM_2), written.map { it.id }.toSet())
        }
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
    }

    /** One account's declines are not the next one's: this object outlives a sign-out. */
    @Test
    fun `what was declined is forgotten at a wipe`() = runTest {
        inboxWithANoteOf(declined = mapOf(NB_1 to DECLINED_AT))
        repository.refresh()
        assertTrue(repository.isKeptAway(ITEM_1))

        repository.wipe()
        assertFalse(repository.isKeptAway(ITEM_1))

        // And the next account's first answer that does not say starts from nothing.
        val written = inboxWithANoteOf(declined = null)
        repository.refresh()
        assertEquals(setOf(ITEM_1, ITEM_2), written.map { it.id }.toSet())
    }

    /**
     * Only the FIRST page describes the inbox. A later page says nothing of declines, and that is
     * not the inbox turning "not told".
     */
    @Test
    fun `the first page's word on what was declined stands when later pages do not say`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val newest = inbox("inside", ITEM_1, wrapped = "sealed", ownerEmail = "a@x.y").copy(createdAt = AFTER_THE_DECLINE)
        val older = inbox("outside", ITEM_2, wrapped = "sealed", ownerEmail = "a@x.y").copy(createdAt = BEFORE_THE_DECLINE)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(ApiResponse(
            success = true, data = inboxPage(listOf(newest), hasMore = true, declined = mapOf(NB_1 to DECLINED_AT)),
        ))
        coEvery { noteApi.listInbox(AFTER_THE_DECLINE, "inside") } returns RetrofitResponse.success(ApiResponse(
            success = true, data = inboxPage(listOf(older), hasMore = false),
        ))
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), ITEM_1) } returns
            """{"title":"in","notebook_id":"$NB_1"}"""
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), ITEM_2) } returns """{"title":"out"}"""
        val written = mutableListOf<ItemEntity>()
        coEvery { itemDao.upsert(capture(written)) } just Runs

        repository.refresh()

        assertEquals(listOf(ITEM_2), written.map { it.id })
        assertTrue(repository.isKeptAway(ITEM_1))
        assertNotNull(repository.listedNotebookIds)
    }

    /**
     * Two shares of one note (the reader has two addresses): one older than the decline, one
     * later. The older one is the reader's in its own right, so the note stays.
     */
    @Test
    fun `a note with a share older than the decline stays, whatever its later share is`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        // Its row is here, so a removal COULD happen (with none, the check below could not fail).
        coEvery { itemDao.getById(ITEM_1) } returns shareRow(ITEM_1, owner = "00000000-0000-0000-0000-000000000001")
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(ApiResponse(success = true, data = inboxPage(
            listOf(
                inbox("later", ITEM_1, wrapped = "sealed", ownerEmail = "a@x.y").copy(createdAt = AFTER_THE_DECLINE),
                inbox("older", ITEM_1, wrapped = "sealed", ownerEmail = "a@x.y").copy(createdAt = BEFORE_THE_DECLINE),
            ),
            hasMore = false, declined = mapOf(NB_1 to DECLINED_AT),
        )))
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), ITEM_1) } returns
            """{"title":"in","notebook_id":"$NB_1"}"""
        val written = mutableListOf<ItemEntity>()
        coEvery { itemDao.upsert(capture(written)) } just Runs

        repository.refresh()

        assertFalse(repository.isKeptAway(ITEM_1))
        assertTrue(written.isNotEmpty() && written.all { it.id == ITEM_1 })
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
    }

    // =============================================================================
    // getContentKey TESTS
    // =============================================================================

    @Test
    fun `getContentKey_unknownId_returnsNull`() {
        assertNull(repository.getContentKey(NEVER_SEEN))
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    /** A row as a share materialises one: another account's, not dirty, no wrap of ours. */
    private fun shareRow(id: String, owner: String = "30000000-0000-4000-8000-000000000003") = ItemEntity(
        id = id, userId = owner, itemType = 1,
        encryptedContent = "enc", contentNonce = "nonce",
        createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
    )

    private fun inbox(
        shareId: String,
        itemId: String,
        wrapped: String?,
        ownerEmail: String?,
        sig: String? = null,
        itemType: Int = 1,
    ) = InboxShareDto(
        shareId = shareId,
        itemId = itemId,
        ownerId = "00000000-0000-0000-0000-000000000001",
        permission = "edit",
        wrappedKey = wrapped,
        sharerPublicKey = "pub-hex",
        ownerEmail = ownerEmail,
        wrapSignature = sig,
        itemType = itemType,
        encryptedContent = "enc",
        contentNonce = "nonce",
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
    )

    /** Stub the batch sender-key directory lookup to return one entry. */
    private fun stubLookup(email: String, signing: String?, sharing: String?) {
        coEvery { noteApi.lookupPublicKeys(any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = PublicKeyLookupResponse(
                    users = listOf(
                        PublicKeyLookupEntry(
                            email = email,
                            userId = "00000000-0000-0000-0000-000000000001",
                            sharingPublicKey = sharing,
                            signingPublicKey = signing,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun dirtyRow(id: String) = ItemEntity(
        id = id,
        userId = "00000000-0000-0000-0000-000000000001",
        itemType = 1,
        encryptedContent = "local-enc",
        contentNonce = "local-nonce",
        decryptedContent = """{"title":"My local edit"}""",
        title = "My local edit",
        version = 2,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2024-02-01T00:00:00Z"),
        isDirty = true,
    )

    // =============================================================================
    // NOTEBOOK shares are NOT notes
    // =============================================================================

    /**
     * The notebook object carries a collection's name and its roster, not a
     * document. Materializing it would put a phantom note titled with the
     * notebook's name in the recipient's list.
     */
    @Test
    fun `refresh_doesNotMaterializeASharedNotebookAsANote`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val share = inbox("s", NB_1, wrapped = "sealed", ownerEmail = "alice@x.y", itemType = 2)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2("nonce", "enc", testContentKey, NB_1) } returns
            """{"title":"Recipes","members":[]}"""

        repository.refresh()

        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    /**
     * The KEY is still kept: a Shared Notebooks view needs it to read the
     * notebook's name and roster. Only the note row is withheld.
     */
    @Test
    fun `refresh_stillKeepsTheNotebookContentKey`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val share = inbox("s", NB_1, wrapped = "sealed", ownerEmail = "alice@x.y", itemType = 2)
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(share)))
        )
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns
            """{"title":"Recipes","members":[]}"""

        repository.refresh()

        assertNotNull(repository.getContentKey(NB_1))
    }

    /** A mixed page must not lose the notes alongside the notebook. */
    @Test
    fun `refresh_materializesTheNotesInAPageThatAlsoHasANotebook`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(
                inbox("s1", NB_1, wrapped = "sealed", ownerEmail = "a@x.y", itemType = 2),
                inbox("s2", ITEM_1, wrapped = "sealed", ownerEmail = "a@x.y"),
            )))
        )
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns
            """{"title":"t"}"""

        repository.refresh()

        val slot = slot<ItemEntity>()
        coVerify(exactly = 1) { itemDao.upsert(capture(slot)) }
        assertEquals(ITEM_1, slot.captured.id)
    }

    /**
     * Gson ignores Kotlin default values, so a server that omits `item_type`
     * leaves the field at the JVM default of ZERO, not 1. Reading it raw would
     * classify every share as neither note nor notebook.
     */
    @Test
    fun `anAbsentItemTypeReadsAsANote`() {
        val raw = com.google.gson.Gson().fromJson(
            """{"share_id":"s","item_id":"i","owner_id":"o","permission":"view",
                "encrypted_content":"e","content_nonce":"n",
                "created_at":"2024-01-01T00:00:00Z","updated_at":"2024-01-01T00:00:00Z"}""",
            InboxShareDto::class.java,
        )
        assertEquals(0, raw.itemType)
        assertEquals(1, raw.itemTypeOrNote)
        assertFalse(raw.isNotebook)
    }

    // =============================================================================
    // The Shared Notebooks channel
    // =============================================================================

    /** Notebooks are kept out of the notes table, so they need their own way to
     *  reach the UI — with a readable name, taken from the content the inbox
     *  key opens. */
    @Test
    fun `refresh_publishesSharedNotebooksWithTheirNames`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(
                inbox("s1", NB_1, wrapped = "sealed", ownerEmail = "alice@x.y", itemType = 2),
            )))
        )
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), NB_1) } returns
            """{"title":"Recipes","members":[]}"""

        repository.refresh()

        val published = repository.sharedNotebooksFlow.value
        assertEquals(1, published.size)
        assertEquals(NB_1, published[0].itemId)
        assertEquals("alice@x.y", published[0].ownerEmail)
    }

    /** Nothing to publish when only notes arrived. */
    @Test
    fun `refresh_publishesNoNotebooksForAPageOfNotes`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = bareList(listOf(
                inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@x.y"),
            )))
        )
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns
            """{"title":"t"}"""

        repository.refresh()

        assertTrue(repository.sharedNotebooksFlow.value.isEmpty())
    }

    // =============================================================================
    // ONE REFRESH AT A TIME, FOR ONE ACCOUNT
    // =============================================================================

    /**
     * Refreshes run one at a time. Two that overlapped (the periodic sync and the one-off sync
     * a push starts) would share one map of "which notebook each note is in": each clears it,
     * refills it and then reads it, and one that read it just after the other had cleared it
     * would find no note in any notebook, keep nothing away, and write a declined notebook's
     * note into the database.
     */
    @Test
    fun `a second refresh waits for the first, and a declined notebook's note is written by neither`() = runTest {
        val written = inboxWithANoteOf(declined = mapOf(NB_1 to DECLINED_AT))
        val answer = noteApi.listInbox()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var asked = 0
        coEvery { noteApi.listInbox() } coAnswers { asked++; gate.await(); answer }

        // Each started at once, up to its first wait.
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { repository.refresh() }
        val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { repository.refresh() }
        // The first is waiting for the server; the second has not even asked.
        assertEquals(1, asked)
        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, asked)
        assertEquals("the note of the declined notebook is written by neither", emptyList<String>(), written.map { it.id }.filter { it == ITEM_1 })
        assertTrue(repository.isKeptAway(ITEM_1))
    }

    /** A refresh that fails releases the next one. */
    @Test
    fun `a refresh that throws does not block the next`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } throws kotlinx.coroutines.CancellationException("stopped")
        try { repository.refresh(); fail("the cancellation leaves refresh()") } catch (_: kotlinx.coroutines.CancellationException) {}

        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(emptyList(), hasMore = false))
        )
        assertEquals(0, repository.refresh())
        assertEquals(emptySet<String>(), repository.listedNotebookIds)
    }

    /**
     * The account signs out while the refresh runs: wipe() empties the repository, and the
     * refresh must not go on to publish the old account's declined notebooks over it or write
     * its shared notes into the next account's database.
     */
    @Test
    fun `an account that changes while the refresh runs gets nothing published or written`() = runTest {
        val written = inboxWithANoteOf(declined = mapOf(NB_1 to DECLINED_AT))
        // The note HAS a row: without one there is nothing to delete, and "nothing was
        // deleted" would hold whatever the refresh did.
        coEvery { itemDao.getById(ITEM_1) } returns shareRow(ITEM_1)
        var opened = false
        every { noteCrypto.openSeal("sealed", any()) } answers { opened = true; testContentKey }
        every { sessionManager.getUserId() } answers { if (opened) "00000000-0000-0000-0000-0000000000ff" else myUserId }

        assertEquals(0, repository.refresh())

        assertEquals(emptyList<ItemEntity>(), written)
        assertFalse(repository.isKeptAway(ITEM_1))
        assertNull(repository.listedNotebookIds)
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
        // Nothing was stored for the next account either, and no floor or owner was saved.
        verify(exactly = 0) { sessionManager.setInboxShareIds(any()) }
        verify(exactly = 0) { sessionManager.setOfferedNotebooks(any()) }
        verify(exactly = 0) { genFloorStore.saveAccepted(any()) }
        assertNull(repository.getContentKey(ITEM_1))
    }

    /** Through the repository: a served row with no `created_at` is a page that was not read. */
    @Test
    fun `a row without its time is a page that was not read - nothing throws, nothing is removed`() = runTest {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        every { sessionManager.getInboxShareIds() } returns setOf(ITEM_GONE)
        coEvery { itemDao.getById(ITEM_GONE) } returns shareRow(ITEM_GONE)
        val row = com.google.gson.Gson().toJsonTree(inbox("s1", ITEM_1, wrapped = "sealed", ownerEmail = "a@b.c")).asJsonObject
        row.remove("created_at")
        val page = com.google.gson.JsonObject().apply {
            add("shares", com.google.gson.JsonArray().apply { add(row) })
            addProperty("has_more", false)
        }
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(ApiResponse(success = true, data = page))

        assertEquals(0, repository.refresh())

        assertNull(repository.listedNotebookIds)
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
        verify(exactly = 0) { sessionManager.setInboxShareIds(any()) }
    }

    // =============================================================================
    // NOTES OF AN INVITATION NOBODY HAS ANSWERED
    // =============================================================================
    //
    // The page holds a shared notebook's notes back until its invitation is accepted,
    // and so must this code: written into the database the widgets read, a notebook
    // nobody had said yes to would be on the home screen.

    /** An inbox that offers NB_1 (a notebook row) with "inside" (ITEM_1) in it, and "outside" (ITEM_2) in none. */
    private fun inboxOffering(notebookOpens: Boolean = true, hasMore: Boolean = false): MutableList<ItemEntity> {
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        val rows = listOf(
            inbox("nbshare", NB_1, wrapped = if (notebookOpens) "sealed" else null, ownerEmail = "a@x.y", itemType = 2),
            inbox("inside", ITEM_1, wrapped = "sealed", ownerEmail = "a@x.y"),
            inbox("outside", ITEM_2, wrapped = "sealed", ownerEmail = "a@x.y"),
        )
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(ApiResponse(success = true, data = inboxPage(rows, hasMore = hasMore)))
        coEvery { noteApi.listInbox(any(), "outside") } returns RetrofitResponse.success(ApiResponse(success = false, data = null))
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), NB_1) } returns """{"title":"Recipes"}"""
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), ITEM_1) } returns """{"title":"in","notebook_id":"$NB_1"}"""
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), ITEM_2) } returns """{"title":"out"}"""
        val written = mutableListOf<ItemEntity>()
        coEvery { itemDao.upsert(capture(written)) } just Runs
        return written
    }

    @Test
    fun `a note of a notebook on offer and not accepted is not written to the database`() = runTest {
        val written = inboxOffering()

        repository.refresh()

        assertEquals(listOf(ITEM_2), written.map { it.id })
        assertTrue(repository.isKeptAway(ITEM_1))
        assertFalse(repository.isKeptAway(ITEM_2))
        // And its key is not handed out: the pull and the backfill cannot open it with one.
        assertNull(repository.getContentKey(ITEM_1))
        assertNotNull(repository.getContentKey(ITEM_2))
    }

    /** An older version's row goes, and is counted so the widgets are told. */
    @Test
    fun `and its row is taken out when an earlier build put it there`() = runTest {
        inboxOffering()
        coEvery { itemDao.getById(ITEM_1) } returns shareRow(ITEM_1)
        // The note that is NOT held has a row too: only then does "its row was not
        // removed" say anything (with no row there is nothing to remove, whatever is
        // held).
        coEvery { itemDao.getById(ITEM_2) } returns shareRow(ITEM_2)

        repository.refresh()

        coVerify(exactly = 1) { itemDao.deleteById(ITEM_1) }
        coVerify(exactly = 0) { itemDao.deleteById(ITEM_2) }
        assertEquals(1L, repository.rowsRemoved)
    }

    @Test
    fun `once the notebook is accepted, its note is written`() = runTest {
        acceptedNotebooks = setOf(NB_1)
        val written = inboxOffering()

        repository.refresh()

        assertEquals(setOf(ITEM_1, ITEM_2), written.map { it.id }.toSet())
        assertFalse(repository.isKeptAway(ITEM_1))
        assertNotNull(repository.getContentKey(ITEM_1))
    }

    @Test
    fun `the accepted notebook is found under any spelling of its id`() = runTest {
        acceptedNotebooks = setOf("{$NB_1}")
        val written = inboxOffering()

        repository.refresh()

        assertEquals(setOf(ITEM_1, ITEM_2), written.map { it.id }.toSet())
    }

    /** The note names its notebook in its own content, in whatever spelling its writer used. */
    @Test
    fun `the note's notebook is found on offer under any spelling of its id`() = runTest {
        val written = inboxOffering()
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), ITEM_1) } returns
            """{"title":"in","notebook_id":"{$NB_1}"}"""

        repository.refresh()

        assertEquals(listOf(ITEM_2), written.map { it.id })
        assertTrue(repository.isKeptAway(ITEM_1))
    }

    /** Offered is what the SERVER lists, whatever this device makes of the row. */
    @Test
    fun `a notebook whose own row does not open is still on offer, and its note is held`() = runTest {
        val written = inboxOffering(notebookOpens = false)

        repository.refresh()

        assertEquals(listOf(ITEM_2), written.map { it.id })
    }

    /** A note shared by itself, whose notebook is not on offer to this account, is like any share. */
    @Test
    fun `a note whose notebook is not on offer is written`() = runTest {
        val written = inboxWithANoteOf(declined = emptyMap())

        repository.refresh()

        assertEquals(setOf(ITEM_1, ITEM_2), written.map { it.id }.toSet())
    }

    /**
     * The list of acceptances is one document for every device, and older app builds can
     * empty it after a single failed inbox request. What this device has seen accepted is
     * remembered, so an emptied list takes nothing off the widgets.
     */
    @Test
    fun `a notebook this device saw accepted stays accepted when the shared list is emptied`() = runTest {
        acceptedNotebooks = setOf(NB_1)
        inboxOffering()
        repository.refresh()
        assertEquals(setOf(NB_1), rememberedAccepted)

        acceptedNotebooks = emptySet()
        val written = inboxOffering()
        repository.refresh()

        assertEquals(setOf(ITEM_1, ITEM_2), written.map { it.id }.toSet())
        assertEquals(setOf(NB_1), rememberedAccepted)
    }

    /** Forgotten only when an inbox READ TO ITS END stops offering the notebook. */
    @Test
    fun `the memory forgets a notebook that is no longer on offer, and only on an inbox read to its end`() = runTest {
        rememberedAccepted = setOf(NB_1)
        // An inbox that was not read to its end says nothing about what has stopped
        // being offered.
        every { sessionManager.getSharingPrivateKey() } returns testPrivB64
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(listOf(inbox("outside", ITEM_2, wrapped = "sealed", ownerEmail = "a@x.y")), hasMore = true))
        )
        coEvery { noteApi.listInbox(any(), "outside") } returns RetrofitResponse.success(ApiResponse(success = false, data = null))
        every { noteCrypto.openSeal("sealed", any()) } returns testContentKey
        repository.refresh()
        assertEquals(setOf(NB_1), rememberedAccepted)

        // Read to its end, and NB_1 is not offered any more (withdrawn, or declined).
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = inboxPage(listOf(inbox("outside", ITEM_2, wrapped = "sealed", ownerEmail = "a@x.y")), hasMore = false))
        )
        repository.refresh()
        assertEquals(emptySet<String>(), rememberedAccepted)

        // So an invitation to it sent again is a question again.
        val written = inboxOffering()
        repository.refresh()
        assertEquals(listOf(ITEM_2), written.map { it.id })
    }

    /** A list that cannot be read names nothing: nothing is "accepted" on its strength. */
    @Test
    fun `acceptances that cannot be read accept nothing`() = runTest {
        coEvery { notebookInvites.accepted() } throws RuntimeException("no metadata yet")
        val written = inboxOffering()

        repository.refresh()

        assertEquals(listOf(ITEM_2), written.map { it.id })
    }

    /**
     * The sync refreshes the inbox BEFORE its pull, and the acceptances come with the pull. So
     * a notebook accepted a moment ago is still "unanswered" at the refresh.
     */
    @Test
    fun `after the pull brought an acceptance, the inbox is read again and the note is written`() = runTest {
        var written = inboxOffering()
        repository.refresh()
        assertEquals(listOf(ITEM_2), written.map { it.id })
        // Nothing changed: no second reading.
        assertFalse(repository.refreshIfAnswersChanged())
        coVerify(exactly = 1) { noteApi.listInbox(any(), any(), any()) }

        acceptedNotebooks = setOf(NB_1)
        written = inboxOffering()
        assertTrue(repository.refreshIfAnswersChanged())

        assertEquals(setOf(ITEM_1, ITEM_2), written.map { it.id }.toSet())
        assertFalse(repository.isKeptAway(ITEM_1))
        // And then it is settled again.
        assertFalse(repository.refreshIfAnswersChanged())
    }

    @Test
    fun `an acceptance taken back holds the note again at the re-read`() = runTest {
        acceptedNotebooks = setOf(NB_1)
        inboxOffering()
        repository.refresh()
        // Declined on another device: off the shared list. This device still remembers it as
        // accepted until an inbox read to its end stops offering it, so nothing changes yet.
        acceptedNotebooks = emptySet()
        assertFalse(repository.refreshIfAnswersChanged())
    }

    @Test
    fun `before any refresh there is nothing to read again`() = runTest {
        acceptedNotebooks = setOf(NB_1)
        assertFalse(repository.refreshIfAnswersChanged())
        coVerify(exactly = 0) { noteApi.listInbox(any(), any(), any()) }
    }

    @Test
    fun `what was on offer is forgotten at a wipe`() = runTest {
        inboxOffering()
        repository.refresh()
        assertTrue(repository.isKeptAway(ITEM_1))

        repository.wipe()

        assertFalse(repository.isKeptAway(ITEM_1))
        // Nothing is "on offer" for the next account: with NB_1 still remembered as offered
        // (and nothing held back any more), this would read as "an answer changed" and fetch
        // the inbox for whoever signs in next.
        assertFalse(repository.refreshIfAnswersChanged())
        coVerify(exactly = 1) { noteApi.listInbox(any(), any(), any()) }
    }
}
