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

/**
 * The launch's account-id check: a saved account id that isn't a UUID is no account, so the
 * session ends like an expiry before the launch reads whether anyone is signed in. MainActivity
 * needs Hilt to run, so its onCreate is read as source (as the Track Android app's
 * AccountIdLaunchCheckTest does); SessionManagerIdTest covers the check itself.
 */
class AccountIdLaunchCheckTest {

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (root in listOf(File(dir, "app/src/main/java/com/pingolin/note"), File(dir, "src/main/java/com/pingolin/note"))) {
                val file = File(root, relative)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not locate $relative from ${File("").absolutePath}")
    }

    @Test
    fun `a saved account id that isn't a UUID ends the session before the launch's sign-in check`() {
        val text = source("ui/MainActivity.kt")
        val onCreate = text.substring(text.indexOf("override fun onCreate("))
        val check = onCreate.indexOf("if (sessionManager.savedAccountIdInvalid()) sessionManager.clearAuthOnly()")
        val signedIn = onCreate.indexOf("if (!sessionManager.isLoggedIn()) {")
        assertTrue("the check is in onCreate, before the sign-in check", check in 0 until signedIn)
    }
}
