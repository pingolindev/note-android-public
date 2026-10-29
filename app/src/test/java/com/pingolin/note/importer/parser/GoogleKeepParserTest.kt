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

class GoogleKeepParserTest {

    private val parser = GoogleKeepParser()

    // =============================================================================
    // BASIC TEXT NOTE TESTS
    // =============================================================================

    @Test
    fun `parse_textNote_extractsTitleAndContent`() = runTest {
        val zip = createKeepZip(
            "note.json" to """
                {
                    "title": "Shopping List",
                    "textContent": "Milk, eggs, bread"
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(1, notes.size)
        assertEquals("Shopping List", notes[0].note.title)
        assertEquals("Milk, eggs, bread", notes[0].note.body)
        assertEquals("text", notes[0].note.noteType)
    }

    @Test
    fun `parse_multipleNotes_parsesAll`() = runTest {
        val zip = createKeepZip(
            "note1.json" to """{"title": "Note 1", "textContent": "Body 1"}""",
            "note2.json" to """{"title": "Note 2", "textContent": "Body 2"}""",
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(2, notes.size)
    }

    // =============================================================================
    // CHECKLIST TESTS
    // =============================================================================

    @Test
    fun `parse_checklistNote_parsesItems`() = runTest {
        val zip = createKeepZip(
            "checklist.json" to """
                {
                    "title": "Todo List",
                    "listContent": [
                        {"text": "Buy groceries", "isChecked": false},
                        {"text": "Clean house", "isChecked": true},
                        {"text": "Call dentist", "isChecked": false}
                    ]
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals("checklist", note.noteType)
        assertEquals("Todo List", note.title)
        assertNotNull(note.checklistItems)
        assertEquals(3, note.checklistItems!!.size)
        assertEquals("Buy groceries", note.checklistItems!![0].text)
        assertFalse(note.checklistItems!![0].checked)
        assertEquals("Clean house", note.checklistItems!![1].text)
        assertTrue(note.checklistItems!![1].checked)
    }

    @Test
    fun `parse_checklistNote_bodyContainsFormattedItems`() = runTest {
        val zip = createKeepZip(
            "checklist.json" to """
                {
                    "title": "List",
                    "listContent": [
                        {"text": "Done item", "isChecked": true},
                        {"text": "Pending item", "isChecked": false}
                    ]
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertTrue(note.body.contains("[x] Done item"))
        assertTrue(note.body.contains("[ ] Pending item"))
    }

    // =============================================================================
    // TRASHED NOTES TESTS
    // =============================================================================

    @Test
    fun `parse_trashedNote_skipped`() = runTest {
        val zip = createKeepZip(
            "trashed.json" to """
                {
                    "title": "Trashed Note",
                    "textContent": "This was deleted",
                    "isTrashed": true
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertTrue(notes.isEmpty())
    }

    @Test
    fun `parse_nonTrashedNote_included`() = runTest {
        val zip = createKeepZip(
            "active.json" to """
                {
                    "title": "Active Note",
                    "textContent": "Still here",
                    "isTrashed": false
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(1, notes.size)
    }

    // =============================================================================
    // LABEL TESTS
    // =============================================================================

    @Test
    fun `parse_noteWithLabels_extractsLabels`() = runTest {
        val zip = createKeepZip(
            "labeled.json" to """
                {
                    "title": "Labeled",
                    "textContent": "Body",
                    "labels": [
                        {"name": "Work"},
                        {"name": "Important"}
                    ]
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals(2, note.labels.size)
        assertTrue(note.labels.contains("Work"))
        assertTrue(note.labels.contains("Important"))
    }

    @Test
    fun `parse_noteWithoutLabels_emptyLabels`() = runTest {
        val zip = createKeepZip(
            "unlabeled.json" to """
                {
                    "title": "No Labels",
                    "textContent": "Body"
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertTrue(note.labels.isEmpty())
    }

    // =============================================================================
    // COLOR TESTS
    // =============================================================================

    @Test
    fun `parse_noteWithColor_mapsToHex`() = runTest {
        val zip = createKeepZip(
            "colored.json" to """
                {
                    "title": "Blue Note",
                    "textContent": "Body",
                    "color": "BLUE"
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals("#8B9FFA", note.color)
    }

    @Test
    fun `parse_noteWithDefaultColor_nullColor`() = runTest {
        val zip = createKeepZip(
            "default.json" to """
                {
                    "title": "Default",
                    "textContent": "Body",
                    "color": "DEFAULT"
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertNull(note.color)
    }

    @Test
    fun `parse_noteWithoutColor_nullColor`() = runTest {
        val zip = createKeepZip(
            "nocolor.json" to """
                {
                    "title": "No Color",
                    "textContent": "Body"
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertNull(note.color)
    }

    // =============================================================================
    // PIN / ARCHIVE STATE TESTS
    // =============================================================================

    @Test
    fun `parse_pinnedNote_isPinnedTrue`() = runTest {
        val zip = createKeepZip(
            "pinned.json" to """
                {
                    "title": "Pinned",
                    "textContent": "Body",
                    "isPinned": true
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertTrue(note.isPinned)
    }

    @Test
    fun `parse_archivedNote_isArchivedTrue`() = runTest {
        val zip = createKeepZip(
            "archived.json" to """
                {
                    "title": "Archived",
                    "textContent": "Body",
                    "isArchived": true
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertTrue(note.isArchived)
    }

    @Test
    fun `parse_defaultPinnedArchived_bothFalse`() = runTest {
        val zip = createKeepZip(
            "default.json" to """
                {
                    "title": "Default State",
                    "textContent": "Body"
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertFalse(note.isPinned)
        assertFalse(note.isArchived)
    }

    // =============================================================================
    // TIMESTAMP TESTS
    // =============================================================================

    @Test
    fun `parse_timestampInMicroseconds_convertsToMillis`() = runTest {
        val timestampUsec = 1705312800000000L // microseconds
        val expectedMs = 1705312800000L        // milliseconds

        val zip = createKeepZip(
            "timed.json" to """
                {
                    "title": "Timed",
                    "textContent": "Body",
                    "userEditedTimestampUsec": $timestampUsec
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals(expectedMs, note.createdAt)
        assertEquals(expectedMs, note.updatedAt)
    }

    @Test
    fun `parse_missingTimestamp_nullTimestamps`() = runTest {
        val zip = createKeepZip(
            "notime.json" to """
                {
                    "title": "No Time",
                    "textContent": "Body"
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertNull(note.createdAt)
        assertNull(note.updatedAt)
    }

    // =============================================================================
    // FILE FILTERING TESTS
    // =============================================================================

    @Test
    fun `parse_skipsNonJsonFiles`() = runTest {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        zos.putNextEntry(ZipEntry("note.json"))
        zos.write("""{"title":"Valid","textContent":"Body"}""".toByteArray())
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("image.png"))
        zos.write("binary data".toByteArray())
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("audio.mp3"))
        zos.write("audio data".toByteArray())
        zos.closeEntry()
        zos.close()

        val events = parser.parse(ByteArrayInputStream(baos.toByteArray())).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(1, notes.size)
    }

    @Test
    fun `parse_skipsDirectories`() = runTest {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        zos.putNextEntry(ZipEntry("Takeout/Keep/"))
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("Takeout/Keep/note.json"))
        zos.write("""{"title":"Nested","textContent":"Body"}""".toByteArray())
        zos.closeEntry()
        zos.close()

        val events = parser.parse(ByteArrayInputStream(baos.toByteArray())).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(1, notes.size)
        assertEquals("Nested", notes[0].note.title)
    }

    // =============================================================================
    // PROGRESS EVENT TESTS
    // =============================================================================

    @Test
    fun `parse_emitsDoneWithCorrectCount`() = runTest {
        val zip = createKeepZip(
            "a.json" to """{"title":"A","textContent":"1"}""",
            "b.json" to """{"title":"B","textContent":"2"}""",
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val done = events.last() as ImportProgress.Done

        assertEquals(2, done.total)
    }

    @Test
    fun `parse_emptyZip_doneWithZero`() = runTest {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).close()

        val events = parser.parse(ByteArrayInputStream(baos.toByteArray())).toList()
        val done = events.last() as ImportProgress.Done

        assertEquals(0, done.total)
    }

    // =============================================================================
    // EDGE CASES
    // =============================================================================

    @Test
    fun `parse_emptyTitle_returnsEmptyString`() = runTest {
        val zip = createKeepZip(
            "empty_title.json" to """
                {
                    "title": "",
                    "textContent": "Body without title"
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals("", note.title)
    }

    @Test
    fun `parse_emptyListContent_treatedAsTextNote`() = runTest {
        val zip = createKeepZip(
            "empty_list.json" to """
                {
                    "title": "Empty List",
                    "listContent": [],
                    "textContent": "Actually text"
                }
            """.trimIndent()
        )

        val events = parser.parse(ByteArrayInputStream(zip)).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals("text", note.noteType)
        assertEquals("Actually text", note.body)
    }

    @Test
    fun `parse_malformedJson_skipped`() = runTest {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        zos.putNextEntry(ZipEntry("bad.json"))
        zos.write("not valid json{{{".toByteArray())
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("good.json"))
        zos.write("""{"title":"Good","textContent":"Body"}""".toByteArray())
        zos.closeEntry()
        zos.close()

        val events = parser.parse(ByteArrayInputStream(baos.toByteArray())).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        // Should skip the bad one and parse the good one
        assertEquals(1, notes.size)
        assertEquals("Good", notes[0].note.title)
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    private fun createKeepZip(vararg entries: Pair<String, String>): ByteArray {
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
