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

package com.pingolin.note.importer.model

data class ImportedNote(
    val title: String,
    val body: String,
    val noteType: String, // "text" or "checklist"
    val checklistItems: List<ImportedChecklistItem>?,
    val labels: List<String>,
    val color: String?,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val createdAt: Long?, // epoch millis from source
    val updatedAt: Long?, // epoch millis from source

    // ---- Restore-only fields
    // ------------------------------------------
    // Set ONLY by the Pingolin backup parser. Keep, Evernote and
    // Standard Notes have no equivalent of any of these, so every
    // other parser leaves them null and the pipeline treats the note
    // as a plain foreign import. Mirrors the web editor's
    // `ImportedNote` — don't drift.

    /**
     * The note's original id. Preserving it is what makes restoring the
     * same backup twice converge instead of doubling the account: the
     * restore inserts IF ABSENT locally, and the server's item insert is
     * ON CONFLICT (id) DO NOTHING. A foreign import has no id and gets a
     * fresh one.
     */
    val id: String? = null,
    val sortOrder: Int? = null,
    val editMode: String? = null,
    val notebookId: String? = null,
    /** Content-JSON keys a newer client wrote, carried verbatim. */
    val extras: Map<String, Any?> = emptyMap(),
)

data class ImportedChecklistItem(val text: String, val checked: Boolean)

enum class ImportSource {
    GOOGLE_KEEP,
    EVERNOTE,
    STANDARD_NOTES,
    PLAIN_TEXT,

    /** Restoring a Pingolin backup, not importing a foreign file. */
    PINGOLIN_BACKUP,
}

sealed class ImportProgress {
    data class Parsing(val current: Int, val total: Int?, val currentTitle: String?) : ImportProgress()
    data class Note(val note: ImportedNote) : ImportProgress()
    data class Done(val total: Int) : ImportProgress()
    data class Error(val message: String) : ImportProgress()
}
