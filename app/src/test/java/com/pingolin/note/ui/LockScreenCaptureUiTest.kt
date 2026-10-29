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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LockScreenCaptureUiTest {

    private val root = File("src/main")
    private val activity = root.resolve("java/com/pingolin/note/ui/LockScreenCaptureActivity.kt").readText()
    private val layout = root.resolve("res/layout/activity_lock_capture.xml").readText()
    private val itemLayout = root.resolve("res/layout/item_lock_capture_checklist.xml").readText()
    private val strings = root.resolve("res/values/strings.xml").readText()

    @Test
    fun `the lock-screen writing surface keeps a clear inset`() {
        assertTrue(layout.contains("android:padding=\"24dp\""))
    }

    @Test
    fun `checklist capture is visibly different from a text note`() {
        assertTrue(layout.contains("android:id=\"@+id/lockModeLabel\""))
        assertTrue(activity.contains("R.string.lock_capture_mode_checklist"))
        assertTrue(activity.contains("R.string.lock_capture_checklist_title_hint"))
        assertTrue(layout.contains("android:id=\"@+id/lockChecklistSection\""))
        assertTrue(itemLayout.contains("android:id=\"@+id/lockItemChecked\""))
        assertTrue(activity.contains("check = root.findViewById<CheckBox>(R.id.lockItemChecked)"))
        assertTrue(activity.contains("checked = row.checked.isChecked"))
        assertTrue(strings.contains("<string name=\"lock_capture_mode_checklist\">NEW CHECKLIST</string>"))
        assertTrue(strings.contains("<string name=\"lock_capture_checklist_item_hint\">List item</string>"))
    }

    @Test
    fun `the capture surface uses the polished editor card and button hierarchy`() {
        assertTrue(layout.contains("android:background=\"@drawable/bg_lock_capture_card\""))
        assertTrue(layout.contains("android:id=\"@+id/lockSave\""))
        assertTrue(layout.contains("app:icon=\"@drawable/ic_add\""))
        assertTrue(layout.contains("android:text=\"@string/lock_capture_add_item\""))
        assertTrue(layout.contains("app:backgroundTint=\"#FFA08AFF\""))
        assertTrue(itemLayout.contains("android:background=\"@drawable/bg_lock_capture_checklist_row\""))
        assertTrue(itemLayout.contains("app:tint=\"#FF8C8FA3\""))
        assertTrue(!itemLayout.contains("android:tint="))
        assertTrue(strings.contains("<string name=\"lock_capture_add_item\">Add item</string>"))
    }
}
