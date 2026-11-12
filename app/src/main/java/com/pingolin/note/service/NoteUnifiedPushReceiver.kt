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
import android.os.Binder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pingolin.note.BuildConfig
import com.pingolin.note.R
import com.pingolin.note.ui.MainActivity
import com.pingolin.note.util.SessionManager
import com.pingolin.note.worker.ReminderTickleWorker
import com.pingolin.note.worker.PushTokenSyncWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush receiver — the non-Google push path. BroadcastReceiver
 * subclasses can't use @AndroidEntryPoint, so dependencies come through a
 * Hilt EntryPoint.
 *
 * Endpoint registration and tickle handling are intentionally a thin copy of
 * NoteFirebaseMessagingService: the server sends the same payload
 * (type="tickle" with the event in `kind`: "reminder" | "share" |
 * "notebook_share" | "sync" | "finish_shares") over both lanes, so keep the two in sync if either
 * changes.
 */
class NoteUnifiedPushReceiver : MessagingReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun sessionManager(): SessionManager
        fun identityRecheck(): com.pingolin.note.domain.repository.IdentityRecheck
    }

    companion object {
        private const val TAG = "NoteUnifiedPush"
        private const val CHANNEL_ID = "note_reminders"
        private const val SHARE_NOTIFICATION_ID = 9_002
        private const val MAX_MESSAGE_SIZE = 64 * 1024

        // Package names allowed to deliver us a push. Mirrors the Track Android
        // app's list of trusted distributors — keep the two lists in step when
        // a distributor is added.
        private val TRUSTED_DISTRIBUTORS: Set<String> = setOfNotNull(
            "io.heckel.ntfy",                        // ntfy
            "io.heckel.ntfy.dev",                    // ntfy (dev)
            "org.unifiedpush.distributor.fcm",       // UP FCM Distributor
            "org.unifiedpush.distributor.nextpush",  // NextPush
            "im.molly.app",                          // Molly (Signal fork)
            // System shell (adb) — DEBUG only, for end-to-end
            // tests. Never in release, where it would let the shell
            // UID inject pushes.
            "com.android.shell".takeIf { BuildConfig.DEBUG },
        )
    }

    /** Tests only: dependencies without a Hilt graph (Robolectric runs a plain Application). */
    @androidx.annotation.VisibleForTesting
    internal var depsForTest: Deps? = null

    private fun deps(context: Context): Deps = depsForTest ?: EntryPointAccessors.fromApplication(
        context.applicationContext, Deps::class.java,
    )

    /**
     * SECURITY: this receiver is EXPORTED (the UnifiedPush spec requires it), so
     * any installed app can broadcast `org.unifiedpush.android.connector.MESSAGE`
     * at it. Without this check a hostile app could forge a "share" tickle to
     * post a fake "New note shared with you" notification, or spam "sync" to
     * drive repeated network wakeups and drain the battery. Verify the caller is
     * a real distributor before acting on anything.
     */
    private fun isCallerTrusted(context: Context): Boolean {
        val callingUid = Binder.getCallingUid()

        // Our own process (tests, and the local-loopback delivery path).
        if (callingUid == android.os.Process.myUid()) return true

        val packages = context.packageManager.getPackagesForUid(callingUid) ?: return false
        for (packageName in packages) {
            if (packageName in TRUSTED_DISTRIBUTORS) return true
            // System apps (UID < 10000) are trusted, EXCEPT the shell UID (2000)
            // in release builds: com.android.shell runs as 2000, and a blanket
            // system-UID allowance would hand adb the injection this check exists
            // to stop. Matches the DEBUG-gating of com.android.shell above.
            if (callingUid < 10000 && (BuildConfig.DEBUG || callingUid != 2000)) return true
        }

        Log.w(TAG, "Rejecting push from untrusted caller: uid=$callingUid, packages=${packages.toList()}")
        return false
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        if (!isCallerTrusted(context)) return
        val url = endpoint.url
        Log.i(TAG, "New UnifiedPush endpoint registered")
        val d = deps(context)
        // Clears push_token_synced: the endpoint is on the device, but the
        // server does not know it yet. Settings reads that flag, so the status
        // line stays honest until the register below actually lands.
        d.sessionManager().savePushSettings(
            token = null,
            type = SessionManager.PUSH_TYPE_UNIFIEDPUSH,
            endpoint = url,
        )
        // Hand the backend register to WorkManager rather than firing
        // it into GlobalScope here. A BroadcastReceiver's process can
        // be killed the moment onNewEndpoint returns, and an inline
        // call has no retry at all: one failed POST (offline, 401
        // mid-refresh, backend blip) would leave push permanently dead
        // while the UI still claimed it was registered.
        if (d.sessionManager().isLoggedIn()) {
            PushTokenSyncWorker.schedule(context)
        } else {
            // Not signed in yet — MainActivity re-schedules after login, and the
            // endpoint is already persisted, so nothing is lost.
            Log.i(TAG, "Endpoint saved; will register with backend after login")
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        if (!isCallerTrusted(context)) return
        Log.w(TAG, "UnifiedPush registration failed: $reason")
    }

    override fun onUnregistered(context: Context, instance: String) {
        if (!isCallerTrusted(context)) return
        Log.i(TAG, "UnifiedPush unregistered")
        val sm = deps(context).sessionManager()
        if (sm.getPushType() == SessionManager.PUSH_TYPE_UNIFIEDPUSH) {
            sm.clearPushSettings()
        }
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        if (!isCallerTrusted(context)) return
        if (message.content.size > MAX_MESSAGE_SIZE) {
            Log.w(TAG, "Rejecting oversized UnifiedPush message: ${message.content.size}B")
            return
        }
        val text = try {
            String(message.content, Charsets.UTF_8)
        } catch (_: Exception) {
            Log.w(TAG, "Non-UTF8 push payload")
            return
        }
        // The dispatcher always sends type="tickle" with the event in `kind`
        // ("reminder" | "share" | "notebook_share" | "sync" | "finish_shares"), so `kind` MUST be
        // branched on first — see [NoteTickleHandler.handle].
        val (type, kind) = try {
            val obj = JSONObject(text)
            obj.optString("type", "") to obj.optString("kind", "").ifEmpty { null }
        } catch (_: Exception) { "" to null }

        val d = deps(context)
        if (!d.sessionManager().isLoggedIn()) {
            Log.d(TAG, "Ignoring push — not logged in")
            return
        }
        // Device-local notification categories (Settings → Notification
        // categories). The server does not gate by category, so these
        // client-side checks are where the "Reminders" / "Shared notes"
        // toggles take effect. The silent "sync" tickle is never gated
        // — it's a data pull, not a user-facing notification.
        val sm = d.sessionManager()
        when (val tag = kind ?: type) {
            "share", ShareNotice.NOTEBOOK_KIND -> if (sm.areShareNotificationsEnabled()) {
                showShareNotification(context, sm, tag)
            }
            "identities" -> {
                // A restore elsewhere. Nothing is read from the
                // push; the re-check is gated per device
                // (IdentityRecheck).
                Log.d(TAG, "Identities tickle — re-checking earlier identities")
                d.identityRecheck().onNudge()
            }
            "sync" -> {
                // Silent pull-now tickle (note changed on another device).
                Log.d(TAG, "Sync tickle — pulling changes")
                com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(context)
            }
            com.pingolin.note.domain.repository.InvitationFinisher.PUSH_KIND -> {
                // Someone the owner shared a note with has signed up: the same
                // silent sync, at whose end the share is finished
                // (InvitationFinisher). Nothing is shown.
                Log.d(TAG, "Finish-shares tickle — syncing to finish them")
                com.pingolin.note.domain.repository.InvitationFinisher.wanted()
                com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(context)
            }
            "reminder", "tickle" -> if (sm.areReminderNotificationsEnabled()) {
                ReminderTickleWorker.schedule(context)
            }
            else -> Log.d(TAG, "Ignoring push: type=$type kind=$kind")
        }
    }

    private fun showShareNotification(context: Context, sm: SessionManager, kind: String?) {
        if (!sm.isLoggedIn()) return
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
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted */ }
    }

    private fun postNotification(context: Context, title: String, body: String, id: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted */ }
    }
}
