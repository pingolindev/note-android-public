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

/**
 * Stores widget-to-note mappings for single note widgets.
 * Uses plain SharedPreferences (only stores note IDs, no sensitive content).
 */
object WidgetPreferences {

    private const val PREFS_NAME = "pingolin_note_widgets"
    private const val KEY_PREFIX = "widget_note_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveNoteIdForWidget(context: Context, appWidgetId: Int, noteId: String) {
        prefs(context).edit().putString("$KEY_PREFIX$appWidgetId", noteId).apply()
    }

    fun getNoteIdForWidget(context: Context, appWidgetId: Int): String? =
        prefs(context).getString("$KEY_PREFIX$appWidgetId", null)

    fun removeWidget(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove("$KEY_PREFIX$appWidgetId").apply()
    }

    /**
     * Drop every widget→note mapping. Called from [LocalDataWiper] on
     * logout / account switch so a home-screen widget can't stay bound to
     * the prior account's note id.
     */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
