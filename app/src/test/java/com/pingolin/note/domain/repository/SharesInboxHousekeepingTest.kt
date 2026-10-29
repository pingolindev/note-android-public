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

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.collab.WrapVerify
import com.pingolin.note.data.local.NoteDatabase
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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import retrofit2.Response as RetrofitResponse

/**
 * What an inbox refresh costs when nothing has changed, and what it must still never do. Three
 * properties, each with the rule it could break:
 *
 * - The floors and owners of the shares a refresh accepts are saved ONCE, not once per share (a
 *   per-share save writes the whole record: about 5.9 GB for a first refresh of 10,000 shares) —
 *   and still before any key the refresh opened is published.
 * - A shared note whose row is already what the share would write is not written again. Against a
 *   REAL database: the server's times carry microseconds and the database keeps milliseconds, so a
 *   row read back never equals the row built from the answer unless it is compared as stored, and
 *   a mocked DAO that hands back the object it was given would never show that.
 * - The key table is locked for the swap alone, not while every signature is checked. So a wipe (a
 *   sign-out) can come in the middle, and a refresh a wipe overtook publishes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SharesInboxHousekeepingTest {

    private val me = "00000000-0000-4000-8000-00000000000e"
    private val alice = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val items = listOf(
        "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
        "2a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
        "3a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
    )
    private val signA = "a".repeat(64)
    private val shareA = "b".repeat(64)
    private val sig = "cd".repeat(64)
    private val contentKey = ByteArray(32) { 7 }
    private val privB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
    private val vault = ByteArray(32) { 5 }

    /** Plain preferences that count commits, can refuse them, and say when one is made. */
    private class WatchedPrefs(val base: SharedPreferences) : SharedPreferences by base {
        var commits = 0
        var refuse = false
        var onCommit: () -> Unit = {}
        override fun edit(): SharedPreferences.Editor {
            val e = base.edit()
            val outer = this
            return object : SharedPreferences.Editor by e {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { e.putString(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { e.remove(key); return this }
                override fun commit(): Boolean {
                    outer.commits++
                    outer.onCommit()
                    return if (outer.refuse) false else e.commit()
                }
            }
        }
    }

    /** The real DAO, with every row write and removal noted, and a say before each write. */
    private class WatchedDao(val real: ItemDao) : ItemDao by real {
        val written = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var beforeUpsert: (ItemEntity) -> Unit = {}
        override suspend fun upsert(item: ItemEntity) {
            beforeUpsert(item)
            written += item.id
            real.upsert(item)
        }
        var afterDelete: () -> Unit = {}
        override suspend fun deleteById(id: String) {
            deleted += id
            real.deleteById(id)
            afterDelete()
        }
    }

    private lateinit var db: NoteDatabase
    private lateinit var dao: WatchedDao
    private lateinit var floorPrefs: WatchedPrefs
    private lateinit var pinPrefs: SharedPreferences
    private lateinit var pins: SenderKeyPinStore
    private lateinit var floors: NoteGenFloorStore
    private val noteApi = mockk<NoteApi>()
    private val session = mockk<SessionManager>()
    /** What the session store holds as "on offer at the last read to the end": null until one is stored. */
    private var offeredStored: Set<String>? = null
    /** As of when what is on offer was last known: the newest share that read saw. */
    private var offersKnownAsOf: String? = null
    /** What the session store holds as "held for an invitation", and how often it was written. */
    private var heldStored: Set<String> = emptySet()
    private var heldWritten = 0
    private val crypto = mockk<NoteCrypto>()
    private val previousIdentities = mockk<PreviousIdentityLoader>()
    private var storedInboxIds: Set<String> = emptySet()
    private var storedIdsWritten = 0
    private var acceptedStored: Set<String> = emptySet()
    private var acceptedWritten = 0
    private var vaultTries = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java).allowMainThreadQueries().build()
        dao = WatchedDao(db.itemDao())
        pinPrefs = context.getSharedPreferences("housekeeping_pins_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        offeredStored = null
        offersKnownAsOf = null
        heldStored = emptySet()
        heldWritten = 0
        acceptedStored = emptySet()
        acceptedWritten = 0
        floorPrefs = WatchedPrefs(
            context.getSharedPreferences("housekeeping_floors_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
        )
        pins = SenderKeyPinStore({ pinPrefs }, session)
        floors = NoteGenFloorStore({ floorPrefs }, session)
        storedInboxIds = emptySet()
        storedIdsWritten = 0
        vaultTries = 0

        every { session.getUserId() } returns me
        every { session.getSharingPrivateKey() } returns privB64
        every { session.getEmail() } returns null
        every { session.getMasterKey() } returns java.util.Base64.getEncoder().encodeToString(vault)
        every { previousIdentities.vaultKeys() } returns emptyList()
        every { session.getInboxShareIds() } answers { storedInboxIds }
        val saved = slot<Set<String>>()
        every { session.setInboxShareIds(capture(saved)) } answers { storedIdsWritten++; storedInboxIds = saved.captured }
        every { session.getSeenAcceptedNotebooks() } answers { acceptedStored }
        every { session.setSeenAcceptedNotebooks(any()) } answers { acceptedWritten++; acceptedStored = firstArg() }
        // What the last read to the inbox's end found on offer: stored like the acceptances.
        every { session.getOfferedNotebooks() } answers { offeredStored }
        every { session.setOfferedNotebooks(any()) } answers { offeredStored = firstArg() }
        every { session.getOffersKnownAsOf() } answers { offersKnownAsOf }
        every { session.setOffersKnownAsOf(any()) } answers { offersKnownAsOf = firstArg() }
        every { session.getHeldForAnInvitation() } answers { heldStored }
        every { session.setHeldForAnInvitation(any()) } answers { heldWritten++; heldStored = firstArg() }

        coEvery { noteApi.lookupPublicKeys(any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = PublicKeyLookupResponse(listOf(PublicKeyLookupEntry("alice@x.y", alice, shareA, signA)))),
        )
        every { crypto.openSeal(any(), any()) } returns contentKey
        // A share's key opens its note; the note says what its ciphertext says.
        every { crypto.decryptNoteWithContentKeyV2(any(), any(), match { it.contentEquals(contentKey) }, any()) } answers {
            """{"title":"${secondArg<String>()}","color":"blue","is_pinned":true,"sort_order":4}"""
        }
        // Our own vault key opens no shared row, and each try is counted.
        every { crypto.decryptNoteWithContentKeyV2(any(), any(), match { it.contentEquals(vault) }, any()) } answers { vaultTries++; null }
        every { crypto.unwrapWithVault(any(), any(), any()) } returns null
        every { crypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) } returns WrapVerify(true, 3u)
        // The sender is one this device already knows, and her shares are signed: the ordinary
        // case. (An unsigned share is taken from a sender met for the first time only; her
        // second unsigned share in the same answer would be refused.)
        pins.pin(alice, signA, shareA)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repo(accepted: () -> Set<String> = { emptySet() }): SharesInboxRepository {
        val invites = mockk<NotebookInvites>()
        coEvery { invites.accepted() } answers { accepted() }
        return SharesInboxRepository(
            noteApi, session, crypto, dao, pins, floors, previousIdentities,
            javax.inject.Provider { invites },
        )
    }

    /** A share as the server sends one: its times to the MICROSECOND. */
    private fun share(itemId: String, content: String = "enc-$itemId", signature: String? = sig, updatedAt: String = "2026-09-29T10:11:12.123456Z") =
        InboxShareDto(
            shareId = "share-$itemId",
            itemId = itemId,
            ownerId = alice,
            permission = "edit",
            wrappedKey = "sealed",
            sharerPublicKey = null,
            ownerEmail = "alice@x.y",
            wrapSignature = signature,
            encryptedContent = content,
            contentNonce = "nonce",
            updatedAt = updatedAt,
            createdAt = "2026-09-28T09:08:07.654321Z",
            contentKeyGeneration = 1L,
            itemType = 1,
        )

    private fun serve(vararg shares: InboxShareDto) {
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(ApiResponse(success = true, data = bareList(shares.toList())))
    }

    /** A first page as the current server sends it, and what a request for the NEXT page
     * gets (null: it fails).
     */
    private fun servePages(first: com.google.gson.JsonElement, next: com.google.gson.JsonElement? = null) {
        coEvery { noteApi.listInbox(any(), any(), any()) } answers {
            val page = if (firstArg<String?>() == null) first else next
            if (page == null) throw java.io.IOException("the next page did not come")
            RetrofitResponse.success(ApiResponse(success = true, data = page))
        }
    }

    /** A notebook's own share: the invitation. */
    private fun notebook(id: String) = share(id).copy(itemType = 2)

    /** A note whose decrypted content says it is in [notebookId] (see the stub in [notesNameTheirNotebook]). */
    private fun noteIn(notebookId: String, itemId: String, sharedAt: String = "2026-09-28T09:08:07.654321Z") =
        share(itemId, content = "in:$notebookId").copy(createdAt = sharedAt)

    private fun notesNameTheirNotebook() {
        every { crypto.decryptNoteWithContentKeyV2(any(), match { it.startsWith("in:") }, match { it.contentEquals(contentKey) }, any()) } answers {
            """{"title":"a note in a notebook","notebook_id":"${secondArg<String>().removePrefix("in:")}"}"""
        }
    }

    /** A row as a refresh would have written it for one of alice's shares. */
    private fun rowOfAlice(id: String) = ItemEntity(
        id = id, userId = alice, itemType = 1, encryptedContent = "enc-$id", contentNonce = "nonce",
        decryptedContent = """{"title":"old"}""", title = "old",
        createdAt = java.time.Instant.parse("2026-01-01T00:00:00Z"), updatedAt = java.time.Instant.parse("2026-01-02T00:00:00Z"),
    )

    private fun storedOwners() = floorPrefs.base.getString("item_owners:$me", null)?.let { JSONObject(it) }
    private fun storedFloors() = floorPrefs.base.getString("gen_floors:$me", null)?.let { JSONObject(it) }

    // --- An unchanged row is not written again ---------------------------------------------

    @Test
    fun `a refresh that brings nothing new writes no row`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        r.refresh()
        assertEquals("the first refresh writes each shared note", items.sorted(), dao.written.sorted())
        val first = items.map { dao.real.getById(it)!! }
        assertEquals("enc-${items[0]}", first[0].title)

        dao.written.clear()
        r.refresh()
        assertEquals("the same answer again: nothing to write", emptyList<String>(), dao.written)
        assertEquals("and the rows are as they were", first, items.map { dao.real.getById(it)!! })

        // A fresh process (nothing in memory) finds the same rows and writes nothing either.
        repo().refresh()
        assertEquals(emptyList<String>(), dao.written)
    }

    @Test
    fun `only the row whose share changed is written`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        r.refresh()
        dao.written.clear()

        // The owner edited the second note: new ciphertext, a later time.
        serve(share(items[0]), share(items[1], content = "enc-edited", updatedAt = "2026-09-30T01:02:03.000004Z"), share(items[2]))
        r.refresh()
        assertEquals(listOf(items[1]), dao.written)
        assertEquals("enc-edited", dao.real.getById(items[1])!!.title)
    }

    @Test
    fun `a change of the time alone is a change`() = runBlocking {
        serve(share(items[0]))
        val r = repo()
        r.refresh()
        dao.written.clear()
        // One millisecond later: the database keeps milliseconds, so this is another row.
        serve(share(items[0], updatedAt = "2026-09-29T10:11:12.124456Z"))
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        // Later by microseconds only: the same row as stored, nothing to write.
        dao.written.clear()
        serve(share(items[0], updatedAt = "2026-09-29T10:11:12.124999Z"))
        r.refresh()
        assertEquals(emptyList<String>(), dao.written)
    }

    @Test
    fun `a row someone changed underneath is put right, and a local edit is left alone`() = runBlocking {
        serve(share(items[0]), share(items[1]))
        val r = repo()
        r.refresh()
        // The first row's title was changed by something else; the second holds an unsent edit.
        dao.real.upsert(dao.real.getById(items[0])!!.copy(title = "not what the share says"))
        dao.real.upsert(dao.real.getById(items[1])!!.copy(title = "my edit", isDirty = true))
        dao.written.clear()
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        assertEquals("enc-${items[0]}", dao.real.getById(items[0])!!.title)
        assertEquals("my edit", dao.real.getById(items[1])!!.title)
    }

    /**
     * Whether a row may be written over is asked by trying each vault key on its body. The
     * refresh asks it once per held row before anything is opened, and again at the write only
     * for a row that would change.
     */
    @Test
    fun `an unchanged row is not asked about a second time`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        r.refresh()
        assertEquals("no row yet: nothing to ask", 0, vaultTries)
        r.refresh()
        assertEquals("once per held row, at the start", items.size, vaultTries)

        // A row that WOULD change is still asked about at the write.
        vaultTries = 0
        serve(share(items[0], content = "enc-edited"), share(items[1]), share(items[2]))
        r.refresh()
        assertEquals(items.size + 1, vaultTries)
    }

    @Test
    fun `one of our own rows is still never written over, changed or not`() = runBlocking {
        // A legacy note of OURS (no wrapped key: its body opens under the vault itself), which
        // the server now serves as somebody's share.
        every { crypto.decryptNoteWithContentKeyV2(any(), "ours", match { it.contentEquals(vault) }, any()) } returns """{"title":"mine"}"""
        val ours = ItemEntity(
            id = items[0], userId = alice, itemType = 1, encryptedContent = "ours", contentNonce = "nonce",
            decryptedContent = """{"title":"mine"}""", title = "mine",
            createdAt = java.time.Instant.parse("2026-01-01T00:00:00Z"), updatedAt = java.time.Instant.parse("2026-01-02T00:00:00Z"),
        )
        dao.real.upsert(ours)
        serve(share(items[0]))
        val r = repo()
        r.refresh()
        assertEquals(emptyList<String>(), dao.written)
        assertEquals(ours, dao.real.getById(items[0]))
        assertNull("and its key is not taken from the share", r.getContentKey(items[0]))
    }

    // --- One save of the floors and owners per refresh -----------------------------------------

    @Test
    fun `the floors and owners of a refresh's shares are saved once, before any of its keys is published`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        val publishedAtSave = mutableListOf<Boolean>()
        floorPrefs.onCommit = { publishedAtSave += items.any { r.getContentKey(it) != null } }

        r.refresh()
        assertEquals("one write for three shares", 1, floorPrefs.commits)
        assertEquals("no key of this refresh was out when its floors were saved", listOf(false), publishedAtSave)
        for (id in items) {
            assertEquals(alice, storedOwners()!!.getString(id))
            assertEquals(3L, storedFloors()!!.getLong(id))
            assertNotNull(r.getContentKey(id))
        }

        // Nothing new to record: no write at all.
        r.refresh()
        assertEquals(1, floorPrefs.commits)
    }

    @Test
    fun `a share is judged by what the same refresh accepted before it, saved or not`() = runBlocking {
        // Two shares of one item in one answer, the second from its owner too but at a lower
        // generation: the floor the first one set is in force for the second, though nothing has
        // been saved yet.
        every { crypto.verifyNoteShareWrapGen(any(), "sig-new", any(), any(), any(), any(), any()) } returns WrapVerify(true, 5u)
        every { crypto.verifyNoteShareWrapGen(any(), "sig-old", any(), any(), any(), any(), any()) } returns WrapVerify(true, 2u)
        serve(
            share(items[0], signature = "sig-new").copy(shareId = "share-new"),
            share(items[0], signature = "sig-old").copy(shareId = "share-old"),
        )
        val r = repo()
        r.refresh()
        // Only the first was unwrapped: the second was refused before it, as out of date.
        verify(exactly = 1) { crypto.openSeal(any(), any()) }
        assertEquals(5L, storedFloors()!!.getLong(items[0]))
        assertEquals(1, floorPrefs.commits)
    }

    /**
     * A floor that is only in memory dies with the process, and the older key it refused is taken
     * again afterwards. So what a refresh opened for an item is not handed out until that item's
     * floor and owner are in the file.
     *
     * Item by item, not "the whole refresh publishes nothing": the store keeps trying to save what
     * it holds, so one failed save would stop every LATER refresh too, and shares that had been
     * withdrawn would stay on the widgets for as long as the save kept failing.
     */
    @Test
    fun `what a refresh could not save is withheld, and the next one saves it and gives it out`() = runBlocking {
        serve(share(items[0]))
        val r = repo()
        floorPrefs.refuse = true
        assertEquals("no key is given out", 0, r.refresh())
        assertEquals("tried a few times, not once", SharesInboxRepository.FLOOR_SAVE_ATTEMPTS, floorPrefs.commits)
        assertNull(storedOwners())
        assertNull(r.getContentKey(items[0]))
        assertEquals("no row written", emptyList<String>(), dao.written)
        assertTrue("and the pull is told to leave it alone too", r.isKeptAway(items[0]))
        // What it accepted is held in memory: the next save writes it, with nothing new accepted.
        assertEquals(alice, floors.owner(items[0]))

        floorPrefs.refuse = false
        assertEquals(1, r.refresh())
        assertEquals(alice, storedOwners()!!.getString(items[0]))
        assertEquals(3L, storedFloors()!!.getLong(items[0]))
        assertNotNull(r.getContentKey(items[0]))
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
    }

    @Test
    fun `a save that fails once and then works is one refresh, not two`() = runBlocking {
        serve(share(items[0]))
        val r = repo()
        floorPrefs.onCommit = { floorPrefs.refuse = floorPrefs.commits < 2 }
        assertEquals(1, r.refresh())
        assertEquals(2, floorPrefs.commits)
        assertEquals(alice, storedOwners()!!.getString(items[0]))
        assertNotNull(r.getContentKey(items[0]))
        assertFalse(r.isKeptAway(items[0]))
    }

    @Test
    fun `only the items whose record is unsaved are withheld - the rest of the refresh goes on`() = runBlocking {
        serve(share(items[0]))
        val r = repo()
        assertEquals(1, r.refresh())
        dao.written.clear()
        storedIdsWritten = 0

        // A second share arrives, and its floor cannot be saved.
        serve(share(items[0]), share(items[1]))
        floorPrefs.refuse = true
        assertEquals(1, r.refresh())
        assertNotNull("the first share is still open: its record is in the file", r.getContentKey(items[0]))
        assertFalse(r.isKeptAway(items[0]))
        assertNull("the new one is not", r.getContentKey(items[1]))
        assertTrue(r.isKeptAway(items[1]))
        assertEquals(emptyList<String>(), dao.written)
        assertEquals("the inbox's ids are stored as after any refresh", 1, storedIdsWritten)
        assertEquals(setOf(items[0], items[1]), storedInboxIds)
        assertNotNull("and its list is one to prune against", r.listedNotebookIds)
    }

    /**
     * A NOTEBOOK whose floor could not be saved is withheld like a note: its key is not given
     * out, and it is not in the list the Shared Notebooks section reads.
     */
    @Test
    fun `a notebook whose record could not be saved is not in the shared notebooks list either`() = runBlocking {
        serve(notebook(nb))
        val r = repo()
        floorPrefs.refuse = true
        r.refresh()
        assertEquals(emptyList<String>(), r.sharedNotebooksFlow.value.map { it.itemId })
        assertNull(r.getContentKey(nb))

        // The control, and the way out: once it can be saved, it is listed.
        floorPrefs.refuse = false
        r.refresh()
        assertEquals(listOf(nb), r.sharedNotebooksFlow.value.map { it.itemId })
        assertNotNull(r.getContentKey(nb))
    }

    @Test
    fun `and one that was listed stays out while a later save of its record fails, with the others`() = runBlocking {
        serve(notebook(nb))
        val r = repo()
        r.refresh()
        assertEquals(listOf(nb), r.sharedNotebooksFlow.value.map { it.itemId })
        // A second notebook arrives, and its record cannot be saved: the first stays listed.
        serve(notebook(nb), notebook(nb2))
        floorPrefs.refuse = true
        r.refresh()
        assertEquals(listOf(nb), r.sharedNotebooksFlow.value.map { it.itemId })
    }

    /** A withdrawn share leaves, row and key, even across refreshes that cannot save another share's
     * record.
     */
    @Test
    fun `a share that was withdrawn leaves, though another share's record cannot be saved`() = runBlocking {
        serve(share(items[0]))
        val r = repo()
        assertEquals(1, r.refresh())
        assertNotNull(dao.real.getById(items[0]))

        // The first share is withdrawn. A new one arrives, and nothing can be saved, now or later.
        floorPrefs.refuse = true
        for (again in 1..3) {
            serve(share(items[1]))
            r.refresh()
            assertNull("refresh $again: the withdrawn share's row is gone", dao.real.getById(items[0]))
            assertNull("and its key", r.getContentKey(items[0]))
            assertNull("the unsaved one stays withheld", r.getContentKey(items[1]))
            assertNull(dao.real.getById(items[1]))
        }
        assertEquals(listOf(items[0]), dao.deleted)
        assertEquals(setOf(items[1]), storedInboxIds)

        // The save works again: the new share is opened at last.
        floorPrefs.refuse = false
        r.refresh()
        assertNotNull(r.getContentKey(items[1]))
        assertNotNull(dao.real.getById(items[1]))
    }

    @Test
    fun `what was withheld for one account is not kept away from the next`() = runBlocking {
        serve(share(items[0]))
        val r = repo()
        floorPrefs.refuse = true
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))
        r.wipe()
        assertFalse(r.isKeptAway(items[0]))
    }

    /** A floor RAISED and not saved: the newer key is not used, and the row keeps what it had. */
    @Test
    fun `a share whose raised floor cannot be saved keeps the row it has, and no key is given out for it`() = runBlocking {
        serve(share(items[0]))
        val r = repo()
        assertEquals(1, r.refresh())
        assertEquals(3L, storedFloors()!!.getLong(items[0]))
        val before = dao.real.getById(items[0])!!
        dao.written.clear()

        // Its key is rotated: generation 4, which cannot be saved.
        every { crypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) } returns WrapVerify(true, 4u)
        serve(share(items[0], content = "enc-newer").copy(contentKeyGeneration = 4L))
        floorPrefs.refuse = true
        r.refresh()
        assertNull("no key, neither the newer nor the one it replaces", r.getContentKey(items[0]))
        assertTrue(r.isKeptAway(items[0]))
        assertEquals(emptyList<String>(), dao.written)
        assertEquals("the row is as it was", before, dao.real.getById(items[0]))
        assertEquals("and the file still holds the floor it had", 3L, storedFloors()!!.getLong(items[0]))

        floorPrefs.refuse = false
        r.refresh()
        assertEquals(4L, storedFloors()!!.getLong(items[0]))
        assertNotNull(r.getContentKey(items[0]))
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
    }

    // --- The key table is locked for the swap alone --------------------------------------------

    @Test
    fun `asking for a share's key does not wait for the inbox's signatures to be checked`() = runBlocking {
        serve(share(items[0]))
        val r = repo()
        r.refresh()
        val held = r.getContentKey(items[0])
        assertNotNull(held)

        // While the next refresh is opening a share, another thread asks for a key.
        var answered: ByteArray? = null
        var waited = false
        every { crypto.openSeal(any(), any()) } answers {
            try {
                answered = CompletableFuture.supplyAsync { r.getContentKey(items[0]) }.get(5, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                waited = true
            }
            contentKey
        }
        r.refresh()
        assertFalse("the question waited for the refresh", waited)
        assertTrue("and was answered with the key held until the swap", held.contentEquals(answered))
    }

    // --- A refresh that a wipe overtook publishes nothing --------------------------------------

    private fun assertNothingOfTheRefreshIsOut(r: SharesInboxRepository) {
        for (id in items) assertNull(r.getContentKey(id))
        assertEquals("no row written", emptyList<String>(), dao.written)
        assertEquals("the stored list of share ids is not touched", 0, storedIdsWritten)
        assertNull(r.listedNotebookIds)
    }

    @Test
    fun `a sign-out while the inbox is being fetched - nothing is published`() = runBlocking {
        val r = repo()
        coEvery { noteApi.listInbox() } answers {
            r.wipe()
            RetrofitResponse.success(ApiResponse(success = true, data = bareList(items.map { share(it) })))
        }
        assertEquals(0, r.refresh())
        assertNothingOfTheRefreshIsOut(r)
    }

    @Test
    fun `a sign-out while the signatures are being checked - nothing is published`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        var opened = 0
        every { crypto.openSeal(any(), any()) } answers {
            if (++opened == 2) r.wipe()
            contentKey
        }
        assertEquals(0, r.refresh())
        assertNothingOfTheRefreshIsOut(r)
    }

    @Test
    fun `a sign-out after the keys are out, before the rows are written - nothing is written`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        // A share that has LEFT the inbox since the last refresh has a row to remove.
        val gone = "9a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        storedInboxIds = setOf(gone)
        dao.real.upsert(rowOfAlice(gone))
        // The stored share ids are read right after the swap: the wipe lands there.
        every { session.getInboxShareIds() } answers { r.wipe(); storedInboxIds }
        assertEquals(0, r.refresh())
        for (id in items) assertNull("the wipe took the keys", r.getContentKey(id))
        assertEquals("no row written after it", emptyList<String>(), dao.written)
        assertEquals("and none removed", emptyList<String>(), dao.deleted)
        assertNotNull(dao.real.getById(gone))
        assertEquals("the stored list of share ids is not touched", 0, storedIdsWritten)
        assertEquals(setOf(gone), storedInboxIds)
        assertNull(r.listedNotebookIds)
    }

    @Test
    fun `the refresh after a wipe is an ordinary one`() = runBlocking {
        val r = repo()
        var first = true
        coEvery { noteApi.listInbox() } answers {
            if (first) { first = false; r.wipe() }
            RetrofitResponse.success(ApiResponse(success = true, data = bareList(items.map { share(it) })))
        }
        assertEquals(0, r.refresh())
        assertEquals(items.size, r.refresh())
        for (id in items) assertNotNull(r.getContentKey(id))
        assertEquals(items.sorted(), dao.written.sorted())
    }

    @Test
    fun `a wipe between two refreshes stops neither`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        assertEquals(items.size, r.refresh())
        r.wipe()
        for (id in items) assertNull(r.getContentKey(id))
        assertEquals(items.size, r.refresh())
        for (id in items) assertNotNull(r.getContentKey(id))
        verify(atLeast = 1) { session.setInboxShareIds(any()) }
    }

    // ============================================================================================
    // Refreshes against account switches, wipes, partial reads and invitations
    // ============================================================================================

    private val other = "0f0f0f0f-0000-4000-8000-00000000000f"
    private val nb = "aaaaaaaa-0000-4000-8000-0000000000a1"
    private val nb2 = "bbbbbbbb-0000-4000-8000-0000000000b2"
    private val gone = "9a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private fun nothingIsRecordedFor(account: String) {
        assertNull("no pin under that account's name", pinPrefs.getString("sender_pins:$account", null))
        assertNull("no item owner", floorPrefs.base.getString("item_owners:$account", null))
        assertNull("no floor", floorPrefs.base.getString("gen_floors:$account", null))
    }

    /** A sender neither account has met: each of her shares is checked against the directory and pinned as it opens. */
    private fun aliceIsNew() {
        pinPrefs.edit().clear().commit()
        pins = SenderKeyPinStore({ pinPrefs }, session)
    }

    // --- The trust stores work for the account the refresh STARTED for
    // ------------------------

    /**
     * The stores resolve "the account" at every call. The refresh checks its shares for a while,
     * and not under a lock a sign-out waits for: once another account has signed in, the rest of
     * the first account's shares must not be checked against the second's pins, pinned as ITS
     * first contact, or bound and floored in ITS record.
     */
    @Test
    fun `another account signs in while the signatures are being checked - nothing is recorded under its name`() = runBlocking {
        aliceIsNew()
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        var opened = 0
        every { crypto.openSeal(any(), any()) } answers {
            if (++opened == 2) every { session.getUserId() } returns other
            contentKey
        }
        assertEquals(0, r.refresh())
        nothingIsRecordedFor(other)
        assertNothingOfTheRefreshIsOut(r)
        assertEquals("and no floor or owner was saved for anyone", 0, floorPrefs.commits)
        // What it pinned for its OWN account before the change is that account's, and stays.
        assertNotNull(pinPrefs.getString("sender_pins:$me", null))
    }

    @Test
    fun `a sign-out and another sign-in while the signatures are being checked - the same`() = runBlocking {
        aliceIsNew()
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        var opened = 0
        every { crypto.openSeal(any(), any()) } answers {
            if (++opened == 2) {
                // As LocalDataWiper does it, and then the sign-in.
                runBlocking { r.wipeIsStarting() }
                r.wipe()
                pins.wipe()
                floors.wipe()
                every { session.getUserId() } returns other
            }
            contentKey
        }
        assertEquals(0, r.refresh())
        nothingIsRecordedFor(other)
        assertNothingOfTheRefreshIsOut(r)
        assertEquals(0, floorPrefs.commits)
    }

    /** The save comes AFTER the look at the wipe counter: a refresh a wipe has overtaken saves nothing. */
    @Test
    fun `a refresh a wipe overtook saves no floor and no owner`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        var opened = 0
        every { crypto.openSeal(any(), any()) } answers {
            if (++opened == 2) r.wipe()
            contentKey
        }
        assertEquals(0, r.refresh())
        assertEquals(0, floorPrefs.commits)
        assertNull(storedOwners())
    }

    @Test
    fun `the account that signs in next has an ordinary refresh, recorded under its own name only`() = runBlocking {
        aliceIsNew()
        serve(share(items[0]), share(items[1]))
        val r = repo()
        var opened = 0
        every { crypto.openSeal(any(), any()) } answers {
            if (++opened == 2) every { session.getUserId() } returns other
            contentKey
        }
        assertEquals(0, r.refresh())
        val mine = pinPrefs.getString("sender_pins:$me", null)

        // The other account's own refresh, of its own inbox.
        every { crypto.openSeal(any(), any()) } returns contentKey
        serve(share(items[2]))
        assertEquals(1, r.refresh())
        assertEquals(setOf(items[2]), JSONObject(floorPrefs.base.getString("item_owners:$other", null)!!).keys().asSequence().toSet())
        assertNotNull(pinPrefs.getString("sender_pins:$other", null))
        assertEquals("the first account's pins are as they were", mine, pinPrefs.getString("sender_pins:$me", null))
        assertNull("and nothing it had only accepted in memory was saved", floorPrefs.base.getString("item_owners:$me", null))
    }

    // --- An inbox that was not read to its end ------------------------------------------------

    /**
     * A notebook's own row is among the oldest in the inbox, and a read that stops early does not
     * reach it. Its notes on the pages that WERE read must not be written, titles and first
     * lines, into the database the widgets read: they belong to an invitation nobody has
     * answered.
     */
    @Test
    fun `a read that stops before the notebook's own row still holds that invitation's notes`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        // A read to the end: the invitation, and one of its notes. Held, as before.
        servePages(inboxPage(listOf(noteIn(nb, items[0]), notebook(nb)), hasMore = false))
        r.refresh()
        assertEquals(emptyList<String>(), dao.written)
        assertEquals(setOf(nb), offeredStored)

        // The next read gets its first page (two of the invitation's notes) and not its second,
        // where the notebook's own row is.
        servePages(inboxPage(listOf(noteIn(nb, items[0]), noteIn(nb, items[1])), hasMore = true), next = null)
        r.refresh()
        assertEquals("neither note is written", emptyList<String>(), dao.written)
        for (id in items.take(2)) {
            assertTrue(r.isKeptAway(id))
            assertNull("nor is its key handed out", r.getContentKey(id))
        }
        assertEquals("what the last read to the end found is kept", setOf(nb), offeredStored)

        // A fresh process (nothing in memory) reads as far again: the same, from what is stored.
        val fresh = repo()
        fresh.refresh()
        assertEquals(emptyList<String>(), dao.written)
        assertTrue(fresh.isKeptAway(items[1]))
        assertNull(fresh.getContentKey(items[1]))
    }

    @Test
    fun `with no read to the end yet, a read that stops early holds every note of a notebook not accepted`() = runBlocking {
        notesNameTheirNotebook()
        // nb2 is a notebook this account accepted; nb is not, and its own row was not reached.
        val r = repo(accepted = { setOf(nb2) })
        val rows = listOf(noteIn(nb, items[0]), noteIn(nb2, items[1]), share(items[2]))
        servePages(inboxPage(rows, hasMore = true), next = null)
        r.refresh()
        assertEquals(
            "the accepted notebook's note and the note in no notebook are written",
            listOf(items[1], items[2]).sorted(), dao.written.sorted(),
        )
        assertTrue(r.isKeptAway(items[0]))
        assertNull(r.getContentKey(items[0]))
        assertNull("a read that stopped early is not remembered as the whole offer", offeredStored)

        // The read that reaches the end finds that notebook is not on offer at all (the note was
        // shared by itself): it is written.
        dao.written.clear()
        servePages(inboxPage(rows, hasMore = false))
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
        assertNotNull(r.getContentKey(items[0]))
        assertEquals(emptySet<String>(), offeredStored)
    }

    /**
     * The sync refreshes the inbox before its pull, and the acceptances come with the pull. When
     * the refresh could not tell what was on offer it held by what was ACCEPTED, so an acceptance
     * that arrives with the pull is an answer to read the inbox again for.
     */
    @Test
    fun `an acceptance that arrives after a read that could not tell what was on offer lets the note in at once`() = runBlocking {
        notesNameTheirNotebook()
        var accepted = emptySet<String>()
        val r = repo(accepted = { accepted })
        servePages(inboxPage(listOf(noteIn(nb, items[0])), hasMore = true), next = null)
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))
        assertFalse("nothing has been answered yet", r.refreshIfAnswersChanged())

        accepted = setOf(nb)
        assertTrue(r.refreshIfAnswersChanged())
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
        assertFalse("and that was the answer: nothing more to read for", r.refreshIfAnswersChanged())
    }

    /**
     * After a read that knew what was on offer, a read that stops early against a server that
     * does not say holds what was on offer THEN. That says nothing of an invitation made since:
     * its notes are the newest rows, its own row the oldest of it, on the page that did not
     * come, and those notes would otherwise be written to the database the widgets read. What
     * arrived SINCE, in a notebook nothing is known about, is held.
     */
    @Test
    fun `once a read has reached the end, one that stops early also holds what has arrived since in a notebook it knows nothing of`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        // Nothing on offer at the last read to the end. This note names a notebook of its
        // owner's that was never shared with this account: it is a note shared by itself.
        servePages(inboxPage(listOf(noteIn(nb, items[0], sharedAt = "2026-09-28T09:08:07.654321Z")), hasMore = false))
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        assertEquals("as of the newest share that read saw", "2026-09-28T09:08:07.654321Z", offersKnownAsOf)
        dao.written.clear()

        // A note arrives afterwards, naming the same notebook, and the read stops early.
        servePages(
            inboxPage(listOf(noteIn(nb, items[1], sharedAt = "2026-09-29T10:00:00Z"), noteIn(nb, items[0], sharedAt = "2026-09-28T09:08:07.654321Z")), hasMore = true),
            next = null,
        )
        r.refresh()
        assertEquals("the new one is not written: it may be an invitation's, whose own row was not reached", emptyList<String>(), dao.written)
        assertTrue(r.isKeptAway(items[1]))
        assertNull(r.getContentKey(items[1]))
        // The one that was here when the offers were known keeps the decision made then.
        assertFalse(r.isKeptAway(items[0]))
        assertNotNull(dao.real.getById(items[0]))
        assertEquals("a read that did not know moves nothing", "2026-09-28T09:08:07.654321Z", offersKnownAsOf)

        // A read to the end: nothing is on offer after all, and the note is let in.
        servePages(
            inboxPage(listOf(noteIn(nb, items[1], sharedAt = "2026-09-29T10:00:00Z"), noteIn(nb, items[0], sharedAt = "2026-09-28T09:08:07.654321Z")), hasMore = false),
        )
        r.refresh()
        assertEquals(listOf(items[1]), dao.written)
        assertFalse(r.isKeptAway(items[1]))
        assertEquals("2026-09-29T10:00:00Z", offersKnownAsOf)
    }

    /** The case in full: the invitation itself, whose own row the early read does not reach. */
    @Test
    fun `an invitation made since the last read that knew is held, by a read that stops before its own row`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(inboxPage(listOf(share(items[2]).copy(createdAt = "2026-09-20T00:00:00Z")), hasMore = false))
        r.refresh()
        dao.written.clear()

        // The invitation: two notes on the first page, its own row on the page that fails.
        servePages(
            inboxPage(
                listOf(
                    noteIn(nb, items[0], sharedAt = "2026-09-21T00:00:02Z"),
                    noteIn(nb, items[1], sharedAt = "2026-09-21T00:00:01Z"),
                    share(items[2]).copy(createdAt = "2026-09-20T00:00:00Z"),
                ),
                hasMore = true,
            ),
            next = null,
        )
        r.refresh()
        assertEquals("nothing of the invitation is written", emptyList<String>(), dao.written)
        assertTrue(r.isKeptAway(items[0]))
        assertTrue(r.isKeptAway(items[1]))
        assertFalse("the note shared by itself stays", r.isKeptAway(items[2]))

        // The next read reaches the invitation's own row: held now as what it is, an unanswered invitation.
        servePages(
            inboxPage(
                listOf(
                    noteIn(nb, items[0], sharedAt = "2026-09-21T00:00:02Z"),
                    noteIn(nb, items[1], sharedAt = "2026-09-21T00:00:01Z"),
                    share(items[2]).copy(createdAt = "2026-09-20T00:00:00Z"),
                    notebook(nb).copy(createdAt = "2026-09-21T00:00:00Z"),
                ),
                hasMore = false,
            ),
        )
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))
        assertEquals(setOf(nb), offeredStored)
        assertEquals(emptyList<String>(), dao.written)
    }

    @Test
    fun `what arrives in a notebook the reader accepted is never held for that`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo(accepted = { setOf(nb) })
        servePages(inboxPage(listOf(noteIn(nb, items[0], sharedAt = "2026-09-28T00:00:00Z"), notebook(nb).copy(createdAt = "2026-09-27T00:00:00Z")), hasMore = false))
        r.refresh()
        dao.written.clear()
        servePages(
            inboxPage(listOf(noteIn(nb, items[1], sharedAt = "2026-09-29T00:00:00Z"), noteIn(nb, items[0], sharedAt = "2026-09-28T00:00:00Z")), hasMore = true),
            next = null,
        )
        r.refresh()
        assertEquals(listOf(items[1]), dao.written)
        assertFalse(r.isKeptAway(items[1]))
    }

    @Test
    fun `nor a note in no notebook, however new`() = runBlocking {
        val r = repo()
        servePages(inboxPage(listOf(share(items[0]).copy(createdAt = "2026-09-28T00:00:00Z")), hasMore = false))
        r.refresh()
        dao.written.clear()
        servePages(
            inboxPage(listOf(share(items[1]).copy(createdAt = "2026-09-29T00:00:00Z"), share(items[0]).copy(createdAt = "2026-09-28T00:00:00Z")), hasMore = true),
            next = null,
        )
        r.refresh()
        assertEquals(listOf(items[1]), dao.written)
    }

    /** With the server's word nothing of this is asked: the list says what is on offer. */
    @Test
    fun `a read that stops early WITH the server's list holds only what the list says`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(inboxPageOffering(listOf(share(items[2]).copy(createdAt = "2026-09-20T00:00:00Z")), hasMore = false, offered = emptyList()))
        r.refresh()
        dao.written.clear()
        // New, in a notebook nothing is known about, and the server says nothing is on offer:
        // it is a note shared by itself.
        servePages(
            inboxPageOffering(listOf(noteIn(nb, items[0], sharedAt = "2026-09-21T00:00:00Z"), share(items[2]).copy(createdAt = "2026-09-20T00:00:00Z")), hasMore = true, offered = emptyList()),
            next = null,
        )
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
        assertEquals("and it is as of this read that the offers are known now", "2026-09-21T00:00:00Z", offersKnownAsOf)
    }

    // --- What the SERVER says is on offer
    // ------------------------------------------------------

    /**
     * A NEW invitation with a page's worth of notes: the notebook's own row is the oldest of it,
     * on the page a read that stops early never comes to, and no earlier read saw it either.
     * Without the server's word its notes would be written to the database the widgets read.
     */
    @Test
    fun `a new invitation whose own row was not reached is held on the server's word`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        // A read to the end, before the invitation existed: nothing is on offer.
        servePages(inboxPageOffering(listOf(share(items[2])), hasMore = false, offered = emptyList()))
        r.refresh()
        assertEquals(listOf(items[2]), dao.written)
        assertEquals(emptySet<String>(), offeredStored)
        dao.written.clear()

        // The invitation arrives. The first page holds its newest notes; the next page, where
        // its own row lies, does not come. The server says the notebook is on offer.
        servePages(
            inboxPageOffering(listOf(noteIn(nb, items[0]), noteIn(nb, items[1]), share(items[2])), hasMore = true, offered = listOf(nb)),
            next = null,
        )
        r.refresh()
        assertEquals("nothing of the invitation is written", emptyList<String>(), dao.written)
        assertTrue(r.isKeptAway(items[0]))
        assertTrue(r.isKeptAway(items[1]))
        assertNull("nor is its key given out", r.getContentKey(items[0]))
        assertFalse("the note shared by itself stays", r.isKeptAway(items[2]))
        assertEquals("and what the server said is kept, for a read against a server that does not say", setOf(nb), offeredStored)
    }

    /** The same for a notebook offered again after a decline. */
    @Test
    fun `a notebook offered again after a decline is held on the server's word, though its own row was not reached`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        val declinedAt = "2026-09-20T00:00:00.000000Z"
        // Declined, and read to the end: nothing on offer, the notebook declined.
        servePages(inboxPageOffering(listOf(share(items[2])), hasMore = false, offered = emptyList(), declined = mapOf(nb to declinedAt)))
        r.refresh()
        dao.written.clear()

        // Its owner takes the reader out and invites them again: on offer again, and
        // not declined. The new notes are on the first page; the invitation's own row
        // is past it.
        servePages(
            inboxPageOffering(
                listOf(noteIn(nb, items[0], sharedAt = "2026-10-07T09:00:00.000000Z"), share(items[2])),
                hasMore = true, offered = listOf(nb), declined = emptyMap(),
            ),
            next = null,
        )
        r.refresh()
        assertEquals(emptyList<String>(), dao.written)
        assertTrue(r.isKeptAway(items[0]))
    }

    /**
     * "Declined" is kept from an earlier answer when this one does not say. A notebook the server
     * now says is ON OFFER is not declined, whatever was kept: its owner took the reader out and
     * invited them again. Its own row may lie past where the reading stopped, so the rows alone
     * could not say so.
     */
    @Test
    fun `a notebook on the server's list is no longer declined, though this answer says nothing of declines`() = runBlocking {
        notesNameTheirNotebook()
        var accepted = emptySet<String>()
        val r = repo(accepted = { accepted })
        // Declined earlier, by an answer that said so.
        servePages(inboxPageOffering(emptyList(), hasMore = false, offered = emptyList(), declined = mapOf(nb to "2026-09-20T00:00:00.000000Z")))
        r.refresh()

        // Offered again and accepted. This answer names what is on offer and says
        // nothing of declines; the invitation's own row is past where the reading
        // stops. The note was shared after the earlier decline.
        accepted = setOf(nb)
        servePages(
            inboxPageOffering(listOf(noteIn(nb, items[0], sharedAt = "2026-10-07T09:00:00.000000Z")), hasMore = true, offered = listOf(nb)),
            next = null,
        )
        r.refresh()
        assertEquals("the accepted notebook's new note is written", listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
    }

    @Test
    fun `an accepted notebook on the server's list has its notes written`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo(accepted = { setOf(nb) })
        servePages(inboxPageOffering(listOf(noteIn(nb, items[0])), hasMore = true, offered = listOf(nb)), next = null)
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
    }

    /** With the server's word, a first read that stops early holds only what IS on offer: not every note of every notebook. */
    @Test
    fun `with the server's list a read that stops early holds nothing on a guess`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        // No read to the end yet on this device. nb is on offer; nb2 is some notebook of its
        // owner's that was never shared with this account (the note was shared by itself).
        servePages(
            inboxPageOffering(listOf(noteIn(nb, items[0]), noteIn(nb2, items[1])), hasMore = true, offered = listOf(nb)),
            next = null,
        )
        r.refresh()
        assertEquals("the note of a notebook that is not on offer is written", listOf(items[1]), dao.written)
        assertTrue(r.isKeptAway(items[0]))
        assertFalse(r.isKeptAway(items[1]))
        assertEquals(setOf(nb), offeredStored)
    }

    @Test
    fun `a server list with an entry that is no id is not taken - the read works it out as if the server had not said`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(inboxPage(listOf(noteIn(nb, items[0])), hasMore = false))
        r.refresh()
        dao.written.clear()
        // Not whole (one entry is no notebook id): as if the server had not said. By
        // the rule for a server that does not say, nothing says the new note's
        // notebook is on offer, and it is written.
        servePages(
            inboxPageOffering(listOf(noteIn(nb, items[0]), noteIn(nb, items[1])), hasMore = true, offered = listOf(nb, "not-a-notebook")),
            next = null,
        )
        r.refresh()
        assertEquals(listOf(items[1]), dao.written)
        assertEquals("and nothing is remembered from it", emptySet<String>(), offeredStored)
    }

    @Test
    fun `only the FIRST page's word on what is on offer counts`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(
            inboxPageOffering(listOf(noteIn(nb, items[0])), hasMore = true, offered = listOf(nb)),
            next = inboxPageOffering(listOf(noteIn(nb2, items[1])), hasMore = false, offered = listOf(nb2)),
        )
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))
        assertEquals("the second page's list is not the inbox's", listOf(items[1]), dao.written)
        assertEquals(setOf(nb), offeredStored)
    }

    @Test
    fun `a read that stops early keeps holding the notes it did not reach`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(inboxPage(listOf(noteIn(nb, items[0]), noteIn(nb, items[1]), notebook(nb)), hasMore = false))
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))
        assertTrue(r.isKeptAway(items[1]))
        // The next read reaches the first note only.
        servePages(inboxPage(listOf(noteIn(nb, items[0])), hasMore = true), next = null)
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))
        assertTrue("the pull still asks about the one that was not reached", r.isKeptAway(items[1]))
        assertEquals(emptyList<String>(), dao.written)

        // A read to the end that does not find the second note lets
        // it go.
        servePages(inboxPage(listOf(noteIn(nb, items[0]), notebook(nb)), hasMore = false))
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))
        assertFalse(r.isKeptAway(items[1]))
    }

    @Test
    fun `a refresh that reads to the end remembers what was on offer and what was accepted`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo(accepted = { setOf(nb2) })
        servePages(inboxPage(listOf(noteIn(nb2, items[0]), notebook(nb), notebook(nb2)), hasMore = false))
        r.refresh()
        assertEquals(setOf(nb, nb2), offeredStored)
        assertEquals(setOf(nb2), acceptedStored)
        assertEquals(listOf(items[0]), dao.written)
    }

    // --- The key of a held note is never handed out ------------------------------------------

    /**
     * The key of a held note is never published, not even briefly: published with the rest at the
     * swap and taken back after the stored-ids work, a decrypt loop and a metadata read, it would
     * let a pull that overlapped open the note in between and write its title.
     */
    @Test
    fun `the key of a held note is never out, not even for a moment`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(inboxPage(listOf(noteIn(nb, items[0]), share(items[1]), notebook(nb)), hasMore = false))
        // Looked at right after the swap (the stored ids are the first thing read then) and at
        // the first row written: is the held note's key out, is the note known as held, and is
        // the other note's key out.
        val seen = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        val look = { seen += Triple(r.getContentKey(items[0]) != null, r.isKeptAway(items[0]), r.getContentKey(items[1]) != null) }
        every { session.getInboxShareIds() } answers { look(); storedInboxIds }
        dao.beforeUpsert = { look() }
        r.refresh()
        assertEquals(listOf(Triple(false, true, true), Triple(false, true, true)), seen)
        assertEquals(listOf(items[1]), dao.written)
    }

    // --- A wipe that has BEGUN -----------------------------------------------------------------

    /**
     * A wipe begins, on another thread as a real one does, and this returns as soon as it HAS
     * begun. Its start then waits its turn among the row writes, with no limit: begun from
     * inside a row's own turn and waited for there, it would wait for itself. The caller waits
     * for [wipesBegun] to finish once the refresh has let it in.
     */
    private val wipesBegun = mutableListOf<CompletableFuture<Void>>()
    private fun aWipeBegins(r: SharesInboxRepository) {
        wipesBegun += CompletableFuture.runAsync { runBlocking { r.wipeIsStarting() } }
        val giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!r.wipeUnderWay) {
            check(System.nanoTime() < giveUp) { "the wipe did not begin" }
            Thread.sleep(1)
        }
    }
    private fun theWipesHaveStarted() {
        wipesBegun.forEach { it.get(10, TimeUnit.SECONDS) }
        wipesBegun.clear()
    }

    /**
     * The wipe proper (`wipe()`) comes after the tables and the web view are cleared. A refresh
     * that learned of the wipe only then would have all that time to store and write, so
     * LocalDataWiper says so first ([SharesInboxRepository.wipeIsStarting]).
     */
    @Test
    fun `a wipe that has begun right after the keys are out - nothing more is stored, removed or written`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        storedInboxIds = setOf(gone)
        dao.real.upsert(rowOfAlice(gone))
        val r = repo()
        every { session.getInboxShareIds() } answers { runBlocking { r.wipeIsStarting() }; storedInboxIds }
        assertEquals(0, r.refresh())
        assertEquals(emptyList<String>(), dao.written)
        assertEquals(emptyList<String>(), dao.deleted)
        assertEquals(0, storedIdsWritten)
        assertNull(r.listedNotebookIds)
    }

    @Test
    fun `a wipe that has begun leaves the row of a held note to the wipe too`() = runBlocking {
        notesNameTheirNotebook()
        // A row stored earlier for a note whose invitation nobody has answered
        // (an older version wrote such rows).
        dao.real.upsert(rowOfAlice(items[0]))
        servePages(inboxPage(listOf(noteIn(nb, items[0]), notebook(nb)), hasMore = false))
        val r = repo()
        // The wipe begins as the share ids are stored: after that step's own question, and
        // before the held notes' rows are looked at.
        val saved = slot<Set<String>>()
        every { session.setInboxShareIds(capture(saved)) } answers { storedIdsWritten++; storedInboxIds = saved.captured; runBlocking { r.wipeIsStarting() } }
        assertEquals(0, r.refresh())
        assertEquals(1, storedIdsWritten)
        assertEquals(emptyList<String>(), dao.deleted)

        // Without the wipe, the row is taken out.
        every { session.setInboxShareIds(capture(saved)) } answers { storedIdsWritten++; storedInboxIds = saved.captured }
        r.wipeIsOver()
        r.refresh()
        assertEquals(listOf(items[0]), dao.deleted)
    }

    @Test
    fun `a wipe that begins as the last row that left is removed leaves the stored ids alone`() = runBlocking {
        serve(share(items[0]))
        storedInboxIds = setOf(gone)
        dao.real.upsert(rowOfAlice(gone))
        val r = repo()
        // After the one removal there is: nothing more to remove, so the next thing asked is
        // whether the ids may be stored.
        dao.afterDelete = { aWipeBegins(r) }
        assertEquals(0, r.refresh())
        theWipesHaveStarted()
        assertEquals(listOf(gone), dao.deleted)
        assertEquals("the ids are not stored once a wipe has begun", 0, storedIdsWritten)
        assertEquals(setOf(gone), storedInboxIds)
        assertEquals(emptyList<String>(), dao.written)
    }

    @Test
    fun `a wipe that begins while the rows are being written stops the rest`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        // It begins inside the second write, which is past its own question; the third asks.
        dao.beforeUpsert = { if (dao.written.size == 1) aWipeBegins(r) }
        assertEquals(0, r.refresh())
        theWipesHaveStarted()
        assertEquals(2, dao.written.size)
        assertNull("and the list is not one to prune against", r.listedNotebookIds)
    }

    @Test
    fun `a wipe that begins while a share's row is being removed stops the rest`() = runBlocking {
        val alsoGone = "8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        serve(share(items[0]))
        storedInboxIds = setOf(gone, alsoGone)
        dao.real.upsert(rowOfAlice(gone))
        dao.real.upsert(rowOfAlice(alsoGone))
        val r = repo()
        dao.afterDelete = { if (wipesBegun.isEmpty()) aWipeBegins(r) }
        assertEquals(0, r.refresh())
        theWipesHaveStarted()
        assertEquals("one row went before the wipe began; the other is left to the wipe", 1, dao.deleted.size)
        assertEquals("the stored ids are not replaced by a list that forgets it", 0, storedIdsWritten)
        assertEquals(emptyList<String>(), dao.written)
    }

    @Test
    fun `a wipe that begins while the inbox is being fetched - nothing is saved, published or remembered`() = runBlocking<Unit> {
        notesNameTheirNotebook()
        val r = repo(accepted = { setOf(nb2) })
        coEvery { noteApi.listInbox(any(), any(), any()) } answers {
            runBlocking { r.wipeIsStarting() }
            RetrofitResponse.success(ApiResponse(success = true, data = inboxPage(items.map { share(it) } + notebook(nb2), hasMore = false)))
        }
        assertEquals(0, r.refresh())
        assertNothingOfTheRefreshIsOut(r)
        assertEquals("no floor or owner saved", 0, floorPrefs.commits)
        assertNull("what is on offer is not stored", offeredStored)
        assertEquals("nor the acceptances this device remembers", 0, acceptedWritten)
        assertEquals(emptyList<SharesInboxRepository.SharedNotebook>(), r.sharedNotebooksFlow.value)
    }

    /**
     * Until the wipe is over the account is still the outgoing one (the session is changed after
     * it), so a refresh that STARTED in the middle would find nothing changed under it, and write
     * that account's rows into tables the wipe had already emptied.
     */
    @Test
    fun `no refresh starts while a wipe is under way, and the one after it is ordinary`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        r.wipeIsStarting()
        assertEquals(0, r.refresh())
        r.wipe()
        assertEquals("not after the inbox's own step of the wipe either", 0, r.refresh())
        io.mockk.coVerify(exactly = 0) { noteApi.listInbox(any(), any(), any()) }
        assertEquals(emptyList<String>(), dao.written)

        r.wipeIsOver()
        assertEquals(items.size, r.refresh())
        assertEquals(items.sorted(), dao.written.sorted())
    }

    // --- A row write and a wipe's start take turns
    // --------------------------------------------

    /**
     * Each write asks first whether a wipe has begun, and something must hold the answer still: a
     * wipe that began between the question and the write would empty the table, and the write
     * would then land in it, leaving one row of the outgoing account behind. A wipe's start waits
     * for a write that has passed its question, so the clear that follows it takes that row with
     * the rest, and every later write finds the wipe begun.
     */
    @Test
    fun `a wipe that begins while a row is being written waits for that row, and no row is written after it`() {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        val inTheWrite = java.util.concurrent.CountDownLatch(1)
        val letItFinish = java.util.concurrent.CountDownLatch(1)
        dao.beforeUpsert = {
            if (dao.written.isEmpty()) {
                inTheWrite.countDown()
                assertTrue("the test let the write finish", letItFinish.await(10, TimeUnit.SECONDS))
            }
        }
        val refresh = CompletableFuture.supplyAsync { runBlocking { r.refresh() } }
        assertTrue("the first row's write is under way", inTheWrite.await(10, TimeUnit.SECONDS))

        // The wipe begins now, on its own thread, as a sign-out does.
        val rowsWhenTheWipeBegan = java.util.concurrent.atomic.AtomicInteger(-1)
        val wipe = CompletableFuture.runAsync {
            runBlocking { r.wipeIsStarting() }
            rowsWhenTheWipeBegan.set(dao.written.size)
        }
        Thread.sleep(300)
        assertFalse("it waits for the row that is being written", wipe.isDone)

        letItFinish.countDown()
        wipe.get(10, TimeUnit.SECONDS)
        assertEquals("the stopped refresh", 0, refresh.get(10, TimeUnit.SECONDS))
        assertEquals("that row was in the table before the wipe began: its clear takes it", 1, rowsWhenTheWipeBegan.get())
        assertEquals("and nothing was written after", 1, dao.written.size)
        r.wipeIsOver()
    }

    /**
     * The same, with the wipe arriving at the one moment that matters: BETWEEN a row's question
     * ("has a wipe begun?") and its write. The two are one turn, so the wipe cannot begin there:
     * it begins after the write, and no row is written once it has. (Asked outside the turn, the
     * question would be answered "no", the wipe would begin, and the row would then be written
     * after it; the test above would not notice, as its wipe arrives while the write itself is
     * under way.)
     */
    @Test
    fun `a wipe that arrives between a row's question and its write begins only after that write`() {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        val rowsWhenTheWipeBegan = java.util.concurrent.atomic.AtomicInteger(-1)
        var wipe: CompletableFuture<Void>? = null
        // The stored ids are written just before the rows are: from there on, the next thing
        // that asks who is signed in is the first row's question.
        var theRowsAreNext = false
        val saved = slot<Set<String>>()
        every { session.setInboxShareIds(capture(saved)) } answers { storedInboxIds = saved.captured; theRowsAreNext = true }
        every { session.getUserId() } answers {
            if (theRowsAreNext && wipe == null) {
                wipe = CompletableFuture.runAsync {
                    runBlocking { r.wipeIsStarting() }
                    rowsWhenTheWipeBegan.set(dao.written.size)
                }
                // Long enough for a wipe that CAN begin to have begun.
                try { wipe!!.get(400, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.TimeoutException) {}
            }
            me
        }
        assertEquals("the refresh is stopped by the wipe", 0, runBlocking { r.refresh() })
        wipe!!.get(10, TimeUnit.SECONDS)
        assertEquals("one row was written, the one whose question had been asked", 1, dao.written.size)
        assertEquals("and it was in the table before the wipe began", dao.written.size, rowsWhenTheWipeBegan.get())
        r.wipeIsOver()
    }

    /** A refresh whose first row write is held by the test: [first] is set when it is under way, [letGo] lets it finish. */
    private class HeldWrite(val refresh: CompletableFuture<Int>, val letGo: () -> Unit)
    private fun aRefreshWhoseFirstWriteIsHeld(r: SharesInboxRepository): HeldWrite {
        val inTheWrite = java.util.concurrent.CountDownLatch(1)
        val letItFinish = java.util.concurrent.CountDownLatch(1)
        dao.beforeUpsert = {
            if (dao.written.isEmpty()) {
                inTheWrite.countDown()
                assertTrue("the test let the write finish", letItFinish.await(20, TimeUnit.SECONDS))
            }
        }
        val refresh = CompletableFuture.supplyAsync { runBlocking { r.refresh() } }
        assertTrue("the first row's write is under way", inTheWrite.await(10, TimeUnit.SECONDS))
        return HeldWrite(refresh) { letItFinish.countDown() }
    }

    /**
     * The wait for the row has no limit: with one, a wipe whose limit ran out would begin anyway
     * and go on to clear the table, and the row it had been waiting for would land AFTER the
     * clear. The wipe's start returns only when that row is in the table.
     */
    @Test
    fun `however long the row takes, the wipe's start does not return before it is written`() {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        val held = aRefreshWhoseFirstWriteIsHeld(r)
        val rowsWhenTheStartReturned = java.util.concurrent.atomic.AtomicInteger(-1)
        val wipe = CompletableFuture.runAsync {
            runBlocking { r.wipeIsStarting() }
            rowsWhenTheStartReturned.set(dao.written.size)
        }
        // Begun at once, long before its start returns: nothing new is let in meanwhile.
        val giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!r.wipeUnderWay) { check(System.nanoTime() < giveUp); Thread.sleep(1) }
        // Longer than the five seconds a bounded wait would
        // give up after.
        Thread.sleep(5_600)
        assertFalse("still waiting for the row", wipe.isDone)
        assertEquals("and nothing is written yet", 0, dao.written.size)

        held.letGo()
        wipe.get(10, TimeUnit.SECONDS)
        assertEquals(0, held.refresh.get(10, TimeUnit.SECONDS))
        assertEquals("the row was in the table when the start returned: the clear takes it", 1, rowsWhenTheStartReturned.get())
        assertEquals(1, dao.written.size)
        r.wipeIsOver()
    }

    /**
     * A wipe that waited for a row counts once. Begun twice and ended once, it would leave
     * no refresh ever starting again in that process.
     */
    @Test
    fun `a wipe that waited for a row is one wipe - when it is over, refreshes start again`() {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        val held = aRefreshWhoseFirstWriteIsHeld(r)
        val wipe = CompletableFuture.runAsync { runBlocking { r.wipeIsStarting() } }
        Thread.sleep(150)
        held.letGo()
        wipe.get(10, TimeUnit.SECONDS)
        held.refresh.get(10, TimeUnit.SECONDS)
        assertTrue(r.wipeUnderWay)
        r.wipeIsOver()
        assertFalse("one start, one end", r.wipeUnderWay)
        dao.beforeUpsert = {}
        dao.written.clear()
        assertEquals(items.size, runBlocking { r.refresh() })
    }

    /**
     * A caller cancelled while it waits has already begun its wipe, so the "over" in its
     * `finally` ends its own wipe — never the count of ANOTHER wipe under way, which would let
     * a refresh start in the middle of it.
     */
    @Test
    fun `a wipe whose caller is cancelled while it waits has begun, and its end is its own`() {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        val held = aRefreshWhoseFirstWriteIsHeld(r)
        // The second wipe (an erasure, say) begins while the row is being written, and waits.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        val saidOver = java.util.concurrent.CountDownLatch(1)
        val second = scope.launch {
            // As LocalDataWiper: started, then said to be over whatever became of it.
            try { r.wipeIsStarting() } finally { r.wipeIsOver(); saidOver.countDown() }
        }
        val giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!r.wipeUnderWay) { check(System.nanoTime() < giveUp); Thread.sleep(1) }
        // The first (a sign-out) is under way too. It waits like the other, on its own thread.
        val first = CompletableFuture.runAsync { runBlocking { r.wipeIsStarting() } }
        Thread.sleep(150)

        // The second one's caller goes away while it waits.
        second.cancel()
        assertTrue(saidOver.await(10, TimeUnit.SECONDS))
        assertTrue("the first wipe is still under way: the cancelled one ended only itself", r.wipeUnderWay)

        held.letGo()
        first.get(10, TimeUnit.SECONDS)
        held.refresh.get(10, TimeUnit.SECONDS)
        assertTrue(r.wipeUnderWay)
        dao.beforeUpsert = {}
        assertEquals("no refresh starts while it is", 0, runBlocking { r.refresh() })
        r.wipeIsOver()
        assertFalse(r.wipeUnderWay)
    }

    /** Two wipes can overlap (a sign-out while an erasure is still clearing): the end of one is not the end of both. */
    @Test
    fun `no refresh starts until EVERY wipe under way is over`() = runBlocking {
        serve(*items.map { share(it) }.toTypedArray())
        val r = repo()
        r.wipeIsStarting()
        r.wipeIsStarting()
        r.wipeIsOver()
        assertEquals("one of the two is still under way", 0, r.refresh())
        io.mockk.coVerify(exactly = 0) { noteApi.listInbox(any(), any(), any()) }
        r.wipeIsOver()
        assertEquals(items.size, r.refresh())
        // One "over" too many changes nothing.
        r.wipeIsOver()
        r.wipeIsStarting()
        assertEquals(0, r.refresh())
        r.wipeIsOver()
    }

    // --- The invitation hold outlives the process
    // ----------------------------------------------

    /**
     * A note held for an invitation stays held when the invitation is declined: the rule for a
     * declined notebook keeps away only what was shared after the decline. Kept in memory only,
     * the hold would be lost at the app's next start, which would then write those notes to the
     * database the widgets read.
     */
    @Test
    fun `a note held for an invitation is still held after a restart, when the invitation has been declined`() = runBlocking {
        notesNameTheirNotebook()
        servePages(inboxPage(listOf(noteIn(nb, items[0]), notebook(nb)), hasMore = false))
        repo().refresh()
        assertEquals("the hold is stored", setOf(items[0]), heldStored)
        assertEquals(emptyList<String>(), dao.written)

        // A new process: nothing in memory. The invitation was declined meanwhile, LATER than the
        // note was shared, and its own row went with the decline.
        val afterRestart = repo()
        servePages(inboxPage(listOf(noteIn(nb, items[0])), hasMore = false, declined = mapOf(nb to "2026-10-01T00:00:00.000000Z")))
        afterRestart.refresh()
        assertEquals(emptyList<String>(), dao.written)
        assertTrue(afterRestart.isKeptAway(items[0]))
        assertNull(afterRestart.getContentKey(items[0]))
    }

    @Test
    fun `the control - with nothing stored, the same note is the reader's in its own right`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(inboxPage(listOf(noteIn(nb, items[0])), hasMore = false, declined = mapOf(nb to "2026-10-01T00:00:00.000000Z")))
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
    }

    @Test
    fun `the stored hold is rewritten only when it changes, and emptied when nothing is held any more`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(inboxPage(listOf(noteIn(nb, items[0]), notebook(nb)), hasMore = false))
        r.refresh()
        r.refresh()
        r.refresh()
        assertEquals("written once for three refreshes that held the same", 1, heldWritten)

        // Accepted: nothing is held for an invitation now.
        val accepting = repo(accepted = { setOf(nb) })
        accepting.refresh()
        assertEquals(emptySet<String>(), heldStored)
        assertEquals(listOf(items[0]), dao.written)
    }

    /** A wipe must not leave the hold in memory. */
    @Test
    fun `the hold is not carried over a wipe - what one account held says nothing of the next`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(inboxPage(listOf(noteIn(nb, items[0]), notebook(nb)), hasMore = false))
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))

        // The wiper: this repository's own step, and the account's stored state cleared.
        r.wipe()
        heldStored = emptySet()
        assertFalse(r.isKeptAway(items[0]))

        // The same note, now of a notebook declined after it was shared: with no hold carried
        // over, it is the reader's in its own right.
        servePages(inboxPage(listOf(noteIn(nb, items[0])), hasMore = false, declined = mapOf(nb to "2026-10-01T00:00:00.000000Z")))
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
    }

    /** A hold is carried across a read that stopped early. */
    @Test
    fun `a hold for an invitation is carried across a read that did not reach the note`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(inboxPage(listOf(noteIn(nb, items[0]), noteIn(nb, items[1]), notebook(nb)), hasMore = false))
        r.refresh()
        assertEquals(setOf(items[0], items[1]), heldStored)

        // A read that stops after the first note: the second is not reached.
        servePages(inboxPage(listOf(noteIn(nb, items[0])), hasMore = true), next = null)
        r.refresh()
        assertEquals("the one it did not reach is still held for the invitation", setOf(items[0], items[1]), heldStored)

        // The invitation is declined, later than both were shared. Both were held for it: both stay.
        servePages(
            inboxPage(listOf(noteIn(nb, items[0]), noteIn(nb, items[1])), hasMore = false, declined = mapOf(nb to "2026-10-01T00:00:00.000000Z")),
        )
        r.refresh()
        assertEquals(emptyList<String>(), dao.written)
        assertTrue(r.isKeptAway(items[0]))
        assertTrue(r.isKeptAway(items[1]))
    }

    /**
     * A note held because it ARRIVED UNSEEN (a new invitation's, whose own row a read that
     * stopped early did not come to) must be remembered as an invitation's. Otherwise, when
     * the reader then declines that invitation, nothing says the note had been held for it,
     * the rule for a declined notebook keeps away only what was shared after the decline, and
     * the note is written to the database the widgets read.
     */
    @Test
    fun `a note held because it arrived unseen stays away when its invitation is then declined`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        // A read to the end: nothing on offer, one note shared by itself.
        servePages(inboxPage(listOf(share(items[2]).copy(createdAt = "2026-09-20T00:00:00Z")), hasMore = false))
        r.refresh()
        dao.written.clear()

        // An invitation made since: its note is the newest row, its own row on the page that does not come.
        servePages(
            inboxPage(
                listOf(noteIn(nb, items[0], sharedAt = "2026-09-21T00:00:02Z"), share(items[2]).copy(createdAt = "2026-09-20T00:00:00Z")),
                hasMore = true,
            ),
            next = null,
        )
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))
        assertEquals("and it is remembered as held for an invitation", setOf(items[0]), heldStored)

        // The reader declines it (in the page, or on another device), AFTER the note was shared.
        servePages(
            inboxPage(
                listOf(noteIn(nb, items[0], sharedAt = "2026-09-21T00:00:02Z"), share(items[2]).copy(createdAt = "2026-09-20T00:00:00Z")),
                hasMore = false, declined = mapOf(nb to "2026-09-22T00:00:00.000000Z"),
            ),
        )
        r.refresh()
        assertEquals("a decline does not let the invitation's note in", emptyList<String>(), dao.written)
        assertTrue(r.isKeptAway(items[0]))
        assertNull(r.getContentKey(items[0]))
        assertFalse("the note shared by itself is untouched", r.isKeptAway(items[2]))
    }

    /**
     * The other side of the same rule: a read that knows NOTHING of what is on offer holds
     * everything not accepted, and none of that is remembered as an invitation's. A notebook
     * declined long before, whose earlier note the reader holds in its own right, is not kept
     * away because of such a read.
     */
    @Test
    fun `a note held only because nothing was known is not remembered as an invitation's`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        // The very first read stops early, and the server does not say what is on offer.
        servePages(inboxPage(listOf(noteIn(nb, items[0], sharedAt = "2026-09-21T00:00:02Z")), hasMore = true), next = null)
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))
        assertEquals(emptySet<String>(), heldStored)

        // A read to the end: its notebook was declined AFTER the note was shared with them by
        // itself, so the note is theirs, and it is let in.
        servePages(
            inboxPage(listOf(noteIn(nb, items[0], sharedAt = "2026-09-21T00:00:02Z")), hasMore = false, declined = mapOf(nb to "2026-09-25T00:00:00.000000Z")),
        )
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
    }

    /** A refresh that was stopped has let nothing in, and says so. */
    @Test
    fun `an answer that leads to a refresh a wipe stops is not reported as one that let the notes in`() = runBlocking {
        notesNameTheirNotebook()
        var accepted = emptySet<String>()
        val r = repo(accepted = { accepted })
        servePages(inboxPage(listOf(noteIn(nb, items[0]), notebook(nb)), hasMore = false))
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))

        // The acceptance arrives, and the refresh it leads to is overtaken by a wipe.
        accepted = setOf(nb)
        coEvery { noteApi.listInbox(any(), any(), any()) } answers {
            runBlocking { r.wipeIsStarting() }
            RetrofitResponse.success(ApiResponse(success = true, data = inboxPage(listOf(noteIn(nb, items[0]), notebook(nb)), hasMore = false)))
        }
        assertFalse(r.refreshIfAnswersChanged())
        assertEquals(emptyList<String>(), dao.written)
        r.wipeIsOver()

        // And the one that gets to its end is.
        servePages(inboxPage(listOf(noteIn(nb, items[0]), notebook(nb)), hasMore = false))
        assertTrue(r.refreshIfAnswersChanged())
        assertEquals(listOf(items[0]), dao.written)
    }

    // --- Declining an invitation ---------------------------------------------------------------

    /**
     * A declined notebook is not on offer, so "not answered" stops holding its notes; and the
     * rule for a declined notebook keeps away only what was shared AFTER the decline. A note
     * shared before it, whose row on the server does not name its notebook (so the server could
     * not take it with the decline), would appear on the widget the moment the reader said no.
     */
    @Test
    fun `declining an invitation does not bring back the notes that were held for it`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        servePages(inboxPage(listOf(noteIn(nb, items[0]), notebook(nb)), hasMore = false))
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))

        // Declined, LATER than the note was shared; the notebook's own row went with the decline.
        // Beside it a note of a notebook declined long ago, which this device never held for an
        // invitation: shared with the reader before that decline, it is theirs in its own right.
        val declinedAt = "2026-10-01T00:00:00.000000Z"
        val declinedBoth = mapOf(nb to declinedAt, nb2 to declinedAt)
        servePages(inboxPage(listOf(noteIn(nb, items[0]), noteIn(nb2, items[1])), hasMore = false, declined = declinedBoth))
        r.refresh()
        assertEquals(listOf(items[1]), dao.written)
        assertTrue(r.isKeptAway(items[0]))
        assertNull(r.getContentKey(items[0]))

        // It stays held at the next refresh too.
        dao.written.clear()
        r.refresh()
        assertEquals(emptyList<String>(), dao.written)
        assertTrue(r.isKeptAway(items[0]))

        // The notebook is not declined now, and not on offer: nothing holds the
        // note.
        servePages(inboxPage(listOf(noteIn(nb, items[0]), noteIn(nb2, items[1])), hasMore = false, declined = mapOf(nb2 to declinedAt)))
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
    }

    /**
     * Only a note that was held FOR THE INVITATION goes with its decline. One that waited for
     * another reason (nothing said what was on offer) and was shared before the decline is the
     * reader's in its own right, as the server has it: it must not stay held for good.
     */
    @Test
    fun `a note that waited only because nothing said what was on offer is not taken by a decline it predates`() = runBlocking {
        notesNameTheirNotebook()
        val r = repo()
        // No read has reached the end yet, and this one stops early: the note waits.
        servePages(inboxPage(listOf(noteIn(nb, items[0])), hasMore = true), next = null)
        r.refresh()
        assertTrue(r.isKeptAway(items[0]))
        assertEquals(emptyList<String>(), dao.written)

        // The read to the end: its notebook was declined, LATER than this note was shared, and
        // was never on offer while this device looked.
        servePages(inboxPage(listOf(noteIn(nb, items[0])), hasMore = false, declined = mapOf(nb to "2026-10-01T00:00:00.000000Z")))
        r.refresh()
        assertEquals(listOf(items[0]), dao.written)
        assertFalse(r.isKeptAway(items[0]))
    }

    // --- The write asks whose row it is, against the real database --------------------------------

    /**
     * "One of our own rows is still never written over" above is refused before anything is
     * unwrapped, so it never comes to the question at the write. This one does: there is no row
     * when the refresh starts, and one of OURS lands (a pull) while the signatures are checked.
     */
    @Test
    fun `one of our own rows that lands while the refresh runs is not written over`() = runBlocking {
        every { crypto.decryptNoteWithContentKeyV2(any(), "ours", match { it.contentEquals(vault) }, any()) } returns """{"title":"mine"}"""
        val ours = ItemEntity(
            id = items[0], userId = alice, itemType = 1, encryptedContent = "ours", contentNonce = "nonce",
            decryptedContent = """{"title":"mine"}""", title = "mine",
            createdAt = java.time.Instant.parse("2026-01-01T00:00:00Z"), updatedAt = java.time.Instant.parse("2026-01-02T00:00:00Z"),
        )
        serve(share(items[0]), share(items[1]))
        val r = repo()
        var opened = 0
        every { crypto.openSeal(any(), any()) } answers {
            if (++opened == 1) runBlocking { dao.real.upsert(ours) }
            contentKey
        }
        r.refresh()
        assertEquals("only the other share's row is written", listOf(items[1]), dao.written)
        assertEquals(ours, dao.real.getById(items[0]))
    }
}
