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

class StandardNotesParserTest {

    private val parser = StandardNotesParser()

    // =============================================================================
    // BASIC PARSING TESTS
    // =============================================================================

    @Test
    fun `parse_singleNote_extractsTitleAndBody`() = runTest {
        val json = """
            {
                "items": [
                    {
                        "content_type": "Note",
                        "content": {
                            "title": "My Note",
                            "text": "Note body content"
                        },
                        "created_at": "2024-01-15T10:00:00.000Z",
                        "updated_at": "2024-01-15T12:00:00.000Z"
                    }
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(1, notes.size)
        assertEquals("My Note", notes[0].note.title)
        assertEquals("Note body content", notes[0].note.body)
    }

    @Test
    fun `parse_multipleNotes_parsesAll`() = runTest {
        val json = """
            {
                "items": [
                    {"content_type": "Note", "content": {"title": "Note 1", "text": "Body 1"}},
                    {"content_type": "Note", "content": {"title": "Note 2", "text": "Body 2"}},
                    {"content_type": "Note", "content": {"title": "Note 3", "text": "Body 3"}}
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(3, notes.size)
        assertEquals("Note 1", notes[0].note.title)
        assertEquals("Note 2", notes[1].note.title)
        assertEquals("Note 3", notes[2].note.title)
    }

    // =============================================================================
    // CONTENT TYPE FILTERING TESTS
    // =============================================================================

    @Test
    fun `parse_skipsNonNoteItems_onlyParsesNotes`() = runTest {
        val json = """
            {
                "items": [
                    {"content_type": "Note", "content": {"title": "Real Note", "text": "Body"}},
                    {"content_type": "Tag", "content": {"title": "SomeTag"}},
                    {"content_type": "Component", "content": {"name": "SomeComponent"}},
                    {"content_type": "Note", "content": {"title": "Another Note", "text": "Body 2"}}
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(2, notes.size)
        assertEquals("Real Note", notes[0].note.title)
        assertEquals("Another Note", notes[1].note.title)
    }

    @Test
    fun `parse_itemWithoutContentType_skipped`() = runTest {
        val json = """
            {
                "items": [
                    {"content": {"title": "No Type", "text": "Body"}}
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertTrue(notes.isEmpty())
    }

    // =============================================================================
    // TIMESTAMP TESTS
    // =============================================================================

    @Test
    fun `parse_validIsoTimestamps_parsesCorrectly`() = runTest {
        val json = """
            {
                "items": [
                    {
                        "content_type": "Note",
                        "content": {"title": "Timed", "text": "Body"},
                        "created_at": "2024-01-15T10:00:00.000Z",
                        "updated_at": "2024-01-15T12:00:00.000Z"
                    }
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertNotNull(note.createdAt)
        assertNotNull(note.updatedAt)
        assertTrue(note.updatedAt!! > note.createdAt!!)
    }

    @Test
    fun `parse_missingTimestamps_nullValues`() = runTest {
        val json = """
            {
                "items": [
                    {"content_type": "Note", "content": {"title": "No Time", "text": "Body"}}
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertNull(note.createdAt)
        assertNull(note.updatedAt)
    }

    @Test
    fun `parse_invalidTimestamp_returnsNull`() = runTest {
        val json = """
            {
                "items": [
                    {
                        "content_type": "Note",
                        "content": {"title": "Bad Time", "text": "Body"},
                        "created_at": "not-a-timestamp"
                    }
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertNull(note.createdAt)
    }

    // =============================================================================
    // DEFAULT VALUES TESTS
    // =============================================================================

    @Test
    fun `parse_noteHasCorrectDefaults`() = runTest {
        val json = """
            {
                "items": [
                    {"content_type": "Note", "content": {"title": "Default", "text": "Body"}}
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals("text", note.noteType)
        assertNull(note.checklistItems)
        assertTrue(note.labels.isEmpty())
        assertNull(note.color)
        assertFalse(note.isPinned)
        assertFalse(note.isArchived)
    }

    // =============================================================================
    // MISSING CONTENT TESTS
    // =============================================================================

    @Test
    fun `parse_emptyTitle_returnsEmptyString`() = runTest {
        val json = """
            {
                "items": [
                    {"content_type": "Note", "content": {"title": "", "text": "Body only"}}
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals("", note.title)
        assertEquals("Body only", note.body)
    }

    @Test
    fun `parse_missingTitle_defaultsToEmpty`() = runTest {
        val json = """
            {
                "items": [
                    {"content_type": "Note", "content": {"text": "Body only"}}
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals("", note.title)
    }

    @Test
    fun `parse_missingText_defaultsToEmpty`() = runTest {
        val json = """
            {
                "items": [
                    {"content_type": "Note", "content": {"title": "Title Only"}}
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals("Title Only", note.title)
        assertEquals("", note.body)
    }

    // =============================================================================
    // ERROR HANDLING TESTS
    // =============================================================================

    @Test
    fun `parse_missingItemsArray_emitsError`() = runTest {
        val json = """{"notes": []}"""

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()

        val errors = events.filterIsInstance<ImportProgress.Error>()
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("missing 'items' array"))
    }

    @Test
    fun `parse_emptyItemsArray_emitsDoneWithZero`() = runTest {
        val json = """{"items": []}"""

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()

        val done = events.last() as ImportProgress.Done
        assertEquals(0, done.total)
    }

    // =============================================================================
    // PROGRESS EVENTS TESTS
    // =============================================================================

    @Test
    fun `parse_emitsCorrectParsingCount`() = runTest {
        val json = """
            {
                "items": [
                    {"content_type": "Note", "content": {"title": "A", "text": "1"}},
                    {"content_type": "Note", "content": {"title": "B", "text": "2"}}
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val parsingEvents = events.filterIsInstance<ImportProgress.Parsing>()

        assertEquals(2, parsingEvents.size)
        assertEquals(1, parsingEvents[0].current)
        assertEquals(2, parsingEvents[1].current)
    }

    @Test
    fun `parse_parsingEventContainsTitle`() = runTest {
        val json = """
            {
                "items": [
                    {"content_type": "Note", "content": {"title": "My Title", "text": "Body"}}
                ]
            }
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val parsing = events.filterIsInstance<ImportProgress.Parsing>().first()

        assertEquals("My Title", parsing.currentTitle)
    }
}
