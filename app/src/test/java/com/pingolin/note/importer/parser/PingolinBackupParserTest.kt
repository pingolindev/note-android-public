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
import com.pingolin.note.importer.model.ImportedNote
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The round trip is the whole feature.
 *
 * These tests are about a note surviving whole — id, colour, labels, editor
 * mode, checklist items and notebook — and about a restore refusing a file it
 * cannot honour rather than half-reading it.
 *
 * Mirrors the Note web editor's backup parser tests — same cases, so a
 * divergence between the two clients shows up as a failing test on one side.
 */
class PingolinBackupParserTest {

    private val parser = PingolinBackupParser()

    private val notebookId = "11111111-1111-4111-8111-111111111111"
    private val noteId = "22222222-2222-4222-8222-222222222222"

    private fun backup(notes: String, notebooks: String = """[{"id":"$notebookId","name":"Work"}]""") = """
        {
          "format": "pingolin-note-backup",
          "formatVersion": 2,
          "client": "web",
          "generatedAt": "2026-08-12T00:00:00.000Z",
          "isComplete": true,
          "notebooks": $notebooks,
          "labelColors": {"urgent":"#ff0000"},
          "editorModes": {},
          "notebookShares": {},
          "notes": $notes,
          "reminders": [],
          "attachments": []
        }
    """.trimIndent()

    private val fullNote = """
        {
          "id": "$noteId",
          "title": "Shopping",
          "body": "body text",
          "noteType": "checklist",
          "color": "#00ff00",
          "isPinned": true,
          "isArchived": false,
          "labels": ["urgent","home"],
          "checklistItems": [{"text":"milk","checked":true},{"text":"eggs","checked":false}],
          "sortOrder": 7,
          "editMode": "markdown",
          "notebookId": "$notebookId",
          "hasAttachments": false,
          "createdAt": "2026-01-01T10:00:00.000Z",
          "updatedAt": "2026-02-02T11:00:00.000Z",
          "extras": {"fromANewerClient":"keep me"}
        }
    """.trimIndent()

    private suspend fun parse(json: String): Pair<List<ImportedNote>, String?> {
        val events = parser.parse(ByteArrayInputStream(json.toByteArray())).toList()
        val notes = events.filterIsInstance<ImportProgress.Note>().map { it.note }
        val error = events.filterIsInstance<ImportProgress.Error>().firstOrNull()?.message
        return notes to error
    }

    @Test
    fun `carries a note across whole, every field and its unknown extras included`() = runTest {
        val (notes, error) = parse(backup("[$fullNote]"))
        assertNull(error)
        assertEquals(1, notes.size)
        val n = notes[0]

        // The id is what makes a repeat restore converge instead of duplicating.
        assertEquals(noteId, n.id)
        assertEquals("Shopping", n.title)
        assertEquals("body text", n.body)
        assertEquals("checklist", n.noteType)
        // A checklist note comes back with its content.
        assertEquals(2, n.checklistItems?.size)
        assertEquals("milk", n.checklistItems?.get(0)?.text)
        assertTrue(n.checklistItems!![0].checked)
        assertEquals("#00ff00", n.color)
        assertEquals(listOf("urgent", "home"), n.labels)
        assertTrue(n.isPinned)
        assertEquals(7, n.sortOrder)
        assertEquals("markdown", n.editMode)
        assertEquals(notebookId, n.notebookId)
        assertEquals("keep me", n.extras["fromANewerClient"])
    }

    @Test
    fun `drops a notebook reference the file does not define`() = runTest {
        // Better unfiled and visible than filed into a notebook that will
        // never exist.
        val (notes, _) = parse(backup("[$fullNote]", notebooks = "[]"))
        assertNull(notes[0].notebookId)
    }

    @Test
    fun `ignores a malformed id rather than sending one the server would reject`() = runTest {
        // A bad id would be rejected on sync and LOSE the note; dropping it
        // only costs idempotence for that row.
        val bad = fullNote.replace("\"$noteId\"", "\"not-a-uuid\"")
        val (notes, _) = parse(backup("[$bad]"))
        assertNull(notes[0].id)
        assertEquals("Shopping", notes[0].title)
    }

    @Test
    fun `survives a hand-edited file with missing and wrong-typed fields`() = runTest {
        val junk = """
            {"id":"$noteId","title":42,"labels":["ok"],
             "checklistItems":[{"text":"fine","checked":"yes"},{"checked":true}],
             "noteType":"nonsense","editMode":"sideways"}
        """.trimIndent()
        val (notes, error) = parse(backup("[$junk]"))
        assertNull(error)
        val n = notes[0]
        assertEquals("", n.title)        // wrong type falls back, does not throw
        assertEquals("", n.body)
        assertEquals("text", n.noteType) // unknown type falls back
        assertNull(n.editMode)           // unknown mode omitted, not guessed
        assertEquals(1, n.checklistItems?.size) // the item with no text is dropped
    }

    @Test
    fun `refuses a file that is not a Pingolin backup`() = runTest {
        val (notes, error) = parse("""{"items":[{"content_type":"Note"}]}""")
        assertNotNull(error)
        assertTrue(error!!.contains("not a Pingolin Note backup"))
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `refuses a backup from a FUTURE format rather than half-reading it`() = runTest {
        // Half-restoring a format we do not understand produces an account
        // that looks whole and is not.
        val future = backup("[$fullNote]").replace("\"formatVersion\": 2", "\"formatVersion\": 99")
        val (notes, error) = parse(future)
        assertNotNull(error)
        assertTrue(error!!.contains("newer version"))
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `reports unreadable JSON instead of throwing`() = runTest {
        val (notes, error) = parse("{ not json")
        assertNotNull(error)
        assertTrue(error!!.contains("Couldn't read that file"))
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `a file that can't be read or opened is said in this app's words, never the parser's or the SDK's`() = runTest {
        // Gson's own words ("Unterminated object at line 1 column 11 …") are for the log.
        assertEquals(PingolinBackupParser.COULD_NOT_READ, parse("{ not json").second)

        io.mockk.mockkStatic("uniffi.pingolin_mobile.Pingolin_mobileKt")
        try {
            io.mockk.every { uniffi.pingolin_mobile.openBackupFile(any(), any()) } throws
                uniffi.pingolin_mobile.MobileException.CryptoException("That backup is malformed: invalid base64 at 12")
            val sealed = """{"format":"pingolin-encrypted-backup","version":1}"""
            val events = PingolinBackupParser(passphrase = "wrong").parse(ByteArrayInputStream(sealed.toByteArray())).toList()
            val error = events.filterIsInstance<ImportProgress.Error>().single().message
            assertEquals("That backup could not be opened. Check the passphrase.", error)
        } finally {
            io.mockk.unmockkStatic("uniffi.pingolin_mobile.Pingolin_mobileKt")
        }
    }

    @Test
    fun `an empty backup finishes cleanly`() = runTest {
        val (notes, error) = parse(backup("[]"))
        assertNull(error)
        assertTrue(notes.isEmpty())
    }
}
