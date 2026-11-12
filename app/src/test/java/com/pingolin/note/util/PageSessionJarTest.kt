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

package com.pingolin.note.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.concurrent.thread

/**
 * The page's jar for the one refresher ([PageSessionJar]), from process start.
 *
 * WHY THESE EXIST
 *   Review of 8e7739c: the reader existed only once the note screen had opened, so the sync queued
 *   at process start, a periodic one in a process that never shows the page, or one behind the app
 *   lock refreshed with no jar at all, sending the stored refresh token while the page held a later
 *   pair of the session.
 */
class PageSessionJarTest {

    private val pair = PageSessionJar.Held("access", "refresh")

    @Test(timeout = 10_000)
    fun `a read waits for an install that is on its way`() {
        val slot = PageSessionJar.Slot()
        slot.expectInstall()
        thread {
            Thread.sleep(150)
            slot.install(read = { pair })
        }
        val started = System.nanoTime()
        assertEquals(pair, slot.read(5_000))
        assertTrue("it waited for the install", (System.nanoTime() - started) / 1_000_000 >= 100)
    }

    @Test(timeout = 10_000)
    fun `no wait when no install is coming, or once the jar is found unavailable, and a bounded one otherwise`() {
        val nothingExpected = PageSessionJar.Slot()
        var started = System.nanoTime()
        assertNull(nothingExpected.read(5_000))
        assertTrue("no wait at all", (System.nanoTime() - started) / 1_000_000 < 1_000)

        val unavailable = PageSessionJar.Slot()
        unavailable.expectInstall()
        unavailable.unavailable()
        started = System.nanoTime()
        assertNull(unavailable.read(5_000))
        assertTrue("no wait at all", (System.nanoTime() - started) / 1_000_000 < 1_000)

        val neverCame = PageSessionJar.Slot()
        neverCame.expectInstall()
        started = System.nanoTime()
        assertNull(neverCame.read(300))
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue("bounded: $waited ms", waited in 250..2_000)
    }

    @Test
    fun `a refreshed pair is written through the installed writer, and nowhere without one`() {
        val written = mutableListOf<Pair<String, String>>()
        val slot = PageSessionJar.Slot()
        slot.write("a", "r")
        slot.install({ pair }, { access, refresh -> written += access to refresh })
        slot.write("a2", "r2")
        assertEquals(listOf("a2" to "r2"), written)
        slot.install({ pair }, { _, _ -> throw IllegalStateException("cookie manager gone") })
        slot.write("a3", "r3")
    }

    @Test
    fun `a reader that throws is no jar`() {
        val slot = PageSessionJar.Slot()
        slot.install(read = { throw IllegalStateException("cookie manager gone") })
        assertNull(slot.read(0))
    }

    /** Both installs, read from the source: at process start, posted to main before the first
     *  sync is queued, and when the note screen opens; a WebView that can't start is no jar. */
    @Test
    fun `the jar is installed from process start, posted to main, before the first sync (read from the source)`() {
        val app = File("src/main/java/com/pingolin/note/NoteApplication.kt").readText()
        val expect = app.indexOf("com.pingolin.note.util.PageSessionJar.expectInstall()")
        val post = app.indexOf(
            "android.os.Handler(android.os.Looper.getMainLooper()).post {\n" +
                "            com.pingolin.note.ui.web.WebAppActivity.installPageSessionJar()\n" +
                "        }",
        )
        val sync = app.indexOf("connectivityMonitor.start()")
        assertTrue(expect in 0 until post)
        assertTrue(post < sync)
        val activity = File("src/main/java/com/pingolin/note/ui/web/WebAppActivity.kt").readText()
        assertTrue(activity.contains("internal fun installPageSessionJar() {"))
        // The refreshed pair goes in as the shell seeds it: the same cookies, the same domain.
        assertTrue(activity.contains("write = { access, refresh -> writeJarPair(access, refresh) },"))
        assertTrue(activity.contains("CookieWriter.write(base, listOf(authCookieHeader(access, domain), refreshCookieHeader(refresh, domain)), 1_000L)"))
        assertTrue(activity.contains("com.pingolin.note.util.PageSessionJar.unavailable()\n                return"))
        assertTrue(activity.contains("        installPageSessionJar()\n        val staleToken = sessionManager.getAccessToken()"))
    }
}
