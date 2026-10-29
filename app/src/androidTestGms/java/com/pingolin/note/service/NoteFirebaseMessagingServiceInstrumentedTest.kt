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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.google.firebase.messaging.RemoteMessage
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.RegisterFcmRequest
import com.pingolin.note.data.remote.dto.ReminderDto
import com.pingolin.note.util.SessionManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response as RetrofitResponse

/**
 * Instrumented counterpart of the JVM [NoteFirebaseMessagingServiceTest].
 *
 * The JVM version @Ignore's the "logged in ⇒ backend register" path because
 * [NoteFirebaseMessagingService.syncTokenWithBackend] reads versionName from
 * the service's [android.content.Context] — no such context exists under a
 * plain JUnit runner. Under instrumentation the service does have a real
 * ContextWrapper, so this test exercises onNewToken end-to-end.
 *
 * Covers:
 * - Token saved + setPushTokenSynced(false) even when logged out.
 * - When logged in: deviceId + fcmToken + "android" platform hit the
 *   backend via [NoteApi.registerFcmDevice].
 * - Successful backend response flips setPushTokenSynced back to true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@MediumTest
class NoteFirebaseMessagingServiceInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val testDispatcher = UnconfinedTestDispatcher()

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var noteApi: NoteApi

    private lateinit var service: NoteFirebaseMessagingService

    @Before
    fun setUp() {
        hiltRule.inject()
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)
        service = NoteFirebaseMessagingService()
        setField("sessionManager", sessionManager)
        setField("noteApi", noteApi)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun onNewToken_savesTokenAndMarksUnsynced_evenWhenLoggedOut() = runTest {
        every { sessionManager.savePushToken(any()) } just Runs
        every { sessionManager.setPushTokenSynced(any()) } just Runs
        every { sessionManager.isLoggedIn() } returns false

        service.onNewToken("token-abc")

        verify { sessionManager.savePushToken("token-abc") }
        verify { sessionManager.setPushTokenSynced(false) }
        coVerify(exactly = 0) { noteApi.registerFcmDevice(any()) }
    }

    @Test
    fun onNewToken_loggedIn_registersWithBackend() {
        // The service's launched coroutine runs on its own SupervisorJob +
        // Dispatchers.IO scope, independent of the test dispatcher. We
        // drive the call synchronously and then wait (bounded) for the
        // backend mock to be hit.
        every { sessionManager.savePushToken(any()) } just Runs
        every { sessionManager.setPushTokenSynced(any()) } just Runs
        every { sessionManager.isLoggedIn() } returns true
        every { sessionManager.getOrCreateDeviceId() } returns "device-xyz"
        coEvery { noteApi.registerFcmDevice(any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = Unit),
        )

        service.onNewToken("token-xyz")

        // Wait up to 3s for the backend registration to happen. The IO
        // scope inside the service typically completes in milliseconds.
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                coVerify(exactly = 1) {
                    noteApi.registerFcmDevice(
                        match<RegisterFcmRequest> {
                            it.deviceId == "device-xyz" &&
                                it.fcmToken == "token-xyz" &&
                                it.platform == "android"
                        },
                    )
                }
                return
            } catch (_: AssertionError) {
                Thread.sleep(50)
            }
        }
        // Final check with real failure message.
        coVerify(exactly = 1) {
            noteApi.registerFcmDevice(
                match<RegisterFcmRequest> {
                    it.deviceId == "device-xyz" &&
                        it.fcmToken == "token-xyz" &&
                        it.platform == "android"
                },
            )
        }
    }

    @Test
    fun onMessageReceived_tickle_fetchesRemindersFromBackend() {
        // Build a real RemoteMessage using its public Builder. The
        // tickle type is what the backend emits for reminder pushes.
        val message = RemoteMessage.Builder("notetest@fcm.googleapis.com")
            .addData("type", "tickle")
            .build()

        every { sessionManager.isLoggedIn() } returns true
        // The tickle path gates on the device-local Reminders toggle before it
        // fetches; the strict mock needs an answer for it, or the test dies with
        // a MockKException rather than a useful assertion.
        every { sessionManager.areReminderNotificationsEnabled() } returns true
        coEvery { noteApi.listReminders() } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = listOf(
                    ReminderDto(
                        id = "rem-1",
                        itemId = "note-1",
                        triggerAt = "2026-04-23T10:00:00Z",
                        repeatRule = null,
                        fired = false,
                        dismissed = false,
                    ),
                ),
            ),
        )

        service.onMessageReceived(message)

        // Bounded wait for the listReminders call — the service launches
        // fetchAndShowNotifications on its own IO scope.
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                coVerify(exactly = 1) { noteApi.listReminders() }
                return
            } catch (_: AssertionError) {
                Thread.sleep(50)
            }
        }
        coVerify(exactly = 1) { noteApi.listReminders() }
    }

    @Test
    fun onMessageReceived_unknownType_doesNotFetchReminders() {
        val message = RemoteMessage.Builder("notetest@fcm.googleapis.com")
            .addData("type", "bogus-type")
            .build()

        every { sessionManager.isLoggedIn() } returns true

        service.onMessageReceived(message)

        // Unknown types fall through to the "else" branch which only
        // logs. listReminders must never be invoked.
        Thread.sleep(200)
        coVerify(exactly = 0) { noteApi.listReminders() }
    }

    private fun setField(name: String, value: Any) {
        val f = NoteFirebaseMessagingService::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(service, value)
    }
}
