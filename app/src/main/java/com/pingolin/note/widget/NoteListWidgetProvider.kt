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
import android.widget.RemoteViews
import com.pingolin.note.R
import com.pingolin.note.ui.LockScreenCaptureActivity

/**
 * Scrollable note list widget showing recent and pinned notes.
 * Uses RemoteViewsService for the ListView adapter.
 */
class NoteListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_note_list)

            // Set up the RemoteViewsService for the ListView
            val serviceIntent = Intent(context, NoteListRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // Unique data URI to prevent intent reuse
                data = android.net.Uri.parse("pingolin://widget/list/$widgetId")
            }
            views.setRemoteAdapter(R.id.widget_note_list_view, serviceIntent)
            views.setEmptyView(R.id.widget_note_list_view, R.id.widget_empty_view)

            // Click template for list items — opens note in MainActivity
            val clickIntent = Intent(context, com.pingolin.note.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val clickPending = PendingIntent.getActivity(
                context, widgetId, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.widget_note_list_view, clickPending)

            // "New Note" button in header
            val newNoteIntent = Intent(context, LockScreenCaptureActivity::class.java).apply {
                putExtra(LockScreenCaptureActivity.EXTRA_NOTE_TYPE, "text")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val newNotePending = PendingIntent.getActivity(
                context, widgetId * 10 + 2, newNoteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_btn_new_note, newNotePending)

            // Refresh button
            val refreshIntent = Intent(context, NoteListWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
            }
            val refreshPending = PendingIntent.getBroadcast(
                context, widgetId * 10 + 3, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshPending)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
