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
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The plain "is anything stashed" marker that keeps the encrypted
 * `note_pending_share` store off the launch path.
 *
 * ## Why these three cases and not a round trip
 *
 * A round trip would have to open the encrypted store, which needs the Android
 * Keystore — there is none under Robolectric. The round trip is covered on a
 * real device by `ShareIntentRepeatInstrumentedTest`. What is tested HERE is the
 * part that cannot be covered there without contriving an upgrade: that an
 * ABSENT marker is not mistaken for "empty".
 *
 * That distinction is the whole safety argument. An install upgrading from a
 * build without the marker can have a draft already sitting in the encrypted
 * file with nothing recorded in plain prefs. If absent read as empty, [take]
 * would short-circuit and the user's shared text would be stranded forever — the
 * exact failure [PendingShareStore] exists to prevent. Absent must fall through
 * to the encrypted store, pay the open once, and record the answer.
 */
/*
 * `sdk = [35]`, matching the other Robolectric tests here — the app targets 36
 * and Robolectric 4.14.1 has no framework jar for it. `application =
 * android.app.Application::class` keeps NoteApplication (and its Hilt graph,
 * its native library load and its launch-time prefetch) out of a unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PendingShareStoreMarkerTest {

    private lateinit var context: Context
    private lateinit var store: PendingShareStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Constructing is safe: both stores are `by lazy`, so nothing reaches
        // for the Keystore until something actually needs the ciphertext.
        store = PendingShareStore(context)
        marker().edit().clear().commit()
    }

    private fun marker() = context.getSharedPreferences("note_prefs", Context.MODE_PRIVATE)

    @Test
    fun `an absent marker does NOT read as empty`() {
        assertFalse(
            "absent must fall through to the encrypted store — an upgraded " +
                "install can hold a draft the marker has never seen",
            store.knownEmpty(),
        )
    }

    @Test
    fun `a marker saying present does not read as empty`() {
        marker().edit().putBoolean("pending_share_present", true).commit()
        assertFalse(store.knownEmpty())
    }

    @Test
    fun `only an explicit false reads as empty`() {
        marker().edit().putBoolean("pending_share_present", false).commit()
        assertTrue(store.knownEmpty())
    }

    @Test
    fun `the short circuit is what keeps the Keystore off the launch path`() {
        // With the marker explicitly false, take() and hasPending() must answer
        // without forcing the encrypted lazy. Under Robolectric there is no
        // Keystore, so if either one reached for it this would throw rather
        // than return — the assertion is that it returns at all.
        marker().edit().putBoolean("pending_share_present", false).commit()
        assertNull(store.take())
        assertFalse(store.hasPending())
    }
}
