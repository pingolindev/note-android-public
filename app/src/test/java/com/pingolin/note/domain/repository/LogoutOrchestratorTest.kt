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
import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.util.PendingPushUnregisterStore
import com.pingolin.note.util.PushNotificationManager
import com.pingolin.note.util.SessionManager
import com.pingolin.note.worker.PushUnregisterWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import com.pingolin.note.data.remote.dto.LogoutRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response as RetrofitResponse

/**
 * The sign-out ORDERING guarantees of LogoutOrchestrator:
 *  - online: unsynced edits flush BEFORE the server revoke (revoking first
 *    would invalidate the token mid-flush);
 *  - offline: the flush is skipped but revoke/wipe/clear still run;
 *  - a throwing revoke never stops the wipe or the session clear.
 */
class LogoutOrchestratorTest {

    private val appContext: Context = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)
    private val syncRepository: SyncRepository = mockk(relaxed = true)
    private val authApi: AuthApi = mockk()
    private val noteApi: NoteApi = mockk(relaxed = true)
    private val localDataWiper: LocalDataWiper = mockk()
    private val pushNotificationManager: PushNotificationManager = mockk(relaxed = true)
    private val pendingPushUnregisterStore: PendingPushUnregisterStore = mockk(relaxed = true)

    private fun orchestrator() = LogoutOrchestrator(
        appContext, sessionManager, syncRepository, authApi, noteApi,
        localDataWiper, pushNotificationManager, pendingPushUnregisterStore,
    )

    @Before
    fun setUp() {
        mockkObject(PushUnregisterWorker.Companion)
        every { PushUnregisterWorker.enqueue(any()) } just Runs
        // No push registration by default — the unregister leg is exercised
        // implicitly; these tests pin the flush/revoke/wipe ordering.
        every { sessionManager.getPushType() } returns null
        every { sessionManager.getPushToken() } returns null
    }

    @After
    fun tearDown() = unmockkAll()

    private fun stubConnectivity(online: Boolean) {
        val cm = mockk<android.net.ConnectivityManager>()
        every { appContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        if (online) {
            val net = mockk<android.net.Network>()
            val caps = mockk<android.net.NetworkCapabilities>()
            every { cm.activeNetwork } returns net
            every { cm.getNetworkCapabilities(net) } returns caps
            every { caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        } else {
            every { cm.activeNetwork } returns null
            every { cm.getNetworkCapabilities(any()) } returns null
        }
    }

    @Test
    fun `online_flushesPendingThenRevokes_thenWipesAndClears`() = runTest {
        stubConnectivity(online = true)
        coEvery { syncRepository.pushPendingChanges() } returns 1
        coEvery { authApi.logout(any()) } returns RetrofitResponse.success(Unit)
        every { sessionManager.clear() } just Runs
        val preWipe = slot<suspend () -> Unit>()
        val onComplete = slot<() -> Unit>()
        every { localDataWiper.wipe(capture(preWipe), capture(onComplete)) } just Runs

        orchestrator().logout()

        verify { localDataWiper.wipe(any(), any()) }
        preWipe.captured.invoke()
        coVerifyOrder {
            syncRepository.pushPendingChanges()
            authApi.logout(any())
        }
        onComplete.captured.invoke()
        verify { sessionManager.clear() }
    }

    @Test
    fun `offline_skipsFlush_stillRevokesAndClears`() = runTest {
        stubConnectivity(online = false)
        coEvery { authApi.logout(any()) } returns RetrofitResponse.success(Unit)
        every { sessionManager.clear() } just Runs
        val preWipe = slot<suspend () -> Unit>()
        val onComplete = slot<() -> Unit>()
        every { localDataWiper.wipe(capture(preWipe), capture(onComplete)) } just Runs

        orchestrator().logout()
        preWipe.captured.invoke()

        coVerify(exactly = 0) { syncRepository.pushPendingChanges() }
        coVerify { authApi.logout(any()) }
        onComplete.captured.invoke()
        verify { sessionManager.clear() }
    }

    @Test
    fun `serverRevokeThrows_stillWipesAndClears`() = runTest {
        stubConnectivity(online = false)
        coEvery { authApi.logout(any()) } throws RuntimeException("network")
        every { sessionManager.clear() } just Runs
        val preWipe = slot<suspend () -> Unit>()
        val onComplete = slot<() -> Unit>()
        every { localDataWiper.wipe(capture(preWipe), capture(onComplete)) } just Runs

        orchestrator().logout()
        preWipe.captured.invoke()

        verify { localDataWiper.wipe(any(), any()) }
        onComplete.captured.invoke()
        verify { sessionManager.clear() }
    }

    // --- the session is named by its refresh token; never refreshed; bounded ---

    @Test
    fun `revoke_namesTheSessionByItsRefreshToken_evenWithAnExpiredBearer`() = runTest {
        stubConnectivity(online = false)
        every { sessionManager.getAccessToken() } returns "expired-access"
        every { sessionManager.getRefreshToken() } returns "the-refresh-token"
        coEvery { authApi.logout(any()) } returns RetrofitResponse.success(Unit)
        val preWipe = slot<suspend () -> Unit>()
        every { localDataWiper.wipe(capture(preWipe), any()) } just Runs

        orchestrator().logout()
        preWipe.captured.invoke()

        coVerify(exactly = 1) { authApi.logout(LogoutRequest("the-refresh-token")) }
    }

    @Test
    fun `revoke_isCappedAtFourSeconds_andTheWipeStillRuns`() = runTest {
        stubConnectivity(online = false)
        every { sessionManager.getRefreshToken() } returns "rt"
        coEvery { authApi.logout(any()) } coAnswers { awaitCancellation() }
        val preWipe = slot<suspend () -> Unit>()
        every { localDataWiper.wipe(capture(preWipe), any()) } just Runs

        orchestrator().logout()
        val start = currentTime
        preWipe.captured.invoke()

        assertEquals("a sign-out never waits longer on the revoke", 4_000L, currentTime - start)
    }

    @Test
    fun `revoke_withOnlyTheRefreshTokenIsStillSent`() = runTest {
        stubConnectivity(online = false)
        every { sessionManager.getAccessToken() } returns null
        every { sessionManager.getRefreshToken() } returns "rt"
        coEvery { authApi.logout(any()) } returns RetrofitResponse.success(Unit)
        val preWipe = slot<suspend () -> Unit>()
        every { localDataWiper.wipe(capture(preWipe), any()) } just Runs

        orchestrator().logout()
        preWipe.captured.invoke()

        coVerify(exactly = 1) { authApi.logout(LogoutRequest("rt")) }
    }

    @Test
    fun `revoke_withNeitherTokenIsNotSent`() = runTest {
        stubConnectivity(online = false)
        every { sessionManager.getAccessToken() } returns null
        every { sessionManager.getRefreshToken() } returns null
        val preWipe = slot<suspend () -> Unit>()
        every { localDataWiper.wipe(capture(preWipe), any()) } just Runs

        orchestrator().logout()
        preWipe.captured.invoke()

        coVerify(exactly = 0) { authApi.logout(any()) }
    }

    /** The wire contract: `{"refresh_token": …}`, and the static sentinel that
     *  makes TokenAuthenticator return null instead of refreshing on a 401. */
    @Test
    fun `revoke_bodyAndNoRefreshSentinel`() {
        assertEquals("""{"refresh_token":"rt"}""", com.google.gson.Gson().toJson(LogoutRequest("rt")))
        val method = AuthApi::class.java.methods.single { it.name == "logout" }
        val headers = method.getAnnotation(retrofit2.http.Headers::class.java)?.value?.toList().orEmpty()
        assertTrue(headers.toString(), "X-Pingolin-No-Refresh: 1" in headers)
    }
}
