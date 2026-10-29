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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Which shared notes of a DECLINED notebook are kept away: once a share is
 * declined, nothing of it shows up.
 *
 * The same two facts the server decides by, and the web editor too: the
 * notebook was declined, and the share is LATER than the decline.
 */
class DeclinedNotebooksTest {
    private val NB = "20000000-0000-4000-8000-000000000001"
    private val OTHER = "20000000-0000-4000-8000-000000000002"
    private val AT = "2026-10-07T10:00:00.123456Z"
    private val at: Instant = Instant.parse(AT)

    // ── the notebooks declined, on the server's word ──

    @Test
    fun `what the server says is what is declined, by canonical id and time`() {
        assertEquals(
            mapOf(NB to at, OTHER to Instant.parse("2026-10-01T00:00:00Z")),
            DeclinedNotebooks.onTheServersWord(
                said = mapOf(NB.uppercase() to AT, "{$OTHER}" to "2026-10-01T00:00:00Z"),
                offered = emptySet(), previous = emptyMap(),
            ),
        )
    }

    @Test
    fun `an entry that is no id, or whose time cannot be read, names nothing`() {
        assertEquals(
            mapOf(NB to at),
            DeclinedNotebooks.onTheServersWord(
                said = mapOf("not-a-uuid" to AT, OTHER to "last tuesday", NB to AT),
                offered = emptySet(), previous = emptyMap(),
            ),
        )
    }

    /** Null is "not told", never "none". */
    @Test
    fun `an answer that does not say keeps what an earlier one said`() {
        val previous = mapOf(NB to at)
        assertEquals(previous, DeclinedNotebooks.onTheServersWord(said = null, offered = emptySet(), previous = previous))
    }

    @Test
    fun `an answer that says none clears what an earlier one said`() {
        assertEquals(
            emptyMap<String, Instant>(),
            DeclinedNotebooks.onTheServersWord(said = emptyMap(), offered = emptySet(), previous = mapOf(NB to at)),
        )
    }

    /** The row is the newer fact, whether the declined list is this answer's or a kept one. */
    @Test
    fun `a notebook the answer offers is not declined`() {
        assertEquals(
            mapOf(OTHER to at),
            DeclinedNotebooks.onTheServersWord(said = mapOf(NB to AT, OTHER to AT), offered = setOf(NB), previous = emptyMap()),
        )
        assertEquals(
            emptyMap<String, Instant>(),
            DeclinedNotebooks.onTheServersWord(said = null, offered = setOf(NB), previous = mapOf(NB to at)),
        )
    }

    // ── the shares kept away ──

    private val declined = mapOf(NB to at)

    @Test
    fun `a share made after the decline is kept away`() {
        assertTrue(DeclinedNotebooks.keepsAway(declined, NB, "2026-10-07T10:00:00.123457Z"))
        assertTrue(DeclinedNotebooks.keepsAway(declined, NB, "2026-10-08T00:00:00Z"))
    }

    /** What the reader held before they declined was shared with them in its own right. */
    @Test
    fun `a share made before the decline, or at the same instant, is not`() {
        assertFalse(DeclinedNotebooks.keepsAway(declined, NB, "2026-10-07T09:59:59.999999Z"))
        assertFalse(DeclinedNotebooks.keepsAway(declined, NB, AT))
    }

    @Test
    fun `a note in no notebook, or in one that was not declined, is not`() {
        assertFalse(DeclinedNotebooks.keepsAway(declined, null, "2026-10-08T00:00:00Z"))
        assertFalse(DeclinedNotebooks.keepsAway(declined, OTHER, "2026-10-08T00:00:00Z"))
        assertFalse(DeclinedNotebooks.keepsAway(declined, "not-a-uuid", "2026-10-08T00:00:00Z"))
        assertFalse(DeclinedNotebooks.keepsAway(emptyMap(), NB, "2026-10-08T00:00:00Z"))
    }

    @Test
    fun `the note's notebook is found under any spelling of its id`() {
        assertTrue(DeclinedNotebooks.keepsAway(declined, NB.uppercase(), "2026-10-08T00:00:00Z"))
        assertTrue(DeclinedNotebooks.keepsAway(declined, "{$NB}", "2026-10-08T00:00:00Z"))
        assertTrue(DeclinedNotebooks.keepsAway(declined, NB.replace("-", ""), "2026-10-08T00:00:00Z"))
    }

    /** Shown rather than taken from a reader who may hold it in its own right. */
    @Test
    fun `a share whose time cannot be read is not kept away`() {
        assertFalse(DeclinedNotebooks.keepsAway(declined, NB, "yesterday"))
        assertFalse(DeclinedNotebooks.keepsAway(declined, NB, ""))
        // Nor one with no time at all: it must not throw either.
        assertFalse(DeclinedNotebooks.keepsAway(declined, NB, null))
    }
}
