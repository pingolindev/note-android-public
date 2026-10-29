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

import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.NotificationPreferencesDto
import com.pingolin.note.util.SessionManager
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The gates these mirror are read by [com.pingolin.note.receiver.ReminderReceiver]
 * and the push receivers, which have no network at the moment they fire. The
 * values live in EncryptedSharedPreferences and are not readable off the phone,
 * so these tests stand in for a device check that cannot be made.
 */
class NotificationPrefsSyncTest {

    private val api: NoteApi = mockk()
    private val session: SessionManager = mockk(relaxed = true)
    private val sync = NotificationPrefsSync(api, session)

    private fun ok(dto: NotificationPreferencesDto) =
        Response.success(ApiResponse(success = true, data = dto, error = null))

    @Test
    fun `a full row is mirrored into every local gate`() = runTest {
        coEvery { api.getNotificationPreferences() } returns ok(
            NotificationPreferencesDto(
                remindersPush = false,
                sharedNotesPush = true,
                emailEnabled = true,
                remindersEmail = false,
                sharedNotesEmail = true,
            ),
        )

        assertTrue(sync.refresh())

        verify { session.setReminderNotificationsEnabled(false) }
        verify { session.setShareNotificationsEnabled(true) }
        verify { session.setEmailNotificationsEnabled(true) }
        verify { session.setReminderEmailEnabled(false) }
        verify { session.setShareEmailEnabled(true) }
    }

    @Test
    fun `a field the server omitted leaves that gate alone`() = runTest {
        // Not defaulting a missing field is the point: defaulting would flip a
        // gate the user set, silently, on every refresh.
        coEvery { api.getNotificationPreferences() } returns ok(
            NotificationPreferencesDto(remindersPush = false),
        )

        assertTrue(sync.refresh())

        verify { session.setReminderNotificationsEnabled(false) }
        verify(exactly = 0) { session.setShareNotificationsEnabled(any()) }
        verify(exactly = 0) { session.setEmailNotificationsEnabled(any()) }
    }

    @Test
    fun `an http error changes nothing`() = runTest {
        coEvery { api.getNotificationPreferences() } returns Response.error(
            500,
            "".toResponseBody("application/json".toMediaType()),
        )

        assertFalse(sync.refresh())

        verify(exactly = 0) { session.setReminderNotificationsEnabled(any()) }
        verify(exactly = 0) { session.setShareNotificationsEnabled(any()) }
    }

    @Test
    fun `being offline changes nothing and does not throw`() = runTest {
        coEvery { api.getNotificationPreferences() } throws java.io.IOException("offline")

        assertFalse(sync.refresh())

        verify(exactly = 0) { session.setReminderNotificationsEnabled(any()) }
    }
}
