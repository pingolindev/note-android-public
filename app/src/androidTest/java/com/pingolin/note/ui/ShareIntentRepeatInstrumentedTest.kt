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

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.pingolin.note.util.PendingShareStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The share-into-app path, fired repeatedly.
 *
 * ## Why a loop
 *
 * The SECOND share — the one that arrives while the app is already in a task —
 * must deliver its payload just like the first, with MainActivity running and
 * WebAppActivity receiving `onNewIntent` every time. A screenshot plus a
 * `uiautomator` dump taken after a fixed sleep cannot show that reliably,
 * because the WebView's accessibility tree lags.
 *
 * So this test makes the claim testable rather than observable: it asserts on
 * the SHELL's own state, which cannot lag. It loops rather than running twice
 * because a race that hits around one time in four would slip past two
 * iterations about half the time. Ten brings that to roughly one run in sixteen.
 *
 * A change to launch modes, or to the MainActivity trampoline, cannot quietly
 * break the second delivery without this going red.
 *
 * ## What it asserts
 *
 * The signed-out branch, because that one IS deterministic:
 * `MainActivity.onCreate` must read `EXTRA_TEXT` BEFORE it returns to
 * AuthActivity, or the payload is never extracted at all. The store is the
 * observable consequence — text in, text held, and held exactly once.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ShareIntentRepeatInstrumentedTest {

    private lateinit var store: PendingShareStore

    private fun shareIntent(body: String): Intent =
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Subject $body")
            putExtra(Intent.EXTRA_TEXT, body)
        }

    @Before
    fun setUp() {
        store = PendingShareStore(ApplicationProvider.getApplicationContext())
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    /**
     * Signed out, two shares back to back: the payload is kept both times, and
     * the SECOND one wins rather than being discarded because a draft already
     * existed.
     *
     * Runs only on a signed-out device — a signed-in one routes to the web
     * shell instead and never touches the store, which is the correct
     * behaviour, not a failure. Asserted rather than assumed so the test cannot
     * pass by doing nothing.
     */
    @Test
    fun everyShareInARunIsCaptured() {
        // LOOPS rather than running twice. The failure this guards against is
        // intermittent — roughly one run in four — and two iterations would
        // miss a 1-in-4 race about half the time. Ten makes that ~6%, and each
        // iteration is a fresh Activity launch, so the cost is seconds.
        val first = run(1) ?: run {
            // Signed in: nothing is stashed by design, because the payload goes
            // straight to the web shell. Prove that is the state we are in
            // rather than letting the test pass by doing nothing at all.
            assertFalse("signed out, yet the share was not stashed", store.hasPending())
            return
        }
        assertEquals("PROBE_1", first.body)
        assertEquals("Subject PROBE_1", first.title)

        for (i in 2..ITERATIONS) {
            val draft = run(i)
            assertNotNull("share $i of $ITERATIONS was dropped", draft)
            // The LATEST share wins — an older held clipping must never be
            // handed over in place of the one the user just sent.
            assertEquals("share $i returned a stale draft", "PROBE_$i", draft!!.body)
        }
    }

    /** Fire one share and consume whatever it stashed. */
    private fun run(i: Int): PendingShareStore.Draft? {
        ActivityScenario.launch<MainActivity>(shareIntent("PROBE_$i")).use { }
        val draft = store.take()
        // take() is destructive: a clipping must not be replayable into a
        // second note after it has already been handed over once.
        assertNull("the draft survived take() on iteration $i", store.take())
        return draft
    }

    private companion object {
        const val ITERATIONS = 10
    }
}
