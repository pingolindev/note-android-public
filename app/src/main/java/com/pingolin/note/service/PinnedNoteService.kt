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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pingolin.note.R
import com.pingolin.note.data.local.entity.ItemEntity
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
 * Manages pinned note notifications.
 *
 * When a user pins a note it appears as a persistent notification in the
 * shade. The notification carries a decrypted title + preview, so it's posted
 * VISIBILITY_PRIVATE (and its channel's lock-screen visibility is PRIVATE
 * too): the lock screen shows a redacted placeholder until the device is
 * unlocked, keeping the content behind the keyguard / biometric App Lock.
 *
 * Tapping a pinned notification opens the note in the editor.
 * Users can unpin from the notification action button.
 */
object PinnedNoteService {

    /** Notification channel for pinned-note notifications. Created in
     *  NoteApplication.createNotificationChannels(); its lock-screen
     *  visibility is PRIVATE. */
    const val CHANNEL_PINNED_NOTES = "pinned_notes"

    /** Pinned-note notification IDs start at this offset (kept clear of the
     *  reminder/sync id space). */
    const val PINNED_NOTE_ID_OFFSET = 1000

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun sessionManager(): SessionManager
        fun itemDao(): com.pingolin.note.data.local.dao.ItemDao
    }

    fun pinNote(context: Context, note: ItemEntity) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_note_id", note.id)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            note.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val unpinIntent = Intent(context, PinnedNoteActionReceiver::class.java).apply {
            action = ACTION_UNPIN
            putExtra(EXTRA_NOTE_ID, note.id)
        }
        val unpinPendingIntent = PendingIntent.getBroadcast(
            context,
            note.id.hashCode() + 10000,
            unpinIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Pinning is a deliberate "put this on my shade" action, so full
        // detail is the default — but the notification-detail level still
        // applies: "titles" keeps the preview body off the lock screen,
        // "generic" shows no decrypted content at all. And when App Lock is
        // enabled, force GENERIC: the shade notification sits OUTSIDE the
        // in-app biometric/PIN gate, so it must be redacted just like the
        // widget (SingleNoteWidgetProvider) does. VISIBILITY_PRIVATE below
        // only redacts the LOCK screen — it does nothing once the phone is
        // unlocked but the app itself is still App-Lock-locked.
        val session = EntryPointAccessors.fromApplication(
            context.applicationContext, Deps::class.java,
        ).sessionManager()
        val detailLevel = if (session.isAppLockEnabled()) {
            SessionManager.NOTIF_DETAIL_GENERIC
        } else {
            session.getNotificationDetailLevel()
        }
        val title = NotificationDetail.pinnedTitle(detailLevel, note.title)
        val preview = NotificationDetail.pinnedPreview(
            detailLevel,
            note.decryptedContent?.let { extractBody(it) }?.take(100),
        ) ?: ""

        val notification = NotificationCompat.Builder(context, CHANNEL_PINNED_NOTES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.primary))
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            // Decrypted title/preview must not surface on the lock screen —
            // redact until unlock (App Lock boundary). The channel is PRIVATE
            // too so this can't be overridden back to public.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(R.drawable.ic_unpin, "Unpin", unpinPendingIntent)
            .setSilent(true)
            .setGroup(GROUP_PINNED)
            .build()

        val notificationId = PINNED_NOTE_ID_OFFSET + note.id.hashCode().and(0x7FFFFFFF) % 9000
        nm.notify(notificationId, notification)
    }

    fun unpinNote(context: Context, noteId: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val notificationId = PINNED_NOTE_ID_OFFSET + noteId.hashCode().and(0x7FFFFFFF) % 9000
        nm.cancel(notificationId)
    }

    /**
     * Re-render every currently-pinned note's notification at the CURRENT
     * notification-detail + App Lock redaction level. Call this when the user
     * changes those settings so a stale-plaintext pinned notification doesn't
     * linger in the shade. Callable outside the Hilt graph (the DAO comes
     * from the Hilt singleton through an EntryPoint); runs on an IO coroutine
     * — mirrors BootReceiver's pinned-note restore.
     */
    fun refreshAllPinned(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Hilt singleton via the EntryPoint this file already uses for
                // SessionManager, not a database opened here: a private
                // open+close would pay the full SQLCipher KDF per refresh — and
                // a close() on the SHARED singleton would be worse: Room's
                // close is a one-way latch that stops the invalidation tracker
                // for the whole process, so no site may ever close the injected
                // DB.
                EntryPointAccessors.fromApplication(appContext, Deps::class.java)
                    .itemDao()
                    .getAllActiveNotes()
                    .filter { it.isPinned }
                    .forEach { pinNote(appContext, it) }
            } catch (e: Exception) {
                android.util.Log.w("PinnedNoteService", "Pinned-note refresh failed", e)
            }
        }
    }

    private fun extractBody(json: String): String? {
        val match = Regex(""""body"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)
        return match?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }

    const val ACTION_UNPIN = "com.pingolin.note.ACTION_UNPIN"
    const val EXTRA_NOTE_ID = "note_id"
    const val GROUP_PINNED = "pinned_notes"
}
