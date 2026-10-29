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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** An id as this app keeps it: a UUID's bytes, lower-case, hyphenated. */
class AccountIdsTest {

    private val canonical = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val hex = canonical.replace("-", "")

    /** Every spelling the SDK's parser (uuid 1.x) takes. */
    private val spellings = listOf(
        canonical, canonical.uppercase(), "0A1b2C3d-4E5f-4A6b-8C7d-9E0f1A2b3C4d",
        hex, hex.uppercase(),
        "{$canonical}", "{${canonical.uppercase()}}",
        "urn:uuid:$canonical", "urn:uuid:${canonical.uppercase()}",
    )

    /** No UUID in any spelling the SDK takes. */
    private val others = listOf(
        null, "", "me", "user-dana",
        " $canonical", "$canonical ", "$canonical\n", "\t$canonical",
        canonical.replace("-", "_"), canonical.dropLast(1), canonical + "0", canonical.replace("4e5f", "4e5g"),
        "0a1b2c3d4e5f-4a6b-8c7d-9e0f1a2b3c4d", "0a1b2c3d-4e5f4a6b-8c7d-9e0f1a2b3c4d", "${hex}0",
        "0a1b2c3-d4e5f-4a6b-8c7d-9e0f1a2b3c4d",
        "{$hex}", "{$canonical", "$canonical}", "[$canonical]", "{ $canonical}", "{{$canonical}}",
        "URN:UUID:$canonical", "Urn:Uuid:$canonical", "urn:uuid:$hex", "urn:uuid:{$canonical}",
        "urn:uuid: $canonical", "uuid:$canonical",
        // Digits that aren't ASCII: Arabic-Indic zero and a fullwidth one.
        canonical.replaceFirst('0', '٠'), canonical.replaceFirst('1', '１'),
    )

    @Test
    fun `every spelling the SDK takes is its canonical form`() {
        for (spelling in spellings) {
            assertEquals(spelling, canonical, AccountIds.canonical(spelling))
            assertEquals(spelling, canonical, AccountIds.parse(spelling))
        }
    }

    @Test
    fun `anything else is no id, and parse refuses it rather than naming another`() {
        for (other in others) {
            assertNull(other.toString(), AccountIds.canonical(other))
            assertThrows(other.toString(), AccountIds.NotAnAccountId::class.java) { AccountIds.parse(other) }
        }
    }

    @Test
    fun `the narrower rule takes 8-4-4-4-12 in any case or 32 hex, and nothing else`() {
        for (spelling in spellings.take(5)) {
            assertEquals(spelling, canonical, AccountIds.strict(spelling))
        }
        for (other in spellings.drop(5) + others) {
            assertNull(other.toString(), AccountIds.strict(other))
        }
    }

    @Test
    fun `two spellings are the same id only with the same bytes`() {
        assertTrue(AccountIds.same(canonical.uppercase(), hex))
        assertTrue(AccountIds.same("{$canonical}", "urn:uuid:${canonical.uppercase()}"))
        assertFalse(AccountIds.same(canonical, "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"))
        assertFalse("no id is nobody's", AccountIds.same("me", "me"))
        assertFalse(AccountIds.same(null, canonical))
        assertFalse(AccountIds.same(canonical, null))
    }
}
