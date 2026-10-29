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
import androidx.test.core.app.ApplicationProvider
import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.collab.WrapVerify
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.InboxShareDto
import com.pingolin.note.data.remote.dto.PublicKeyLookupEntry
import com.pingolin.note.data.remote.dto.PublicKeyLookupResponse
import com.pingolin.note.util.NoteGenFloorStore
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response as RetrofitResponse

/**
 * The inbox against the REAL pin and floor stores (over plain preferences: EncryptedSharedPreferences
 * needs the Keystore, which Robolectric lacks). Every id is taken in its canonical form before the
 * pin, the floor or anything else reads it, and one that is no UUID drops the share. Each drop asserts
 * the rule that refused it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SharesInboxCanonicalIdsTest {

    private val me = "00000000-0000-4000-8000-00000000000e"
    private val alice = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val aliceHex = alice.replace("-", "")
    private val aliceSpellings = listOf(alice.uppercase(), aliceHex, aliceHex.uppercase(), "{$alice}", "urn:uuid:$alice")
    private val item = "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val itemHex = item.replace("-", "")
    private val itemSpellings = listOf(item.uppercase(), itemHex, itemHex.uppercase(), "{$item}", "urn:uuid:$item")
    private val other = "2a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private val signA = "a".repeat(64)
    private val shareA = "b".repeat(64)
    private val badSig = "cd".repeat(64)
    private val contentKey = ByteArray(32) { 7 }
    private val privB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 })


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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        pinPrefs = context.getSharedPreferences("inbox_pins_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        floorPrefs = context.getSharedPreferences("inbox_floors_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        pins = SenderKeyPinStore({ pinPrefs }, session)
        floors = NoteGenFloorStore({ floorPrefs }, session)

        every { session.getUserId() } returns me
        every { session.getSharingPrivateKey() } returns privB64
        every { session.getEmail() } returns null
        every { session.getInboxShareIds() } answers { storedInboxIds }
        val saved = slot<Set<String>>()
        every { session.setInboxShareIds(capture(saved)) } answers { storedInboxIds = saved.captured }

        coEvery { noteApi.lookupPublicKeys(any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = PublicKeyLookupResponse(listOf(PublicKeyLookupEntry("alice@x.y", alice, shareA, signA))),
            ),
        )
        every { crypto.openSeal(any(), any()) } returns contentKey
        every { crypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns """{"title":"t"}"""
        coEvery { itemDao.getById(any()) } returns null
        coEvery { itemDao.getByIds(any()) } returns emptyList()
        every { session.getMasterKey() } returns null
        every { previousIdentities.vaultKeys() } returns emptyList()
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

    private fun share(owner: String, itemId: String = item, sig: String? = badSig, gen: Long = 1L, shareId: String = "share-1") =
        InboxShareDto(
            shareId = shareId,
            itemId = itemId,
            ownerId = owner,
            permission = "edit",
            wrappedKey = "sealed",
            sharerPublicKey = null,
            ownerEmail = "alice@x.y",
            wrapSignature = sig,
            encryptedContent = "enc",
            contentNonce = "nonce",
            updatedAt = "2026-09-29T00:00:00Z",
            createdAt = "2026-09-29T00:00:00Z",
            contentKeyGeneration = gen,
        )

    private fun serve(vararg shares: InboxShareDto) {
        coEvery { noteApi.listInbox() } returns RetrofitResponse.success(ApiResponse(success = true, data = bareList(shares.toList())))
    }

    private fun verifies(ok: Boolean, gen: UInt = 1u) {
        every { crypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) } returns WrapVerify(ok, gen)
    }

    private fun pinnedKeys(): Set<String> =
        pinPrefs.getString("sender_pins:$me", null)?.let { JSONObject(it).keys().asSequence().toSet() } ?: emptySet()

    @Test
    fun `a pinned sender under another spelling is verified against the pin, and a bad signature drops the share`() = runTest {
        pins.pin(alice, signA, shareA)
        verifies(false)
        for (spelling in aliceSpellings) {
            val r = repo()
            serve(share(spelling))
            r.refresh()
            assertNull(spelling, r.getContentKey(item))
            assertTrue(spelling, storedInboxIds.isEmpty())
        }
        verify(exactly = aliceSpellings.size) {
            crypto.verifyNoteShareWrapGen(signA, badSig, alice, me, item, any(), any())
        }
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
        assertEquals("nothing pinned under the other spellings", setOf(alice), pinnedKeys())
    }

    @Test
    fun `a pinned sender under another spelling with its signature stripped is dropped`() = runTest {
        pins.pin(alice, signA, shareA)
        verifies(true)
        for (spelling in aliceSpellings) {
            val r = repo()
            serve(share(spelling, sig = null))
            r.refresh()
            assertNull(spelling, r.getContentKey(item))
        }
        verify(exactly = 0) { crypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
    }

    @Test
    fun `a pinned sender under another spelling below the floor is dropped as out of date`() = runTest {
        pins.pin(alice, signA, shareA)
        floors.bump(item, 5L)
        verifies(true, 3u)
        for (spelling in aliceSpellings) {
            val r = repo()
            serve(share(spelling, gen = 3L))
            r.refresh()
            assertNull(spelling, r.getContentKey(item))
        }
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
    }

    @Test
    fun `an item under another spelling reads its floor, so an older generation is dropped`() = runTest {
        pins.pin(alice, signA, shareA)
        floors.bump(item, 5L)
        verifies(true, 3u)
        for (spelling in itemSpellings) {
            val r = repo()
            serve(share(alice, itemId = spelling, gen = 3L))
            r.refresh()
            assertNull(spelling, r.getContentKey(item))
            assertNull(spelling, r.getContentKey(spelling))
        }
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
        assertEquals(5L, floors.floor(item))
    }

    @Test
    fun `an owner id that is no UUID drops the share before any pin, even with none`() = runTest {
        verifies(true)
        for (owner in listOf("not-a-uuid", "", " $alice", "{$aliceHex}", "URN:UUID:$alice")) {
            val r = repo()
            serve(share(owner, sig = null))
            r.refresh()
            assertTrue(owner, storedInboxIds.isEmpty())
        }
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
        assertTrue("nothing pinned", pinnedKeys().isEmpty())
    }

    @Test
    fun `an item id that is no UUID drops the share as damaged`() = runTest {
        verifies(true)
        for (itemId in listOf("item-1", "", " $item", "{$itemHex}", "URN:UUID:$item")) {
            val r = repo()
            serve(share(alice, itemId = itemId, sig = null))
            r.refresh()
            assertTrue(itemId, storedInboxIds.isEmpty())
        }
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
        assertTrue("nothing pinned", pinnedKeys().isEmpty())
    }

    @Test
    fun `with no own id, or one that is no UUID, the refresh fetches nothing and writes nothing`() = runTest {
        storedInboxIds = setOf(other)
        serve(share(alice, sig = null))
        for (own in listOf(null, "me", " $me")) {
            every { session.getUserId() } returns own
            assertEquals(own.toString(), 0, repo().refresh())
        }
        coVerify(exactly = 0) { noteApi.listInbox() }
        verify(exactly = 0) { session.setInboxShareIds(any()) }
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
        coVerify(exactly = 0) { itemDao.upsert(any()) }
        assertNull(pinPrefs.getString("sender_pins:$me", null))
        assertNull(floorPrefs.getString("gen_floors:$me", null))
    }

    @Test
    fun `a first contact pins canonically, and the next share under another spelling is checked against it`() = runTest {
        verifies(false)
        val r = repo()
        serve(share(aliceHex.uppercase(), sig = null))
        r.refresh()
        assertTrue("first contact still opens", r.getContentKey(item) != null)
        assertEquals(setOf(alice), pinnedKeys())

        serve(share("{$alice}", sig = badSig))
        r.refresh()
        assertNull(r.getContentKey(item))
        verify(exactly = 1) { crypto.verifyNoteShareWrapGen(signA, badSig, alice, me, item, any(), any()) }
    }

    @Test
    fun `a conflict from two spellings verifies nothing`() = runTest {
        pinPrefs.edit().putString(
            "sender_pins:$me",
            JSONObject()
                .put(alice, JSONObject().put("signing", signA).put("sharing", shareA).put("pinnedAt", 1L))
                .put(aliceHex, JSONObject().put("signing", "e".repeat(64)).put("sharing", shareA).put("pinnedAt", 2L))
                .toString(),
        ).commit()
        verifies(true)
        val r = repo()
        serve(share(alice))
        r.refresh()
        assertNull(r.getContentKey(item))
        verify(exactly = 0) { crypto.verifyNoteShareWrapGen(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a pin store that can't be read stops the refresh before the fetch, and writes nothing`() = runTest {
        val unreadable = object : SharedPreferences by pinPrefs {
            override fun getString(key: String?, defValue: String?): String? = throw SecurityException("decrypt")
            override fun getAll(): MutableMap<String, *> = throw SecurityException("decrypt")
        }
        pins = SenderKeyPinStore({ unreadable }, session)
        storedInboxIds = setOf(other)
        serve(share(alice, sig = null))
        assertEquals(0, repo().refresh())
        coVerify(exactly = 0) { noteApi.listInbox() }
        verify(exactly = 0) { session.setInboxShareIds(any()) }
        coVerify(exactly = 0) { itemDao.deleteById(any()) }
        verify(exactly = 0) { crypto.openSeal(any(), any()) }
    }

    @Test
    fun `a floor store that can't be read stops the refresh before the fetch`() = runTest {
        floorPrefs.edit().putString("gen_floors:$me", "{broken").commit()
        serve(share(alice, sig = null))
        assertEquals(0, repo().refresh())
        coVerify(exactly = 0) { noteApi.listInbox() }
        assertEquals("{broken", floorPrefs.getString("gen_floors:$me", null))
    }

    @Test
    fun `the stored inbox-id set is compared canonically, so a live share is not deleted`() = runTest {
        storedInboxIds = setOf(item.uppercase(), "{$other}")
        // What is left is a share's row: Alice's, not dirty, with no wrap of ours.
        coEvery { itemDao.getById(other) } returns com.pingolin.note.data.local.entity.ItemEntity(
            id = other, userId = alice, itemType = 1, encryptedContent = "enc", contentNonce = "nonce",
            createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )
        serve(share(alice, sig = null))
        repo().refresh()
        coVerify(exactly = 0) { itemDao.deleteById(item) }
        coVerify(exactly = 0) { itemDao.deleteById(item.uppercase()) }
        coVerify(exactly = 1) { itemDao.deleteById(other) }
        assertEquals(setOf(item), storedInboxIds)
    }

    @Test
    fun `a materialised row carries the canonical item and owner ids`() = runTest {
        serve(share("urn:uuid:$alice", itemId = itemHex.uppercase(), sig = null))
        val r = repo()
        r.refresh()
        coVerify(exactly = 1) { itemDao.upsert(match { it.id == item && it.userId == alice }) }
        coVerify(exactly = 1) { itemDao.getById(item) }
        // Its content key is found under any spelling of the item.
        for (spelling in itemSpellings + item) assertTrue(spelling, r.getContentKey(spelling) != null)
    }
}
