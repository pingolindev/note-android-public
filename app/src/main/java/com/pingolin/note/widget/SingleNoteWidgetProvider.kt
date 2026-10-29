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

package com.pingolin.note.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.pingolin.note.R
import com.pingolin.note.data.local.NoteDatabase
import com.pingolin.note.util.DatabasePassphraseManager
import com.pingolin.note.util.NotificationDetail
import com.pingolin.note.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single note display widget. Shows the title and body of a user-selected note.
 * Configured via SingleNoteConfigActivity which lets the user pick a note.
 */
class SingleNoteWidgetProvider : AppWidgetProvider() {

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface Deps {
        fun itemDao(): com.pingolin.note.data.local.dao.ItemDao
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // onUpdate is a BroadcastReceiver callback (Main thread). Opening
        // SQLCipher + querying the note here throws (NoteDatabase has no
        // allowMainThreadQueries), so move the DB read + render onto IO and keep
        // this receiver's process alive via goAsync until the render finishes.
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (widgetId in appWidgetIds) {
                    // Per-widget catch: buildViews reads
                    // through the shared SQLCipher
                    // singleton, whose FIRST open in a
                    // process can still throw (corrupt DB,
                    // bad passphrase). Without this the
                    // exception becomes an uncaught
                    // coroutine crash and takes down the
                    // whole batch — mirror BootReceiver /
                    // refreshAllPinned, which swallow + log
                    // too.
                    runCatching {
                        val views = buildViews(appContext, widgetId)
                        appWidgetManager.updateAppWidget(widgetId, views)
                    }.onFailure {
                        android.util.Log.w("SingleNoteWidget", "Widget $widgetId update failed", it)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            WidgetPreferences.removeWidget(context, widgetId)
        }
    }

    companion object {
        /**
         * Refresh a single widget from a non-receiver caller (the config
         * activity's note-pick, which runs on the Main thread). The SQLCipher
         * read happens on IO, so this returns immediately and the widget
         * repaints once the async render lands.
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
        ) {
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                // Swallow + log a corrupt-DB / decrypt failure instead of letting
                // it crash the coroutine (mirrors onUpdate / BootReceiver).
                runCatching {
                    val views = buildViews(appContext, widgetId)
                    appWidgetManager.updateAppWidget(widgetId, views)
                }.onFailure {
                    android.util.Log.w("SingleNoteWidget", "Widget $widgetId update failed", it)
                }
            }
        }

        /**
         * Build the RemoteViews for one widget. Reads the note from SQLCipher,
         * so this MUST run off the Main thread; callers apply the result via
         * [AppWidgetManager.updateAppWidget]. Without a session no note is read:
         * the widget shows the app's name over "Sign in to continue". Tests pass
         * their own [session] and [itemDao].
         */
        internal fun buildViews(
            context: Context,
            widgetId: Int,
            session: SessionManager = SessionManager(context),
            itemDao: () -> com.pingolin.note.data.local.dao.ItemDao = {
                // Hilt singleton via EntryPoint (an AppWidgetProvider is a
                // BroadcastReceiver — same pattern as ReminderReceiver). A
                // per-refresh private DB open would cost the full SQLCipher
                // KDF per SINGLE-NOTE WIDGET per save.
                dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext, Deps::class.java,
                ).itemDao()
            },
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_single_note)
            val noteId = WidgetPreferences.getNoteIdForWidget(context, widgetId)

            if (!session.isLoggedIn()) {
                views.setTextViewText(R.id.widget_single_title, context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_single_body, context.getString(R.string.auth_subtitle))
                views.setInt(R.id.widget_single_root, "setBackgroundColor", 0xFF313244.toInt())
                views.setViewVisibility(R.id.widget_single_pin, View.GONE)
            } else if (noteId == null) {
                views.setTextViewText(R.id.widget_single_title, "Tap to configure")
                views.setTextViewText(R.id.widget_single_body, "Select a note to display")
                views.setInt(R.id.widget_single_root, "setBackgroundColor", 0xFF313244.toInt())
            } else {
                val note = itemDao().getByIdSync(noteId)

                if (note != null) {
                    // Redact like the notifications do. Hilt isn't available in
                    // an AppWidgetProvider, so read the level straight from
                    // SessionManager. A home-screen widget has no lock-screen
                    // VISIBILITY_PRIVATE boundary, so drop all decrypted content
                    // while App Lock is enabled.
                    val level = if (session.isAppLockEnabled()) {
                        SessionManager.NOTIF_DETAIL_GENERIC
                    } else {
                        session.getNotificationDetailLevel()
                    }

                    val title = NotificationDetail.widgetTitle(level, note.title)
                    views.setTextViewText(R.id.widget_single_title, title)

                    val body = NotificationDetail.widgetPreview(
                        level,
                        extractBody(note.decryptedContent),
                    ) ?: ""
                    views.setTextViewText(R.id.widget_single_body, body)

                    // Apply note color as background tint
                    val bgColor = note.color?.let {
                        try {
                            // Use color at ~20% opacity over surface
                            val c = Color.parseColor(it)
                            Color.argb(50, Color.red(c), Color.green(c), Color.blue(c))
                        } catch (_: Exception) { null }
                    } ?: 0xFF313244.toInt()
                    views.setInt(R.id.widget_single_root, "setBackgroundColor", bgColor)

                    views.setViewVisibility(
                        R.id.widget_single_pin,
                        if (note.isPinned) View.VISIBLE else View.GONE,
                    )
                } else {
                    views.setTextViewText(R.id.widget_single_title, "Note deleted")
                    views.setTextViewText(R.id.widget_single_body, "This note no longer exists")
                    views.setInt(R.id.widget_single_root, "setBackgroundColor", 0xFF313244.toInt())
                }
            }

            // Click opens the note in MainActivity
            val clickIntent = Intent(context, com.pingolin.note.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                noteId?.let { putExtra("open_note_id", it) }
            }
            val clickPending = PendingIntent.getActivity(
                context, widgetId, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_single_root, clickPending)

            return views
        }

        private fun extractBody(json: String?): String {
            if (json == null) return ""
            val match = Regex(""""body"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)
            return match?.groupValues?.get(1)
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?: ""
        }
    }
}
