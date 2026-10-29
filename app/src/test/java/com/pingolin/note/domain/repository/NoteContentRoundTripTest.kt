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

package com.pingolin.note.domain.repository

import android.app.Application
import com.pingolin.note.data.local.entity.ItemEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Cross-platform schema guard: a note edited on this client must not lose
 * content-JSON fields it doesn't model. The iOS client writes the same
 * encrypted JSON, so any key one platform strips on re-encode is silent
 * data loss for the other — `edit_mode` falling back to "markdown" on every
 * editor save, for example.
 *
 * Uses the real org.json artifact (testImplementation) — the mockable
 * android.jar stubs it out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteContentRoundTripTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(com.pingolin.note.worker.SyncWorker.Companion)
        every { com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(any()) } just Runs
        mockkObject(com.pingolin.note.service.PinnedNoteService)
        every { com.pingolin.note.service.PinnedNoteService.pinNote(any(), any()) } just Runs
        every { com.pingolin.note.service.PinnedNoteService.unpinNote(any(), any()) } just Runs
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // =============================================================================
    // NoteContent codec
    // =============================================================================

    @Test
    fun `extrasFrom_capturesOnlyUnknownKeys`() {
        val obj = JSONObject(
            """{"title":"t","body":"b","edit_mode":"rich",
                "ios_future_field":{"nested":1},"another":"x"}"""
        )

        val extras = NoteContent.extrasFrom(obj)

        assertEquals(setOf("ios_future_field", "another"), extras.keys)
    }

    @Test
    fun `toJson_emitsExtrasOfEveryJsonType`() {
        val source = JSONObject(
            """{"title":"t","body":"b",
                "f_obj":{"a":1},"f_arr":[1,2],"f_str":"s","f_num":7,"f_bool":true,"f_null":null}"""
        )

        val out = JSONObject(
            NoteContent(title = "t", body = "b", extras = NoteContent.extrasFrom(source)).toJson()
        )

        assertEquals(1, out.getJSONObject("f_obj").getInt("a"))
        assertEquals(2, out.getJSONArray("f_arr").length())
        assertEquals("s", out.getString("f_str"))
        assertEquals(7, out.getInt("f_num"))
        assertTrue(out.getBoolean("f_bool"))
        assertTrue(out.isNull("f_null"))
    }

    @Test
    fun `toJson_withoutExtras_staysCanonical`() {
        val out = JSONObject(NoteContent(title = "a", body = "b").toJson())

        for (key in out.keys()) {
            assertTrue("unexpected key $key", key in NoteContent.KNOWN_KEYS)
        }
    }

    // =============================================================================
    // Edit paths — list mutations and editor saves must round-trip
    // foreign fields
    // =============================================================================

    private val foreignJson =
        """{"title":"t","body":"b","note_type":"text","labels":["l1"],
            "sort_order":3,"edit_mode":"rich",
            "checklist_items":[{"text":"milk","checked":true}],
            "ios_future_field":{"nested":1}}"""

    @Test
    fun `parseMutateRewrite_setPinned_preservesForeignFields`() {
        // The exact shape every native mutation must use: parse the
        // stored plaintext with parseNoteContent, copy one field,
        // re-serialise. The guard pins the repository-level helper
        // itself.
        val parsed = parseNoteContent(entity("note-1", foreignJson))
        requireNotNull(parsed)

        val out = JSONObject(parsed.copy(isPinned = true).toJson())
        assertTrue(out.getBoolean("is_pinned"))
        assertEquals("rich", out.getString("edit_mode"))
        assertEquals(3, out.getInt("sort_order"))
        assertEquals(1, out.getJSONObject("ios_future_field").getInt("nested"))
        val checklist = out.getJSONArray("checklist_items")
        assertEquals("milk", checklist.getJSONObject(0).getString("text"))
        assertTrue(checklist.getJSONObject(0).getBoolean("checked"))
    }

    private fun entity(id: String, json: String) = ItemEntity(
        id = id,
        userId = "user-1",
        itemType = 1,
        encryptedContent = "enc",
        contentNonce = "nonce",
        decryptedContent = json,
        title = "t",
        version = 1,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
