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

import com.pingolin.note.data.remote.dto.UnfinishedShareDto
import com.pingolin.note.domain.repository.FinishSingleInvitations.DirectoryKeys
import com.pingolin.note.domain.repository.FinishSingleInvitations.FINISH_PER_PASS
import com.pingolin.note.domain.repository.FinishSingleInvitations.FINISH_RETRY_MS
import com.pingolin.note.domain.repository.FinishSingleInvitations.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web editor's `finishSingleInvitations.test.ts`, case for case, against the port
 * ([FinishSingleInvitations]), then what the phone adds: kept tries, a re-keyed note read again at
 * the next pass, and a pass that stops when the account changes.
 *
 * WHY THESE EXIST
 *   The phone must finish exactly the shares the owner's page would: a notebook's row, an expired
 *   or unclaimed one, or a note whose notebook has the address on its standing list finished here
 *   would hand someone a note with no invitation to answer.
 */
class FinishSingleInvitationsTest {

    private val keys = DirectoryKeys("zoe-id", "cc".repeat(32), "dd".repeat(32))
    private val zoe = mapOf("zoe@example.test" to keys)

    private var n = 0

    private fun row(
        notebookId: String? = null,
        recipientEmail: String = "Zoe@Example.test",
        itemType: Int? = 1,
        single: Boolean? = true,
        claimed: Boolean? = true,
        expired: Boolean? = false,
    ): UnfinishedShareDto {
        n += 1
        return UnfinishedShareDto(
            shareId = "s$n", itemId = "note-$n", itemType = itemType, notebookId = notebookId,
            recipientEmail = recipientEmail, permission = "view", single = single, claimed = claimed,
            inviteExpiresAt = "2026-11-20T00:00:00Z", expired = expired, createdAt = "2026-11-12T00:00:00Z",
        )
    }

    /** The page's `SingleTried`: a map. */
    private class MapTried : FinishSingleInvitations.Tried {
        val map = LinkedHashMap<String, Long>()
        override fun get(shareId: String): Long? = map[shareId]
        override fun set(shareId: String, at: Long) { map[shareId] = at }
        override fun delete(shareId: String) { map.remove(shareId) }
    }

    private inner class Harness(
        private val rows: List<UnfinishedShareDto>?,
        private val directory: Map<String, DirectoryKeys>? = zoe,
        private val outcome: (UnfinishedShareDto) -> Outcome = { Outcome.DONE },
        private val standing: Map<String, List<String>> = emptyMap(),
        /** The notebook this device has each note in. */
        private val local: Map<String, String> = emptyMap(),
    ) {
        var clock = 5_000_000L
        val finished = mutableListOf<String>()
        val asked = mutableListOf<List<String>>()

        val steps = object : FinishSingleInvitations.Steps {
            override suspend fun unfinished(): List<UnfinishedShareDto>? = rows
            override fun inStanding(notebookId: String, email: String): Boolean =
                (standing[notebookId] ?: emptyList()).contains(email.lowercase())
            override suspend fun localNotebookOf(itemId: String): String? = local[itemId]
            override suspend fun lookup(emails: List<String>): Map<String, DirectoryKeys>? {
                asked += emails
                return directory
            }
            override suspend fun finish(share: UnfinishedShareDto, keys: DirectoryKeys): Outcome {
                val o = outcome(share)
                finished += share.shareId!!
                return o
            }
            override fun now(): Long = clock
        }

        fun tick(ms: Long) {
            clock += ms
        }
    }

    // --- The page's cases ---

    @Test
    fun `finishes the claimed ones, and only notes not covered by a notebook invitation`() = runTest {
        val claimed = row()
        val waiting = row(claimed = false)
        val ranOut = row(expired = true)
        val notebook = row(itemType = 2)
        val inBook = row(notebookId = "nb-1")
        val inOtherBook = row(notebookId = "nb-2")
        val h = Harness(listOf(claimed, waiting, ranOut, notebook, inBook, inOtherBook), standing = mapOf("nb-1" to listOf("zoe@example.test")))
        val done = FinishSingleInvitations.finishSingleInvitations(h.steps, MapTried())
        assertEquals(listOf(claimed.shareId, inOtherBook.shareId), done)
        assertEquals(listOf(claimed.shareId, inOtherBook.shareId), h.finished)
        assertEquals(listOf(listOf("zoe@example.test")), h.asked)
    }

    /** Removing someone from a notebook takes them off its standing list first and deletes their
     *  note rows one at a time: rows a removal has not reached yet look like a notebook's waiting
     *  rows, and another of the owner's devices must not finish them (the page's review of
     *  2026-11-11). */
    @Test
    fun `finishes only a share made on its own, never a notebook's row`() = runTest {
        val own = row()
        val notebooks = row(single = false, notebookId = "nb-gone")
        val unsaid = row(single = null)
        val h = Harness(listOf(own, notebooks, unsaid))
        assertEquals(listOf(own.shareId), FinishSingleInvitations.finishSingleInvitations(h.steps, MapTried()))
        assertEquals(listOf(own.shareId), h.finished)
    }

    @Test
    fun `leaves a note whose notebook HERE has the address on its standing list, whatever the server row says`() = runTest {
        val inLocalBook = row(notebookId = null)
        val h = Harness(listOf(inLocalBook), local = mapOf(inLocalBook.itemId!! to "nb-9"), standing = mapOf("nb-9" to listOf("zoe@example.test")))
        assertEquals(emptyList<String>(), FinishSingleInvitations.finishSingleInvitations(h.steps, MapTried()))
    }

    @Test
    fun `concludes nothing from no answer`() = runTest {
        val tried = MapTried()
        val noList = Harness(null)
        assertEquals(emptyList<String>(), FinishSingleInvitations.finishSingleInvitations(noList.steps, tried))
        assertEquals(emptyList<List<String>>(), noList.asked)

        val noDirectory = Harness(listOf(row()), directory = null)
        assertEquals(emptyList<String>(), FinishSingleInvitations.finishSingleInvitations(noDirectory.steps, tried))
        assertEquals(emptyList<String>(), noDirectory.finished)
        assertEquals(0, tried.map.size)
    }

    @Test
    fun `tries each at most once per retry interval, and forgets one that went`() = runTest {
        val failing = row()
        val going = row()
        val tried = MapTried()
        val h = Harness(listOf(failing, going), outcome = { if (it === failing) Outcome.FAILED else Outcome.DONE })
        assertEquals(listOf(going.shareId), FinishSingleInvitations.finishSingleInvitations(h.steps, tried))
        assertTrue(tried.map.containsKey(failing.shareId))
        assertFalse(tried.map.containsKey(going.shareId))

        h.finished.clear()
        h.tick(FINISH_RETRY_MS - 1)
        FinishSingleInvitations.finishSingleInvitations(h.steps, tried)
        assertEquals(listOf(going.shareId), h.finished)

        h.finished.clear()
        h.tick(1)
        FinishSingleInvitations.finishSingleInvitations(h.steps, tried)
        assertEquals(listOf(failing.shareId, going.shareId), h.finished)
    }

    @Test
    fun `leaves an address the directory has no account for, and tries it again later`() = runTest {
        val tried = MapTried()
        val r = row(recipientEmail = "nobody@example.test")
        val h = Harness(listOf(r))
        assertEquals(emptyList<String>(), FinishSingleInvitations.finishSingleInvitations(h.steps, tried))
        assertEquals(emptyList<String>(), h.finished)
        assertTrue(tried.map.containsKey(r.shareId))
    }

    @Test
    fun `stops at a plan that no longer shares`() = runTest {
        val rows = listOf(row(), row(), row())
        val h = Harness(rows, outcome = { Outcome.UPGRADE })
        FinishSingleInvitations.finishSingleInvitations(h.steps, MapTried())
        assertEquals(listOf(rows[0].shareId), h.finished)
    }

    @Test
    fun `seals a bounded number in one pass`() = runTest {
        val rows = List(FINISH_PER_PASS + 3) { row() }
        assertEquals(
            FINISH_PER_PASS,
            FinishSingleInvitations.dueToFinish(rows, { _, _ -> false }, { null }, MapTried(), 0).size,
        )
        val h = Harness(rows)
        assertEquals(FINISH_PER_PASS, FinishSingleInvitations.finishSingleInvitations(h.steps, MapTried()).size)
    }

    // --- What the phone adds ---

    @Test
    fun `the retry interval and the per-pass bound are the page's`() {
        assertEquals(10 * 60_000L, FINISH_RETRY_MS)
        assertEquals(10, FINISH_PER_PASS)
    }

    @Test
    fun `a note re-keyed meanwhile is read again at the next pass, not in ten minutes`() = runTest {
        val stale = row()
        val tried = MapTried()
        val h = Harness(listOf(stale), outcome = { Outcome.STALE })
        FinishSingleInvitations.finishSingleInvitations(h.steps, tried)
        assertFalse(tried.map.containsKey(stale.shareId))
        FinishSingleInvitations.finishSingleInvitations(h.steps, tried)
        assertEquals(listOf(stale.shareId, stale.shareId), h.finished)
    }

    @Test
    fun `a share that is no longer unfinished, or a server that says later, is not tried again for ten minutes`() = runTest {
        for (outcome in listOf(Outcome.NOT_UNFINISHED, Outcome.LATER, Outcome.KEY_CHANGED, Outcome.CANNOT_SIGN, Outcome.NOT_HERE)) {
            val r = row()
            val tried = MapTried()
            val h = Harness(listOf(r), outcome = { outcome })
            FinishSingleInvitations.finishSingleInvitations(h.steps, tried)
            FinishSingleInvitations.finishSingleInvitations(h.steps, tried)
            assertEquals("$outcome", listOf(r.shareId), h.finished)
        }
    }

    @Test
    fun `a kept try from a clock that has since gone back holds nothing back`() = runTest {
        val r = row()
        val tried = MapTried()
        val h = Harness(listOf(r), outcome = { Outcome.FAILED })
        tried.set(r.shareId!!, h.clock + 365L * 24 * 3_600_000)
        FinishSingleInvitations.finishSingleInvitations(h.steps, tried)
        assertEquals(listOf(r.shareId), h.finished)
        assertEquals("tried now instead", h.clock, tried.map[r.shareId])
    }

    /** Review of bb1d05b: the server takes 30 shares a minute from an owner, and pushes can bunch.
     *  A 429 stops the pass; the row it was for waits its ten minutes, and the rows the pass
     *  didn't reach are untried, so due at the next pass. */
    @Test
    fun `a 429 stops the pass, and the rows it didn't reach are due at the next pass`() = runTest {
        val rows = listOf(row(), row(), row())
        val tried = MapTried()
        val h = Harness(rows, outcome = { if (it === rows[0]) Outcome.RATE_LIMITED else Outcome.DONE })
        assertEquals(emptyList<String>(), FinishSingleInvitations.finishSingleInvitations(h.steps, tried))
        assertEquals(listOf(rows[0].shareId), h.finished)
        assertEquals(setOf(rows[0].shareId), tried.map.keys)

        h.finished.clear()
        h.tick(1)
        assertEquals(listOf(rows[1].shareId, rows[2].shareId), FinishSingleInvitations.finishSingleInvitations(h.steps, tried))
        assertEquals(listOf(rows[1].shareId, rows[2].shareId), h.finished)
    }

    @Test
    fun `a pass whose account changed stops where it is`() = runTest {
        val rows = listOf(row(), row(), row())
        val h = Harness(rows, outcome = { if (it === rows[1]) Outcome.ABORTED else Outcome.DONE })
        assertEquals(listOf(rows[0].shareId), FinishSingleInvitations.finishSingleInvitations(h.steps, MapTried()))
        assertEquals(listOf(rows[0].shareId, rows[1].shareId), h.finished)
    }

    @Test
    fun `a finish that throws is a failure, and the pass goes on`() = runTest {
        val rows = listOf(row(), row())
        val h = Harness(rows, outcome = { if (it === rows[0]) throw IllegalStateException("boom") else Outcome.DONE })
        val tried = MapTried()
        assertEquals(listOf(rows[1].shareId), FinishSingleInvitations.finishSingleInvitations(h.steps, tried))
        assertTrue(tried.map.containsKey(rows[0].shareId))
    }

    @Test
    fun `a row the server sent without what finishing it needs is not finished`() = runTest {
        val noId = row().copy(shareId = null)
        val noItem = row().copy(itemId = null)
        val noAddress = row().copy(recipientEmail = null)
        val unsaidClaim = row(claimed = null)
        val unsaidType = row(itemType = null)
        val h = Harness(listOf(noId, noItem, noAddress, unsaidClaim, unsaidType))
        assertEquals(emptyList<String>(), FinishSingleInvitations.finishSingleInvitations(h.steps, MapTried()))
        assertEquals(emptyList<List<String>>(), h.asked)
    }

    @Test
    fun `the directory is asked once, for each address lower-cased`() = runTest {
        val h = Harness(listOf(row(recipientEmail = "Zoe@Example.test"), row(recipientEmail = "zoe@example.TEST"), row(recipientEmail = "Ann@Example.test")))
        FinishSingleInvitations.finishSingleInvitations(h.steps, MapTried())
        assertEquals(listOf(listOf("zoe@example.test", "ann@example.test")), h.asked)
    }
}
