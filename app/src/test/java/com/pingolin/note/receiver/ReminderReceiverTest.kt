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

package com.pingolin.note.receiver

import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * Unit tests for the reminder BroadcastReceiver.
 *
 * [ReminderReceiver.onReceive] hands a [android.app.PendingIntent] and a
 * [android.app.NotificationChannel] to the OS via real Android system
 * services. Those services need an Android runtime, so the tests in this
 * class are ignored placeholders that document what an instrumented test
 * would cover.
 */
class ReminderReceiverTest {

    @Before
    fun setup() {
        // All tests in this file are @Ignore — no setup required beyond
        // MockK defaults should they be enabled in an instrumented env.
    }

    @After
    fun teardown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    @Ignore("Needs instrumented test: uses PendingIntent.getActivity + NotificationChannel which require a real Android runtime.")
    fun `onReceive_readsNoteIdAndPostsNotification`() {
        // Intentionally left empty; see class-level docstring for why.
    }

    @Test
    @Ignore("Needs instrumented test: missing noteId extra path requires real Intent plumbing under Robolectric.")
    fun `onReceive_missingNoteIdExtra_returnsEarly`() {
        // Intentionally empty.
    }

    @Test
    @Ignore("Needs instrumented test: notification channel creation requires NotificationManager system service.")
    fun `onReceive_ensuresNotificationChannelExists`() {
        // Intentionally empty.
    }
}
