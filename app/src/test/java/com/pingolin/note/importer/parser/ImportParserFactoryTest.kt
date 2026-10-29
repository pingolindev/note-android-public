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
import org.junit.Assert.*
import org.junit.Test

@Suppress("DEPRECATION") // exercises the back-compat createParser entry point
class ImportParserFactoryTest {

    @Test
    fun `createFileParser_googleKeep_returnsGoogleKeepParser`() {
        assertTrue(ImportParserFactory.createFileParser(ImportSource.GOOGLE_KEEP) is GoogleKeepParser)
    }

    @Test
    fun `createFileParser_evernote_returnsEvernoteParser`() {
        assertTrue(ImportParserFactory.createFileParser(ImportSource.EVERNOTE) is EvernoteParser)
    }

    @Test
    fun `createFileParser_standardNotes_returnsStandardNotesParser`() {
        assertTrue(ImportParserFactory.createFileParser(ImportSource.STANDARD_NOTES) is StandardNotesParser)
    }

    @Test
    fun `createFileParser_plainText_returnsPlainTextParserNotZip`() {
        assertTrue(ImportParserFactory.createFileParser(ImportSource.PLAIN_TEXT) is PlainTextParser)
    }

    @Test
    fun `createPlainTextZipParser_returnsPlainTextParser`() {
        assertTrue(ImportParserFactory.createPlainTextZipParser() is PlainTextParser)
    }

    @Test
    fun `createParser_backCompat_delegatesToFileParser`() {
        assertTrue(ImportParserFactory.createParser(ImportSource.GOOGLE_KEEP) is GoogleKeepParser)
    }
}
