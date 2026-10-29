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

package com.pingolin.note.data.remote.dto

import com.google.gson.Gson
import com.google.gson.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three states the server distinguishes for `notebook_id` on update, and
 * the two ways of getting them wrong.
 *
 * Absent means "leave the column alone", `null` means "un-file". Gson OMITS
 * null fields by default, so a plain `String?` could never send the second —
 * a note taken out of a notebook would keep its old grouping server-side
 * forever, reading as still filed with nothing on screen to show it.
 *
 * And naming a notebook that is not a real row is a foreign-key violation
 * (`items.notebook_id` is `REFERENCES items(id)`), which fails the save
 * outright rather than degrading.
 */
class NotebookIdFieldTest {

    private val gson = Gson()
    private val real = setOf("nb-real")

    private fun jsonFor(field: com.google.gson.JsonElement?): String =
        gson.toJson(
            UpdateItemRequest(
                encryptedContent = "c", contentNonce = "n", expectedVersion = 1,
                notebookId = field,
            ),
        )

    @Test
    fun `a real notebook is sent as its id`() {
        val f = notebookIdField("nb-real", real)
        assertEquals("nb-real", f?.asString)
        assertTrue(jsonFor(f).contains("\"notebook_id\":\"nb-real\""))
    }

    /** The state a plain `String?` could never express. */
    @Test
    fun `no notebook is sent as an explicit json null, not omitted`() {
        val f = notebookIdField(null, real)
        assertEquals(JsonNull.INSTANCE, f)
        assertTrue(
            "an un-file must reach the wire as null",
            jsonFor(f).contains("\"notebook_id\":null"),
        )
    }

    /**
     * A web-only notebook exists in the picker and on no server. Naming it
     * would be a foreign-key violation, so the field is omitted and the server
     * leaves the column alone.
     */
    @Test
    fun `a notebook that is not a real row omits the field entirely`() {
        val f = notebookIdField("nb-web-only", real)
        assertNull(f)
        assertFalse(
            "a dangling id must never reach the wire",
            jsonFor(f).contains("notebook_id"),
        )
    }
}
