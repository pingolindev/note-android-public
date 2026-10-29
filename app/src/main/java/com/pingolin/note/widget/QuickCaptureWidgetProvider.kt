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
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.pingolin.note.R
import com.pingolin.note.ui.LockScreenCaptureActivity
import com.pingolin.note.ui.MainActivity

/**
 * Quick Capture widget. Capture actions (New note / Checklist) launch
 * [LockScreenCaptureActivity] (which shows over — and saves from — the lock
 * screen); "All notes" opens the app (requires unlock). It renders NO note
 * content, so it's safe to surface on the Android 16 lock screen (unlike the
 * content widgets, which opt out via the res/xml-v36 provider-info overrides).
 *
 * Responsive: a compact icon bar when short (home screen) and a rich card
 * (widget_quick_capture_large — brand header + primary/secondary action
 * tiles) when tall (lock-screen cell or a resized home placement). On API 31+
 * both are supplied as a size map so the host picks per actual size; older
 * versions choose by the reported min height.
 */
class QuickCaptureWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (widgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context, widgetId))
        }
    }

    /** Re-render on resize so the pre-S size pick stays correct. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, appWidgetId))
    }

    private fun buildRemoteViews(context: Context, widgetId: Int): RemoteViews {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The host renders the largest mapping that fits the current size.
            return RemoteViews(
                mapOf(
                    SizeF(160f, 40f) to compactViews(context, widgetId),
                    // 150dp, NOT ~100: One UI reports a SINGLE home row as
                    // ~105dp (appWidgetMinHeight=105), so a 100dp threshold
                    // would hand a 3x1 placement the tall layout, whose
                    // weighted action rows then collapse to ZERO height — real,
                    // clickable, invisible buttons: the widget looks fine and
                    // taps do nothing.
                    SizeF(160f, 150f) to largeViews(context, widgetId),
                )
            )
        }
        // Pre-S: choose by the host-reported minimum height.
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        // 180, NOT 100 — same One UI trap as the S+ mapping above: a single
        // Samsung row reports ~105dp, and picking the tall layout for it
        // collapses the buttons to zero height. Two rows report well above 180.
        return if (minHeightDp >= 180) largeViews(context, widgetId) else compactViews(context, widgetId)
    }

    private fun compactViews(context: Context, widgetId: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_quick_capture).also {
            wireActions(context, widgetId, it)
        }

    private fun largeViews(context: Context, widgetId: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_quick_capture_large).also {
            wireActions(context, widgetId, it)
        }

    private fun wireActions(context: Context, widgetId: Int, views: RemoteViews) {
        views.setOnClickPendingIntent(R.id.btn_new_note, capturePendingIntent(context, widgetId, "text", 0))
        views.setOnClickPendingIntent(R.id.btn_new_checklist, capturePendingIntent(context, widgetId, "checklist", 1))
        // "All notes" is only present in the rich (tall/lock-screen) layout.
        // setOnClickPendingIntent on a missing id is a harmless no-op for the
        // compact bar. Opens the app — which requires unlock — so no content
        // is ever exposed on the keyguard.
        views.setOnClickPendingIntent(R.id.btn_all_notes, openAppPendingIntent(context, widgetId))
    }

    private fun openAppPendingIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, widgetId * 10 + 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun capturePendingIntent(
        context: Context,
        widgetId: Int,
        noteType: String,
        slot: Int,
    ): PendingIntent {
        val intent = Intent(context, LockScreenCaptureActivity::class.java).apply {
            putExtra(LockScreenCaptureActivity.EXTRA_NOTE_TYPE, noteType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, widgetId * 10 + slot, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
