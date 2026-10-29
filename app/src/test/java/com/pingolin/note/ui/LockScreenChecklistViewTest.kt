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

package com.pingolin.note.ui

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.checkbox.MaterialCheckBox
import com.pingolin.note.R
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LockScreenChecklistViewTest {

    private val context: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.Theme_PingolinNote_LockScreen,
    )

    @Test
    fun `the lock screen layout carries the requested writing inset`() {
        val root = LayoutInflater.from(context).inflate(R.layout.activity_lock_capture, null)
        val expected = (24 * context.resources.displayMetrics.density).roundToInt()
        assertEquals(expected, root.paddingLeft)
        assertEquals(expected, root.paddingRight)
    }

    @Test
    fun `a checklist row renders a real checkbox and editable item`() {
        val row = LayoutInflater.from(context)
            .inflate(R.layout.item_lock_capture_checklist, null)
        assertTrue(row.findViewById<View>(R.id.lockItemChecked) is MaterialCheckBox)
        assertEquals(View.VISIBLE, row.findViewById<View>(R.id.lockItemText).visibility)
        assertEquals(View.VISIBLE, row.findViewById<View>(R.id.lockItemRemove).visibility)
    }
}
