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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The page's "Delete account" row opens the deletion warning itself.
 *
 * It used to open a sheet holding one button that opened the warning: a menu
 * of one item. Each piece below fails quietly when lost. Route the account
 * section back to the sheet and the sheet comes up empty (it has no account
 * group any more). Keep a second copy of the flow in the settings screen and
 * the two drift. Leave the flow on a screen after a Cancel and the row does
 * nothing on its next tap (a second start is refused while one is running).
 */
class DeleteAccountOpensDirectlyTest {

    private fun read(path: String) = File("src/main/java/com/pingolin/note/ui/$path").readText()

    @Test
    fun `the account section starts the flow, before the sheet is considered`() {
        val activity = read("web/WebAppActivity.kt")
        val branch = activity.substringAfter("if (req.url.scheme == SETTINGS_SCHEME) {")
        val flow = branch.indexOf("DeleteAccountFlow.start(supportFragmentManager)")
        val sheet = branch.indexOf("DeviceSettingsSheet.handles(section)")
        assertTrue("the account section starts DeleteAccountFlow", flow > 0)
        assertTrue("before the sheet check", flow < sheet)
        assertTrue(
            "keyed on the account section",
            branch.substring(0, flow).contains("if (section == WebAppDeviceState.SECTION_ACCOUNT) {"),
        )
    }

    @Test
    fun `the sheet no longer offers an account section`() {
        val sheet = read("dialogs/DeviceSettingsSheet.kt")
        assertFalse(sheet.contains("SECTION_ACCOUNT"))
    }

    @Test
    fun `the settings screen starts the same flow and keeps no copy of it`() {
        val settings = read("settings/SettingsFragment.kt")
        assertTrue(settings.contains("DeleteAccountFlow.start(requireActivity().supportFragmentManager)"))
        for (gone in listOf("showDeleteAccountConfirmation", "showPasswordDialogForDeletion", "deletionState", "onAccountDeleted")) {
            assertFalse("$gone is in DeleteAccountFlow only", settings.contains(gone))
        }
    }

    @Test
    fun `every way out without a deletion ends the flow`() {
        val flow = read("settings/DeleteAccountFlow.kt")
        val negatives = Regex("""\.setNegativeButton\("[^"]+"\) \{ _, _ -> end\(\) \}""").findAll(flow).count()
        val anyNegative = Regex("""\.setNegativeButton\(""").findAll(flow).count()
        val cancels = Regex("""\.setOnCancelListener \{ end\(\) \}""").findAll(flow).count()
        // The warning, the password and the credit question: three dialogs.
        assertEquals("every Cancel-type button ends the flow", anyNegative, negatives)
        assertEquals(3, negatives)
        assertEquals("every cancellable dialog ends the flow when dismissed", 3, cancels)
        val failed = flow.substringAfter("is AccountDeletionState.Failed -> {").substringBefore("}")
        assertTrue("a failure ends the flow", failed.contains("end()"))
    }

    @Test
    fun `a second tap while it runs does not stack a second flow`() {
        val flow = read("settings/DeleteAccountFlow.kt")
        assertTrue(flow.contains("if (fm.findFragmentByTag(TAG) != null) return"))
    }
}
