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

package com.pingolin.note.ui.web

import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.ui.web.ColdOpenSession.Next
import com.pingolin.note.util.SessionManager
import com.pingolin.note.util.TokenAuthenticator
import com.pingolin.note.util.TokenAuthenticator.RefreshOutcome
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A cold open's session, settled before the page loads: refreshed only when the page will boot on
 * a stale token, the load held until then, and a refresh that doesn't answer in time no reason
 * not to load.
 *
 * WHY THESE EXIST
 *   A cold open on an expired token used to hand the page a dead one, and the page's boot sent
 *   the person to the account portal's password prompt (reproduced on an A50, twice). The owner's
 *   rule: users are never asked for their password.
 */
class ColdOpenSessionTest {

    private val now = 1_800_000_000L
    private val b64 = java.util.Base64.getUrlEncoder().withoutPadding()

    private fun expiringAt(exp: Long) =
        b64.encodeToString("""{"alg":"EdDSA"}""".toByteArray()) + "." +
            b64.encodeToString("""{"exp":$exp}""".toByteArray()) + ".c2ln"

    private val expired = expiringAt(now - 600)
    private val aboutTo = expiringAt(now + 30)
    private val valid = expiringAt(now + 900)

    @Test
    fun `refreshes first only when the page will boot on a stale token`() {
        assertTrue(ColdOpenSession.mustRefreshFirst(booted = false, accessToken = expired, nowSeconds = now))
        assertTrue(ColdOpenSession.mustRefreshFirst(booted = false, accessToken = aboutTo, nowSeconds = now))
        assertTrue(ColdOpenSession.mustRefreshFirst(booted = false, accessToken = "unreadable", nowSeconds = now))
        assertTrue(ColdOpenSession.mustRefreshFirst(booted = false, accessToken = null, nowSeconds = now))
        assertFalse("a good token loads at once", ColdOpenSession.mustRefreshFirst(booted = false, accessToken = valid, nowSeconds = now))
        assertFalse(
            "a document booted in this process doesn't check again",
            ColdOpenSession.mustRefreshFirst(booted = true, accessToken = expired, nowSeconds = now),
        )
    }

    @Test
    fun `refreshed or no answer loads, a refusal goes to the native sign-in`() = runBlocking {
        val asked = mutableListOf<Pair<String?, Long>>()
        fun answering(outcome: RefreshOutcome): (String?, Long) -> RefreshOutcome = { stale, waitMs ->
            asked += stale to waitMs
            outcome
        }
        assertEquals(Next.LOAD, ColdOpenSession.settle(expired, answering(RefreshOutcome.REFRESHED)))
        assertEquals("no answer in time: the page's offline path", Next.LOAD, ColdOpenSession.settle(expired, answering(RefreshOutcome.UNREACHABLE)))
        assertEquals(Next.SIGN_IN, ColdOpenSession.settle(expired, answering(RefreshOutcome.REFUSED)))
        assertEquals(
            "the one refresher is asked with the stale token, and the cold open's wait",
            List(3) { expired to ColdOpenSession.WAIT_MS },
            asked,
        )
    }

    @Test
    fun `a refresh that hasn't answered by the bound is no answer, and the load goes on`() = runBlocking {
        val started = System.nanoTime()
        val next = ColdOpenSession.settle(expired, { _, _ ->
            Thread.sleep(30_000)
            RefreshOutcome.REFRESHED
        }, boundMs = 200)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(Next.LOAD, next)
        assertTrue("went on after ${tookMs}ms", tookMs < 5_000)
        assertEquals(6_000L, ColdOpenSession.WAIT_MS + 1_000L)
    }

    @Test
    fun `a held load waits, the newest one wins, and it goes once when released`() {
        val ran = mutableListOf<String>()
        val held = HeldLoad()
        held.run { ran += "at once" }
        held.hold()
        held.run { ran += "first" }
        held.run { ran += "newest" }
        assertTrue(held.holding)
        assertEquals("nothing loads while held", listOf("at once"), ran)
        held.release()
        assertEquals(listOf("at once", "newest"), ran)
        held.release()
        held.run { ran += "after" }
        assertEquals(listOf("at once", "newest", "after"), ran)
    }

    @Test
    fun `a restore that holds no page loads the intent's page instead`() {
        for (nothing in listOf<Int?>(null, 0)) {
            var navigated = 0
            assertFalse(ColdOpenSession.restoreOrNavigate({ nothing }, { navigated++ }))
            assertEquals("$nothing", 1, navigated)
        }
        var navigated = 0
        assertTrue(ColdOpenSession.restoreOrNavigate({ 3 }, { navigated++ }))
        assertEquals("a real history is restored, and nothing else loads", 0, navigated)
    }

    /**
     * Review of d21b3b7: an activity recreated during a cold open's hold (a theme flip, a font
     * scale, split screen), or rebuilt from Recents after a process death, restores a state saved
     * before any load. Through the hold, it loads the intent's page once the session is settled,
     * never a blank screen.
     */
    @Test
    fun `an activity restored during the hold loads its page once the session is settled`() {
        val loads = mutableListOf<String>()
        val held = HeldLoad()
        held.hold()
        held.run { ColdOpenSession.restoreOrNavigate({ null }, { loads += "the intent's page" }) }
        assertEquals("nothing loads while held", emptyList<String>(), loads)
        held.release()
        assertEquals(listOf("the intent's page"), loads)
    }

    @Test
    fun `a dropped hold loads nothing`() {
        val ran = mutableListOf<String>()
        val held = HeldLoad()
        held.hold()
        held.run { ran += "load" }
        held.drop()
        held.release()
        assertEquals(emptyList<String>(), ran)
        assertFalse(held.holding)
    }

    /**
     * The activity's side, read from the source: the seed decision comes before the WebView is
     * obtained, every load (onCreate's, a restore's, a new intent's) goes through the held load,
     * and the refusal goes to the native sign-in.
     */
    @Test
    fun `every load of the activity waits for a cold open's session (read from the source)`() {
        val activity = File("src/main/java/com/pingolin/note/ui/web/WebAppActivity.kt").readText()
        val decide = activity.indexOf("val mustRefreshFirst = ColdOpenSession.mustRefreshFirst(")
        val obtain = activity.indexOf("web = RetainedWebView.obtain(this)")
        assertTrue(decide in 0 until obtain)
        assertTrue(activity.contains("if (savedInstanceState == null) heldLoad.run { navigateTo(urlFor(intent)) }"))
        assertTrue(activity.contains("heldLoad.run {\n            if (!RetainedWebView.isBootedOn(originOf(BuildConfig.NOTE_API_BASE_URL))) {\n                ColdOpenSession.restoreOrNavigate("))
        assertTrue(activity.contains("restore = { web.restoreState(savedInstanceState)?.size },\n                    navigate = { navigateTo(urlFor(intent)) },"))
        assertTrue(activity.contains("heldLoad.run { navigateTo(urlFor(intent)) }\n    }"))
        assertEquals(
            "every loadUrl and restoreState is inside navigateTo or a held load",
            2,
            Regex("""web\.loadUrl\(""").findAll(activity).count(),
        )
        assertTrue(activity.contains("ColdOpenSession.Next.SIGN_IN -> toNativeSignIn()"))
        // The one refresher gets the page's jar on main before the cold open's refresh, which
        // adopts a later pair of the session itself (TokenAuthenticator.refreshSession).
        val installed = activity.indexOf("        installPageSessionJar()\n        val staleToken = sessionManager.getAccessToken()")
        assertTrue(installed in 0 until decide)
        assertTrue(activity.contains("val next = ColdOpenSession.settle(staleToken, { stale, waitMs ->\n                    tokenAuthenticator.get().refreshSession(stale, waitMs)\n                })"))
        assertTrue(activity.contains("internal fun installPageSessionJar() {\n            val cookies = try {\n                CookieManager.getInstance()"))
    }

    // ---- The cold open adopts a later pair of the session in the jar before any refresh ----

    private val user = "0b6f3d1e-5a4c-4e8b-9f2a-1c3d5e7f9a0b"
    private val sid = "7d9e1f3a-2b4c-4d6e-8f0a-1b2c3d4e5f6a"

    /** An access token as auth mints them (services/jwt.rs): `sub`, `sid`, `iat`, `exp`. */
    private fun token(iat: Long, exp: Long, sessionId: String = sid, sub: String = user) =
        b64.encodeToString("""{"alg":"EdDSA"}""".toByteArray()) + "." +
            b64.encodeToString("""{"sub":"$sub","sid":"$sessionId","iat":$iat,"exp":$exp}""".toByteArray()) + ".c2ln"

    /** The stored token: the app's own, lapsed. */
    private val storedStale = token(now - 960, now - 60)

    /** The jar's, refreshed in the page after it (the portal's: a browser's token, a day). */
    private val portal = token(now - 600, now + 85_800)

    /** Review of 1008a92: the cold open refreshes through the one refresher, which adopts a later
     *  pair of the session from the page's jar before it picks a refresh token. End to end through
     *  the REAL TokenAuthenticator: the jar's pair stored in one write, nothing asked of the server,
     *  and the session's older refresh token never sent. */
    @Test(timeout = 20_000)
    fun `the cold open adopts a later pair from the jar through the real refresher and sends nothing`() {
        val sessionManager = mockk<SessionManager>()
        val authApi = mockk<AuthApi>()
        val access = java.util.concurrent.atomic.AtomicReference<String?>(storedStale)
        val refreshToken = java.util.concurrent.atomic.AtomicReference<String?>("app-refresh")
        every { sessionManager.getAccessToken() } answers { access.get() }
        every { sessionManager.getRefreshToken() } answers { refreshToken.get() }
        every { sessionManager.saveTokens(any(), any()) } answers {
            access.set(firstArg())
            refreshToken.set(secondArg())
        }
        every { sessionManager.clearAuthOnly() } just Runs
        val authenticator = TokenAuthenticator(
            sessionManager,
            javax.inject.Provider { authApi },
            setOf("note.example.test"),
            { com.pingolin.note.util.PageSessionJar.Held(portal, "portal-refresh") },
            { now },
        )
        val next = runBlocking {
            ColdOpenSession.settle(storedStale, { stale, waitMs -> authenticator.refreshSession(stale, waitMs) })
        }
        assertEquals(Next.LOAD, next)
        assertEquals(portal, access.get())
        assertEquals("portal-refresh", refreshToken.get())
        coVerify(exactly = 0) { authApi.refreshToken(any(), any()) }
    }
}
