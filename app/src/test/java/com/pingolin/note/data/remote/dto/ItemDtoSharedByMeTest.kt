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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `shared_by_me` is a server field that older payloads lack, so every client
 * will meet payloads without it — an older backend, or a cached response.
 *
 * The trap this guards is documented on `contentKeyGeneration` in the same
 * DTO: **Gson ignores Kotlin default values.** An absent field is left at the
 * JVM default, not at the `= false` written here. For a Boolean those happen to
 * agree, which is exactly why it needs a test — the day someone changes the
 * declared default to `true`, Gson will keep handing back `false` and the
 * badge will silently stop appearing.
 */
class ItemDtoSharedByMeTest {

    private val gson = Gson()

    private fun json(extra: String) = """
        {"id":"i1","user_id":"u1","item_type":1,"encrypted_content":"x",
         "content_nonce":"n","version":1,"created_at":"2026-01-01T00:00:00Z",
         "updated_at":"2026-01-01T00:00:00Z","deleted":false,"circle_id":null,
         "notebook_id":null,"content_size":10$extra}
    """.trimIndent()

    @Test
    fun `absent shared_by_me reads as not shared`() {
        assertFalse(gson.fromJson(json(""), ItemDto::class.java).sharedByMe)
    }

    @Test
    fun `true is carried through`() {
        assertTrue(gson.fromJson(json(""","shared_by_me":true"""), ItemDto::class.java).sharedByMe)
    }

    @Test
    fun `explicit false is carried through`() {
        assertFalse(gson.fromJson(json(""","shared_by_me":false"""), ItemDto::class.java).sharedByMe)
    }
}
