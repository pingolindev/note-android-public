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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `has_wrap` read from a row as the server sends it (`GET /items/{id}/shares`; true
 * when the share holds a wrapped key), not from a row this app built itself, so a
 * renamed wire field cannot go unseen. A row without a key is not re-sealed
 * (ContentKeyRotator), and a server that does not say leaves it null.
 */
class ShareInfoHasWrapWireTest {

    private val gson = Gson()

    private fun row(extra: String) = """
        {"id":"7b1c0f6e-2f35-4d4e-9a77-0c4f8f8d1a01","recipient_email":"ann@example.test",
         "permission":"view","created_at":"2026-10-11T09:00:00.123456Z","notified_at":null$extra}
    """.trimIndent()

    @Test
    fun `a row the server says has no key reads as having none`() {
        assertEquals(false, gson.fromJson(row(""","has_wrap":false"""), ShareInfoDto::class.java).hasWrap)
    }

    @Test
    fun `a row the server says has a key reads as having one`() {
        assertEquals(true, gson.fromJson(row(""","has_wrap":true"""), ShareInfoDto::class.java).hasWrap)
    }

    @Test
    fun `a server that does not say leaves it unknown`() {
        assertNull(gson.fromJson(row(""), ShareInfoDto::class.java).hasWrap)
    }
}
