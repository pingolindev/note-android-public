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

package com.pingolin.note.importer

import com.pingolin.note.data.local.entity.ItemEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * The written side of the backup format.
 *
 * These tests pin the shared envelope (the same as the web's, in field names
 * and field set), and in particular the two things about the attachment
 * manifest that are easy to get subtly wrong: names must actually appear, and
 * "couldn't read the list" must stay distinguishable from "there were none".
 */
class BackupFormatTest {

    private val noteId = "22222222-2222-4222-8222-222222222222"

    private fun note(id: String, content: String) = ItemEntity(
        id = id,
        userId = "u",
        itemType = 1,
        encryptedContent = "ct",
        contentNonce = "n",
        decryptedContent = content,
        title = "t",
        createdAt = Instant.parse("2026-01-01T10:00:00Z"),
        updatedAt = Instant.parse("2026-02-02T11:00:00Z"),
    )

    private val withAttachment = note(noteId, """{"title":"Has files","has_attachments":true}""")
    private val plain = note("33333333-3333-4333-8333-333333333333", """{"title":"Plain"}""")

    private fun manifestOf(doc: JSONObject) = doc.getJSONArray("attachments")

    @Test
    fun `writes the shared envelope`() {
        val doc = BackupFormat.build(listOf(plain), null)
        assertEquals("pingolin-note-backup", doc.getString("format"))
        assertEquals(2, doc.getInt("formatVersion"))
        assertEquals("android", doc.getString("client"))
        assertEquals(1, doc.getJSONArray("notes").length())
    }

    @Test
    fun `names the attachment files a backup cannot carry`() {
        val doc = BackupFormat.build(
            listOf(withAttachment), null,
            mapOf(noteId to listOf("invoice.pdf", "photo.jpg")),
        )
        val entry = manifestOf(doc).getJSONObject(0)
        assertEquals(noteId, entry.getString("noteId"))
        // "2 notes lost their attachments" is not actionable; the names are.
        val names = entry.getJSONArray("filenames")
        assertEquals(2, names.length())
        assertEquals("invoice.pdf", names.getString(0))
        assertEquals("photo.jpg", names.getString(1))
    }

    @Test
    fun `an unreadable attachment list stays distinct from an empty one`() {
        // null means "we could not read this note's attachments" — a restore
        // should say so. An empty list would claim there was nothing to lose,
        // which is a different and misleading statement.
        val unreadable = BackupFormat.build(listOf(withAttachment), null, mapOf(noteId to null))
        assertTrue(manifestOf(unreadable).getJSONObject(0).isNull("filenames"))

        val none = BackupFormat.build(listOf(withAttachment), null, mapOf(noteId to emptyList()))
        val empty = none.getJSONArray("attachments").getJSONObject(0)
        assertFalse(empty.isNull("filenames"))
        assertEquals(0, empty.getJSONArray("filenames").length())
    }

    @Test
    fun `a note with no attachments is absent from the manifest and does not spoil completeness`() {
        val doc = BackupFormat.build(listOf(plain), JSONObject("""{"notebooks":[]}"""))
        assertEquals(0, manifestOf(doc).length())
        assertTrue(doc.getBoolean("isComplete"))
        assertEquals(0, doc.getJSONObject("counts").getInt("notesWithAttachments"))
    }

    @Test
    fun `attachments make the backup declare itself incomplete, with a reason`() {
        val doc = BackupFormat.build(listOf(withAttachment), JSONObject("""{"notebooks":[]}"""))
        // The file must never look whole when it isn't — that is the whole
        // point of carrying a manifest rather than silently dropping files.
        assertFalse(doc.getBoolean("isComplete"))
        assertTrue(doc.getJSONArray("incompleteReasons").getString(0).contains("attachments"))
        assertEquals(1, doc.getJSONObject("counts").getInt("notesWithAttachments"))
    }

    @Test
    fun `unreadable metadata is called out rather than silently omitting notebooks`() {
        val doc = BackupFormat.build(listOf(plain), null)
        assertFalse(doc.getBoolean("isComplete"))
        val reasons = doc.getJSONArray("incompleteReasons")
        assertTrue((0 until reasons.length()).any { reasons.getString(it).contains("Notebooks") })
    }

    @Test
    fun `carries a newer client's unknown content keys verbatim`() {
        val n = note(noteId, """{"title":"T","fromANewerClient":"keep me"}""")
        val doc = BackupFormat.build(listOf(n), null)
        val extras = doc.getJSONArray("notes").getJSONObject(0).getJSONObject("extras")
        // Dropping these would make a backup a downgrade.
        assertEquals("keep me", extras.getString("fromANewerClient"))
    }

    @Test
    fun `strips cached recipient keys from notebook shares`() {
        val meta = JSONObject("""
            {"notebooks":[],"notebookShares":{"nb1":[
              {"email":"a@example.com","permission":"view","publicKey":"CACHED","userId":"U"}
            ]}}
        """.trimIndent())
        val share = BackupFormat.build(listOf(plain), meta)
            .getJSONObject("notebookShares").getJSONArray("nb1").getJSONObject(0)
        assertEquals("a@example.com", share.getString("email"))
        assertEquals("view", share.getString("permission"))
        // A key that has rotated since would wrap future notes for whoever held
        // the old one, so the cache must not travel.
        assertFalse(share.has("publicKey"))
        assertFalse(share.has("userId"))
    }

    @Test
    fun `merging metadata is additive and never overwrites what is already there`() {
        val live = JSONObject("""
            {"notebooks":[{"id":"nb1","name":"Renamed since the backup"}],
             "labelColors":{"work":"#new"}}
        """.trimIndent())
        val backup = JSONObject("""
            {"notebooks":[{"id":"nb1","name":"Old name"},{"id":"nb2","name":"Missing one"}],
             "labelColors":{"work":"#old","home":"#green"}}
        """.trimIndent())

        val r = BackupFormat.mergeMetadata(live, backup)

        // Someone restoring has already had something go wrong; winding their
        // newer edits back is the wrong way to help.
        val notebooks = live.getJSONArray("notebooks")
        assertEquals(2, notebooks.length())
        assertEquals("Renamed since the backup", notebooks.getJSONObject(0).getString("name"))
        assertEquals("nb2", notebooks.getJSONObject(1).getString("id"))
        assertEquals("#new", live.getJSONObject("labelColors").getString("work"))
        assertEquals("#green", live.getJSONObject("labelColors").getString("home"))
        assertEquals(1, r.notebooksAdded)
        assertEquals(1, r.labelColorsAdded)
        assertTrue(r.changedAnything())
    }

    @Test
    fun `a restore merges no share list back`() {
        val live = JSONObject("""{"notebooks":[{"id":"nb1","name":"Work"}]}""")
        val backup = JSONObject("""
            {"notebooks":[{"id":"nb1","name":"Work"}],
             "notebookShares":{"nb1":[{"email":"removed-since@example.com","permission":"edit"}]}}
        """.trimIndent())

        val r = BackupFormat.mergeMetadata(live, backup)

        // A backup's share list is an old consent state: merged back, it would
        // share the notebook again with someone removed since.
        assertFalse(live.has("notebookShares"))
        assertFalse(r.changedAnything())
    }

    @Test
    fun `merging a backup that adds nothing reports no change`() {
        val live = JSONObject("""{"notebooks":[{"id":"nb1","name":"Work"}]}""")
        val r = BackupFormat.mergeMetadata(live, JSONObject("""{"notebooks":[{"id":"nb1","name":"Work"}]}"""))
        // The caller only writes the metadata row when something changed —
        // a no-op write would bump its version and race another device.
        assertFalse(r.changedAnything())
    }
}
