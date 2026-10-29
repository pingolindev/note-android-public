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

/**
 * Per-note editor-mode pins: the rule, with no storage and no Android.
 *
 * The global setting is the DEFAULT for every note. Changing the mode with a
 * specific note open pins that note; setting it back to whatever the global
 * currently is un-pins it, so the note follows the default again.
 *
 * Kept in step with the Note web editor's and the iOS app's editor-mode pins —
 * the map is one shared blob, so all three have to agree about what it means.
 *
 * WHY NOT IN THE NOTE. The note body is what syncs to collaborators and to
 * `/shared/:token` readers, so a view preference stored there would impose one
 * reader's choice on everyone — and writing it would bump `updated_at`,
 * reordering the notes list for nothing more than looking at a note in a
 * different mode. A pin never writes the note.
 */
object EditorModePins {

    /** The three modes, as `SessionManager.EDITOR_MODE_*` spells them. */
    private val MODES = setOf("hybrid", "markdown", "preview")

    /** Drop anything that is not a known mode.
     *
     *  The blob is shared with clients this build has never met, including
     *  later ones, so an unrecognised value is treated as absent rather than
     *  trusted into a UI that switches on three names. */
    fun sanitize(raw: JSONObject?): Map<String, String> {
        if (raw == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in raw.keys()) {
            val v = raw.optString(key)
            if (key.isNotEmpty() && v in MODES) out[key] = v
        }
        return out
    }

    /** Parse the `editorModes` object out of a metadata blob's JSON text. */
    fun parse(metadataJson: String?): Map<String, String> {
        if (metadataJson.isNullOrEmpty()) return emptyMap()
        return try {
            sanitize(JSONObject(metadataJson).optJSONObject("editorModes"))
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** The mode a note is actually edited in. */
    fun resolve(pins: Map<String, String>, noteId: String?, global: String): String =
        if (noteId == null) global else pins[noteId] ?: global

    /** True when this note is pinned AWAY from the current global — i.e. when
     *  the UI should say "this note" rather than "default". A pin that happens
     *  to equal the global reads as not pinned, which is honest: it changes
     *  nothing the user can see. */
    fun isPinned(pins: Map<String, String>, noteId: String?, global: String): Boolean =
        noteId != null && pins[noteId] != null && pins[noteId] != global

    /**
     * Apply the pin/un-pin rule.
     *
     * Returns the SAME map when nothing changed, so callers can skip a write —
     * the map shares a blob with notebooks and label colours, and a pointless
     * save would bump its version and race another device for nothing.
     */
    fun withMode(
        pins: Map<String, String>, noteId: String, mode: String, global: String,
    ): Map<String, String> {
        val current = pins[noteId]
        if (mode == global) {
            if (current == null) return pins
            return pins.filterKeys { it != noteId }
        }
        if (current == mode) return pins
        return pins + (noteId to mode)
    }

    /** Forget every pin. Backs "Reset all notes to the default" — a pin is
     *  visible only with its note open, so without this a user who pinned a
     *  few and forgot which has no way back. */
    fun cleared(pins: Map<String, String>): Map<String, String> =
        if (pins.isEmpty()) pins else emptyMap()

    /**
     * Drop pins whose note is gone, so the map cannot grow forever.
     *
     * [liveIds] must be the ids the caller actually knows to exist. A partial
     * list would delete pins for notes that are merely absent from it.
     */
    fun gc(pins: Map<String, String>, liveIds: Set<String>): Map<String, String> {
        if (pins.isEmpty()) return pins
        if (pins.keys.all { it in liveIds }) return pins
        return pins.filterKeys { it in liveIds }
    }

    /** Serialise back into the shape the blob carries. */
    fun toJson(pins: Map<String, String>): JSONObject {
        val obj = JSONObject()
        for ((id, mode) in pins) obj.put(id, mode)
        return obj
    }
}
