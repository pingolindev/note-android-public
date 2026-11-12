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
import com.pingolin.note.collab.NotePayload
import com.pingolin.note.collab.WrapVerify
import com.pingolin.note.collab.WrappedKeyPair
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.ItemDto
import com.pingolin.note.data.remote.dto.PublicKeyLookupEntry
import com.pingolin.note.data.remote.dto.PublicKeyLookupRequest
import com.pingolin.note.data.remote.dto.PublicKeyLookupResponse
import com.pingolin.note.data.remote.dto.ShareNoteRequest
import com.pingolin.note.data.remote.dto.UnfinishedShareDto
import com.pingolin.note.util.KeptWebTrustStore
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.time.Instant

/**
 * The background finisher's own half ([InvitationFinisher]) against the REAL kept-trust store: whom
 * it seals to (every row of the trust rule), what it sends, when it stops, and what it keeps.
 *
 * WHY THESE EXIST
 *   This seals a note's content key to a key the server hands over, with nobody looking. Sealed
 *   to a key the page has not trusted, a substituted key would read the note; sent unsigned, or
 *   under the wrong generation, the reader's app drops it and the owner is never told; sent as a
 *   plain share, it would bring back a share the owner removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class InvitationFinisherTest {

    private val me = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val other = "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    /** The reader: Zoe's account, as the directory names it. */
    private val zoe = "2b2b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val sharingKey = "cc".repeat(32)
    private val signingKey = "dd".repeat(32)

    private val vault = ByteArray(32) { 7 }
    private val recoveredVault = ByteArray(32) { 8 }
    private val seed = ByteArray(32) { 9 }
    private val sharingPrivate = ByteArray(32) { 5 }
    private val noteKey = ByteArray(32) { 1 }

    private lateinit var prefs: SharedPreferences
    private lateinit var store: KeptWebTrustStore
    private lateinit var senderPrefs: SharedPreferences
    private lateinit var senderPins: SenderKeyPinStore
    private lateinit var crypto: FakeCrypto
    private val api = mockk<NoteApi>()
    private val dao = mockk<ItemDao>()
    private val session = mockk<SessionManager>()
    private val previous = mockk<PreviousIdentityLoader>()

    private var signedIn: String? = me
    private var now = 1_800_000_000_000L
    private var notebookShares: String? = "{}"
    private var signingSeedB64: String? = null
    private var recovered: List<String> = emptyList()
    private val localNotebooks = mutableMapOf<String, String>()
    private val generations = mutableMapOf<String, Long>()
    private var rows: List<UnfinishedShareDto> = emptyList()
    private var directory: List<PublicKeyLookupEntry> = emptyList()
    private val lookedUp = mutableListOf<List<String>>()
    private val read = mutableListOf<String>()
    private val sent = mutableListOf<Pair<String, ShareNoteRequest>>()
    private var shareAnswer: (String) -> Response<ApiResponse<Unit>> = { ok() }
    private var onSend: () -> Unit = {}

    private var n = 0

    private fun b64(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun row(
        email: String = "Zoe@Example.test",
        notebookId: String? = null,
        permission: String = "edit",
    ): UnfinishedShareDto {
        n += 1
        return UnfinishedShareDto(
            shareId = "50000000-0000-4000-8000-%012d".format(n),
            itemId = "60000000-0000-4000-8000-%012d".format(n),
            itemType = 1, notebookId = notebookId, recipientEmail = email, permission = permission,
            single = true, claimed = true, inviteExpiresAt = null, expired = false,
            createdAt = "2026-11-12T00:00:00Z",
        )
    }

    private fun entry(email: String = "zoe@example.test", userId: String? = zoe, sharing: String? = sharingKey, signing: String? = signingKey) =
        PublicKeyLookupEntry(email, userId, sharing, signing)

    private fun item(id: String) = ItemDto(
        id = id, userId = me, itemType = 1, encryptedContent = "ct", contentNonce = "cn", version = 4,
        createdAt = "2026-11-01T00:00:00Z", updatedAt = "2026-11-01T00:00:00Z", deleted = false,
        circleId = null, notebookId = null, contentSize = 10, sharedByMe = true,
        wrappedContentKey = "wrap-$id", wrappedContentKeyNonce = "nonce-$id",
        contentKeyGeneration = generations[id] ?: 3L,
    )

    private fun ok(): Response<ApiResponse<Unit>> = Response.success(ApiResponse(success = true))

    private fun refused(status: Int, code: String? = null): Response<ApiResponse<Unit>> {
        val body = JSONObject().put("success", false).put("error", "no").apply { code?.let { put("error_code", it) } }
        return Response.error(status, body.toString().toResponseBody("application/json".toMediaType()))
    }

    /** The page's pins blob with one pin for [contact]. */
    private fun pinsBlob(
        contact: String = zoe,
        sharing: String = sharingKey,
        signing: String = signingKey,
        conflict: Boolean = false,
    ): String {
        val pin = JSONObject().put("sharingPubkeyHex", sharing).put("signingPubkeyHex", signing)
            .put("firstPinnedAt", 1_700_000_000).put("lastVerifiedAt", 1_700_000_000)
        if (conflict) pin.put("conflict", true)
        return JSONObject().put(contact, pin).toString()
    }

    /** A pins blob the page keeps for an account that has pinned somebody else only. */
    private val somebodyElsesPin get() = pinsBlob(contact = other, sharing = "ee".repeat(32), signing = "ff".repeat(32))

    private data class SignCall(val withSeed: Boolean, val sender: String, val recipient: String, val item: String, val version: UInt, val wrapped: String)

    /** The crypto the finisher calls, recording what it was asked. */
    private inner class FakeCrypto : NoteCrypto {
        var opensWith: ByteArray = vault
        var signature: String? = "ab".repeat(64)
        val sealedTo = mutableListOf<String>()
        val signed = mutableListOf<SignCall>()

        override fun unwrapWithVault(wrappedKeyB64: String, nonceB64: String, vaultKey: ByteArray): ByteArray? =
            if (vaultKey.contentEquals(opensWith)) noteKey.copyOf() else null

        override fun sealForRecipient(contentKey: ByteArray, recipientPublicKey: String): String {
            check(contentKey.contentEquals(noteKey)) { "sealed something else than the note's key" }
            sealedTo += recipientPublicKey
            return "sealed-for-$recipientPublicKey"
        }

        override fun deriveSharingPublicKey(privateKey: ByteArray): String? =
            if (privateKey.contentEquals(sharingPrivate)) "AB".repeat(32) else null

        override fun signNoteShareWrap(
            signingSeed: ByteArray,
            senderUserId: String,
            recipientUserId: String,
            itemId: String,
            contentKeyVersion: UInt,
            wrappedKeyB64: String,
        ): String? {
            signed += SignCall(signingSeed.contentEquals(seed), senderUserId, recipientUserId, itemId, contentKeyVersion, wrappedKeyB64)
            return signature
        }

        override fun generateContentKey(): ByteArray = error("not used")
        override fun wrapWithVault(contentKey: ByteArray, vaultKey: ByteArray): WrappedKeyPair = error("not used")
        override fun encryptNoteWithContentKey(plaintext: String, contentKey: ByteArray): NotePayload = error("not used")
        override fun decryptNoteWithContentKey(nonceB64: String, ciphertextB64: String, contentKey: ByteArray): String? = error("not used")
        override fun encryptNoteWithContentKeyV2(plaintext: String, contentKey: ByteArray, itemId: String): NotePayload = error("not used")
        override fun decryptNoteWithContentKeyV2(nonceB64: String, ciphertextB64: String, contentKey: ByteArray, itemId: String): String? = error("not used")
        override fun openSeal(wrappedB64: String, privateKey: ByteArray): ByteArray? = error("not used")
        override fun verifyNoteShareWrap(signingPubkeyHex: String, signatureHex: String, senderUserId: String, recipientUserId: String, itemId: String, contentKeyVersion: UInt, wrappedKeyB64: String): Boolean? = error("not used")
        override fun verifyNoteShareWrapGen(signingPubkeyHex: String, signatureHex: String, senderUserId: String, recipientUserId: String, itemId: String, contentKeyVersion: UInt, wrappedKeyB64: String): WrapVerify? = error("not used")
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("finisher_kept_trust", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        store = KeptWebTrustStore { prefs }
        senderPrefs = context.getSharedPreferences("finisher_sender_pins", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        senderPins = SenderKeyPinStore({ senderPrefs }, session)
        crypto = FakeCrypto()
        signingSeedB64 = b64(seed)
        directory = listOf(entry())

        every { session.isLoggedIn() } answers { signedIn != null }
        every { session.getUserId() } answers { signedIn }
        every { session.getMasterKey() } answers { b64(vault) }
        every { session.getSigningPrivateKey() } answers { signingSeedB64 }
        every { session.getSharingPrivateKey() } answers { b64(sharingPrivate) }
        every { session.getNotebookShares() } answers { notebookShares }
        every { previous.vaultKeys() } answers { recovered }
        coEvery { dao.getById(any()) } answers {
            val id = firstArg<String>()
            localNotebooks[id]?.let { nb ->
                ItemEntity(id = id, userId = me, itemType = 1, encryptedContent = "ct", contentNonce = "cn",
                    createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, notebookId = nb)
            }
        }
        coEvery { api.listUnfinishedShares() } answers { Response.success(ApiResponse(success = true, data = rows)) }
        coEvery { api.lookupPublicKeys(any()) } answers {
            lookedUp += firstArg<PublicKeyLookupRequest>().emails
            Response.success(ApiResponse(success = true, data = PublicKeyLookupResponse(directory)))
        }
        coEvery { api.getItem(any()) } answers {
            val id = firstArg<String>()
            read += id
            Response.success(ApiResponse(success = true, data = item(id)))
        }
        coEvery { api.shareNote(any(), any()) } answers {
            val id = firstArg<String>()
            onSend()
            sent += id to secondArg<ShareNoteRequest>()
            shareAnswer(id)
        }
    }

    /** The delays of the delayed syncs a finisher set (SyncWorker.FINISH_LATER), in order. */
    private val laterSyncs = mutableListOf<Long>()

    private fun finisher(s: KeptWebTrustStore = store, receivePins: SenderKeyPinStore = senderPins) =
        InvitationFinisher(ApplicationProvider.getApplicationContext(), api, dao, crypto, session, s, previous, receivePins).also {
            it.clock = { now }
            it.syncLater = { delayMs -> laterSyncs += delayMs }
        }

    private fun seals(): List<KeptWebTrustStore.Seal> = (store.seals(me) as KeptWebTrustStore.Seals.Held).seals

    // --- 1. The kept pins: none, or none that read, finishes NOTHING ---

    @Test
    fun `no pins kept for the account finishes nothing, and asks the server nothing`() = runTest {
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        coVerify(exactly = 0) { api.listUnfinishedShares() }
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
        assertEquals("no seal either", emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    /**
     * The page keeps an EMPTY record of pins, the string "{}", for an account that has pinned
     * nobody (note 570fcb5: `pins: blobs.pins ?? '{}'`). That is a record, of nobody: a first
     * contact is finished. Sent through the channel exactly as the page sends it.
     */
    @Test
    fun `an empty record of pins, as the page keeps it for an account that has pinned nobody, lets a first contact be finished`() = runTest {
        val origin = "https://note.example.test"
        val keep = JSONObject().put("op", "keepTrust").put("v", 1).put("account", me)
            .put("pins", "{}").put("floors", JSONObject.NULL).put("owners", JSONObject.NULL).put("id", 1).toString()
        val answer = com.pingolin.note.ui.web.KeptWebTrustChannel.decide(keep, origin, true, origin, me, store)
        assertEquals("kept", JSONObject(answer!!).getString("state"))
        assertEquals(KeptWebTrustStore.Pins.Held(emptyMap()), store.pins(me))

        val r = row()
        rows = listOf(r)
        assertEquals(listOf(r.shareId), finisher().afterSync(me))
        assertEquals(listOf(KeptWebTrustStore.Seal(zoe, sharingKey, signingKey, now)), seals())
        assertEquals(true, sent.single().second.finish)
    }

    @Test
    fun `kept trust that can't be read, or pins that don't parse, finish nothing`() = runTest {
        rows = listOf(row())
        val broken = KeptWebTrustStore { throw IllegalStateException("keystore said no") }
        assertEquals(emptyList<String>(), finisher(broken).afterSync(me))

        for (blob in listOf("not json", "[]", "\"pins\"", "null")) {
            store.keep(me, blob, null, null)
            assertEquals(blob, emptyList<String>(), finisher().afterSync(me))
        }
        coVerify(exactly = 0) { api.listUnfinishedShares() }
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
    }

    // --- 2. A kept pin decides ---

    @Test
    fun `a kept pin with the directory's sharing key is sealed to, in any case, and records no seal`() = runTest {
        store.keep(me, pinsBlob(sharing = sharingKey.uppercase()), null, null)
        val r = row()
        rows = listOf(r)
        assertEquals(listOf(r.shareId), finisher().afterSync(me))
        assertEquals(listOf(sharingKey), crypto.sealedTo)
        assertEquals(emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    @Test
    fun `a kept pin that is a conflict is not sealed to`() = runTest {
        store.keep(me, pinsBlob(conflict = true, sharing = "0".repeat(64), signing = "0".repeat(64)), null, null)
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(emptyList<String>(), crypto.sealedTo)
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
        assertEquals(emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    @Test
    fun `a kept pin with another sharing key is not sealed to (the page asks to confirm it's them)`() = runTest {
        store.keep(me, pinsBlob(sharing = "ee".repeat(32)), null, null)
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
        assertEquals("never a seal over a pin", emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    @Test
    fun `a kept pin under another spelling of the reader's id still decides`() = runTest {
        store.keep(me, pinsBlob(contact = zoe.uppercase().replace("-", ""), sharing = "ee".repeat(32)), null, null)
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
    }

    // --- 3. No kept pin, a pending seal decides ---

    @Test
    fun `a pending seal with the directory's sharing key is sealed to, and not recorded twice`() = runTest {
        store.keep(me, somebodyElsesPin, null, null)
        assertEquals(KeptWebTrustStore.SealRecord.RECORDED, store.recordSeal(me, zoe, sharingKey, signingKey, 100L))
        val r = row()
        rows = listOf(r)
        assertEquals(listOf(r.shareId), finisher().afterSync(me))
        assertEquals(listOf(KeptWebTrustStore.Seal(zoe, sharingKey, signingKey, 100L)), seals())
    }

    @Test
    fun `a pending seal with another sharing key is not sealed to`() = runTest {
        store.keep(me, somebodyElsesPin, null, null)
        store.recordSeal(me, zoe, "ee".repeat(32), signingKey, 100L)
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
        assertEquals(1, seals().size)
    }

    @Test
    fun `seals that can't be read make a first contact a refusal`() = runTest {
        store.keep(me, somebodyElsesPin, null, null)
        prefs.edit().putString("$me|seals", "{broken").commit()
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
    }

    // --- 4. Neither: a first contact ---

    @Test
    fun `a first contact records its seal, committed, before anything is sealed or sent`() = runTest {
        store.keep(me, somebodyElsesPin, null, null)
        directory = listOf(entry(userId = zoe.uppercase(), sharing = sharingKey.uppercase(), signing = signingKey.uppercase()))
        var sealsAtSend: List<KeptWebTrustStore.Seal>? = null
        var sealedAtSend: List<String>? = null
        onSend = {
            // Read back through another store over the same preferences.
            sealsAtSend = (KeptWebTrustStore { prefs }.seals(me) as KeptWebTrustStore.Seals.Held).seals
            sealedAtSend = crypto.sealedTo.toList()
        }
        val r = row()
        rows = listOf(r)
        assertEquals(listOf(r.shareId), finisher().afterSync(me))
        val expected = KeptWebTrustStore.Seal(zoe, sharingKey, signingKey, now)
        assertEquals(listOf(expected), sealsAtSend)
        assertEquals(listOf(sharingKey), sealedAtSend)
        assertEquals(listOf(expected), seals())
    }

    @Test
    fun `a first contact without two 64-hex keys is not sealed to, and records nothing`() = runTest {
        store.keep(me, somebodyElsesPin, null, null)
        for (bad in listOf(entry(signing = "dd".repeat(31)), entry(sharing = "cc".repeat(33)), entry(signing = "zz".repeat(32)), entry(sharing = "0".repeat(64)))) {
            directory = listOf(bad)
            rows = listOf(row())
            InvitationFinisher.wanted()
            assertEquals(emptyList<String>(), finisher().afterSync(me))
        }
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
        assertEquals(emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    @Test
    fun `a directory id that is no account id is never a first contact`() = runTest {
        store.keep(me, somebodyElsesPin, null, null)
        directory = listOf(entry(userId = "zoe-id"))
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
        assertEquals(emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    @Test
    fun `an entry without all three of id and keys is no account, and nothing is concluded`() = runTest {
        store.keep(me, somebodyElsesPin, null, null)
        directory = listOf(entry(signing = null), entry(userId = null), entry(sharing = ""))
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
    }

    // --- What is sent ---

    @Test
    fun `the finish is the page's, the address as listed, the wrap signed under the note's generation, the sharer's key as hex`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val r = row(email = "Zoe@Example.test", permission = "edit")
        generations[r.itemId!!] = 7L
        rows = listOf(r)
        finisher().afterSync(me)

        assertEquals(listOf(r.itemId), read)
        val (to, body) = sent.single()
        assertEquals(r.itemId, to)
        assertEquals("Zoe@Example.test", body.email)
        assertEquals("edit", body.permission)
        assertEquals("sealed-for-$sharingKey", body.wrappedKey)
        assertEquals("ab".repeat(64), body.wrapSignature)
        assertEquals("ab".repeat(32), body.sharerPublicKey)
        assertEquals(true, body.finish)
        assertEquals(7L, body.contentKeyGeneration)
        assertEquals(
            listOf(SignCall(withSeed = true, sender = me, recipient = zoe, item = r.itemId, version = 7u, wrapped = "sealed-for-$sharingKey")),
            crypto.signed,
        )
    }

    @Test
    fun `a note whose generation the server leaves out is generation 1, and one a u32 can't hold is not sent`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val absent = row()
        val huge = row()
        generations[absent.itemId!!] = 0L
        generations[huge.itemId!!] = 0x1_0000_0000L
        rows = listOf(absent, huge)
        assertEquals(listOf(absent.shareId), finisher().afterSync(me))
        assertEquals(1L, sent.single().second.contentKeyGeneration)
        assertEquals(1u, crypto.signed.single().version)
    }

    @Test
    fun `a wrap that can't be signed is not sent`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        rows = listOf(row())
        for (signature in listOf(null, "", "ab".repeat(63), "zz".repeat(64))) {
            crypto.signature = signature
            InvitationFinisher.wanted()
            now += FinishSingleInvitations.FINISH_RETRY_MS
            assertEquals("$signature", emptyList<String>(), finisher().afterSync(me))
        }
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
        assertEquals(4, crypto.signed.size)
    }

    @Test
    fun `without a signing key nothing is done at all`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        signingSeedB64 = null
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        coVerify(exactly = 0) { api.listUnfinishedShares() }
    }

    @Test
    fun `the content key opens under a recovered identity's vault key when the current one doesn't`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        crypto.opensWith = recoveredVault
        recovered = listOf(b64(recoveredVault))
        val r = row()
        rows = listOf(r)
        assertEquals(listOf(r.shareId), finisher().afterSync(me))
    }

    @Test
    fun `a note this account's keys can't open is left, and no seal is recorded for it`() = runTest {
        store.keep(me, somebodyElsesPin, null, null)
        crypto.opensWith = ByteArray(32) { 3 }
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
        assertEquals(emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    // --- The server's answers ---

    @Test
    fun `a 402 stops the whole pass`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val rs = listOf(row(), row(), row())
        rows = rs
        shareAnswer = { refused(402, "premium_required_sharing") }
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(listOf(rs[0].itemId), sent.map { it.first })
        assertEquals("the others aren't even read", listOf(rs[0].itemId), read)
    }

    @Test
    fun `a 5xx is tried again later, and the pass goes on`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val rs = listOf(row(), row())
        rows = rs
        shareAnswer = { id -> if (id == rs[0].itemId) refused(503) else ok() }
        assertEquals(listOf(rs[1].shareId), finisher().afterSync(me))
        assertEquals(setOf(rs[0].shareId), store.triedAt(me)!!.keys)
    }

    /** Review of bb1d05b: the server takes 30 shares a minute from an owner. */
    @Test
    fun `a 429 stops the pass, and the rows it didn't reach are due at the next pass`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val rs = listOf(row(), row(), row())
        rows = rs
        shareAnswer = { refused(429, "rate_limited") }
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(listOf(rs[0].itemId), sent.map { it.first })
        assertEquals("only the refused row waits its ten minutes", setOf(rs[0].shareId), store.triedAt(me)!!.keys)

        shareAnswer = { ok() }
        now += InvitationFinisher.LOOK_MS
        assertEquals(listOf(rs[1].shareId, rs[2].shareId), finisher().afterSync(me))
    }

    @Test
    fun `a 429 reading a note stops the pass too`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val rs = listOf(row(), row())
        rows = rs
        coEvery { api.getItem(any()) } answers {
            read += firstArg<String>()
            Response.error(429, "{}".toResponseBody("application/json".toMediaType()))
        }
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(listOf(rs[0].itemId), read)
        assertEquals(setOf(rs[0].shareId), store.triedAt(me)!!.keys)
    }

    @Test
    fun `a stale generation is read again at the next pass, a share that is not unfinished in ten minutes`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val stale = row()
        val gone = row()
        rows = listOf(stale, gone)
        shareAnswer = { id -> if (id == stale.itemId) refused(409, "share_generation_stale") else refused(409, "share_not_unfinished") }
        finisher().afterSync(me)
        assertEquals(setOf(gone.shareId), store.triedAt(me)!!.keys)

        read.clear()
        now += InvitationFinisher.LOOK_MS
        generations[stale.itemId!!] = 4L
        shareAnswer = { ok() }
        assertEquals(listOf(stale.shareId), finisher().afterSync(me))
        assertEquals("the note is read afresh, with its new generation", listOf(stale.itemId), read)
        assertEquals(4L, sent.last().second.contentKeyGeneration)
    }

    // --- Kept tries ---

    @Test
    fun `the tries are kept, so a fresh process does not try a share again within ten minutes, and does after`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val r = row()
        rows = listOf(r)
        shareAnswer = { refused(500) }
        finisher().afterSync(me)
        assertEquals(mapOf(r.shareId!! to now), store.triedAt(me))

        now += FinishSingleInvitations.FINISH_RETRY_MS - 1
        finisher().afterSync(me)
        assertEquals(1, sent.size)

        now += 1
        shareAnswer = { ok() }
        assertEquals(listOf(r.shareId), finisher().afterSync(me))
        assertEquals(2, sent.size)
        assertEquals("a finished share's try goes", emptyMap<String, Long>(), store.triedAt(me))
    }

    @Test
    fun `a try is kept before its share goes out`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val r = row()
        rows = listOf(r)
        var triedAtSend: Map<String, Long>? = null
        onSend = { triedAtSend = KeptWebTrustStore { prefs }.triedAt(me) }
        finisher().afterSync(me)
        assertEquals(mapOf(r.shareId!! to now), triedAtSend)
    }

    @Test
    fun `a share the server no longer lists has its try let go of`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        store.keepTriedAt(me, mapOf("gone-share" to now - 1_000, "still-listed" to now - 1_000))
        rows = listOf(row().copy(shareId = "still-listed"))
        shareAnswer = { refused(500) }
        finisher().afterSync(me)
        assertEquals(setOf("still-listed"), store.triedAt(me)!!.keys)
    }

    // --- Which rows: the standing lists ---

    @Test
    fun `a note in a notebook whose standing list has the address is left, by the server's notebook or this device's`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val byServer = row(notebookId = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")
        val byDevice = row()
        val free = row(notebookId = "8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")
        localNotebooks[byDevice.itemId!!] = "9a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        notebookShares = JSONObject()
            .put("7A1B2C3D-4E5F-4A6B-8C7D-9E0F1A2B3C4D", org.json.JSONArray().put(JSONObject().put("email", "ZOE@example.test").put("permission", "view")))
            .put("9a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", org.json.JSONArray().put(JSONObject().put("email", "zoe@example.test")))
            .put("8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", org.json.JSONArray().put(JSONObject().put("email", "ann@example.test")))
            .toString()
        rows = listOf(byServer, byDevice, free)
        assertEquals(listOf(free.shareId), finisher().afterSync(me))
    }

    @Test
    fun `standing lists this device can't read leave every note in a notebook to the page`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val inBook = row(notebookId = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")
        val inLocalBook = row()
        val loose = row()
        localNotebooks[inLocalBook.itemId!!] = "9a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        rows = listOf(inBook, inLocalBook, loose)
        for (unreadable in listOf(null, "not json")) {
            notebookShares = unreadable
            sent.clear()
            store.keepTriedAt(me, emptyMap())
            InvitationFinisher.wanted()
            assertEquals("$unreadable", listOf(loose.shareId), finisher().afterSync(me))
        }
        // One notebook's list that doesn't read leaves that notebook's notes only.
        notebookShares = JSONObject().put("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "garbled").toString()
        store.keepTriedAt(me, emptyMap())
        InvitationFinisher.wanted()
        assertEquals(listOf(inLocalBook.shareId, loose.shareId), finisher().afterSync(me))
    }

    // --- The directory ---

    @Test
    fun `the directory is asked for each address once, lower-cased, and no answer concludes nothing`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        rows = listOf(row(email = "Zoe@Example.test"), row(email = "ZOE@example.test"))
        coEvery { api.lookupPublicKeys(any()) } answers {
            lookedUp += firstArg<PublicKeyLookupRequest>().emails
            Response.error(502, "{}".toResponseBody("application/json".toMediaType()))
        }
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        assertEquals(listOf(listOf("zoe@example.test")), lookedUp)
        assertEquals("nothing tried: asked again next pass", emptyMap<String, Long>(), store.triedAt(me))
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
    }

    // --- Whose pass ---

    @Test
    fun `a pass is only for the account the sync began as, while it is signed in`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher().afterSync(other))
        assertEquals(emptyList<String>(), finisher().afterSync(null))
        signedIn = null
        assertEquals(emptyList<String>(), finisher().afterSync(me))
        coVerify(exactly = 0) { api.listUnfinishedShares() }
    }

    @Test
    fun `another account signing in mid-pass stops it, and nothing more is read or sent`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val rs = listOf(row(), row(), row())
        rows = rs
        onSend = { signedIn = other }
        assertEquals(listOf(rs[0].shareId), finisher().afterSync(me))
        assertEquals(listOf(rs[0].itemId), read)
        assertEquals(1, sent.size)
    }

    /** Review of bb1d05b: a burst of pushes must not outrun the server's 30 shares a minute. */
    @Test
    fun `passes a minute apart, a finish_shares push skipping the wait at most once a minute`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        rows = emptyList()
        val f = finisher()
        val start = now
        f.afterSync(me)
        now = start + 10_000
        f.afterSync(me)
        coVerify(exactly = 1) { api.listUnfinishedShares() }

        InvitationFinisher.wanted()
        f.afterSync(me)
        coVerify(exactly = 2) { api.listUnfinishedShares() }

        now = start + 20_000
        InvitationFinisher.wanted()
        f.afterSync(me)
        coVerify(exactly = 2) { api.listUnfinishedShares() } // a second push inside that minute skips nothing

        now = start + 80_000
        f.afterSync(me)
        coVerify(exactly = 3) { api.listUnfinishedShares() } // a minute after the last pass

        now = start + 81_000
        InvitationFinisher.wanted()
        f.afterSync(me)
        coVerify(exactly = 4) { api.listUnfinishedShares() } // a minute after the last skip, a push skips again

        // A clock that went back is no reason to wait.
        now = start - 3_600_000L
        f.afterSync(me)
        coVerify(exactly = 5) { api.listUnfinishedShares() }
    }

    /** Review of bb1d05b: the pass runs inside the unique immediate sync (KEEP), so a push that
     *  lands while it runs starts no sync of its own. */
    @Test
    fun `a push that lands while a pass runs gets one more pass in the same sync, and only one`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val first = row()
        val second = row()
        var lists = 0
        coEvery { api.listUnfinishedShares() } answers {
            lists++
            // A claim, and its push, land while each pass runs.
            now += 1
            InvitationFinisher.wanted()
            Response.success(ApiResponse(success = true, data = if (lists == 1) listOf(first) else listOf(second)))
        }
        assertEquals(listOf(first.shareId, second.shareId), finisher().afterSync(me))
        assertEquals("one more pass, not a third", 2, lists)
    }

    @Test
    fun `the pass after a push that already skipped the wait this minute is not followed by another`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        val f = finisher()
        rows = emptyList()
        f.afterSync(me)
        now += 10_000
        var lists = 0
        coEvery { api.listUnfinishedShares() } answers {
            lists++
            now += 1
            InvitationFinisher.wanted()
            Response.success(ApiResponse(success = true, data = emptyList<UnfinishedShareDto>()))
        }
        InvitationFinisher.wanted()
        f.afterSync(me)
        assertEquals("the push's own pass, and no second skip inside the minute", 1, lists)
        // The push that landed during it is held back by the cap: the delayed sync is set for the
        // end of the minute the pass began (it began at now - 1).
        assertEquals(listOf(InvitationFinisher.LOOK_MS + 1_000 - 1), laterSyncs)
    }

    /** Re-review of 42fd9a6: a push held back only by the once-a-minute cap must not wait for an
     *  unrelated sync (the 15-minute periodic one, or later in Doze). */
    @Test
    fun `a push held back by the once-a-minute cap sets one delayed sync for when the minute is up, and none otherwise`() = runTest {
        store.keep(me, pinsBlob(), null, null)
        rows = emptyList()
        val f = finisher()
        val start = now
        f.afterSync(me)
        now = start + 10_000
        f.afterSync(me)
        assertEquals("waiting with no push held back sets nothing", emptyList<Long>(), laterSyncs)

        InvitationFinisher.wanted()
        f.afterSync(me) // the push skips the wait: a pass, and the minute starts again
        now = start + 20_000
        InvitationFinisher.wanted()
        f.afterSync(me) // held back by the cap
        coVerify(exactly = 2) { api.listUnfinishedShares() }
        val minuteUp = start + 10_000 + InvitationFinisher.LOOK_MS
        assertEquals(listOf(minuteUp + 1_000 - now), laterSyncs)

        // That sync's pass, when it comes: the minute is up, and the held-back push is seen.
        now = minuteUp + 1_000
        f.afterSync(me)
        coVerify(exactly = 3) { api.listUnfinishedShares() }
        assertEquals("nothing more is set", 1, laterSyncs.size)
    }

    /** Review of bb1d05b: the page folds a seal for Zoe into a conflict, keeps that and clears the
     *  seal, while a pass runs. Decided by the pins read at the pass's start, Zoe would have
     *  neither pin nor seal any more, and be a first contact again. */
    @Test
    fun `each person is decided against the pins kept at that moment, not those the pass began with`() = runTest {
        val annId = "3c3c2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val annPin = pinsBlob(contact = annId, sharing = "ee".repeat(32), signing = "ff".repeat(32))
        store.keep(me, annPin, null, null)
        store.recordSeal(me, zoe, sharingKey, signingKey, 100L)
        directory = listOf(entry(), entry(email = "ann@example.test", userId = annId, sharing = "ee".repeat(32), signing = "ff".repeat(32)))
        val toAnn = row(email = "ann@example.test")
        val toZoe = row()
        rows = listOf(toAnn, toZoe)
        onSend = {
            if (sent.isEmpty()) {
                val conflict = JSONObject().put("sharingPubkeyHex", "0".repeat(64)).put("signingPubkeyHex", "0".repeat(64))
                    .put("firstPinnedAt", 1).put("lastVerifiedAt", 1).put("conflict", true)
                store.keep(me, JSONObject(annPin).put(zoe, conflict).toString(), null, null)
                store.clearSeals(me, 100.0)
            }
        }
        assertEquals(listOf(toAnn.shareId), finisher().afterSync(me))
        assertEquals("Zoe's share isn't sent", listOf(toAnn.itemId), sent.map { it.first })
        assertEquals("and she isn't recorded as a first contact again", emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    // --- The app's own receive-side pins: a veto, read only ---

    private fun receivePinsBlob(signing: String, sharing: String?) =
        JSONObject().put(zoe, JSONObject().put("signing", signing).put("pinnedAt", 1).apply { sharing?.let { put("sharing", it) } }).toString()

    /** Review of bb1d05b: the keys this app pinned when Zoe shared with this account. */
    @Test
    fun `a receive-side pin with another sharing key, a conflict, or pins that can't be read refuse, and nothing is written there`() = runTest {
        store.keep(me, "{}", null, null)
        for (blob in listOf(
            receivePinsBlob(signing = signingKey, sharing = "ee".repeat(32)),
            receivePinsBlob(signing = "0".repeat(64), sharing = "0".repeat(64)),
            "{not json",
        )) {
            senderPrefs.edit().clear().putString("sender_pins:$me", blob).commit()
            val before = senderPrefs.all.toMap()
            rows = listOf(row())
            InvitationFinisher.wanted()
            assertEquals(blob, emptyList<String>(), finisher(receivePins = SenderKeyPinStore({ senderPrefs }, session)).afterSync(me))
            assertEquals(blob, before, senderPrefs.all.toMap())
        }
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
        assertEquals("no seal recorded for a refused contact", emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    /** Re-review of 42fd9a6: SenderKeyPinStore never re-pins. After Zoe reset her keys and the
     *  owner approved her new one in the page (the kept pins hold it), her old receive-side pin
     *  must not refuse her forever: a kept pin is the page's decision, and stands. */
    @Test
    fun `a kept pin decides over the receive-side pin, so a key approved in the page is sealed to`() = runTest {
        store.keep(me, pinsBlob(sharing = sharingKey), null, null)
        for (blob in listOf(receivePinsBlob(signing = "ff".repeat(32), sharing = "ee".repeat(32)), "{not json")) {
            senderPrefs.edit().clear().putString("sender_pins:$me", blob).commit()
            val r = row()
            rows = listOf(r)
            assertEquals(blob, listOf(r.shareId), finisher(receivePins = SenderKeyPinStore({ senderPrefs }, session)).afterSync(me))
        }
        assertEquals("a kept pin: no seal", emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    @Test
    fun `with no kept pin for them the receive-side pin refuses, and a kept pin at another key refuses whatever it says`() = runTest {
        // No record for Zoe in the kept pins: her receive-side pin at another key refuses.
        store.keep(me, "{}", null, null)
        senderPrefs.edit().clear().putString("sender_pins:$me", receivePinsBlob(signing = signingKey, sharing = "ee".repeat(32))).commit()
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher(receivePins = SenderKeyPinStore({ senderPrefs }, session)).afterSync(me))

        // A kept pin at another key refuses, even with a receive-side pin that agrees with the directory.
        store.keep(me, pinsBlob(sharing = "ee".repeat(32)), null, null)
        senderPrefs.edit().clear().putString("sender_pins:$me", receivePinsBlob(signing = signingKey, sharing = sharingKey)).commit()
        rows = listOf(row())
        assertEquals(emptyList<String>(), finisher(receivePins = SenderKeyPinStore({ senderPrefs }, session)).afterSync(me))
        assertEquals(emptyList<Pair<String, ShareNoteRequest>>(), sent)
        assertEquals(emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    @Test
    fun `a receive-side pin with the directory's sharing key, or one that holds no sharing key, refuses nothing`() = runTest {
        store.keep(me, "{}", null, null)
        for (blob in listOf(
            receivePinsBlob(signing = signingKey, sharing = sharingKey.uppercase()),
            receivePinsBlob(signing = signingKey, sharing = null),
        )) {
            senderPrefs.edit().clear().putString("sender_pins:$me", blob).commit()
            val before = senderPrefs.all.toMap()
            val r = row()
            rows = listOf(r)
            InvitationFinisher.wanted()
            assertEquals(blob, listOf(r.shareId), finisher(receivePins = SenderKeyPinStore({ senderPrefs }, session)).afterSync(me))
            assertEquals(blob, before, senderPrefs.all.toMap())
        }
        assertEquals(2, sent.size)
    }

    @Test
    fun `nothing it does reaches the log but counts`() = runTest {
        org.robolectric.shadows.ShadowLog.clear()
        store.keep(me, somebodyElsesPin, null, null)
        rows = listOf(row(), row(email = "ann@example.test"))
        finisher().afterSync(me)
        val logged = org.robolectric.shadows.ShadowLog.getLogs().filter { it.tag == "InvitationFinisher" }.joinToString("\n") { it.msg }
        assertTrue("the pass said what it did", logged.contains("DONE=1"))
        for (secret in listOf("zoe", "Zoe", "example.test", zoe, me, sharingKey, signingKey, "ab".repeat(32), rows[0].shareId!!, rows[0].itemId!!)) {
            assertTrue("logged: $secret", !logged.contains(secret))
        }
    }
}
