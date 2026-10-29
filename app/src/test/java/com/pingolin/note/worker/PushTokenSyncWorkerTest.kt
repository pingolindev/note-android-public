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

package com.pingolin.note.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.RegisterFcmRequest
import com.pingolin.note.util.SessionManager
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * A single fire-and-forget register whose failure is only logged leaves push
 * permanently dead after one bad moment, while Settings still shows
 * "Registered". This worker is the heal path; these tests pin the behaviour
 * that makes it one — above all that a transient failure RETRIES rather than
 * being swallowed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushTokenSyncWorkerTest {

    @MockK
    private lateinit var context: Context

    @MockK
    private lateinit var workerParams: WorkerParameters

    @MockK
    private lateinit var noteApi: NoteApi

    @MockK
    private lateinit var sessionManager: SessionManager

    private lateinit var worker: PushTokenSyncWorker

    private fun ok() = Response.success(ApiResponse<Unit>(success = true))

    private fun err(code: Int) = Response.error<ApiResponse<Unit>>(
        code,
        "{}".toResponseBody("application/json".toMediaType()),
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)
        // appVersion is best-effort (runCatching), so a throwing PackageManager
        // is a valid state rather than something to stub out.
        every { context.applicationContext } returns context
        every { context.packageName } returns "com.pingolin.note"
        every { context.packageManager } throws RuntimeException("no PM in unit test")

        every { sessionManager.isLoggedIn() } returns true
        every { sessionManager.isPushTokenSynced() } returns false
        every { sessionManager.getOrCreateDeviceId() } returns "device-1"
        every { sessionManager.getPushType() } returns SessionManager.PUSH_TYPE_UNIFIEDPUSH
        every { sessionManager.getPushToken() } returns null
        every { sessionManager.getPushEndpoint() } returns "https://ntfy.sh/abc123"

        worker = PushTokenSyncWorker(context, workerParams, noteApi, sessionManager)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `not logged in does nothing`() = runTest {
        every { sessionManager.isLoggedIn() } returns false
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { noteApi.registerFcmDevice(any()) }
    }

    @Test
    fun `already synced does not re-register`() = runTest {
        every { sessionManager.isPushTokenSynced() } returns true
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { noteApi.registerFcmDevice(any()) }
    }

    @Test
    fun `unifiedpush without an endpoint waits instead of burning the backoff`() = runTest {
        every { sessionManager.getPushEndpoint() } returns null
        // The server rejects a unifiedpush row with no endpoint, so
        // retrying would spend the whole chain on a guaranteed 400.
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { noteApi.registerFcmDevice(any()) }
    }

    @Test
    fun `successful register marks synced and sends the endpoint in both fields`() = runTest {
        val body = slot<RegisterFcmRequest>()
        coEvery { noteApi.registerFcmDevice(capture(body)) } returns ok()

        assertTrue(worker.doWork() is ListenableWorker.Result.Success)

        verify { sessionManager.setPushTokenSynced(true) }
        assertEquals(SessionManager.PUSH_TYPE_UNIFIEDPUSH, body.captured.pushType)
        assertEquals("https://ntfy.sh/abc123", body.captured.pushEndpoint)
        // The server routes on push_type, but fcm_token must still carry the
        // endpoint — a blank token would make the eviction predicate match.
        assertEquals("https://ntfy.sh/abc123", body.captured.fcmToken)
    }

    @Test
    fun `server error retries rather than giving up`() = runTest {
        coEvery { noteApi.registerFcmDevice(any()) } returns err(503)
        assertTrue(worker.doWork() is ListenableWorker.Result.Retry)
        verify(exactly = 0) { sessionManager.setPushTokenSynced(true) }
    }

    @Test
    fun `network exception retries`() = runTest {
        coEvery { noteApi.registerFcmDevice(any()) } throws java.io.IOException("offline")
        assertTrue(worker.doWork() is ListenableWorker.Result.Retry)
        verify(exactly = 0) { sessionManager.setPushTokenSynced(true) }
    }

    @Test
    fun `unauthorized retries so a token refresh can land first`() = runTest {
        coEvery { noteApi.registerFcmDevice(any()) } returns err(401)
        assertTrue(worker.doWork() is ListenableWorker.Result.Retry)
    }

    @Test
    fun `rate limited retries`() = runTest {
        coEvery { noteApi.registerFcmDevice(any()) } returns err(429)
        assertTrue(worker.doWork() is ListenableWorker.Result.Retry)
    }

    @Test
    fun `malformed registration fails permanently instead of spinning`() = runTest {
        coEvery { noteApi.registerFcmDevice(any()) } returns err(400)
        assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
        verify(exactly = 0) { sessionManager.setPushTokenSynced(true) }
    }

    @Test
    fun `fcm lane sends the token, not the endpoint`() = runTest {
        every { sessionManager.getPushType() } returns SessionManager.PUSH_TYPE_FCM
        every { sessionManager.getPushToken() } returns "fcm-token-xyz"
        every { sessionManager.getPushEndpoint() } returns null
        val body = slot<RegisterFcmRequest>()
        coEvery { noteApi.registerFcmDevice(capture(body)) } returns ok()

        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        assertEquals("fcm-token-xyz", body.captured.fcmToken)
        assertEquals(null, body.captured.pushEndpoint)
    }
}
