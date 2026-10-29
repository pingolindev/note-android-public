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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pingolin.note.R
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.ui.MainActivity
import com.pingolin.note.util.NotificationDetail
import com.pingolin.note.util.SessionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that fires when a note reminder alarm triggers.
 * Shows a local notification linking to the note.
 */
class ReminderReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun sessionManager(): SessionManager
        fun itemDao(): ItemDao
        fun reminderScheduler(): com.pingolin.note.domain.repository.ReminderScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext, Deps::class.java,
        )
        val sessionManager = deps.sessionManager()
        // A scheduled reminder alarm outlives logout / account switch,
        // so no title is carried in the alarm extras (it could show
        // another account's decrypted title). Require an active
        // session AND re-resolve the note from Room — which is wiped
        // on logout and repopulated per-account on switch — at fire
        // time. No session → skip.
        if (!sessionManager.isLoggedIn()) return

        // Device-local "Reminders" notification category (Settings →
        // Notification categories). Off → don't surface reminder
        // notifications on this device. The server does not gate by
        // category, so this client-side check is where the toggle
        // takes effect.
        if (!sessionManager.areReminderNotificationsEnabled()) return

        val itemDao = deps.itemDao()
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // The alarm has gone off: record that BEFORE any of the posting
                // guards below can bail out. ReminderScheduler.refresh() re-arms
                // everything not locally fired on every sync, and a delivered
                // (or deliberately suppressed) reminder that stayed unfired
                // would re-fire, in the past, on every one of them.
                runCatching { deps.reminderScheduler().markFired(noteId) }
                // Absent row = wiped (logged out) or a DIFFERENT account (item
                // ids are unique per note, so account B never holds account A's
                // note) → don't post at all. Present = title resolved for the
                // CURRENT account, exactly like PinnedNoteService renders
                // note.title (a null/blank title falls back to generic copy in
                // NotificationDetail.reminderBody).
                val note = runCatching { itemDao.getByIdSync(noteId) }.getOrNull() ?: return@launch
                // Notification-detail redaction: the note title is decrypted
                // content, and at the "generic" level it must not reach the
                // tray / lock screen. When App Lock is enabled, force GENERIC —
                // the tray notification sits OUTSIDE the in-app biometric/PIN
                // gate, so it must be redacted just like PinnedNoteService does.
                val detailLevel = if (sessionManager.isAppLockEnabled()) {
                    SessionManager.NOTIF_DETAIL_GENERIC
                } else {
                    sessionManager.getNotificationDetailLevel()
                }
                val body = NotificationDetail.reminderBody(detailLevel, note.title)
                postReminderNotification(appContext, noteId, body)
            } finally {
                pending.finish()
            }
        }
    }

    private fun postReminderNotification(context: Context, noteId: String, body: String) {
        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            // Deep-link to the note via the EXTRA_OPEN_NOTE_ID key that
            // MainActivity consumes.
            putExtra(MainActivity.EXTRA_OPEN_NOTE_ID, noteId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, noteId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            // The body can carry a decrypted note title — redact it on the lock
            // screen. Per-notification visibility takes effect on existing
            // installs (the channel is immutable once created).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(noteId.hashCode(), notification)
        }
    }

    /**
     * Create `note_reminders` only if the app-start pass has not.
     *
     * [com.pingolin.note.NoteApplication.createNotificationChannels] holds the
     * canonical definition of this id. A channel's name is mutable through
     * `createNotificationChannel`, so re-creating it here unconditionally under
     * another name would flip the label the user sees in Android's notification
     * settings depending on which ran last; and `enableVibration`, which is
     * immutable after creation, would be lost whenever this one won the race.
     * This is purely a safety net for a receiver that fires before
     * [NoteApplication.onCreate] has run.
     */
    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_reminders_description)
                enableVibration(true)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "note_reminders"
        const val EXTRA_NOTE_ID = "note_id"
    }
}
