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

import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.PreviousIdentitiesResponse
import com.pingolin.note.data.remote.dto.PreviousIdentityDto
import com.pingolin.note.data.remote.dto.SealedPreviousIdentityDto
import com.pingolin.note.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.MobileException

/**
 * The recovery path's key handling.
 *
 * The seal opens under a value derived from the CURRENT account private key,
 * which lives in the SDK's in-memory slot — and this app fills that slot
 * only at login. Nothing refills it when the process restarts on a stored
 * session, so without the restore a phone that had already synced would keep
 * showing "Untitled" after its owner recovered the identity in the portal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviousIdentityLoaderTest {

    @MockK
    private lateinit var authApi: AuthApi

    @MockK
    private lateinit var cryptoManager: CryptoManager

    @MockK
    private lateinit var sessionManager: SessionManager

    private lateinit var loader: PreviousIdentityLoader

    private companion object {
        const val PERSISTED_KEY = "cGVyc2lzdGVkLXNoYXJpbmcta2V5LTMyLWJ5dGVzISE="
    }

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { cryptoManager.previousVaultKeys() } returns emptyList()
        loader = PreviousIdentityLoader(authApi, cryptoManager, sessionManager)
    }

    @After
    fun teardown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun sealedResponse(count: Int = 1) = Response.success(
        ApiResponse(
            success = true,
            data = PreviousIdentitiesResponse(
                identities = (1..count).map {
                    PreviousIdentityDto(
                        keyVersion = it,
                        appSealedPrivateKey = SealedPreviousIdentityDto(
                            nonce = "nonce-$it",
                            ciphertext = "ciphertext-$it",
                        ),
                    )
                }
            ),
        )
    )

    /**
     * On every launch after the one that signed in, the SDK holds no
     * private key, so the import throws KeyNotFound. The key is not lost — login
     * persisted it — so the loader must hand it back and retry instead of
     * reporting zero recovered identities.
     */
    @Test
    fun `ensureLoaded_restoresThePersistedPrivateKey_whenTheSdkSlotIsEmpty`() = runTest {
        coEvery { authApi.getPreviousIdentities() } returns sealedResponse()
        every { sessionManager.getSharingPrivateKey() } returns PERSISTED_KEY
        every { cryptoManager.importPrivateKey(any()) } just Runs
        // Empty slot on the first attempt; the retry after the key is restored
        // is the one that opens the seal.
        every { cryptoManager.importPreviousIdentities(any()) } throwsMany
            listOf(MobileException.KeyNotFound("no private key loaded; cannot open a recovered identity")) andThen 1u

        val imported = loader.ensureLoaded()

        assertEquals(1, imported)
        verify(exactly = 1) { cryptoManager.importPrivateKey(PERSISTED_KEY) }
        verify(exactly = 2) { cryptoManager.importPreviousIdentities(any()) }
    }

    /**
     * The happy path must not touch the key slot: overwriting a live in-memory
     * key with a persisted copy is a needless way to break the session that just
     * logged in.
     */
    @Test
    fun `ensureLoaded_leavesTheKeySlotAlone_whenTheImportSucceeds`() = runTest {
        coEvery { authApi.getPreviousIdentities() } returns sealedResponse()
        every { cryptoManager.importPreviousIdentities(any()) } returns 1u

        assertEquals(1, loader.ensureLoaded())

        verify(exactly = 0) { cryptoManager.importPrivateKey(any()) }
        verify(exactly = 0) { sessionManager.getSharingPrivateKey() }
    }

    /**
     * With no persisted key either there is nothing to retry with, and the
     * failure must stay contained: zero recovered identities, no throw, sync
     * carries on.
     */
    @Test
    fun `ensureLoaded_noPersistedKey_returnsZeroWithoutThrowing`() = runTest {
        coEvery { authApi.getPreviousIdentities() } returns sealedResponse()
        every { sessionManager.getSharingPrivateKey() } returns null
        every { cryptoManager.importPreviousIdentities(any()) } throws
            MobileException.KeyNotFound("no private key loaded")

        assertEquals(0, loader.ensureLoaded())

        verify(exactly = 0) { cryptoManager.importPrivateKey(any()) }
    }

    /**
     * `loaded` means "we have taken in what this account has", not "we asked
     * once". Latching on a FAILED import would make the failure permanent
     * for the whole process — only a restart would cure it.
     */
    @Test
    fun `ensureLoaded_retriesAfterAFailedImport`() = runTest {
        coEvery { authApi.getPreviousIdentities() } returns sealedResponse()
        every { sessionManager.getSharingPrivateKey() } returns null
        every { cryptoManager.importPreviousIdentities(any()) } throws
            MobileException.KeyNotFound("no private key loaded")

        assertEquals(0, loader.ensureLoaded())

        // The key becomes available (e.g. the user logs back in) and the next
        // sync must take the identities in rather than skip on a stale latch.
        every { cryptoManager.importPreviousIdentities(any()) } returns 1u

        assertEquals(1, loader.ensureLoaded())
    }

    /** The answer for almost every account: nothing sealed, latched, silent. */
    @Test
    fun `ensureLoaded_noIdentities_latchesWithoutRefetching`() = runTest {
        coEvery { authApi.getPreviousIdentities() } returns Response.success(
            ApiResponse(success = true, data = PreviousIdentitiesResponse(identities = emptyList()))
        )

        assertEquals(0, loader.ensureLoaded())
        assertEquals(0, loader.ensureLoaded())

        coVerify(exactly = 1) { authApi.getPreviousIdentities() }
        verify(exactly = 0) { cryptoManager.importPreviousIdentities(any()) }
        // Nothing to see: no log line for the case every account hits.
        verify(exactly = 0) { android.util.Log.w(any(), any<String>()) }
    }

    /**
     * A successful import latches, so the whole session costs one GET.
     */
    @Test
    fun `ensureLoaded_successfulImport_isNotRepeated`() = runTest {
        coEvery { authApi.getPreviousIdentities() } returns sealedResponse()
        every { cryptoManager.importPreviousIdentities(any()) } returns 1u
        every { cryptoManager.previousVaultKeys() } returns listOf("vault-key-b64")

        assertEquals(1, loader.ensureLoaded())
        assertEquals(1, loader.ensureLoaded())

        coVerify(exactly = 1) { authApi.getPreviousIdentities() }
        verify(exactly = 1) { cryptoManager.importPreviousIdentities(any()) }
    }

    /**
     * What the account-switch wipe relies on. The latch is a field on a
     * @Singleton that outlives the account, so after [PreviousIdentityLoader.reset]
     * the INCOMING account must fetch its own identities rather than inherit the
     * previous account's "already loaded" answer.
     */
    @Test
    fun `reset_makesTheNextEnsureLoadedFetchAgain`() = runTest {
        coEvery { authApi.getPreviousIdentities() } returns sealedResponse()
        every { cryptoManager.importPreviousIdentities(any()) } returns 1u
        every { cryptoManager.previousVaultKeys() } returns listOf("vault-key-b64")

        loader.ensureLoaded()
        loader.ensureLoaded()
        coVerify(exactly = 1) { authApi.getPreviousIdentities() }

        loader.reset()
        loader.ensureLoaded()

        coVerify(exactly = 2) { authApi.getPreviousIdentities() }
    }

    /**
     * Two syncs starting together must produce ONE fetch, not two: a
     * foreground refresh and the worker can both pass the `loaded` check
     * within milliseconds of each other and each do the whole round-trip. That
     * is benign only for as long as the import stays idempotent.
     */
    @Test
    fun `ensureLoaded_concurrentCallers_fetchOnce`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { authApi.getPreviousIdentities() } coAnswers {
            calls++
            // Hold the first caller inside the fetch so the second one arrives
            // while it is still in flight, which makes the race deterministic.
            if (calls == 1) {
                started.complete(Unit)
                release.await()
            }
            sealedResponse()
        }
        every { cryptoManager.importPreviousIdentities(any()) } returns 1u
        every { cryptoManager.previousVaultKeys() } returns listOf("vault-key-b64")

        val first = async { loader.ensureLoaded() }
        started.await()
        val second = async { loader.ensureLoaded() }
        release.complete(Unit)

        assertEquals(1, first.await())
        assertEquals(1, second.await())
        coVerify(exactly = 1) { authApi.getPreviousIdentities() }
        verify(exactly = 1) { cryptoManager.importPreviousIdentities(any()) }
    }

    /**
     * A load that was already on the network when the account was wiped must not
     * latch afterwards: the flag it would set belongs to the departed account,
     * and the incoming one would then skip its own fetch — exactly the failure
     * [PreviousIdentityLoader.reset] exists to prevent, a few milliseconds late.
     */
    @Test
    fun `ensureLoaded_resetDuringTheFetch_doesNotLatchForTheNextAccount`() = runTest {
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { authApi.getPreviousIdentities() } coAnswers {
            calls++
            if (calls == 1) {
                inFlight.complete(Unit)
                release.await()
            }
            sealedResponse()
        }
        every { cryptoManager.importPreviousIdentities(any()) } returns 1u
        every { cryptoManager.previousVaultKeys() } returns listOf("vault-key-b64")

        val racing = async { loader.ensureLoaded() }
        inFlight.await()
        loader.reset()          // the account switch lands mid-fetch
        release.complete(Unit)
        racing.await()

        // The incoming account must still go and ask for its own.
        loader.ensureLoaded()
        coVerify(exactly = 2) { authApi.getPreviousIdentities() }
    }

    /**
     * A network failure must not latch either — the identities are still there
     * to fetch on the next sync.
     */
    @Test
    fun `ensureLoaded_networkFailure_retriesOnTheNextSync`() = runTest {
        coEvery { authApi.getPreviousIdentities() } throws RuntimeException("offline")

        assertEquals(0, loader.ensureLoaded())

        coEvery { authApi.getPreviousIdentities() } returns sealedResponse()
        every { cryptoManager.importPreviousIdentities(any()) } returns 1u

        assertEquals(1, loader.ensureLoaded())
    }

    /**
     * The one line worth seeing must survive R8. `-assumenosideeffects` in
     * proguard-rules.pro strips v/d/i from release builds, so the diagnostic has
     * to be at WARN — and it must fire for `0 of 1` too, which is the state where
     * the user is looking at unreadable notes.
     */
    @Test
    fun `ensureLoaded_logsAtWarn_whenTheAccountHasSealedIdentities`() = runTest {
        coEvery { authApi.getPreviousIdentities() } returns sealedResponse(count = 2)
        every { cryptoManager.importPreviousIdentities(any()) } returns 0u

        loader.ensureLoaded()

        verify { android.util.Log.w("PreviousIdentity", "previous identities: 0 of 2 sealed opened") }
    }

    // --- The re-check only ADDS (the SDK import replaces its set) ---

    /** The SDK as it is: importPreviousIdentities REPLACES what it holds. */
    private fun sdkThatReplaces(): MutableList<List<uniffi.pingolin_mobile.SealedPreviousIdentity>> {
        val imports = mutableListOf<List<uniffi.pingolin_mobile.SealedPreviousIdentity>>()
        var held = emptyList<uniffi.pingolin_mobile.SealedPreviousIdentity>()
        every { cryptoManager.importPreviousIdentities(any()) } answers {
            held = firstArg()
            imports += held
            held.size.toUInt()
        }
        every { cryptoManager.previousVaultKeys() } answers { held.map { "vault-of-${it.nonce}" } }
        return imports
    }

    private fun answer(vararg n: Int) = Response.success(
        ApiResponse(
            success = true,
            data = PreviousIdentitiesResponse(
                identities = n.map {
                    PreviousIdentityDto(
                        keyVersion = it,
                        appSealedPrivateKey = SealedPreviousIdentityDto(nonce = "nonce-$it", ciphertext = "ciphertext-$it"),
                    )
                },
            ),
        ),
    )

    @Test
    fun `recheck - an empty answer after a good one keeps the count (the union)`() = runTest {
        val imports = sdkThatReplaces()
        coEvery { authApi.getPreviousIdentities() } returnsMany listOf(answer(1), answer())

        assertTrue("the first answer opens one", loader.recheck())
        assertEquals(1, loader.vaultKeys().size)

        assertFalse("an empty answer opens nothing new", loader.recheck())
        assertEquals("and takes nothing away", 1, loader.vaultKeys().size)
        assertEquals(listOf("nonce-1"), imports.last().map { it.nonce })
    }

    @Test
    fun `recheck - a later answer adds to what was held`() = runTest {
        val imports = sdkThatReplaces()
        coEvery { authApi.getPreviousIdentities() } returnsMany listOf(answer(1), answer(2))

        loader.recheck()
        assertTrue(loader.recheck())
        assertEquals(listOf("nonce-1", "nonce-2"), imports.last().map { it.nonce })
        assertEquals(2, loader.vaultKeys().size)
    }

    @Test
    fun `recheck - the same answer again opens nothing new`() = runTest {
        sdkThatReplaces()
        coEvery { authApi.getPreviousIdentities() } returns answer(1)

        assertTrue(loader.recheck())
        assertFalse(loader.recheck())
    }

    @Test
    fun `recheck - sign-out drops the held blobs`() = runTest {
        val imports = sdkThatReplaces()
        coEvery { authApi.getPreviousIdentities() } returnsMany listOf(answer(1), answer(2))

        loader.recheck()
        loader.reset()
        loader.recheck()
        assertEquals("the next account's import carries none of the last one's", listOf("nonce-2"), imports.last().map { it.nonce })
    }

    /** A key load that awaits the network re-checks the session after the
     * await. Sign-out mid-fetch → nothing is added. */
    @Test
    fun `recheck - a sign-out while the fetch is on the network adds nothing`() = runTest {
        val imports = sdkThatReplaces()
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { authApi.getPreviousIdentities() } coAnswers {
            inFlight.complete(Unit)
            release.await()
            answer(1)
        }

        val result = async { loader.recheck() }
        inFlight.await()
        loader.reset()
        release.complete(Unit)

        assertFalse(result.await())
        assertTrue("nothing was imported into the next session", imports.isEmpty())
        assertEquals(0, loader.vaultKeys().size)
    }

    @Test
    fun `ensureLoaded - a sign-out while the fetch is on the network adds nothing`() = runTest {
        val imports = sdkThatReplaces()
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { authApi.getPreviousIdentities() } coAnswers {
            inFlight.complete(Unit)
            release.await()
            answer(1)
        }

        val result = async { loader.ensureLoaded() }
        inFlight.await()
        loader.reset()
        release.complete(Unit)

        assertEquals(0, result.await())
        assertTrue(imports.isEmpty())
    }

    @Test
    fun `ensureLoaded after a recheck does not fetch again`() = runTest {
        sdkThatReplaces()
        coEvery { authApi.getPreviousIdentities() } returns answer(1)

        loader.recheck()
        loader.ensureLoaded()
        coVerify(exactly = 1) { authApi.getPreviousIdentities() }
    }
}
