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
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayInputStream

@Ignore("XmlPullParserFactory.newInstance() returns null outside the Android runtime; needs Robolectric to run as a JVM unit test.")
class EvernoteParserTest {

    private val parser = EvernoteParser()

    // =============================================================================
    // BASIC PARSING TESTS
    // =============================================================================

    @Test
    fun `parse_singleNote_extractsTitleAndBody`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>My First Note</title>
                <content>Hello world</content>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()

        val notes = events.filterIsInstance<ImportProgress.Note>()
        assertEquals(1, notes.size)
        assertEquals("My First Note", notes[0].note.title)
        assertEquals("Hello world", notes[0].note.body)
    }

    @Test
    fun `parse_multipleNotes_parsesAll`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>Note One</title>
                <content>Body one</content>
              </note>
              <note>
                <title>Note Two</title>
                <content>Body two</content>
              </note>
              <note>
                <title>Note Three</title>
                <content>Body three</content>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()

        val notes = events.filterIsInstance<ImportProgress.Note>()
        assertEquals(3, notes.size)
        assertEquals("Note One", notes[0].note.title)
        assertEquals("Note Two", notes[1].note.title)
        assertEquals("Note Three", notes[2].note.title)
    }

    @Test
    fun `parse_emptyExport_emitsDoneWithZero`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()

        val done = events.last() as ImportProgress.Done
        assertEquals(0, done.total)
        assertTrue(events.filterIsInstance<ImportProgress.Note>().isEmpty())
    }

    // =============================================================================
    // HTML STRIPPING TESTS
    // =============================================================================

    @Test
    fun `parse_htmlContent_stripsAllTags`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>HTML Note</title>
                <content><![CDATA[<div>Hello <b>bold</b> and <i>italic</i></div>]]></content>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertFalse(note.body.contains("<"))
        assertFalse(note.body.contains(">"))
    }

    @Test
    fun `parse_htmlEntities_decoded`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>Entities</title>
                <content>&amp; &lt; &gt; &quot; &#39; &nbsp;</content>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertTrue(note.body.contains("&"))
        assertTrue(note.body.contains("<"))
        assertTrue(note.body.contains(">"))
        assertTrue(note.body.contains("\""))
        assertTrue(note.body.contains("'"))
    }

    // =============================================================================
    // TAG / LABEL TESTS
    // =============================================================================

    @Test
    fun `parse_notesWithTags_extractsLabels`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>Tagged Note</title>
                <content>Body</content>
                <tag>label1</tag>
                <tag>label2</tag>
                <tag>label3</tag>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals(3, note.labels.size)
        assertTrue(note.labels.contains("label1"))
        assertTrue(note.labels.contains("label2"))
        assertTrue(note.labels.contains("label3"))
    }

    @Test
    fun `parse_noteWithoutTags_emptyLabels`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>No Tags</title>
                <content>Body</content>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertTrue(note.labels.isEmpty())
    }

    // =============================================================================
    // TIMESTAMP TESTS
    // =============================================================================

    @Test
    fun `parse_validTimestamps_parsesCreatedAndUpdated`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>Timestamped</title>
                <content>Body</content>
                <created>20240115T100000Z</created>
                <updated>20240115T120000Z</updated>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertNotNull(note.createdAt)
        assertNotNull(note.updatedAt)
        assertTrue(note.updatedAt!! > note.createdAt!!)
    }

    @Test
    fun `parse_missingTimestamps_nullCreatedAndUpdated`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>No Timestamps</title>
                <content>Body</content>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertNull(note.createdAt)
        assertNull(note.updatedAt)
    }

    // =============================================================================
    // DEFAULT VALUES TESTS
    // =============================================================================

    @Test
    fun `parse_note_hasCorrectDefaults`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>Default Note</title>
                <content>Body</content>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()
        val note = events.filterIsInstance<ImportProgress.Note>().first().note

        assertEquals("text", note.noteType)
        assertNull(note.checklistItems)
        assertNull(note.color)
        assertFalse(note.isPinned)
        assertFalse(note.isArchived)
    }

    // =============================================================================
    // PROGRESS EVENT TESTS
    // =============================================================================

    @Test
    fun `parse_emitsParsingEventsBeforeNoteEvents`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>Progress Test</title>
                <content>Body</content>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()

        // Should be: Parsing, Note, Done
        assertTrue(events[0] is ImportProgress.Parsing)
        assertTrue(events[1] is ImportProgress.Note)
        assertTrue(events[2] is ImportProgress.Done)
    }

    @Test
    fun `parse_parsingEventContainsCorrectCount`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note><title>One</title><content>B</content></note>
              <note><title>Two</title><content>B</content></note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()
        val parsingEvents = events.filterIsInstance<ImportProgress.Parsing>()

        assertEquals(2, parsingEvents.size)
        assertEquals(1, parsingEvents[0].current)
        assertEquals(2, parsingEvents[1].current)
    }

    @Test
    fun `parse_doneContainsTotalCount`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note><title>A</title><content>B</content></note>
              <note><title>C</title><content>D</content></note>
              <note><title>E</title><content>F</content></note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()
        val done = events.last() as ImportProgress.Done

        assertEquals(3, done.total)
    }

    // =============================================================================
    // TAGS CLEARING BETWEEN NOTES TESTS
    // =============================================================================

    @Test
    fun `parse_tagsDontLeakBetweenNotes`() = runTest {
        val enex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <en-export>
              <note>
                <title>Tagged</title>
                <content>Body</content>
                <tag>important</tag>
              </note>
              <note>
                <title>Untagged</title>
                <content>Body</content>
              </note>
            </en-export>
        """.trimIndent()

        val events = parser.parse(ByteArrayInputStream(enex.toByteArray())).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>()

        assertEquals(1, notes[0].note.labels.size)
        assertTrue(notes[1].note.labels.isEmpty())
    }
}
