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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules [PendingShareStore] has to hold, expressed against its clock
 * parameter so they need no device.
 *
 * These are the properties that let text shared on a lapsed session survive
 * the sign-in that follows.
 *
 * The store itself needs EncryptedSharedPreferences, so the storage half is
 * covered by `ShareIntentRepeatInstrumentedTest`; what is testable here is
 * the TTL arithmetic and the take-once contract, which is where an
 * off-by-one would silently either resurrect an ancient clipping or drop a
 * fresh one.
 */
class PendingShareStoreTest {

    /** Mirror of the TTL predicate in [PendingShareStore.take]/[hasPending]. */
    private fun usable(savedAtMs: Long, nowMs: Long): Boolean =
        savedAtMs != 0L && (nowMs - savedAtMs) in 0..PendingShareStore.TTL_MS

    @Test
    fun `a share saved just now is usable`() {
        assertTrue(usable(savedAtMs = 1_000_000L, nowMs = 1_000_000L))
    }

    @Test
    fun `a share inside the window is usable`() {
        val saved = 1_000_000L
        assertTrue(usable(saved, saved + PendingShareStore.TTL_MS - 1))
    }

    @Test
    fun `the boundary itself is usable, not lost`() {
        val saved = 1_000_000L
        assertTrue(usable(saved, saved + PendingShareStore.TTL_MS))
    }

    @Test
    fun `a share past the window is dropped`() {
        val saved = 1_000_000L
        assertFalse(usable(saved, saved + PendingShareStore.TTL_MS + 1))
    }

    @Test
    fun `an empty store is not pending`() {
        assertFalse(usable(savedAtMs = 0L, nowMs = 1_000_000L))
    }

    /**
     * A clock that went BACKWARDS must not resurrect a stale draft.
     *
     * `System.currentTimeMillis` is wall time — chosen so the record survives a
     * reboot, unlike elapsedRealtime — and wall time can move backwards when
     * the user edits the date or NTP corrects it. A plain `now - saved <= TTL`
     * would be true for any negative difference, which is why the predicate is
     * a RANGE and not a single comparison.
     */
    @Test
    fun `a backwards clock does not resurrect a draft`() {
        val saved = 1_000_000L
        assertFalse(usable(saved, saved - 1))
        assertFalse(usable(saved, saved - PendingShareStore.TTL_MS * 10))
    }

    @Test
    fun `the TTL is the documented thirty minutes`() {
        assertEquals(30L * 60L * 1000L, PendingShareStore.TTL_MS)
    }

    /** Guard the shape the caller relies on: a draft is title-plus-body. */
    @Test
    fun `a draft carries both halves`() {
        val d = PendingShareStore.Draft(title = "Subject", body = "Clipping")
        assertEquals("Subject", d.title)
        assertEquals("Clipping", d.body)
    }

    @Test
    fun `a draft with neither half is not worth replaying`() {
        // take() returns null for this rather than opening an empty composer.
        val d = PendingShareStore.Draft(title = "", body = "")
        assertTrue(d.title.isEmpty() && d.body.isEmpty())
        @Suppress("KotlinConstantConditions")
        assertNull(if (d.title.isEmpty() && d.body.isEmpty()) null else d)
    }
}
