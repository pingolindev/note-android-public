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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pingolin.note.R
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.ui.MainActivity
import com.pingolin.note.util.SessionManager
import com.pingolin.note.worker.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared "what a tickle does" router + notification renderer.
 *
 * The note server's push dispatcher sends content-free tickles — JSON of the shape
 * `{"type":"tickle","kind":"reminder|share|notebook_share|sync","refId":<id>}` —
 * over whichever push channels the device has registered: FCM or UnifiedPush. This class
 * centralises the downstream:
 *   - "share"             → generic "new share" notification (device-local
 *                           "Shared notes" category gates it); the inbox syncs
 *                           on next open regardless. "notebook_share" is the
 *                           same, in its own words ([ShareNotice]).
 *   - "sync"              → silent immediate [SyncWorker] pull (never gated —
 *                           it's a data pull, not a user-facing notification).
 *   - "reminder"/"tickle" → fetch pending reminders and post a generic
 *                           "Reminder" notification (device-local "Reminders"
 *                           category gates it). No note content is embedded —
 *                           only opaque item ids for the deep link.
 *
 * [ReminderTickleWorker] runs the reminder fetch through here. This is a
 * faithful copy of [NoteFirebaseMessagingService.onMessageReceived] and
 * [NoteUnifiedPushReceiver.onMessage]: those two field-inject their own
 * dependencies (and are pinned by unit/instrumented tests that reflect mocks
 * into their fields), so they keep their inline copies. Keep the three in sync
 * if the tickle contract changes. Notification channel + ids match the other
 * two so a reminder delivered through here replaces (not duplicates) the same
 * tray entry as one delivered via FCM.
 */
@Singleton
class NoteTickleHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val noteApi: NoteApi,
    private val identityRecheck: com.pingolin.note.domain.repository.IdentityRecheck,
) {

    /**
     * Route a decoded tickle. [kind] MUST win over [type] — the dispatcher
     * always sends `type="tickle"` with the real event in `kind`, so branching
     * on `type` alone sends share invites into the reminder fetch, which finds
     * nothing and drops them silently. Bare `type` values are also accepted, for
     * a sender that doesn't set `kind`.
     * Suspends for the reminder fetch; callers launch it on their own scope.
     */
    suspend fun handle(kind: String?, type: String?) {
        when (val tag = kind ?: type) {
            "share", ShareNotice.NOTEBOOK_KIND -> if (sessionManager.areShareNotificationsEnabled()) {
                showShareNotification(tag)
            }
            "sync" -> {
                Log.d(TAG, "Sync tickle — pulling changes")
                SyncWorker.enqueueImmediateSync(context)
            }
            "identities" -> identityRecheck.onNudge()
            "reminder", "tickle" -> if (sessionManager.areReminderNotificationsEnabled()) {
                Log.d(TAG, "Reminder tickle — fetching pending reminders")
                fetchAndShowReminders()
            }
            else -> Log.d(TAG, "Ignoring tickle: type=$type kind=$kind")
        }
    }

    private fun showShareNotification(kind: String?) {
        if (!sessionManager.isLoggedIn()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_INBOX, true)
        }
        val pending = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(ShareNotice.title(kind))
            .setContentText(ShareNotice.text(kind))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.primary))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(SHARE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted.
        }
    }

    private suspend fun fetchAndShowReminders() {
        if (!sessionManager.isLoggedIn()) return
        try {
            val response = noteApi.listReminders()
            if (!response.isSuccessful) {
                Log.w(TAG, "listReminders failed: ${response.code()}")
                return
            }
            val reminders = response.body()?.data.orEmpty()
                .filter { !it.fired && !it.dismissed }
            if (reminders.isEmpty()) return

            val count = reminders.size
            val title = if (count == 1) "Reminder" else "$count reminders"
            // A single pending reminder deep-links straight to its note (the item
            // id is opaque — no E2EE content leaks). With several we can't pick
            // one, so fall back to just opening the app.
            val singleNoteId = reminders.singleOrNull()?.itemId
            val body = if (singleNoteId != null) "Tap to open the note" else "Tap to view your notes"

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (singleNoteId != null) putExtra(MainActivity.EXTRA_OPEN_NOTE_ID, singleNoteId)
            }
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_notification)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.primary))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; silently drop.
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndShowReminders failed", e)
        }
    }

    private companion object {
        const val TAG = "NoteTickle"
        // Match NoteFirebaseMessagingService / NoteUnifiedPushReceiver so a
        // tickle handled here updates the same tray entry rather than
        // stacking a duplicate alongside an FCM/UnifiedPush one.
        const val CHANNEL_ID = "note_reminders"
        const val NOTIFICATION_ID = 9_001
        const val SHARE_NOTIFICATION_ID = 9_002
    }
}
