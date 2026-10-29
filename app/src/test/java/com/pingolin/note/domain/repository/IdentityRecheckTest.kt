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

import android.content.Context
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The client tests for the re-check limits: at most one re-check per window,
 * one pending flag for whatever was refused, and nothing left over after a
 * sign-out. Virtual time throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityRecheckTest {

    private val scope = TestScope()
    private val loader = mockk<PreviousIdentityLoader>()
    private val itemDao = mockk<ItemDao>()
    private val sessionManager = mockk<SessionManager>()
    private var generation = 0L
    private var requests = 0
    private var reloads = 0
    private var hold: CompletableDeferred<Unit>? = null
    private var opensSomethingNew = false
    private lateinit var recheck: IdentityRecheck

    private val window = IdentityRecheck.WINDOW_MS

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { loader.generation() } answers { generation }
        every { sessionManager.isLoggedIn() } returns true
        coEvery { itemDao.countUndecryptedItems() } returns 0
        coEvery { loader.recheck() } coAnswers {
            requests++
            hold?.await()
            opensSomethingNew
        }
        recheck = IdentityRecheck(loader, itemDao, sessionManager, mockk<Context>(relaxed = true)).apply {
            clock = { this@IdentityRecheckTest.scope.testScheduler.currentTime }
            this.scope = this@IdentityRecheckTest.scope
            afterNewIdentity = { reloads++ }
        }
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `many nudges inside the window make exactly one request, when the window ends`() {
        recheck.onNudge()
        scope.runCurrent()
        assertEquals(1, requests)

        repeat(12) {
            scope.advanceTimeBy(10_000)
            recheck.onNudge()
            scope.runCurrent()
        }
        assertEquals("nothing inside the window", 1, requests)

        scope.advanceTimeBy(window - 12 * 10_000 - 1)
        scope.runCurrent()
        assertEquals(1, requests)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertEquals("one request when the window ends", 2, requests)

        scope.advanceTimeBy(10 * window)
        scope.runCurrent()
        assertEquals("and nothing after it", 2, requests)
    }

    @Test
    fun `a nudge during a running re-check makes exactly one follow-up`() {
        hold = CompletableDeferred()
        recheck.onNudge()
        scope.runCurrent()
        assertEquals(1, requests)

        repeat(5) { recheck.onNudge() }
        scope.runCurrent()
        assertEquals("the running one is not doubled", 1, requests)

        hold!!.complete(Unit)
        scope.runCurrent()
        scope.advanceUntilIdle()
        assertEquals("exactly one follow-up", 2, requests)
    }

    @Test
    fun `sign-out clears the pending flag and the window`() {
        recheck.onNudge()
        scope.runCurrent()
        repeat(3) { recheck.onNudge() }
        scope.runCurrent()

        // LocalDataWiper: the loader's reset (new generation), then clear().
        generation++
        recheck.clear()
        scope.advanceTimeBy(2 * window)
        scope.runCurrent()
        assertEquals("the refused nudges never run for the next account", 1, requests)

        recheck.onNudge()
        scope.runCurrent()
        assertEquals("no window carried over", 2, requests)
    }

    @Test
    fun `a pending flag from before an account change is dropped even without clear`() {
        recheck.onNudge()
        scope.runCurrent()
        recheck.onNudge()
        scope.runCurrent()

        generation++
        scope.advanceTimeBy(2 * window)
        scope.runCurrent()
        assertEquals(1, requests)
    }

    @Test
    fun `the foreground re-checks only while something is locked`() {
        recheck.onForeground()
        scope.runCurrent()
        assertEquals("nothing locked, nothing pending: no request", 0, requests)

        coEvery { itemDao.countUndecryptedItems() } returns 3
        recheck.onForeground()
        scope.runCurrent()
        assertEquals(1, requests)
    }

    @Test
    fun `only a re-check that opened something new re-decrypts`() {
        recheck.onNudge()
        scope.runCurrent()
        assertEquals(0, reloads)

        opensSomethingNew = true
        scope.advanceTimeBy(window)
        recheck.onNudge()
        scope.runCurrent()
        assertEquals(1, reloads)
    }

    @Test
    fun `a sign-out during the re-check re-decrypts nothing`() {
        hold = CompletableDeferred()
        opensSomethingNew = true
        recheck.onNudge()
        scope.runCurrent()

        generation++ // the wipe's loader reset, while the fetch is out
        hold!!.complete(Unit)
        scope.runCurrent()
        assertEquals(0, reloads)
    }

    @Test
    fun `signed out, a nudge makes no request`() {
        every { sessionManager.isLoggedIn() } returns false
        recheck.onNudge()
        scope.runCurrent()
        assertEquals(0, requests)
    }

    @Test
    fun `every nudge is handed to the page`() {
        val seen = mutableListOf<Unit>()
        val job = scope.backgroundScope.launchCollect(recheck, seen)
        scope.runCurrent()
        recheck.onNudge()
        scope.runCurrent()
        assertEquals(1, seen.size)
        job.cancel()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(r: IdentityRecheck, into: MutableList<Unit>) =
        launch { r.pageEvents.collect { into += it } }
}
