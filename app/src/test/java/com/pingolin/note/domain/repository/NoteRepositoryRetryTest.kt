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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 409 refetch-and-retry policy for editing a materialized shared
 * note, matching the iOS client. The helper is generic/pure, so this
 * exercises it without the network or native crypto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteRepositoryRetryTest {

    private data class Resp(val version: Long, val status: Int)

    @Test
    fun `success on first try does not refetch or retry`() = runTest {
        val puts = mutableListOf<Long>()
        var liveCalls = 0
        val result = NoteRepository.retryingOnVersionConflict<Resp?>(
            expectedVersion = 3,
            statusOf = { it?.status },
            liveVersion = { liveCalls++; 99 },
            put = { v -> puts += v; Resp(v, 200) },
        )
        assertEquals(listOf(3L), puts)
        assertEquals(0, liveCalls)
        assertEquals(Resp(3, 200), result)
    }

    @Test
    fun `409 refetches the live version and retries once`() = runTest {
        // Materialized share carries version=1; owner is live at 7.
        val puts = mutableListOf<Long>()
        val result = NoteRepository.retryingOnVersionConflict<Resp?>(
            expectedVersion = 1,
            statusOf = { it?.status },
            liveVersion = { 7 },
            put = { v -> puts += v; Resp(v, if (v == 1L) 409 else 200) },
        )
        assertEquals("retried with the live version", listOf(1L, 7L), puts)
        assertEquals(Resp(7, 200), result)
    }

    @Test
    fun `409 with unavailable live version returns the first result`() = runTest {
        val puts = mutableListOf<Long>()
        val result = NoteRepository.retryingOnVersionConflict<Resp?>(
            expectedVersion = 1,
            statusOf = { it?.status },
            liveVersion = { null }, // refetch failed
            put = { v -> puts += v; Resp(v, 409) },
        )
        assertEquals("no retry when live version is unknown", listOf(1L), puts)
        assertEquals(Resp(1, 409), result)
    }

    @Test
    fun `a second 409 after refetch propagates and does not retry again`() = runTest {
        val puts = mutableListOf<Long>()
        val result = NoteRepository.retryingOnVersionConflict<Resp?>(
            expectedVersion = 1,
            statusOf = { it?.status },
            liveVersion = { 7 },
            put = { v -> puts += v; Resp(v, 409) }, // always conflicts
        )
        assertEquals("retried exactly once", listOf(1L, 7L), puts)
        assertEquals(Resp(7, 409), result)
    }

    @Test
    fun `null result (network error) is not treated as a conflict`() = runTest {
        val puts = mutableListOf<Long>()
        var liveCalls = 0
        val result = NoteRepository.retryingOnVersionConflict<Resp?>(
            expectedVersion = 2,
            statusOf = { it?.status },
            liveVersion = { liveCalls++; 7 },
            put = { v -> puts += v; null },
        )
        assertEquals(listOf(2L), puts)
        assertEquals(0, liveCalls)
        assertNull(result)
    }
}
