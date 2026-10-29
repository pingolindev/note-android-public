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
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.pingolin.note.domain.repository.BARE_LIST_ON_THE_WIRE
import com.pingolin.note.domain.repository.NONE_OFFERED_ON_THE_WIRE
import com.pingolin.note.domain.repository.OFFERED_ON_THE_WIRE
import com.pingolin.note.domain.repository.PAGE_ON_THE_WIRE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading one answer of the inbox, in both of the server's shapes.
 *
 * The two `ON_THE_WIRE` texts are the server's answer byte for byte (see InboxWire.kt), decoded
 * here the way Retrofit decodes them: the envelope by Gson, its `data` left as JSON.
 */
class InboxAnswerTest {

    private fun dataOf(body: String): JsonElement? {
        val type = object : TypeToken<ApiResponse<JsonElement>>() {}.type
        return Gson().fromJson<ApiResponse<JsonElement>>(body, type).data
    }
    private fun json(text: String): JsonElement = JsonParser.parseString(text)
    private fun rows(n: Int) = (0 until n).joinToString(",", "[", "]") {
        """{"share_id":"s$it","item_id":"i$it","owner_id":"o","permission":"edit","encrypted_content":"c","content_nonce":"n","updated_at":"u","created_at":"t$it"}"""
    }

    @Test
    fun `a page as the server sends it - its shares, and the server's word that more follows`() {
        val answer = InboxAnswer.read(dataOf(PAGE_ON_THE_WIRE), legacyPageLimit = 500)

        assertNotNull(answer)
        assertTrue(answer!!.more)
        assertEquals(1, answer.shares.size)
        val row = answer.shares.single()
        assertEquals("5a5a5a5a-0000-4000-8000-000000000001", row.shareId)
        assertEquals("6b6b6b6b-0000-4000-8000-000000000001", row.itemId)
        assertEquals("22222222-2222-4222-8222-222222222222", row.ownerId)
        assertEquals("edit", row.permission)
        assertEquals("d3JhcHBlZA==", row.wrappedKey)
        assertEquals("sender@example.com", row.ownerEmail)
        assertNull(row.wrapSignature)
        assertTrue(row.isNotebook)
        assertEquals("Y2lwaGVy", row.encryptedContent)
        assertEquals("bm9uY2U=", row.contentNonce)
        // The cursor for the next page is this row's time and id, sent back as they came.
        assertEquals("2026-10-07T01:02:03.456789Z", row.createdAt)
        // And the notebooks this account declined, with the time of each decline.
        assertEquals(
            mapOf("99999999-1111-4000-8000-000000000009" to "2026-10-06T23:00:00.123456Z"),
            answer.declined,
        )
    }

    /**
     * The answers here are the server's own bytes (InboxWire), not built from this app's own
     * idea of the field.
     */
    @Test
    fun `the notebooks on offer as the server sends them - the list, an empty list, and no word at all`() {
        val offering = InboxAnswer.read(dataOf(OFFERED_ON_THE_WIRE), legacyPageLimit = 500)
        assertNotNull(offering)
        assertEquals(
            setOf("6b6b6b6b-0000-4000-8000-000000000001", "6b6b6b6b-0000-4000-8000-000000000002"),
            offering!!.offered,
        )
        assertEquals(emptyMap<String, String>(), offering.declined)
        assertFalse(offering.more)

        // None on offer is said with an EMPTY list. Absent is "not told", and is not the same.
        val none = InboxAnswer.read(dataOf(NONE_OFFERED_ON_THE_WIRE), legacyPageLimit = 500)
        assertEquals(emptySet<String>(), none!!.offered)
        assertNull("a page that does not say", InboxAnswer.read(dataOf(PAGE_ON_THE_WIRE), legacyPageLimit = 500)!!.offered)
        assertNull("nor does an older server's bare list", InboxAnswer.read(dataOf(BARE_LIST_ON_THE_WIRE), legacyPageLimit = 500)!!.offered)
    }

    @Test
    fun `the bare list as an older server sends it - its rows, and the end inferred from its length`() {
        val answer = InboxAnswer.read(dataOf(BARE_LIST_ON_THE_WIRE), legacyPageLimit = 500)

        assertNotNull(answer)
        assertFalse(answer!!.more)
        assertEquals(listOf("5a5a5a5a-0000-4000-8000-000000000001"), answer.shares.map { it.shareId })
        assertEquals("2026-10-07T01:02:03.456789Z", answer.shares.single().createdAt)
    }

    /** The server's `has_more` decides, whatever the page's length is against this
     * app's own idea of a page.
     */
    @Test
    fun `a page is never measured by its length`() {
        assertFalse(InboxAnswer.read(json("""{"shares":${rows(3)},"has_more":false}"""), 3)!!.more)
        assertFalse(InboxAnswer.read(json("""{"shares":${rows(5)},"has_more":false}"""), 3)!!.more)
        assertTrue(InboxAnswer.read(json("""{"shares":${rows(1)},"has_more":true}"""), 3)!!.more)
        assertTrue(InboxAnswer.read(json("""{"shares":[],"has_more":true}"""), 3)!!.more)
    }

    @Test
    fun `the bare list has more exactly when it is as long as a page can be`() {
        assertFalse(InboxAnswer.read(json(rows(2)), 3)!!.more)
        assertTrue(InboxAnswer.read(json(rows(3)), 3)!!.more)
        assertTrue(InboxAnswer.read(json(rows(4)), 3)!!.more)
        assertFalse(InboxAnswer.read(json("[]"), 3)!!.more)
        assertEquals(0, InboxAnswer.read(json("[]"), 3)!!.shares.size)
    }

    /**
     * "Not told" and "none" are two answers. Not told (an older server, a later page, the server's
     * own lookup failing) keeps what an earlier answer said; none clears it (DeclinedNotebooks).
     */
    @Test
    fun `the declined notebooks - not told is null, none is empty`() {
        assertNull(InboxAnswer.read(json("""{"shares":${rows(1)},"has_more":false}"""), 500)!!.declined)
        assertNull(InboxAnswer.read(json("""{"shares":[],"has_more":false,"declined_notebooks":null}"""), 500)!!.declined)
        assertNull(InboxAnswer.read(json("""{"shares":[],"has_more":false,"declined_notebooks":{}}"""), 500)!!.declined)
        assertNull(InboxAnswer.read(json("""{"shares":[],"has_more":false,"declined_notebooks":"none"}"""), 500)!!.declined)
        assertEquals(emptyMap<String, String>(), InboxAnswer.read(json("""{"shares":[],"has_more":false,"declined_notebooks":[]}"""), 500)!!.declined)
        // The bare list of an older server cannot say.
        assertNull(InboxAnswer.read(dataOf(BARE_LIST_ON_THE_WIRE), 500)!!.declined)
    }

    /** An entry that is not an id with a time says nothing usable, and never costs the page its shares. */
    @Test
    fun `a declined entry that is no id with a time is left out, and the page is still read`() {
        val answer = InboxAnswer.read(json(
            """{"shares":${rows(2)},"has_more":false,"declined_notebooks":[
                "a-bare-id", null, 7, [], {},
                {"id":"nb-no-time"}, {"declined_at":"2026-10-07T10:00:00Z"},
                {"id":7,"declined_at":"2026-10-07T10:00:00Z"}, {"id":"nb-time-not-text","declined_at":7},
                {"id":"","declined_at":"2026-10-07T10:00:00Z"}, {"id":"nb-blank-time","declined_at":" "},
                {"id":"nb-1","declined_at":"2026-10-07T10:00:00Z"}
            ]}""",
        ), 500)

        assertNotNull(answer)
        assertEquals(2, answer!!.shares.size)
        assertEquals(mapOf("nb-1" to "2026-10-07T10:00:00Z"), answer.declined)
    }

    /**
     * An invitation is the notebook's own row, the oldest of the invitation: a read that stops
     * early does not come to it. The first page says what is on offer itself.
     */
    @Test
    fun `the notebooks on offer - not told is null, none is empty`() {
        val a = "6b6b6b6b-0000-4000-8000-00000000000a"
        val b = "6b6b6b6b-0000-4000-8000-00000000000b"
        fun page(offered: String?): InboxAnswer {
            val field = if (offered == null) "" else ""","offered_notebooks":$offered"""
            return InboxAnswer.read(json("""{"shares":[],"has_more":true$field}"""), 500)!!
        }
        assertEquals(setOf(a, b), page("""["$a","$b"]""").offered)
        assertEquals("none on offer is an EMPTY list, which says so", emptySet<String>(), page("[]").offered)
        assertNull("an older server does not say", page(null).offered)
        assertNull(page("null").offered)
        for (noList in listOf(""""all"""", "7", "{}", "true")) assertNull(noList, page(noList).offered)
        // The bare list of an older server never says.
        assertNull(InboxAnswer.read(json(rows(2)), 500)!!.offered)
        assertNull(InboxAnswer.read(dataOf(BARE_LIST_ON_THE_WIRE), 500)!!.offered)
        // Named twice is named once.
        assertEquals(setOf(a), page("""["$a","$a"]""").offered)
    }

    /** A list with a hole looks whole and is not: what is missing from it would read as "not on offer". */
    @Test
    fun `the notebooks on offer are the WHOLE list or nothing - one entry that is no id, and it says nothing`() {
        val a = "6b6b6b6b-0000-4000-8000-00000000000a"
        for (bad in listOf("null", "7", """""""", """"  """", "{}", """["$a"]""", "true")) {
            val answer = InboxAnswer.read(json("""{"shares":${rows(2)},"has_more":false,"offered_notebooks":["$a",$bad]}"""), 500)
            assertNotNull("the page is still read: $bad", answer)
            assertEquals(2, answer!!.shares.size)
            assertNull(bad, answer.offered)
        }
    }

    @Test
    fun `the notebooks on offer and the declined ones are read side by side`() {
        val a = "6b6b6b6b-0000-4000-8000-00000000000a"
        val d = "9c9c9c9c-0000-4000-8000-00000000000d"
        val answer = InboxAnswer.read(
            json("""{"shares":[],"has_more":false,"declined_notebooks":[{"id":"$d","declined_at":"2026-10-07T10:00:00Z"}],"offered_notebooks":["$a"]}"""),
            500,
        )!!
        assertEquals(mapOf(d to "2026-10-07T10:00:00Z"), answer.declined)
        assertEquals(setOf(a), answer.offered)
    }

    /**
     * Null, never an empty inbox. An empty inbox read to its end means every share was
     * withdrawn, and the rows removed on that must not rest on something this could not read.
     */
    @Test
    fun `anything else is not an answer`() {
        assertNull(InboxAnswer.read(null, 500))
        for (text in listOf(
            "null", "7", "true", "\"shares\"", "{}",
            """{"shares":[]}""",
            """{"has_more":false}""",
            """{"shares":{},"has_more":false}""",
            """{"shares":null,"has_more":false}""",
            """{"shares":[],"has_more":"false"}""",
            """{"shares":[],"has_more":0}""",
            """{"shares":[],"has_more":null}""",
            """{"rows":[],"has_more":false}""",
            // A row that is no object, in either shape: the page is not half-read.
            """[null]""", """[7]""", """["row"]""",
            """{"shares":[null],"has_more":false}""",
            """{"shares":[[1]],"has_more":false}""",
        )) {
            assertNull(text, InboxAnswer.read(json(text), 500))
        }
    }

    /**
     * Review of 2026-10-07, R5b-12. Gson leaves a Kotlin non-null property null when its field
     * is missing, and the first Kotlin function handed it throws: one such row made the whole
     * refresh throw. A row without one of the fields every row carries is no row, and by this
     * file's rule the page was then not read (so nothing is opened, pruned or removed on it).
     */
    @Test
    fun `a row without one of the fields every row carries is no row`() {
        val full = mapOf(
            "share_id" to "s", "item_id" to "i", "owner_id" to "o", "permission" to "edit",
            "encrypted_content" to "c", "content_nonce" to "n", "updated_at" to "u", "created_at" to "t",
        )
        fun page(row: Map<String, Any?>) = json("""{"shares":[${Gson().toJson(row)}],"has_more":false}""")
        assertNotNull("the control: every field present", InboxAnswer.read(page(full), 500))
        for (missing in full.keys) {
            assertNull("without $missing", InboxAnswer.read(page(full - missing), 500))
            assertNull("with $missing not a text", InboxAnswer.read(page(full + (missing to 7)), 500))
            // The bare list of an older server is held to the same.
            assertNull("bare list without $missing", InboxAnswer.read(json("[${Gson().toJson(full - missing)}]"), 500))
        }
        // A field that may be absent stays optional.
        assertNotNull(InboxAnswer.read(page(full + ("wrapped_key" to null)), 500))
    }
}
