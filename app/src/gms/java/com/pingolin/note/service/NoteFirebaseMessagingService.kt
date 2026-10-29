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
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pingolin.note.R
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.RegisterFcmRequest
import com.pingolin.note.ui.MainActivity
import com.pingolin.note.util.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service for note reminder pushes.
 *
 * GMS FLAVOUR ONLY — this class and its `<service>` declaration
 * (`src/gms/AndroidManifest.xml`) are compiled into gms variants and are absent
 * from a libre APK entirely, which is what keeps `com.google.firebase` off that
 * variant's classpath. A libre build receives the identical tickles over
 * UnifiedPush ([NoteUnifiedPushReceiver]), which is in src/main.
 *
 * Tickle-only model: the server never embeds reminder content in the push
 * payload (server is E2EE-blind on note content). On receiving a tickle,
 * this service fetches the list of pending reminders via the authenticated
 * API and posts a generic "Reminder" notification that opens the app.
 * Reminder titles/bodies are resolved inside the app after tap, using the
 * vault key that isn't available in this service context.
 */
@AndroidEntryPoint
class NoteFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var noteApi: NoteApi

    @Inject
    lateinit var identityRecheck: com.pingolin.note.domain.repository.IdentityRecheck

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received")
        sessionManager.savePushToken(token)
        sessionManager.setPushTokenSynced(false)
        if (sessionManager.isLoggedIn()) {
            serviceScope.launch { syncTokenWithBackend(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // The dispatcher always sends type="tickle" and carries the event
        // in `kind` ("reminder" | "share" | "notebook_share" | "sync"), so `kind`
        // MUST be branched on first — see [NoteTickleHandler.handle].
        val type = message.data[KEY_TYPE]
        val kind = message.data[KEY_KIND]
        when (val tag = kind ?: type) {
            "share", ShareNotice.NOTEBOOK_KIND -> {
                // A note (or a notebook) has just been shared with this user. The payload
                // itself carries nothing encrypted — we just surface a
                // generic "New share" notification and let the app pull
                // the inbox on next open. The sync loop will pick up
                // the new content key and decrypt the item.
                Log.d(TAG, "Share-invite push received")
                // Device-local "Shared notes" category (Settings → Notification
                // categories). Off → skip the local notification; the inbox
                // still syncs, we just don't tap the tray.
                if (sessionManager.areShareNotificationsEnabled()) {
                    serviceScope.launch { showShareNotification(tag) }
                }
            }
            "identities" -> {
                // A restore was made elsewhere. The push
                // carries nothing; the re-check is the normal
                // signed-in fetch, at most once per window
                // (IdentityRecheck).
                Log.d(TAG, "Identities tickle — re-checking earlier identities")
                identityRecheck.onNudge()
            }
            "sync" -> {
                // Silent "your notes changed elsewhere" tickle — pull now so a
                // web edit appears in seconds instead of on the next periodic
                // sync. No notification; data-only messages reach us even in
                // the background.
                Log.d(TAG, "Sync tickle — pulling changes")
                com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(applicationContext)
            }
            "reminder", "tickle" -> {
                // Device-local "Reminders" category (Settings → Notification
                // categories). Off → don't fetch/post on this device.
                if (sessionManager.areReminderNotificationsEnabled()) {
                    Log.d(TAG, "Tickle received — fetching pending reminders")
                    serviceScope.launch { fetchAndShowNotifications() }
                }
            }
            else -> Log.d(TAG, "Ignoring push: type=$type kind=$kind")
        }
    }

    private suspend fun syncTokenWithBackend(token: String) {
        try {
            val response = noteApi.registerFcmDevice(
                RegisterFcmRequest(
                    deviceId = sessionManager.getOrCreateDeviceId(),
                    fcmToken = token,
                    platform = "android",
                    appVersion = BuildConfigSafe.versionName(this),
                )
            )
            if (response.isSuccessful) {
                sessionManager.setPushTokenSynced(true)
                Log.d(TAG, "FCM token registered with the server")
            } else {
                Log.w(TAG, "FCM register failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FCM register exception", e)
            // The next app start retries:
            // PushRegistrar.ensure() registers again.
        }
    }

    private fun showShareNotification(kind: String?) {
        if (!sessionManager.isLoggedIn()) return
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_INBOX, true)
        }
        val pending = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(ShareNotice.title(kind))
            .setContentText(ShareNotice.text(kind))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(SHARE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted.
        }
    }

    private suspend fun fetchAndShowNotifications() {
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
            // A single pending reminder deep-links straight to its note (the
            // item id is opaque — no E2EE content leaks). With several we
            // can't pick one, so fall back to just opening the app.
            val singleNoteId = reminders.singleOrNull()?.itemId
            val body = if (singleNoteId != null) "Tap to open the note" else "Tap to view your notes"

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (singleNoteId != null) putExtra(MainActivity.EXTRA_OPEN_NOTE_ID, singleNoteId)
            }
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()

            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; silently drop.
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndShowNotifications failed", e)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NoteFCM"
        private const val KEY_TYPE = "type"
        private const val KEY_KIND = "kind"
        private const val CHANNEL_ID = "note_reminders"
        private const val NOTIFICATION_ID = 9_001
        private const val SHARE_NOTIFICATION_ID = 9_002
    }
}

/** versionName via PackageManager, null on any failure — a push must never be
 *  dropped because the version lookup threw. */
private object BuildConfigSafe {
    fun versionName(ctx: android.content.Context): String? = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (_: Exception) { null }
}
