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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for what is ON OFFER when an inbox read stopped early, and for the notes held because
 * of an invitation. Each rule here, each with the case that would show it broken; how the refresh
 * uses them is SharesInboxHousekeepingTest.
 */
class NotebooksOnOfferTest {
    private val a = "aaaaaaaa-0000-4000-8000-000000000001"
    private val b = "bbbbbbbb-0000-4000-8000-000000000002"
    private val c = "cccccccc-0000-4000-8000-000000000003"

    // --- What is on offer -----------------------------------------------------------------------

    @Test
    fun `a read to the end is the whole answer - what an earlier one found says nothing`() {
        assertEquals(setOf(a), NotebooksOnOffer.of(setOf(a), complete = true, lastComplete = setOf(b, c), declined = emptySet()))
        assertEquals(emptySet<String>(), NotebooksOnOffer.of(emptySet(), complete = true, lastComplete = setOf(b), declined = emptySet()))
    }

    @Test
    fun `a read that stopped early also counts what the last read to the end found`() {
        // b's own row is past where the reading stopped: it is still on offer.
        assertEquals(setOf(a, b), NotebooksOnOffer.of(setOf(a), complete = false, lastComplete = setOf(b), declined = emptySet()))
        assertEquals(setOf(b), NotebooksOnOffer.of(emptySet(), complete = false, lastComplete = setOf(b), declined = emptySet()))
    }

    @Test
    fun `but not one the server now says was declined - unless this read serves it again`() {
        assertEquals(setOf(a), NotebooksOnOffer.of(setOf(a), complete = false, lastComplete = setOf(b), declined = setOf(b)))
        // Served by this very read: on offer, whatever the declined list still says.
        assertEquals(setOf(b), NotebooksOnOffer.of(setOf(b), complete = false, lastComplete = setOf(b), declined = setOf(b)))
    }

    @Test
    fun `with no read to the end yet, a read that stopped early has only what it reached`() {
        assertEquals(setOf(a), NotebooksOnOffer.of(setOf(a), complete = false, lastComplete = null, declined = emptySet()))
    }

    @Test
    fun `what is on offer is unknown only when this read stopped early and none ever reached the end`() {
        assertTrue(NotebooksOnOffer.unknown(complete = false, lastComplete = null))
        // An EMPTY set is an answer: the last read to the end found nothing on offer.
        assertFalse(NotebooksOnOffer.unknown(complete = false, lastComplete = emptySet()))
        assertFalse(NotebooksOnOffer.unknown(complete = true, lastComplete = null))
        assertFalse(NotebooksOnOffer.unknown(complete = true, lastComplete = setOf(a)))
    }

    // --- What the SERVER says is on offer
    // ------------------------------------------------------

    @Test
    fun `the server's list is what is on offer, however far the read got`() {
        // b's own row was not reached, and no earlier read ever saw it: the server names it.
        assertEquals(setOf(a, b), NotebooksOnOffer.of(emptySet(), complete = false, lastComplete = emptySet(), declined = emptySet(), said = setOf(a, b)))
        assertEquals(setOf(b), NotebooksOnOffer.of(emptySet(), complete = false, lastComplete = null, declined = emptySet(), said = setOf(b)))
        assertEquals(setOf(b), NotebooksOnOffer.of(emptySet(), complete = true, lastComplete = null, declined = emptySet(), said = setOf(b)))
    }

    @Test
    fun `with the server's list, what an earlier read found says nothing`() {
        // c was on offer at the last read to the end and is not any more (withdrawn since).
        assertEquals(setOf(a), NotebooksOnOffer.of(emptySet(), complete = false, lastComplete = setOf(c), declined = emptySet(), said = setOf(a)))
        assertEquals(emptySet<String>(), NotebooksOnOffer.of(emptySet(), complete = false, lastComplete = setOf(c), declined = emptySet(), said = emptySet()))
    }

    @Test
    fun `the rows this read reached are counted beside the server's list`() {
        assertEquals(setOf(a, b), NotebooksOnOffer.of(setOf(a), complete = false, lastComplete = null, declined = emptySet(), said = setOf(b)))
    }

    @Test
    fun `without the server's list everything is as it was`() {
        assertEquals(setOf(a), NotebooksOnOffer.of(setOf(a), complete = true, lastComplete = setOf(b), declined = emptySet(), said = null))
        assertEquals(setOf(a, b), NotebooksOnOffer.of(setOf(a), complete = false, lastComplete = setOf(b), declined = emptySet(), said = null))
        assertEquals(setOf(a), NotebooksOnOffer.of(setOf(a), complete = false, lastComplete = setOf(b), declined = setOf(b), said = null))
    }

    @Test
    fun `once the server has said, what is on offer is never unknown`() {
        assertFalse(NotebooksOnOffer.unknown(complete = false, lastComplete = null, said = emptySet()))
        assertFalse(NotebooksOnOffer.unknown(complete = false, lastComplete = null, said = setOf(a)))
        assertTrue("it did not say, and no read reached the end: unknown", NotebooksOnOffer.unknown(complete = false, lastComplete = null, said = null))
    }

    @Test
    fun `the server's list is taken in canonical ids, whole or not at all`() {
        val canonical: (String) -> String? = { id -> id.lowercase().takeIf { it.length == 36 } }
        assertEquals(setOf(a, b), NotebooksOnOffer.said(setOf(a.uppercase(), b), canonical))
        assertEquals(emptySet<String>(), NotebooksOnOffer.said(emptySet(), canonical))
        assertEquals("not told stays not told", null, NotebooksOnOffer.said(null, canonical))
        // One entry that is no id: the list is not whole, and says nothing.
        assertEquals(null, NotebooksOnOffer.said(setOf(a, "not-an-id"), canonical))
    }

    // --- Held because of an invitation ----------------------------------------------------------

    @Test
    fun `a note of a notebook on offer and not answered is held`() {
        assertTrue(NotebooksOnOffer.heldForAnInvitation(a, unanswered = setOf(a), declined = emptySet(), heldForOneBefore = false))
        assertFalse(NotebooksOnOffer.heldForAnInvitation(b, unanswered = setOf(a), declined = emptySet(), heldForOneBefore = false))
    }

    @Test
    fun `a note in no notebook is never held for one`() {
        assertFalse(NotebooksOnOffer.heldForAnInvitation(null, unanswered = setOf(a), declined = setOf(b), heldForOneBefore = true))
    }

    @Test
    fun `a note held for an invitation stays held when the invitation is declined`() {
        // Declined: not on offer, so not "unanswered". It was held until now: it stays.
        assertTrue(NotebooksOnOffer.heldForAnInvitation(a, unanswered = emptySet(), declined = setOf(a), heldForOneBefore = true))
    }

    @Test
    fun `a note that was NOT held for the invitation is not taken by its decline`() {
        // Shared with the reader by itself and filed into the notebook later: theirs in
        // its own right, as the server has it.
        assertFalse(NotebooksOnOffer.heldForAnInvitation(a, unanswered = emptySet(), declined = setOf(a), heldForOneBefore = false))
    }

    @Test
    fun `a note held for an invitation is let go when its notebook is neither on offer nor declined`() {
        // Accepted, or the owner took the invitation back.
        assertFalse(NotebooksOnOffer.heldForAnInvitation(a, unanswered = emptySet(), declined = emptySet(), heldForOneBefore = true))
        // And one held for a, whose note now names b (moved), is not held because a was declined.
        assertFalse(NotebooksOnOffer.heldForAnInvitation(b, unanswered = emptySet(), declined = setOf(a), heldForOneBefore = true))
    }

    // --- Held because nothing says what is on offer ---------------------------------------------

    @Test
    fun `when what is on offer is unknown, a note of any notebook not accepted waits`() {
        assertTrue(NotebooksOnOffer.heldForNotKnowing(a, unknown = true, accepted = setOf(b)))
        assertFalse("an accepted notebook's note is shown", NotebooksOnOffer.heldForNotKnowing(b, unknown = true, accepted = setOf(b)))
        assertFalse("a note in no notebook is shown", NotebooksOnOffer.heldForNotKnowing(null, unknown = true, accepted = emptySet()))
    }

    @Test
    fun `when it is known, nothing waits for that`() {
        assertFalse(NotebooksOnOffer.heldForNotKnowing(a, unknown = false, accepted = emptySet()))
    }

    // --- What stays held ------------------------------------------------------------------------

    @Test
    fun `a read to the end decides about everything`() {
        assertEquals(setOf(a), NotebooksOnOffer.held(heldNow = setOf(a), decidedNow = setOf(a, b), heldBefore = setOf(b, c), complete = true))
    }

    @Test
    fun `a read that stopped early keeps what was held among the notes it did not reach`() {
        // b was reached and is not held now; c was not reached and stays
        // held.
        assertEquals(setOf(a, c), NotebooksOnOffer.held(heldNow = setOf(a), decidedNow = setOf(a, b), heldBefore = setOf(b, c), complete = false))
    }

    // --- What arrived since anything knew what was on offer
    // ----------------------------------

    private val asOf = "2026-10-01T12:00:00.500000Z"
    private val before = "2026-10-01T12:00:00.499999Z"
    private val after = "2026-10-01T12:00:00.500001Z"
    private val knownBooks = setOf("accepted", "on-offer", "declined")

    @Test
    fun `a note that arrived since, in a notebook nothing is known about, is held when the read stopped early unsaid`() {
        assertTrue(NotebooksOnOffer.heldForArrivingUnseen("new-book", true, knownBooks, after, asOf))
    }

    @Test
    fun `one that was here when the offers were last known keeps the decision made then`() {
        assertFalse(NotebooksOnOffer.heldForArrivingUnseen("new-book", true, knownBooks, before, asOf))
        assertFalse("the newest share of that read itself", NotebooksOnOffer.heldForArrivingUnseen("new-book", true, knownBooks, asOf, asOf))
    }

    @Test
    fun `nothing is held for that when the read knew - the server said, or it reached the end`() {
        assertFalse(NotebooksOnOffer.heldForArrivingUnseen("new-book", false, knownBooks, after, asOf))
    }

    @Test
    fun `nor a note of a notebook something is known about - each has its own rule`() {
        for (book in knownBooks) assertFalse(book, NotebooksOnOffer.heldForArrivingUnseen(book, true, knownBooks, after, asOf))
    }

    @Test
    fun `nor a note in no notebook`() {
        assertFalse(NotebooksOnOffer.heldForArrivingUnseen(null, true, knownBooks, after, asOf))
    }

    /** Of a note that may be an unanswered invitation's, the safe side is the one that shows nothing. */
    @Test
    fun `with no time to compare by, it is held`() {
        assertTrue("nothing ever knew", NotebooksOnOffer.heldForArrivingUnseen("new-book", true, knownBooks, after, null))
        assertTrue("the share's time cannot be read", NotebooksOnOffer.heldForArrivingUnseen("new-book", true, knownBooks, "yesterday", asOf))
        assertTrue("nor that of the last read", NotebooksOnOffer.heldForArrivingUnseen("new-book", true, knownBooks, after, "then"))
        assertTrue(NotebooksOnOffer.heldForArrivingUnseen("new-book", true, knownBooks, null, asOf))
    }

    /** The server writes a whole second with no fraction at all: compared as text that is LATER than any fraction of it. */
    @Test
    fun `times are compared as times, not as text`() {
        val wholeSecond = "2026-10-01T12:00:00Z"
        val withinIt = "2026-10-01T12:00:00.250000Z"
        assertTrue(NotebooksOnOffer.heldForArrivingUnseen("new-book", true, knownBooks, withinIt, wholeSecond))
        assertFalse(NotebooksOnOffer.heldForArrivingUnseen("new-book", true, knownBooks, wholeSecond, withinIt))
    }

    @Test
    fun `the newest of the times served is given back as it was served`() {
        assertEquals("2026-10-01T12:00:00.250000Z", NotebooksOnOffer.newest(listOf("2026-10-01T12:00:00Z", "2026-10-01T12:00:00.250000Z", "2026-09-30T23:59:59.999999Z")))
        assertEquals("2026-10-01T12:00:01Z", NotebooksOnOffer.newest(listOf("2026-10-01T12:00:00.999999Z", "2026-10-01T12:00:01Z")))
        assertEquals("what cannot be read is passed over", "2026-10-01T12:00:00Z", NotebooksOnOffer.newest(listOf("tomorrow", "2026-10-01T12:00:00Z", "")))
        assertNull(NotebooksOnOffer.newest(emptyList()))
        assertNull(NotebooksOnOffer.newest(listOf("never")))
    }
}
