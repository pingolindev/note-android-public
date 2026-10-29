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
import com.pingolin.note.collab.WrappedKeyPair
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.ItemDto
import com.pingolin.note.data.remote.dto.PublicKeyLookupEntry
import com.pingolin.note.data.remote.dto.PublicKeyLookupRequest
import com.pingolin.note.data.remote.dto.PublicKeyLookupResponse
import com.pingolin.note.data.remote.dto.RotateShareEntry
import com.pingolin.note.data.remote.dto.RotateSharesRequest
import com.pingolin.note.data.remote.dto.RotateSharesResponse
import com.pingolin.note.data.remote.dto.ShareInfoDto
import com.pingolin.note.util.RetainedContentKeyStore
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response as RetrofitResponse
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ContentKeyRotatorTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @MockK
    private lateinit var noteApi: NoteApi

    @MockK
    private lateinit var itemDao: ItemDao

    @MockK
    private lateinit var noteCrypto: NoteCrypto

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var pinStore: SenderKeyPinStore

    @MockK
    private lateinit var retainedContentKeyStore: RetainedContentKeyStore

    @MockK
    private lateinit var okHttpClient: OkHttpClient

    private lateinit var rotator: ContentKeyRotator

    private val vaultB64 =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
    private val oldContentKey = ByteArray(32) { 1 }
    private val newContentKey = ByteArray(32) { 2 }

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        every { android.util.Base64.encodeToString(any<ByteArray>(), any<Int>()) } answers {
            java.util.Base64.getEncoder().withoutPadding().encodeToString(firstArg<ByteArray>())
        }

        every { sessionManager.getMasterKey() } returns vaultB64
        // ContentKeyRotator reads the Ed25519 signing seed and the user_id
        // to sign each rewrap. Stub null so the tests skip the signing
        // path. getSharingPrivateKey is not the signing seed, but is kept
        // stubbed in case the fan-out reads it.
        every { sessionManager.getSharingPrivateKey() } returns null
        every { sessionManager.getSigningPrivateKey() } returns null
        every { sessionManager.getUserId() } returns null

        // Defaults for the crypto path
        every { noteCrypto.unwrapWithVault(any(), any(), any()) } returns oldContentKey
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns "{}"
        every { noteCrypto.generateContentKey() } returns newContentKey
        every { noteCrypto.encryptNoteWithContentKeyV2(any(), any(), any()) } returns
            NotePayload("new-nonce", "new-ct")
        every { noteCrypto.wrapWithVault(any(), any()) } returns
            WrappedKeyPair("new-wrap", "wrap-nonce")

        coEvery { itemDao.upsert(any()) } just Runs
        // The local confirm is a compare-and-swap: default to "applied"
        // (1 row) so the happy-path tests see the rotation land. The skip path
        // (0 rows) is exercised explicitly below.
        coEvery {
            itemDao.writeRotatedItemIfUnchanged(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns 1

        // Default: no pin conflict — send-side gate allows every reseal. The
        // refusal path is exercised explicitly in its own test below.
        every { pinStore.allowSealToRecipient(any(), any(), any()) } returns true

        // Rotation retains the retired vault-wrap for attachment availability.
        every { retainedContentKeyStore.retain(any(), any(), any()) } just Runs

        // The inline attachment re-key (rotate path) calls noteApi.listAttachments;
        // left unstubbed it throws, which rekeyAttachments swallows (best-effort) so
        // these rotation-focused tests are unaffected. okHttpClient is never reached
        // (the early return fires first), so a bare mock satisfies the constructor.
        rotator = ContentKeyRotator(noteApi, itemDao, noteCrypto, sessionManager, pinStore, retainedContentKeyStore, okHttpClient)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    // =============================================================================
    // rotate TESTS — error paths
    // =============================================================================

    @Test
    fun `rotate_noteNotFound_returnsNotRotated`() = runTest {
        coEvery { itemDao.getById("note-1") } returns null

        val result = rotator.rotate("note-1")

        assertFalse(result.rotated)
        assertEquals(0, result.remainingCount)
    }

    @Test
    fun `rotate_noWrappedKey_returnsNotRotated`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithoutWrap()

        val result = rotator.rotate("note-1")

        assertFalse(result.rotated)
    }

    @Test
    fun `rotate_noVaultKey_returnsNotRotated`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        every { sessionManager.getMasterKey() } returns null

        val result = rotator.rotate("note-1")

        assertFalse(result.rotated)
    }

    @Test
    fun `rotate_unwrapFails_returnsNotRotated`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        every { noteCrypto.unwrapWithVault(any(), any(), any()) } returns null

        val result = rotator.rotate("note-1")

        assertFalse(result.rotated)
    }

    @Test
    fun `rotate_getItemFails_returnsNotRotated`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } throws RuntimeException("net")

        val result = rotator.rotate("note-1")

        assertFalse(result.rotated)
    }

    @Test
    fun `rotate_decryptFails_returnsNotRotated`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns null

        val result = rotator.rotate("note-1")

        assertFalse(result.rotated)
    }

    // =============================================================================
    // rotate TESTS — happy path, no remaining recipients
    // =============================================================================

    @Test
    fun `rotate_callsRotateSharesApi_withNewCiphertextAndWrap`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = emptyList())
        )
        val captured = slot<RotateSharesRequest>()
        coEvery { noteApi.rotateShares("note-1", capture(captured)) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )

        val result = rotator.rotate("note-1")

        assertTrue(result.rotated)
        assertEquals(0, result.remainingCount)
        assertEquals("new-ct", captured.captured.encryptedContent)
        assertEquals("new-nonce", captured.captured.contentNonce)
        assertEquals("new-wrap", captured.captured.wrappedContentKey)
        assertEquals("wrap-nonce", captured.captured.wrappedContentKeyNonce)
        assertTrue(captured.captured.updateShares.isEmpty())
    }

    @Test
    fun `rotate_upsertsLocalRow_withNewWrap_andClearsDirty`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = emptyList())
        )
        coEvery { noteApi.rotateShares(any(), any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )

        rotator.rotate("note-1")

        // The rotation lands via the CAS confirm (not a blind upsert): it writes
        // the re-keyed body + fresh wrap + adopts the bumped version, gated on the
        // row still holding the pre-rotation ciphertext/nonce ("old-ct"/"old-nonce").
        coVerify {
            itemDao.writeRotatedItemIfUnchanged(
                id = "note-1",
                ciphertext = "new-ct",
                nonce = "new-nonce",
                // The plaintext that was actually re-keyed travels with its
                // ciphertext, so the row's cipher and plain halves can't drift
                // apart (the rotation decrypts the SERVER copy while the CAS
                // matches on the LOCAL ciphertext).
                decrypted = "{}",
                title = null,
                wrappedKey = "new-wrap",
                wrappedNonce = "wrap-nonce",
                version = 2L,
                expectedCiphertext = "old-ct",
                expectedNonce = "old-nonce",
            )
        }
        // Never fall back to a blind full-row upsert: it would drop a concurrent edit.
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    @Test
    fun `rotate_writesTheRekeyedPlaintextAndTitle_notJustTheCiphertext`() = runTest {
        // The rotation decrypts the SERVER copy and re-encrypts THAT
        // plaintext, while the local CAS matches on the LOCAL ciphertext.
        // On a clean-but-behind row those are different documents:
        // writing only the ciphertext would leave encrypted_content
        // holding E_Knew(P_server) while decrypted_content still holds
        // P_local, and the next edit — built from decrypted_content —
        // would push that stale body out as a silent revert of the other
        // device.
        val serverPlaintext = """{"title":"Server copy","body":"newer"}"""
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        every { noteCrypto.decryptNoteWithContentKeyV2(any(), any(), any(), any()) } returns serverPlaintext
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = emptyList())
        )
        coEvery { noteApi.rotateShares(any(), any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )

        rotator.rotate("note-1")

        // The plaintext that was actually re-keyed lands with its ciphertext, so
        // the row's cipher and plain halves describe the same document.
        coVerify {
            itemDao.writeRotatedItemIfUnchanged(
                id = "note-1",
                ciphertext = "new-ct",
                nonce = "new-nonce",
                decrypted = serverPlaintext,
                title = "Server copy",
                wrappedKey = "new-wrap",
                wrappedNonce = "wrap-nonce",
                version = 2L,
                expectedCiphertext = "old-ct",
                expectedNonce = "old-nonce",
            )
        }
    }

    @Test
    fun `rotate_localRowMovedOn_reportsRotated_andSkipsBlindUpsert`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = emptyList())
        )
        coEvery { noteApi.rotateShares(any(), any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )
        // A concurrent interactive edit landed during the rotate round-trip: the
        // CAS guard matches no row and leaves the dirty edit in place.
        coEvery {
            itemDao.writeRotatedItemIfUnchanged(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns 0

        val result = rotator.rotate("note-1")

        // The server re-key committed, so the rotation is still reported
        // successful even though the local overwrite was skipped to preserve the
        // concurrent edit.
        assertTrue(result.rotated)
        assertEquals(2L, result.newVersion)
        // The blind upsert is never used as a fallback: it would clobber the
        // concurrent edit.
        coVerify(exactly = 0) { itemDao.upsert(any()) }
    }

    // =============================================================================
    // rotate TESTS — remaining recipients, update_shares list
    // =============================================================================

    @Test
    fun `rotate_withRemainingRecipients_sealsForEach`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = listOf(
                    share("share-A", "alice@x.y"),
                    share("share-B", "bob@x.y"),
                ),
            )
        )
        coEvery { noteApi.lookupPublicKeys(any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = PublicKeyLookupResponse(
                    users = listOf(
                        PublicKeyLookupEntry("alice@x.y", "u-a", "alice-pub", null),
                        PublicKeyLookupEntry("bob@x.y", "u-b", "bob-pub", null),
                    ),
                ),
            )
        )
        every { noteCrypto.sealForRecipient(newContentKey, "alice-pub") } returns "sealed-A"
        every { noteCrypto.sealForRecipient(newContentKey, "bob-pub") } returns "sealed-B"

        val captured = slot<RotateSharesRequest>()
        coEvery { noteApi.rotateShares("note-1", capture(captured)) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )

        val result = rotator.rotate("note-1")

        // This fixture has no signing seed (getSigningPrivateKey() == null), so
        // neither rewrap can be signed. The rotation refuses to send an unsigned
        // rewrap: both recipients have received a share from this account
        // before, so they hold its pin and would DROP an unsigned one — the
        // rotation would cut them off while telling nobody. They surface as
        // re-wrap failures instead, which the caller reports to the owner.
        assertTrue(result.rotated)
        assertEquals(2, result.remainingCount)
        assertTrue(captured.captured.updateShares.isEmpty())
        assertEquals(listOf("alice@x.y", "bob@x.y"), result.reWrapFailures)
    }

    /**
     * A row with no key is someone invited before they had an account. Once the address has
     * one the directory answers for it, and a re-key that sealed the new key to that account
     * would hand over the note, readable, with no invitation.
     */
    @Test
    fun `a re-key never gives a key to a share that carries none`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = listOf(
                    share("share-A", "alice@x.y", hasWrap = true),
                    share("share-Z", "zoe@x.y", hasWrap = false),
                ),
            )
        )
        val asked = slot<PublicKeyLookupRequest>()
        coEvery { noteApi.lookupPublicKeys(capture(asked)) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = PublicKeyLookupResponse(
                    users = listOf(
                        PublicKeyLookupEntry("alice@x.y", "u-a", "alice-pub", null),
                        PublicKeyLookupEntry("zoe@x.y", "u-z", "zoe-pub", null),
                    ),
                ),
            )
        )
        every { noteCrypto.sealForRecipient(newContentKey, any()) } returns "sealed"
        val captured = slot<RotateSharesRequest>()
        coEvery { noteApi.rotateShares("note-1", capture(captured)) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )

        val result = rotator.rotate("note-1")

        assertTrue(result.rotated)
        // Not asked about, not sealed to, not a failure, and their row is not removed.
        assertEquals(listOf("alice@x.y"), asked.captured.emails)
        io.mockk.verify(exactly = 0) { noteCrypto.sealForRecipient(newContentKey, "zoe-pub") }
        assertTrue(captured.captured.updateShares.none { it.shareId == "share-Z" })
        assertTrue(captured.captured.removeShareIds.isEmpty())
        assertEquals(1, result.remainingCount)
        assertTrue("zoe@x.y" !in result.reWrapFailures)
    }

    /** A server that does not say whether a row carries a key (an older server):
     * every row is taken to carry one and is re-sealed.
     */
    @Test
    fun `a share the server says nothing about is taken to carry a key and is re-sealed`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = listOf(share("share-A", "alice@x.y")))
        )
        val asked = slot<PublicKeyLookupRequest>()
        coEvery { noteApi.lookupPublicKeys(capture(asked)) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = PublicKeyLookupResponse(users = listOf(PublicKeyLookupEntry("alice@x.y", "u-a", "alice-pub", null))))
        )
        every { noteCrypto.sealForRecipient(newContentKey, "alice-pub") } returns "sealed-A"
        coEvery { noteApi.rotateShares("note-1", any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )

        val result = rotator.rotate("note-1")

        assertEquals(listOf("alice@x.y"), asked.captured.emails)
        assertEquals(1, result.remainingCount)
    }

    @Test
    fun `rotate_withSigningSeed_sealsAndSignsForEach`() = runTest {
        // The happy path the test above cannot reach: with a signing seed the
        // rewraps are signed and sent.
        every { sessionManager.getSigningPrivateKey() } returns
            android.util.Base64.encodeToString(ByteArray(32) { 7 }, android.util.Base64.NO_WRAP)
        every { sessionManager.getUserId() } returns "u-me"
        every {
            noteCrypto.signNoteShareWrap(any(), any(), any(), any(), any(), any())
        } returns "sig"

        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = listOf(
                    share("share-A", "alice@x.y"),
                    share("share-B", "bob@x.y"),
                ),
            )
        )
        coEvery { noteApi.lookupPublicKeys(any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = PublicKeyLookupResponse(
                    users = listOf(
                        PublicKeyLookupEntry("alice@x.y", "u-a", "alice-pub", null),
                        PublicKeyLookupEntry("bob@x.y", "u-b", "bob-pub", null),
                    ),
                ),
            )
        )
        every { noteCrypto.sealForRecipient(newContentKey, "alice-pub") } returns "sealed-A"
        every { noteCrypto.sealForRecipient(newContentKey, "bob-pub") } returns "sealed-B"

        val captured = slot<RotateSharesRequest>()
        coEvery { noteApi.rotateShares("note-1", capture(captured)) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )

        val result = rotator.rotate("note-1")

        assertTrue(result.rotated)
        assertTrue(result.reWrapFailures.isEmpty())
        assertEquals(
            listOf(
                RotateShareEntry(shareId = "share-A", wrappedKey = "sealed-A", wrapSignature = "sig"),
                RotateShareEntry(shareId = "share-B", wrappedKey = "sealed-B", wrapSignature = "sig"),
            ),
            captured.captured.updateShares,
        )
    }

    @Test
    fun `rotate_removeShareIds_areExcludedFromUpdateShares`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = listOf(
                    share("share-A", "alice@x.y"),
                    share("share-B", "bob@x.y"),
                ),
            )
        )
        coEvery { noteApi.lookupPublicKeys(any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = PublicKeyLookupResponse(
                    users = listOf(
                        PublicKeyLookupEntry("alice@x.y", "u-a", "alice-pub", null),
                    ),
                ),
            )
        )
        every { noteCrypto.sealForRecipient(newContentKey, "alice-pub") } returns "sealed-A"

        val captured = slot<RotateSharesRequest>()
        coEvery { noteApi.rotateShares("note-1", capture(captured)) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )

        val result = rotator.rotate("note-1", removeShareIds = listOf("share-B"))

        assertTrue(result.rotated)
        assertEquals(1, result.remainingCount)
        // The removal still happens — revoking must not be blocked by this
        // device's own key state.
        assertEquals(listOf("share-B"), captured.captured.removeShareIds)
        // ...but with no signing seed in this fixture the surviving recipient's
        // rewrap can't be signed, so it is reported rather than sent unsigned.
        assertTrue(captured.captured.updateShares.isEmpty())
        assertEquals(listOf("alice@x.y"), result.reWrapFailures)
    }

    @Test
    fun `rotate_missingPubkey_goesToFailuresList`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = listOf(share("share-A", "ghost@x.y")),
            )
        )
        coEvery { noteApi.lookupPublicKeys(any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = PublicKeyLookupResponse(users = emptyList()),
            )
        )
        coEvery { noteApi.rotateShares(any(), any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )

        val result = rotator.rotate("note-1")

        assertTrue(result.rotated)
        assertTrue("ghost recipient recorded as failure", "ghost@x.y" in result.reWrapFailures)
    }

    @Test
    fun `rotate_pinMismatch_refusesToReseal_recipientGoesToFailures`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = listOf(share("share-A", "alice@x.y")))
        )
        coEvery { noteApi.lookupPublicKeys(any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = PublicKeyLookupResponse(
                    users = listOf(
                        PublicKeyLookupEntry("alice@x.y", "u-a", "alice-pub", "alice-sign"),
                    ),
                ),
            )
        )
        // Server substituted alice's sharing key — the pin gate refuses.
        every { pinStore.allowSealToRecipient("u-a", "alice-sign", "alice-pub") } returns false

        val captured = slot<RotateSharesRequest>()
        coEvery { noteApi.rotateShares("note-1", capture(captured)) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )

        val result = rotator.rotate("note-1")

        assertTrue(result.rotated)
        // Refused recipient is NOT sealed and is surfaced as a failure.
        assertTrue(captured.captured.updateShares.isEmpty())
        assertTrue("alice recorded as failure", "alice@x.y" in result.reWrapFailures)
        verify(exactly = 0) { noteCrypto.sealForRecipient(any(), "alice-pub") }
    }

    @Test
    fun `rotate_apiReturnsRevokedShareLink_exposesFlag`() = runTest {
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = emptyList())
        )
        coEvery { noteApi.rotateShares(any(), any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = RotateSharesResponse(version = 2L, revokedShareLink = true),
            )
        )

        val result = rotator.rotate("note-1")

        assertTrue(result.rotated)
        assertTrue(result.revokedShareLink)
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    // =============================================================================
    // attachment re-key — the v2 chunked format the web editor writes
    // =============================================================================

    /**
     * A web upload is a v2 blob. A re-key that cannot read v2 fails both
     * probes on it and silently leaves the blob under the OLD key: the removed
     * recipient keeps access and the survivors lose it. It must move to the
     * new key and stay v2 (every client reads v2; the uploader that wrote it
     * chose it for the memory profile).
     */
    @Test
    fun `rotate_rekeysAV2AttachmentBlob_ontoTheNewKey_andKeepsItV2`() = runTest {
        val plaintext = ByteArray(3000) { (it * 7 + 3).toByte() }
        val v2Blob = com.pingolin.note.collab.AttachmentBlobFormat.encryptV2(
            oldContentKey, plaintext, java.security.SecureRandom(), 1024,
        )
        coEvery { itemDao.getById("note-1") } returns entityWithWrap()
        coEvery { noteApi.getItem("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = remoteItem("note-1"))
        )
        coEvery { noteApi.listShares("note-1") } returns RetrofitResponse.success(
            ApiResponse(success = true, data = emptyList())
        )
        coEvery { noteApi.rotateShares(any(), any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = RotateSharesResponse(version = 2L))
        )
        coEvery { noteApi.listAttachments("note-1") } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = listOf(
                    com.pingolin.note.data.remote.dto.AttachmentDto(
                        id = "att-1", itemId = "note-1", r2Key = "r1", encryptedMeta = "meta-ct",
                        metaNonce = "meta-n", sizeBytes = v2Blob.size, createdAt = "2024-01-01T00:00:00.000Z",
                    ),
                ),
            )
        )
        coEvery { noteApi.downloadAttachment("att-1") } returns
            RetrofitResponse.success(with(okhttp3.ResponseBody.Companion) { v2Blob.toResponseBody(null) })
        coEvery { noteApi.presignUpload(any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = com.pingolin.note.data.remote.dto.PresignedUrlResponse("https://r2/put", "k2"))
        )
        val sent = slot<okhttp3.Request>()
        val call = mockk<okhttp3.Call>()
        every { okHttpClient.newCall(capture(sent)) } returns call
        every { call.execute() } answers {
            okhttp3.Response.Builder().request(sent.captured).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(with(okhttp3.ResponseBody.Companion) { ByteArray(0).toResponseBody(null) }).build()
        }
        val rekey = slot<com.pingolin.note.data.remote.dto.RekeyAttachmentRequest>()
        coEvery { noteApi.rekeyAttachment("att-1", capture(rekey)) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = null)
        )

        assertTrue(rotator.rotate("note-1").rotated)

        val body = okio.Buffer().also { sent.captured.body!!.writeTo(it) }.readByteArray()
        assertTrue("re-sealed as v2", com.pingolin.note.collab.AttachmentBlobFormat.isV2(body))
        assertArrayEquals(plaintext, com.pingolin.note.collab.AttachmentBlobFormat.decryptV2(newContentKey, body))
        assertNull(com.pingolin.note.collab.AttachmentBlobFormat.decryptV2(oldContentKey, body))
        assertEquals("k2", rekey.captured.r2Key)
        assertEquals(body.size, rekey.captured.sizeBytes)
    }

    private fun entityWithWrap() = ItemEntity(
        id = "note-1",
        userId = "u",
        itemType = 1,
        encryptedContent = "old-ct",
        contentNonce = "old-nonce",
        decryptedContent = "{}",
        title = "t",
        version = 1,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        wrappedContentKey = "old-wrap",
        wrappedContentKeyNonce = "old-wrap-nonce",
    )

    private fun entityWithoutWrap() = entityWithWrap().copy(
        wrappedContentKey = null,
        wrappedContentKeyNonce = null,
    )

    private fun remoteItem(id: String) = ItemDto(
        id = id,
        userId = "u",
        itemType = 1,
        encryptedContent = "server-ct",
        contentNonce = "server-nonce",
        version = 1,
        createdAt = "2024-01-01T00:00:00.000Z",
        updatedAt = "2024-01-01T00:00:00.000Z",
        deleted = false,
        circleId = null,
        notebookId = null,
        contentSize = 10,
    )

    private fun share(shareId: String, email: String, hasWrap: Boolean? = null) = ShareInfoDto(
        id = shareId,
        recipientEmail = email,
        permission = "edit",
        createdAt = "2024-01-01T00:00:00.000Z",
        hasWrap = hasWrap,
    )
}
