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
import android.os.Build
import android.service.quicksettings.TileService
import com.pingolin.note.ui.LockScreenCaptureActivity

/**
 * Quick Settings tile for instant note capture.
 *
 * Accessible from the notification shade (swipe down twice).
 * Works from the lock screen — tapping opens LockScreenCaptureActivity,
 * which routes to the full editor when unlocked or a create-only editor
 * over the keyguard when locked.
 *
 * The tile shows a note icon and "Quick Note" label.
 */
class QuickCaptureTileService : TileService() {

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, LockScreenCaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // On API 34+ the Intent overload of startActivityAndCollapse
            // is deprecated and THROWS UnsupportedOperationException, so
            // the tile would silently do nothing. The PendingIntent
            // overload (added in API 34) is the supported path.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
