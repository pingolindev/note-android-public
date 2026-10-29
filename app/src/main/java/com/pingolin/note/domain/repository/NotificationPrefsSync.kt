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

import android.util.Log
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.util.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the device-local notification gates in step with the server row.
 *
 * ## Why this has to exist
 *
 * `ReminderReceiver`, `NoteTickleHandler`, `NoteUnifiedPushReceiver` and
 * `NoteFirebaseMessagingService` all gate on the SessionManager copy
 * (`areReminderNotificationsEnabled` / `areShareNotificationsEnabled`), not on
 * the server — they have to, because a fired alarm has no network and a push
 * has already arrived by the time it is read.
 *
 * The categories are changed in the web panel, which writes the SERVER row.
 * Without this sync nothing on the device would learn about the change — so
 * turning reminders off on the web would leave the phone firing local reminder
 * notifications forever.
 *
 * Best-effort by design: offline or a failed request leaves the last known
 * values in place. It never WRITES to the server — the panel that made the
 * change already did.
 */
@Singleton
class NotificationPrefsSync @Inject constructor(
    private val api: NoteApi,
    private val sessionManager: SessionManager,
) {

    /**
     * Pull the row and mirror it locally.
     *
     * @return true when the local copy was refreshed from the server.
     */
    suspend fun refresh(): Boolean {
        return try {
            val res = api.getNotificationPreferences()
            val prefs = res.body()?.data
            if (!res.isSuccessful || prefs == null) {
                Log.i(TAG, "prefs refresh skipped: http=${res.code()}")
                return false
            }
            // Null means "the server did not say", which for a full GET only
            // happens on an older/partial payload — leave those alone rather
            // than defaulting them, or a missing field would silently flip a
            // gate the user set.
            prefs.remindersPush?.let { sessionManager.setReminderNotificationsEnabled(it) }
            prefs.sharedNotesPush?.let { sessionManager.setShareNotificationsEnabled(it) }
            prefs.emailEnabled?.let { sessionManager.setEmailNotificationsEnabled(it) }
            prefs.remindersEmail?.let { sessionManager.setReminderEmailEnabled(it) }
            prefs.sharedNotesEmail?.let { sessionManager.setShareEmailEnabled(it) }
            true
        } catch (e: Exception) {
            // Offline is the common case here and is not worth a warning.
            Log.i(TAG, "prefs refresh failed: ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "NotificationPrefsSync"
    }
}
