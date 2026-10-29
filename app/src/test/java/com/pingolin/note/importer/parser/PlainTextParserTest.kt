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

import com.pingolin.note.importer.model.ImportProgress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PlainTextParserTest {

    // =============================================================================
    // SINGLE FILE PARSING TESTS
    // =============================================================================

    @Test
    fun `parse_singleTextFile_emitsParsingNoteAndDone`() = runTest {
        val parser = PlainTextParser(isZip = false)
        val content = "My Note Title\nThis is the body of my note."
        val stream = ByteArrayInputStream(content.toByteArray())

        val events = parser.parse(stream).toList()

        // Should emit: Parsing, Note, Done
        assertEquals(3, events.size)
        assertTrue(events[0] is ImportProgress.Parsing)
        assertTrue(events[1] is ImportProgress.Note)
        assertTrue(events[2] is ImportProgress.Done)

        val note = (events[1] as ImportProgress.Note).note
        assertEquals("My Note Title", note.title)
        assertEquals("This is the body of my note.", note.body)
        assertEquals("text", note.noteType)
    }

    @Test
    fun `parse_singleFile_usesFirstLineAsTitle`() = runTest {
        val parser = PlainTextParser(isZip = false)
        val content = "First Line Title\nSecond line body\nThird line"
        val stream = ByteArrayInputStream(content.toByteArray())

        val events = parser.parse(stream).toList()
        val note = (events[1] as ImportProgress.Note).note

        assertEquals("First Line Title", note.title)
        assertEquals("Second line body\nThird line", note.body)
    }

    @Test
    fun `parse_singleFile_markdownHeading_stripsHash`() = runTest {
        val parser = PlainTextParser(isZip = false)
        val content = "# Markdown Heading\nBody content here"
        val stream = ByteArrayInputStream(content.toByteArray())

        val events = parser.parse(stream).toList()
        val note = (events[1] as ImportProgress.Note).note

        assertEquals("Markdown Heading", note.title)
        assertEquals("Body content here", note.body)
    }

    @Test
    fun `parse_singleFile_emptyFirstLine_usesImportedNote`() = runTest {
        val parser = PlainTextParser(isZip = false)
        val content = "\nBody only"
        val stream = ByteArrayInputStream(content.toByteArray())

        val events = parser.parse(stream).toList()
        val note = (events[1] as ImportProgress.Note).note

        assertEquals("Imported note", note.title)
        assertEquals("Body only", note.body)
    }

    @Test
    fun `parse_singleFile_noBody_bodyIsEmpty`() = runTest {
        val parser = PlainTextParser(isZip = false)
        val content = "Title Only"
        val stream = ByteArrayInputStream(content.toByteArray())

        val events = parser.parse(stream).toList()
        val note = (events[1] as ImportProgress.Note).note

        assertEquals("Title Only", note.title)
        assertEquals("", note.body)
    }

    @Test
    fun `parse_singleFile_defaultsAreCorrect`() = runTest {
        val parser = PlainTextParser(isZip = false)
        val content = "Title\nBody"
        val stream = ByteArrayInputStream(content.toByteArray())

        val events = parser.parse(stream).toList()
        val note = (events[1] as ImportProgress.Note).note

        assertNull(note.checklistItems)
        assertTrue(note.labels.isEmpty())
        assertNull(note.color)
        assertFalse(note.isPinned)
        assertFalse(note.isArchived)
        assertNull(note.createdAt)
        assertNull(note.updatedAt)
    }

    @Test
    fun `parse_singleFile_doneCountIsOne`() = runTest {
        val parser = PlainTextParser(isZip = false)
        val stream = ByteArrayInputStream("Title\nBody".toByteArray())

        val events = parser.parse(stream).toList()
        val done = events.last() as ImportProgress.Done

        assertEquals(1, done.total)
    }

    // =============================================================================
    // ZIP FILE PARSING TESTS
    // =============================================================================

    @Test
    fun `parse_zipWithMultipleTextFiles_parsesAll`() = runTest {
        val parser = PlainTextParser(isZip = true)
        val zipBytes = createZip(
            "note1.txt" to "Note One\nBody one",
            "note2.md" to "# Note Two\nBody two",
            "note3.markdown" to "Note Three\nBody three",
        )
        val stream = ByteArrayInputStream(zipBytes)

        val events = parser.parse(stream).toList()

        val notes = events.filterIsInstance<ImportProgress.Note>().map { it.note }
        assertEquals(3, notes.size)
        assertEquals("Note One", notes[0].title)
        assertEquals("Note Two", notes[1].title)
        assertEquals("Note Three", notes[2].title)
    }

    @Test
    fun `parse_zipSkipsNonTextFiles_ignoresImages`() = runTest {
        val parser = PlainTextParser(isZip = true)
        val zipBytes = createZip(
            "note.txt" to "A Note\nWith body",
            "image.png" to "binary data",
            "data.json" to "{\"key\": \"value\"}",
        )
        val stream = ByteArrayInputStream(zipBytes)

        val events = parser.parse(stream).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(1, notes.size)
        assertEquals("A Note", notes[0].note.title)
    }

    @Test
    fun `parse_zipSkipsDirectories`() = runTest {
        val parser = PlainTextParser(isZip = true)

        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        // Add a directory entry
        zos.putNextEntry(ZipEntry("subdir/"))
        zos.closeEntry()
        // Add a file inside the directory
        zos.putNextEntry(ZipEntry("subdir/note.txt"))
        zos.write("Nested Note\nBody".toByteArray())
        zos.closeEntry()
        zos.close()

        val stream = ByteArrayInputStream(baos.toByteArray())
        val events = parser.parse(stream).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(1, notes.size)
        assertEquals("Nested Note", notes[0].note.title)
    }

    @Test
    fun `parse_zipUsesFilenameWithoutExtensionAsTitle_whenFirstLineBlank`() = runTest {
        val parser = PlainTextParser(isZip = true)
        val zipBytes = createZip("my-note.txt" to "\nJust body content")
        val stream = ByteArrayInputStream(zipBytes)

        val events = parser.parse(stream).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals("my-note", note.title)
    }

    @Test
    fun `parse_zipDoneContainsCorrectCount`() = runTest {
        val parser = PlainTextParser(isZip = true)
        val zipBytes = createZip(
            "a.txt" to "Note A\nBody A",
            "b.txt" to "Note B\nBody B",
        )
        val stream = ByteArrayInputStream(zipBytes)

        val events = parser.parse(stream).toList()
        val done = events.last() as ImportProgress.Done

        assertEquals(2, done.total)
    }

    @Test
    fun `parse_emptyZip_emitsDoneWithZero`() = runTest {
        val parser = PlainTextParser(isZip = true)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).close()
        val stream = ByteArrayInputStream(baos.toByteArray())

        val events = parser.parse(stream).toList()
        val done = events.last() as ImportProgress.Done

        assertEquals(0, done.total)
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    private fun createZip(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        for ((name, content) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.toByteArray())
            zos.closeEntry()
        }
        zos.close()
        return baos.toByteArray()
    }
}
