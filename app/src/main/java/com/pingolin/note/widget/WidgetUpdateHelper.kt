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

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.pingolin.note.R

/**
 * Utility to refresh all note widgets after data changes.
 * Call this from NoteRepository, SyncRepository,
 * LockScreenCaptureActivity, and ImportRepository.
 */
object WidgetUpdateHelper {

    fun notifyDataChanged(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return

        // Refresh note list widgets
        val listIds = mgr.getAppWidgetIds(
            ComponentName(context, NoteListWidgetProvider::class.java)
        )
        for (id in listIds) {
            mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_note_list_view)
        }

        // Refresh single note widgets
        val singleIds = mgr.getAppWidgetIds(
            ComponentName(context, SingleNoteWidgetProvider::class.java)
        )
        if (singleIds.isNotEmpty()) {
            val intent = Intent(context, SingleNoteWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, singleIds)
            }
            context.sendBroadcast(intent)
        }
    }
}
