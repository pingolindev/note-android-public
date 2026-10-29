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

package com.pingolin.note.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deleting the Pingolin account deletes the Track circles it owns, for everyone in them. Every delete
 * screen says so, before the button — Note's included.
 */
class DeleteSaysTrackCirclesEndTest {

    @Test
    fun `the delete warning says owned Track circles end, after what Note loses and before the button`() {
        val settings = File("src/main/java/com/pingolin/note/ui/settings/SettingsFragment.kt").readText()
        val warning = settings.substringAfter("private fun showDeleteAccountConfirmation()").substringBefore("\n    private fun ")
        val notes = warning.indexOf("All your notes, notebooks, attachments, shares and reminders will be permanently deleted.")
        val circles = warning.indexOf(
            "\"\\n\\nCircles you own in Pingolin Track are deleted for everyone in them, along with all the \" +\n" +
                "                    \"location history they hold. To keep a circle going, transfer its ownership in Track first.\" +"
        )
        assertTrue("in the warning", circles > 0)
        assertTrue("after what Note loses", notes in 0 until circles)
        assertTrue("before the button", circles < warning.indexOf(".setPositiveButton(\"Delete\")"))
    }
}
