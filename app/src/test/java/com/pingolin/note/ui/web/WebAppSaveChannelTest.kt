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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The save channel takes the note origin's main frame only, as the trust channel does: the
 * allowlist injects `pingolinSave` into every frame of the note origin, a frame of it inside
 * another page included.
 */
class WebAppSaveChannelTest {

    private val note = "https://note.pingolindev.com"

    @Test
    fun `only the note origin's main frame is taken`() {
        assertTrue(WebAppSaveChannel.accepts(note, isMainFrame = true, origin = note))
        assertFalse("a subframe", WebAppSaveChannel.accepts(note, isMainFrame = false, origin = note))
        for (other in listOf("https://account.pingolindev.com", "http://note.pingolindev.com", "https://note.pingolindev.com:8443", "https://evil.example", "")) {
            assertFalse(other, WebAppSaveChannel.accepts(other, isMainFrame = true, origin = note))
        }
    }

    @Test
    fun `the listener checks the frame before it reads the payload (read from the source)`() {
        val source = File("src/main/java/com/pingolin/note/ui/web/WebAppSaveChannel.kt").readText()
        val params = source.indexOf("_, message, sourceOrigin, isMainFrame, _ ->")
        val check = source.indexOf("if (!accepts(\"\${sourceOrigin.scheme}://\${sourceOrigin.authority}\", isMainFrame, origin)) {")
        val read = source.indexOf("val payload = message.data ?: return@addWebMessageListener")
        assertTrue(params in 0 until check)
        assertTrue(check < read)
    }
}
