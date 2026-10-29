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

package com.pingolin.note.service

import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.RegisterFcmRequest
import com.pingolin.note.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response as RetrofitResponse

/**
 * Unit tests for the FCM service. Because
 * [NoteFirebaseMessagingService] extends [com.google.firebase.messaging.FirebaseMessagingService]
 * (an Android Service), some methods that lean on the real service context
 * are inherently hard to exercise in plain JVM tests. Those are covered by
 * `@Ignore` with a reason below. For the ones that only touch injected
 * dependencies we drive them via reflection-injected fields.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteFirebaseMessagingServiceTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var noteApi: NoteApi

    private lateinit var service: NoteFirebaseMessagingService

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        // We can't use Hilt for JVM unit tests, so we instantiate the
        // service directly and inject the lateinit fields via reflection.
        service = NoteFirebaseMessagingService()
        setField("sessionManager", sessionManager)
        setField("noteApi", noteApi)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    // =============================================================================
    // onNewToken TESTS
    // =============================================================================

    @Test
    fun `onNewToken_savesTokenAndMarksUnsynced`() = runTest {
        every { sessionManager.savePushToken(any()) } just Runs
        every { sessionManager.setPushTokenSynced(any()) } just Runs
        every { sessionManager.isLoggedIn() } returns false

        service.onNewToken("fcm-token-123")

        verify { sessionManager.savePushToken("fcm-token-123") }
        verify { sessionManager.setPushTokenSynced(false) }
    }

    @Test
    fun `onNewToken_notLoggedIn_skipsBackendRegister`() = runTest {
        every { sessionManager.savePushToken(any()) } just Runs
        every { sessionManager.setPushTokenSynced(any()) } just Runs
        every { sessionManager.isLoggedIn() } returns false

        service.onNewToken("fcm-token")

        // Backend register should NOT be called.
        coVerify(exactly = 0) { noteApi.registerFcmDevice(any()) }
    }

    @Test
    @org.junit.Ignore("syncTokenWithBackend() uses service context (this) for versionName — needs Robolectric or instrumented test")
    fun `onNewToken_loggedIn_registersWithBackend`() = runTest {
        every { sessionManager.savePushToken(any()) } just Runs
        every { sessionManager.setPushTokenSynced(any()) } just Runs
        every { sessionManager.isLoggedIn() } returns true
        every { sessionManager.getOrCreateDeviceId() } returns "device-xyz"
        coEvery { noteApi.registerFcmDevice(any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = Unit)
        )

        service.onNewToken("fcm-token")

        // On successful registration, we flip push_token_synced back to true.
        coVerify {
            noteApi.registerFcmDevice(match<RegisterFcmRequest> {
                it.deviceId == "device-xyz" && it.fcmToken == "fcm-token" && it.platform == "android"
            })
        }
    }

    @Test
    @org.junit.Ignore("onMessageReceived / notification posting requires real Android service context")
    fun `onMessageReceived_tickle_fetchesReminders`() = runTest {
        // Intentionally ignored — the notification + service context path
        // can only be exercised under Robolectric or via instrumented
        // tests. Leaving this stub as documentation.
    }

    private fun setField(name: String, value: Any) {
        val f = NoteFirebaseMessagingService::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(service, value)
    }
}
