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

package com.pingolin.note.importer.parser

import com.pingolin.note.importer.model.ImportSource

object ImportParserFactory {

    /** Returns the file-based parser for `source`. */
    fun createFileParser(source: ImportSource, passphrase: String? = null): NoteImportParser = when (source) {
        ImportSource.GOOGLE_KEEP -> GoogleKeepParser()
        ImportSource.EVERNOTE -> EvernoteParser()
        ImportSource.STANDARD_NOTES -> StandardNotesParser()
        ImportSource.PLAIN_TEXT -> PlainTextParser(isZip = false)
        // Restoring our own backup, not importing a foreign file. Same
        // pipeline; the difference is that this one carries ids, so a repeat
        // converges instead of duplicating.
        ImportSource.PINGOLIN_BACKUP -> PingolinBackupParser(passphrase)
    }

    /**
     * Deprecated alias of [createFileParser], which new code should
     * call instead.
     */
    @Deprecated(
        "Use createFileParser() — the two are equivalent now that all importers are file-based.",
        ReplaceWith("createFileParser(source)"),
    )
    fun createParser(source: ImportSource): NoteImportParser = createFileParser(source)

    /** For ZIP files containing plain text/markdown files. */
    fun createPlainTextZipParser(): NoteImportParser = PlainTextParser(isZip = true)
}
