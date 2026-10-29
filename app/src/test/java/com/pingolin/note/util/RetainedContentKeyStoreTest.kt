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

package com.pingolin.note.util

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real retired-key store over plain preferences: its namespace is this account's canonical id,
 * and maps kept under another spelling of it are merged in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RetainedContentKeyStoreTest {

    private val me = "00000000-0000-4000-8000-00000000000e"
    private val other = "3c1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private lateinit var prefs: SharedPreferences
    private val session = mockk<SessionManager>()

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("retired_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        every { session.getUserId() } returns me
    }

    private fun map(vararg notes: Pair<String, List<Pair<String, String>>>): String {
        val root = JSONObject()
        for ((note, list) in notes) {
            val arr = JSONArray()
            for ((w, n) in list) arr.put(JSONObject().put("w", w).put("n", n))
            root.put(note, arr)
        }
        return root.toString()
    }

    @Test
    fun `wraps kept under another spelling of this account's id are merged in, then that spelling goes`() {
        prefs.edit()
            .putString("retired_keys:${me.uppercase()}", map("n1" to listOf("w1" to "a", "w2" to "b")))
            .putString("retired_keys:$me", map("n1" to listOf("w2" to "b", "w3" to "c"), "n2" to listOf("w9" to "z")))
            .putString("retired_keys:$other", map("n1" to listOf("x" to "y")))
            .commit()
        val s = RetainedContentKeyStore({ prefs }, session)
        assertEquals(
            listOf("w1", "w2", "w3"),
            s.retained("n1").map { it.wrap },
        )
        assertEquals(listOf("w9"), s.retained("n2").map { it.wrap })
        assertNull(prefs.getString("retired_keys:${me.uppercase()}", null))
        assertEquals(map("n1" to listOf("x" to "y")), prefs.getString("retired_keys:$other", null))
    }

    @Test
    fun `the merge keeps the newest 32 per note`() {
        val older = (1..30).map { "o$it" to "n" }
        val mine = (1..5).map { "m$it" to "n" }
        prefs.edit()
            .putString("retired_keys:{$me}", map("n1" to older))
            .putString("retired_keys:$me", map("n1" to mine))
            .commit()
        val wraps = RetainedContentKeyStore({ prefs }, session).retained("n1").map { it.wrap }
        assertEquals(32, wraps.size)
        assertEquals((4..30).map { "o$it" } + (1..5).map { "m$it" }, wraps)
    }
}
