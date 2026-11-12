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

package com.pingolin.note.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The note screen leaves for the native sign-in when its session ends, once.
 *
 * WHY THESE EXIST
 *   A50, 1.0.1069: a session revoked from another device was refused on the next refresh and
 *   cleared, and the screen stayed BLANK. The only watcher of the login state was MainActivity's,
 *   gone once the note screen is the task's root.
 */
class SessionEndSignInTest {

    @Test
    fun `the sign-in is left for once, whoever asks first`() {
        val once = SignInOnce()
        assertTrue("the first asker goes", once.leave())
        repeat(3) { assertFalse("every later one stays: no second sign-in screen", once.leave()) }
        assertTrue("another screen has its own", SignInOnce().leave())
    }

    /** The activity's side, read from the source. */
    @Test
    fun `the note screen watches the login state while started, and every way out goes through one sign-in (read from the source)`() {
        val activity = File("src/main/java/com/pingolin/note/ui/web/WebAppActivity.kt").readText()
        val keyless = activity.indexOf("AuthActivity.REAUTH_KEYS")
        val watch = activity.indexOf(
            "repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {\n" +
                "                sessionManager.isLoggedInFlow.collect { loggedIn ->\n" +
                "                    if (!loggedIn) toNativeSignIn()\n" +
                "                }",
        )
        val coldOpen = activity.indexOf("val mustRefreshFirst = ColdOpenSession.mustRefreshFirst(")
        assertTrue("after the keyless exit, whose clear must not race its own sign-in", keyless in 0 until watch)
        assertTrue("registered in onCreate, before the cold open", watch < coldOpen)
        assertTrue(activity.contains("if (isFinishing || isDestroyed || !signInOnce.leave()) return"))
        // The page's sign-out claims the sign-in before its logout clears the session.
        val claim = activity.indexOf("val first = signInOnce.leave()\n                            logoutOrchestrator.get().logout()\n                            if (first) {")
        assertTrue(claim > 0)
        assertEquals("one sign-in guard, used by both ways out", 2, Regex("""signInOnce\.leave\(\)""").findAll(activity).count())
    }
}
