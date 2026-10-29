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

package com.pingolin.note.data.local

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ConvertersTest {

    private lateinit var converters: Converters

    @Before
    fun setup() {
        converters = Converters()
    }

    // =============================================================================
    // fromTimestamp TESTS
    // =============================================================================

    @Test
    fun `fromTimestamp_validTimestamp_returnsInstant`() {
        val millis = 1704067200000L // 2024-01-01T00:00:00Z
        val result = converters.fromTimestamp(millis)

        assertNotNull(result)
        assertEquals(Instant.ofEpochMilli(millis), result)
    }

    @Test
    fun `fromTimestamp_nullValue_returnsNull`() {
        val result = converters.fromTimestamp(null)
        assertNull(result)
    }

    @Test
    fun `fromTimestamp_zeroTimestamp_returnsEpoch`() {
        val result = converters.fromTimestamp(0L)

        assertNotNull(result)
        assertEquals(Instant.EPOCH, result)
    }

    @Test
    fun `fromTimestamp_negativeTimestamp_returnsInstantBeforeEpoch`() {
        val result = converters.fromTimestamp(-1000L)

        assertNotNull(result)
        assertEquals(Instant.ofEpochMilli(-1000L), result)
    }

    // =============================================================================
    // toTimestamp TESTS
    // =============================================================================

    @Test
    fun `toTimestamp_validInstant_returnsMillis`() {
        val instant = Instant.ofEpochMilli(1704067200000L)
        val result = converters.toTimestamp(instant)

        assertNotNull(result)
        assertEquals(1704067200000L, result)
    }

    @Test
    fun `toTimestamp_nullInstant_returnsNull`() {
        val result = converters.toTimestamp(null)
        assertNull(result)
    }

    @Test
    fun `toTimestamp_epoch_returnsZero`() {
        val result = converters.toTimestamp(Instant.EPOCH)

        assertNotNull(result)
        assertEquals(0L, result)
    }

    // =============================================================================
    // ROUND-TRIP TESTS
    // =============================================================================

    @Test
    fun `roundTrip_timestampToInstantAndBack_preservesValue`() {
        val originalMillis = 1704067200000L
        val instant = converters.fromTimestamp(originalMillis)
        val resultMillis = converters.toTimestamp(instant)

        assertEquals(originalMillis, resultMillis)
    }

    @Test
    fun `roundTrip_instantToTimestampAndBack_preservesValue`() {
        val originalInstant = Instant.now()
        val millis = converters.toTimestamp(originalInstant)
        val resultInstant = converters.fromTimestamp(millis)

        // Instant.now() has nanosecond precision but millis drops sub-millis
        assertEquals(originalInstant.toEpochMilli(), resultInstant?.toEpochMilli())
    }

    @Test
    fun `roundTrip_nullRoundTrip_preservesNull`() {
        val millis = converters.toTimestamp(null)
        val instant = converters.fromTimestamp(millis)

        assertNull(millis)
        assertNull(instant)
    }
}
