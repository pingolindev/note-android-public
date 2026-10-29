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

package com.pingolin.note.collab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import okhttp3.OkHttpClient
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CollabSession exponential-backoff reconnect test.
 *
 * The production [CollabSession.handleDisconnect] schedules a reconnect
 * with `(1000 shl attempt).coerceAtMost(30_000L)` — so a mocked
 * WebSocketListener could assert that a second openSocket() happens after
 * ~1s. However the class takes a real `OkHttpClient` and constructs a real
 * [okhttp3.WebSocket] internally; there is no seam to inject a fake socket
 * without changing production code, so the tests below are ignored: they
 * need a local WebSocket server.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@MediumTest
class CollabSessionInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    @Ignore("Needs a real collab backend or a local MockWebServer-backed WebSocket stub: CollabSession builds its own WebSocket from a real OkHttpClient.")
    fun reconnectsWithExponentialBackoffAfterDisconnect() {
        // Sketch of the intended behaviour, preserved as documentation:
        //
        // val client = OkHttpClient()
        // val session = CollabSession(
        //     client = client,
        //     baseUrl = "https://localhost:0",
        //     noteId = "note-123",
        //     contentKey = ByteArray(32),
        // )
        // session.connect("")
        // // Trigger fake disconnect ...
        // // Assert a second openSocket() happens after ~1s.
        // session.destroy()
    }

    @Test
    @Ignore("CollabSession uses the separate uniffi_collab native library (not the pingolin_mobile one loaded by NoteApplication). Under HiltTestApplication the collab SDK .so isn't loaded, and the app has no central loadLibrary call the test path could use to load it.")
    fun canInstantiateCollabSession_withRealDependencies() {
        // Sketch of the check, for a harness that loads
        // uniffi_collab in a shared
        // Application class:
        //
        // val session = CollabSession(
        //     client = OkHttpClient(),
        //     baseUrl = "https://note.pingolindev.com/",
        //     noteId = "smoke-" + System.nanoTime(),
        //     contentKey = ByteArray(32),
        // )
        // assert(session.textFlow.value == "")
        // session.destroy()
    }
}
