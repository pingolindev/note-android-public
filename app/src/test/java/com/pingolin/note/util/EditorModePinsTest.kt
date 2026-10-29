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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pin/un-pin rule, which is the whole of this feature that can be wrong
 * quietly. Mirrors the Note web editor's editor-mode override tests case for
 * case — the map is ONE blob shared by three clients, so a disagreement about
 * what a key means is a user watching a note change mode when they open it
 * elsewhere.
 *
 * Plain JUnit, no Robolectric: `org.json` is on the unit-test classpath via the
 * `org.json:json` dependency, and nothing here needs a framework.
 */
class EditorModePinsTest {

    private val a = "note-a"
    private val b = "note-b"

    // --- resolve ---

    @Test fun `falls back to the global when the note has no pin`() {
        assertEquals("preview", EditorModePins.resolve(emptyMap(), a, "preview"))
    }

    @Test fun `prefers the pin when there is one`() {
        assertEquals("markdown", EditorModePins.resolve(mapOf(a to "markdown"), a, "preview"))
    }

    @Test fun `only applies a pin to its own note`() {
        assertEquals("preview", EditorModePins.resolve(mapOf(a to "markdown"), b, "preview"))
    }

    @Test fun `uses the global for a note with no id yet`() {
        // A note being composed has no server id to key a pin on.
        assertEquals("hybrid", EditorModePins.resolve(mapOf(a to "markdown"), null, "hybrid"))
    }

    // --- withMode ---

    @Test fun `pins when the chosen mode differs from the global`() {
        assertEquals(mapOf(a to "markdown"),
            EditorModePins.withMode(emptyMap(), a, "markdown", "preview"))
    }

    @Test fun `UN-pins when the chosen mode is the global`() {
        assertEquals(emptyMap<String, String>(),
            EditorModePins.withMode(mapOf(a to "markdown"), a, "preview", "preview"))
    }

    @Test fun `changes an existing pin in place`() {
        assertEquals(mapOf(a to "hybrid"),
            EditorModePins.withMode(mapOf(a to "markdown"), a, "hybrid", "preview"))
    }

    @Test fun `leaves other notes alone`() {
        assertEquals(mapOf(b to "hybrid"),
            EditorModePins.withMode(mapOf(a to "markdown", b to "hybrid"), a, "preview", "preview"))
    }

    @Test fun `returns the SAME map when nothing changed, so no needless write`() {
        val pinned = mapOf(a to "markdown")
        assertSame(pinned, EditorModePins.withMode(pinned, a, "markdown", "preview"))
        val none = emptyMap<String, String>()
        assertSame(none, EditorModePins.withMode(none, a, "preview", "preview"))
    }

    @Test fun `a pin survives the global moving to match it`() {
        // Pin A to markdown, then make markdown the global. The pin is now
        // invisible, but it is still the user's explicit choice for that note,
        // so moving the global away again must bring it back.
        val pins = EditorModePins.withMode(emptyMap(), a, "markdown", "preview")
        assertEquals("markdown", EditorModePins.resolve(pins, a, "markdown"))
        assertEquals("markdown", EditorModePins.resolve(pins, a, "hybrid"))
    }

    // --- isPinned ---

    @Test fun `pinned only when it differs from the current global`() {
        assertTrue(EditorModePins.isPinned(mapOf(a to "markdown"), a, "preview"))
        assertFalse(EditorModePins.isPinned(emptyMap(), a, "preview"))
        assertFalse(EditorModePins.isPinned(mapOf(a to "markdown"), a, "markdown"))
        assertFalse(EditorModePins.isPinned(mapOf(a to "markdown"), null, "preview"))
    }

    // --- gc ---

    @Test fun `drops pins for notes that no longer exist`() {
        assertEquals(mapOf(a to "markdown"),
            EditorModePins.gc(mapOf(a to "markdown", b to "hybrid"), setOf(a)))
    }

    @Test fun `keeps everything, by identity, when every note is live`() {
        val all = mapOf(a to "markdown", b to "hybrid")
        assertSame(all, EditorModePins.gc(all, setOf(a, b)))
    }

    @Test fun `collects everything when no notes are live`() {
        assertEquals(emptyMap<String, String>(), EditorModePins.gc(mapOf(a to "markdown"), emptySet()))
    }

    // --- cleared ---

    @Test fun `cleared forgets every pin`() {
        assertEquals(emptyMap<String, String>(),
            EditorModePins.cleared(mapOf(a to "markdown", b to "hybrid")))
        val none = emptyMap<String, String>()
        assertSame(none, EditorModePins.cleared(none))
    }

    // --- parse / sanitize / toJson ---

    @Test fun `parses the map out of a metadata blob`() {
        val json = """{"notebooks":[],"editorModes":{"$a":"markdown"}}"""
        assertEquals(mapOf(a to "markdown"), EditorModePins.parse(json))
    }

    @Test fun `drops values that are not modes`() {
        val json = """{"editorModes":{"$a":"zoomed","$b":"preview"}}"""
        assertEquals(mapOf(b to "preview"), EditorModePins.parse(json))
    }

    @Test fun `survives a blob with no map, or a broken one`() {
        assertEquals(emptyMap<String, String>(), EditorModePins.parse(null))
        assertEquals(emptyMap<String, String>(), EditorModePins.parse(""))
        assertEquals(emptyMap<String, String>(), EditorModePins.parse("{}"))
        assertEquals(emptyMap<String, String>(), EditorModePins.parse("not json"))
        assertEquals(emptyMap<String, String>(), EditorModePins.parse("""{"editorModes":"nope"}"""))
    }

    @Test fun `round-trips through JSON`() {
        val pins = mapOf(a to "markdown", b to "hybrid")
        val root = JSONObject().put("editorModes", EditorModePins.toJson(pins))
        assertEquals(pins, EditorModePins.parse(root.toString()))
    }
}
