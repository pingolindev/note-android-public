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
import android.util.Log
import androidx.test.core.app.ApplicationProvider
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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Instant
import retrofit2.Response as RetrofitResponse

/**
 * The inbox's owner guards against the REAL pin and floor stores:
 * - A share naming a row this device holds that isn't that owner's — one of ours by its user
 *   id or by a wrap our vault opens, or another owner's — is refused before anything is unwrapped;
 *   so is a share naming this account as its sender. Only that owner's clean row is written over,
 *   and the un-share diff deletes only a share's row.
 * - Each item is bound to the owner of its first accepted share; anyone else's share of it is
 *   refused, and a binding survives a refresh without the owner's share.
 * - A first share whose directory entry names another account pins nothing and is refused.
 * - An owners blob that doesn't parse stops the refresh before the fetch, with an error line.
 * - A note is sealed with the key that opened it: the inbox's key is never handed out for one of
 *   our rows, even one that landed after the refresh (sharedKeyFor).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SharesInboxOwnerGuardTest {

    private val me = "00000000-0000-4000-8000-00000000000e"
    private val alice = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val bob = "2a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val item = "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private val signA = "a".repeat(64)
    private val shareA = "b".repeat(64)
    private val signB = "c".repeat(64)
    private val shareB = "d".repeat(64)
    private val sig = "cd".repeat(64)
    private val contentKey = ByteArray(32) { 7 }
    private val privB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
    private val vault = ByteArray(32) { 5 }
    private val recoveredVault = ByteArray(32) { 6 }
    private val b64 = { k: ByteArray -> java.util.Base64.getEncoder().encodeToString(k) }


    private lateinit var pinPrefs: SharedPreferences
    private lateinit var floorPrefs: SharedPreferences
    private lateinit var pins: SenderKeyPinStore
    private lateinit var floors: NoteGenFloorStore
    private val noteApi = mockk<NoteApi>()
    private val session = mockk<SessionManager>()
    /** What the session store holds as "on offer at the last read to the end": null until one is stored. */
    private var offeredStored: Set<String>? = null
    private val crypto = mockk<NoteCrypto>()
    private val itemDao = mockk<ItemDao>()
    private val previousIdentities = mockk<PreviousIdentityLoader>()
    private var storedInboxIds: Set<String> = emptySet()
    private val rows = mutableMapOf<String, ItemEntity>()

    @Before
    fun setUp() {
        ShadowLog.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        pinPrefs = context.getSharedPreferences("guard_pins_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        floorPrefs = context.getSharedPreferences("guard_floors_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        pins = SenderKeyPinStore({ pinPrefs }, session)
        floors = NoteGenFloorStore({ floorPrefs }, session)
        rows.clear()

        every { session.getUserId() } returns me
        every { session.getSharingPrivateKey() } returns privB64
        every { session.getEmail() } returns null
        every { session.getMasterKey() } returns b64(vault)
        every { previousIdentities.vaultKeys() } returns emptyList()
        every { session.getInboxShareIds() } answers { storedInboxIds }
        val saved = slot<Set<String>>()
        every { session.setInboxShareIds(capture(saved)) } answers { storedInboxIds = saved.captured }

        directory(PublicKeyLookupEntry("alice@x.y", alice, shareA, signA), PublicKeyLookupEntry("bob@x.y", bob, shareB, signB))
        every { crypto.openSeal(any(), any()) } returns contentKey
        every { crypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns """{"title":"t"}"""
        // Our vault keys open no row's body unless a test says so (a legacy note of ours).
        every {
            crypto.decryptNoteWithContentKeyV2(any(), any(), match { it.contentEquals(vault) || it.contentEquals(recoveredVault) }, any())
        } returns null
        every { crypto.unwrapWithVault(any(), any(), any()) } returns null
        coEvery { itemDao.getByIds(any()) } answers { firstArg<List<String>>().mapNotNull { rows[it] } }
        coEvery { itemDao.getById(any()) } answers { rows[firstArg()] }
        coEvery { itemDao.upsert(any()) } just Runs
        coEvery { itemDao.deleteById(any()) } just Runs
    }

    /** No shared notebook is accepted here, and none is on offer with notes inside: the hold
     *  on unanswered invitations has nothing to act on in these tests. */
    private fun repo(): SharesInboxRepository {
        val invites = mockk<NotebookInvites>()
        coEvery { invites.accepted() } returns emptySet()
        every { session.getSeenAcceptedNotebooks() } returns emptySet()
        every { session.setSeenAcceptedNotebooks(any()) } just Runs
        // What the last read to the inbox's end found on offer: stored like the acceptances.
        every { session.getOfferedNotebooks() } answers { offeredStored }
        every { session.getOffersKnownAsOf() } returns null
        every { session.setOffersKnownAsOf(any()) } returns Unit
        every { session.setOfferedNotebooks(any()) } answers { offeredStored = firstArg() }
        every { session.getHeldForAnInvitation() } returns emptySet()
        every { session.setHeldForAnInvitation(any()) } returns Unit
        return SharesInboxRepository(
            noteApi, session, crypto, itemDao, pins, floors, previousIdentities,
            javax.inject.Provider { invites },
        )
    }

    private fun directory(vararg entries: PublicKeyLookupEntry) {
        coEvery { noteApi.lookupPublicKeys(any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = PublicKeyLookupResponse(entries.toList())),
        )
    }

    private fun share(owner: String, email: String, itemId: String = item, signature: String? = null, shareId: String = "share-$owner", notebook: Boolean = false) =
        InboxShareDto(
            shareId = shareId,
            itemId = itemId,
            ownerId = owner,
            permission = "edit",
            wrappedKey = "sealed",
            sharerPublicKey = null,
            ownerEmail = email,
            wrapSignature = signature,
            encryptedContent = "enc",
            contentNonce = "nonce",
            updatedAt = "2026-09-29T00:00:00Z",
            createdAt = "2026-09-29T00:00:00Z",
            contentKeyGeneration = 1L,
            itemType = if (notebook) 2 else 1,
        )

    private fun serve(vararg shares: InboxShareDto) {
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(ApiResponse(success = true, data = bareList(shares.toList())))
    }

    private fun row(id: String = item, userId: String, wrap: String? = null, dirty: Boolean = false) = ItemEntity(
        id = id, userId = userId, itemType = 1, encryptedContent = "own-enc", contentNonce = "own-nonce",
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, isDirty = dirty,
        wrappedContentKey = wrap, wrappedContentKeyNonce = wrap?.let { "wn" },
    )

    // --- A share naming a row this device holds -----------------------------------------------

    @Test
    fun `a share naming one of our own rows is refused before anything is unwrapped, and the row is left alone`() = runTest {
        rows[item] = row(userId = me.uppercase(), wrap = "own-wrap")
        serve(share(alice, "alice@x.y"))
        val r = repo()
        r.refresh()
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
        assertNull(r.getContentKey(item))
        assertFalse(item in storedInboxIds)
        assertNull(pins.getPinned(alice))
    }

    @Test
    fun `a row our vault opens is ours whatever user id the server gave it`() = runTest {
        rows[item] = row(userId = alice, wrap = "own-wrap")
        every { crypto.unwrapWithVault("own-wrap", "wn", match { it.contentEquals(vault) }) } returns ByteArray(32) { 1 }
        serve(share(alice, "alice@x.y"))
        val r = repo()
        r.refresh()
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `so is a row a recovered identity's vault opens`() = runTest {
        rows[item] = row(userId = alice, wrap = "old-era-wrap")
        every { previousIdentities.vaultKeys() } returns listOf(b64(recoveredVault))
        every { crypto.unwrapWithVault("old-era-wrap", "wn", match { it.contentEquals(recoveredVault) }) } returns ByteArray(32) { 1 }
        serve(share(alice, "alice@x.y"))
        val r = repo()
        r.refresh()
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
    }

    @Test
    fun `a legacy row whose body our vault opens is ours whatever user id the server gave it`() = runTest {
        rows[item] = row(userId = alice)
        every { crypto.decryptNoteWithContentKeyV2("own-nonce", "own-enc", match { it.contentEquals(vault) }, item) } returns "{}"
        serve(share(alice, "alice@x.y"))
        val r = repo()
        r.refresh()
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
        assertNull(floors.owner(item))
    }

    @Test
    fun `a share naming another owner's row, or naming this account as its sender, is refused`() = runTest {
        rows[item] = row(userId = bob)
        serve(share(alice, "alice@x.y"))
        val r = repo()
        r.refresh()

        rows.clear()
        serve(share(me, "me@x.y"))
        r.refresh()
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
    }

    @Test
    fun `that owner's clean row is written over, a dirty one never`() = runTest {
        rows[item] = row(userId = alice.uppercase())
        serve(share(alice, "alice@x.y"))
        repo().refresh()
        coVerify(exactly = 1) { itemDao.upsert(match { it.id == item && it.userId == alice }) }

        rows[item] = row(userId = alice, dirty = true)
        repo().refresh()
        coVerify(exactly = 1) { itemDao.upsert(any()) }
    }

    @Test
    fun `the un-share diff deletes a share's row only`() = runTest {
        val own = "3a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val dirty = "4a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val unknown = "5a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val ours = "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val theirs = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        rows[own] = row(own, userId = me)
        rows[dirty] = row(dirty, userId = alice, dirty = true)
        rows[unknown] = row(unknown, userId = "")
        rows[ours] = row(ours, userId = alice, wrap = "own-wrap")
        rows[theirs] = row(theirs, userId = alice)
        every { crypto.unwrapWithVault("own-wrap", "wn", any()) } returns ByteArray(32) { 1 }
        storedInboxIds = setOf(own, dirty, unknown, ours, theirs)
        serve()
        repo().refresh()
        coVerify(exactly = 1) { itemDao.deleteById(theirs) }
        coVerify(exactly = 1) { itemDao.deleteById(any()) }
    }

    // --- the key that opened it -----------------------------------------------------------------

    @Test
    fun `one of our rows landing after the refresh never gets the share's key, and the next refresh refuses the share`() = runTest {
        // No row at the refresh (the sync refreshes before it pulls): the share is admitted.
        serve(share(alice, "alice@x.y"))
        val r = repo()
        r.refresh()
        assertTrue(r.getContentKey(item) != null)

        // The pull lands our own row: ours by its user id, by a wrap our vault opens under a
        // relabelled user id, or, with no wrap, by a body our vault opens.
        assertNull(r.sharedKeyFor(row(userId = me.uppercase(), wrap = "own-wrap")))
        every { crypto.unwrapWithVault("own-wrap", "wn", match { it.contentEquals(vault) }) } returns ByteArray(32) { 1 }
        assertNull(r.sharedKeyFor(row(userId = alice, wrap = "own-wrap")))
        every { crypto.decryptNoteWithContentKeyV2("own-nonce", "own-enc", match { it.contentEquals(vault) }, item) } returns "{}"
        assertNull(r.sharedKeyFor(row(userId = alice)))

        // The next refresh sees the row and refuses the share: the key is gone.
        rows[item] = row(userId = me)
        r.refresh()
        assertNull(r.getContentKey(item))
    }

    @Test
    fun `that owner's row gets the share's key, and a row the inbox holds no key for gets none`() = runTest {
        serve(share(alice, "alice@x.y"))
        val r = repo()
        r.refresh()
        assertTrue(r.sharedKeyFor(row(userId = alice)).contentEquals(contentKey))
        assertNull(r.sharedKeyFor(row(id = "3a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", userId = alice)))
    }

    // --- An item is bound to the owner of its first accepted share ----------------------------

    @Test
    fun `a share of a bound item from anyone else is refused before any pin, verify or unwrap`() = runTest {
        floors.accept(item, alice, 2L)
        pins.pin(bob, signB, shareB)
        every { crypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) } returns WrapVerify(true, 9u)
        serve(share(bob, "bob@x.y", signature = sig))
        val r = repo()
        r.refresh()
        verify(exactly = 0) { crypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
        assertEquals(2L, floors.floor(item))
        assertEquals(alice, floors.owner(item))
    }

    @Test
    fun `a binding survives a refresh without the owner's share`() = runTest {
        serve(share(alice, "alice@x.y"))
        repo().refresh()
        assertEquals(alice, floors.owner(item))
        serve()
        repo().refresh()
        assertEquals(alice, floors.owner(item))
        serve(share(bob, "bob@x.y"))
        val r = repo()
        r.refresh()
        assertNull(r.getContentKey(item))
        assertNull(pins.getPinned(bob))
    }

    @Test
    fun `an unbound item named by two owners in one refresh is refused both times and bound to nobody`() = runTest {
        serve(share(alice, "alice@x.y"), share(bob, "bob@x.y"))
        val r = repo()
        r.refresh()
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
        assertNull(floors.owner(item))
    }

    @Test
    fun `a verified share binds with its floor, a first contact binds once it opens, a notebook too`() = runTest {
        pins.pin(alice, signA, shareA)
        every { crypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) } returns WrapVerify(true, 3u)
        serve(share(alice, "alice@x.y", signature = sig))
        repo().refresh()
        assertEquals(alice, floors.owner(item))
        assertEquals(3L, floors.floor(item))
        assertEquals(alice, JSONObject(floorPrefs.getString("item_owners:$me", null)!!).getString(item))

        // A first contact that doesn't open binds nothing (its sender is pinned as usual)…
        val other = "8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        every { crypto.decryptNoteWithContentKeyV2(any(), any(), any(), other) } returns null
        serve(share(bob, "bob@x.y", itemId = other))
        repo().refresh()
        assertNull("a first contact that didn't open binds nothing", floors.owner(other))

        // …one that opens binds its item.
        val carol = "bb1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val third = "cc1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        directory(PublicKeyLookupEntry("carol@x.y", carol, "12".repeat(32), "34".repeat(32)))
        serve(share(carol, "carol@x.y", itemId = third))
        repo().refresh()
        assertEquals(carol, floors.owner(third))

        // A notebook binds as a note does.
        val notebook = "9a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        serve(share(carol, "carol@x.y", itemId = notebook, signature = sig, notebook = true))
        repo().refresh()
        assertEquals(carol, floors.owner(notebook))
    }

    @Test
    fun `a refused share never binds`() = runTest {
        pins.pin(alice, signA, shareA)
        serve(share(alice, "alice@x.y", signature = null))
        repo().refresh()
        assertNull(floors.owner(item))
    }

    // --- A first share's directory entry must name its sender ---------------------------------

    @Test
    fun `a first share whose directory entry names another account is refused and pins nothing`() = runTest {
        directory(PublicKeyLookupEntry("alice@x.y", bob, shareB, signB))
        serve(share(alice, "alice@x.y"))
        val r = repo()
        r.refresh()
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
        assertNull(pins.getPinned(alice))
        assertNull(pins.getPinned(bob))
    }

    @Test
    fun `a pinned owner's share is verified against the pin, whatever account the directory entry names`() = runTest {
        pins.pin(alice, signA, shareA)
        directory(PublicKeyLookupEntry("alice@x.y", bob, shareB, signB))
        every { crypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) } returns WrapVerify(true, 1u)
        serve(share(alice, "alice@x.y", signature = sig))
        val r = repo()
        r.refresh()
        assertTrue(r.getContentKey(item) != null)
    }

    // --- An owners blob that does not parse ---------------------------------------------------

    @Test
    fun `an owners blob that does not parse stops the refresh before the fetch, and says so`() = runTest {
        floorPrefs.edit().putString("item_owners:$me", "{not json").commit()
        serve(share(alice, "alice@x.y"))
        assertEquals(0, repo().refresh())
        coVerify(exactly = 0) { noteApi.listInbox() }
        assertEquals("{not json", floorPrefs.getString("item_owners:$me", null))
        assertTrue(ShadowLog.getLogsForTag("SharesInboxRepository").any {
            it.type == Log.ERROR && it.msg.contains("INBOX NOT REFRESHED")
        })
    }
}
