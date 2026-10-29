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

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.pingolin.note.R
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.util.NotificationDetail
import com.pingolin.note.util.SessionManager

/**
 * Provides note data to the NoteListWidgetProvider's ListView.
 *
 * Runs on a binder thread, so uses synchronous (blocking) DAO methods.
 * The DAO is the Hilt singleton, handed in by
 * NoteListRemoteViewsService (an @AndroidEntryPoint).
 */
class NoteListRemoteViewsFactory(
    private val context: Context,
    // The Hilt singleton DAO, passed in by the @AndroidEntryPoint
    // service. A NoteDatabase built per refresh would pay SQLCipher's
    // full 256,000-round KDF (~509ms on device) on a binder thread, once
    // per widget refresh — and notifyDataChanged fires one on every note
    // save.
    private val itemDao: com.pingolin.note.data.local.dao.ItemDao,
    /** The session, read at each refresh. Tests pass their own. */
    private val session: () -> SessionManager = { SessionManager(context) },
) : RemoteViewsService.RemoteViewsFactory {

    private var notes: List<ItemEntity> = emptyList()

    /** Whether there was a session at the last refresh. Without one no note is read, and
     * the list is one line: the app's name over "Sign in to continue". */
    private var signedIn = false

    /** Effective notification-detail redaction level for the rows, resolved
     *  in [onDataSetChanged]. Forced to generic while App Lock is on. */
    private var redactionLevel: String = SessionManager.NOTIF_DETAIL_FULL

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val session = session()
        signedIn = session.isLoggedIn()
        notes = if (signedIn) itemDao.getRecentNotesSync(10) else emptyList()
        // Redact the rows like the notifications do. The factory is not
        // Hilt-injected, so read the level straight from SessionManager. A
        // home-screen widget has no lock-screen VISIBILITY_PRIVATE boundary, so
        // drop all decrypted content while App Lock is enabled.
        redactionLevel = if (session.isAppLockEnabled()) {
            SessionManager.NOTIF_DETAIL_GENERIC
        } else {
            session.getNotificationDetailLevel()
        }
    }

    override fun onDestroy() {
        notes = emptyList()
    }

    override fun getCount(): Int = if (signedIn) notes.size else 1

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_note_list_item)

        if (!signedIn) return signInRow(views)
        if (position >= notes.size) return views

        val note = notes[position]

        // Title (redacted per the notification-detail level + App Lock gate)
        val title = NotificationDetail.widgetTitle(redactionLevel, note.title)
        views.setTextViewText(R.id.widget_item_title, title)

        // Body preview (extract from decryptedContent JSON), redacted the same way
        val body = NotificationDetail.widgetPreview(
            redactionLevel,
            extractBody(note.decryptedContent).take(80),
        ) ?: ""
        views.setTextViewText(R.id.widget_item_body, body)

        // Color indicator
        val color = note.color
        if (color != null) {
            try {
                views.setInt(R.id.widget_item_color, "setBackgroundColor", Color.parseColor(color))
            } catch (_: Exception) {
                views.setInt(R.id.widget_item_color, "setBackgroundColor", Color.TRANSPARENT)
            }
            views.setViewVisibility(R.id.widget_item_color, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_item_color, android.view.View.GONE)
        }

        // Pin indicator
        views.setViewVisibility(
            R.id.widget_item_pin,
            if (note.isPinned) android.view.View.VISIBLE else android.view.View.GONE,
        )

        // Fill-in intent for click handling
        val fillIntent = Intent().apply {
            putExtra("open_note_id", note.id)
        }
        views.setOnClickFillInIntent(R.id.widget_item_root, fillIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long =
        if (!signedIn) SIGN_IN_ROW_ID else notes.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true

    /** The one line while signed out: the app's name over "Sign in to continue". A tap opens the
     *  app, which asks to sign in. */
    private fun signInRow(views: RemoteViews): RemoteViews {
        views.setTextViewText(R.id.widget_item_title, context.getString(R.string.app_name))
        views.setTextViewText(R.id.widget_item_body, context.getString(R.string.auth_subtitle))
        views.setViewVisibility(R.id.widget_item_color, android.view.View.GONE)
        views.setViewVisibility(R.id.widget_item_pin, android.view.View.GONE)
        views.setOnClickFillInIntent(R.id.widget_item_root, Intent())
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

    private companion object {
        /** The sign-in line's stable id: outside the Int range a note row's id comes from. */
        const val SIGN_IN_ROW_ID = Long.MIN_VALUE
    }
}
