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

package com.pingolin.note.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline queue replay — there is no injectable NetworkMonitor.
 *
 * Sync gating is done entirely by WorkManager's
 * `NetworkType.CONNECTED` constraint (see
 * [com.pingolin.note.worker.SyncWorker.enqueueImmediateSync]) rather
 * than a NetworkMonitor abstraction. To simulate offline → online a
 * test would need to either:
 *   a) Airplane-mode the device (requires `adb shell cmd connectivity …`
 *      plus root for some OEMs).
 *   b) Inject a fake NetworkMonitor in production code.
 *
 * The "dirty flag stays set across reboots and flushes on next sync"
 * behaviour is covered by the JVM unit tests `SyncRepositoryTest` and
 * `NoteRepositoryTest.retryDirtyItems`. This class is a placeholder
 * for a Hilt override of the network gate.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class OfflineQueueInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    @Ignore("No injectable NetworkMonitor in production code, and a test can't switch some devices to airplane mode without root.")
    fun offlineEdits_replayOnReconnect() {
        // Sketch of the flow, given an injectable NetworkMonitor:
        //   1. Override NetworkMonitor with a fake returning offline.
        //   2. Create a note → verify isDirty=1 in Room.
        //   3. Flip NetworkMonitor to online, trigger sync.
        //   4. Verify the note was pushed and isDirty cleared.
    }
}
