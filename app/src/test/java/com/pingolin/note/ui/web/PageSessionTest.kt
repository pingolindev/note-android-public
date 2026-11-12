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
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.TokenResponse
import com.pingolin.note.util.PageSessionJar
import com.pingolin.note.util.SessionManager
import com.pingolin.note.util.TokenAuthenticator
import com.pingolin.note.util.TokenAuthenticator.AdoptOutcome
import com.pingolin.note.util.TokenAuthenticator.RefreshOutcome
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Provider
import kotlin.concurrent.thread

/**
 * The page's API requests, made to go out on a good session ([PageSession]): a jar refreshed in the
 * page adopted, a stale stored token refreshed through the one refresher, and the cookie jar
 * brought up to the stored token, with nothing more than a comparison when both are already good.
 *
 * WHY THESE EXIST
 *   The page never refreshes, and its once-a-minute account check signs out on a dead cookie: a
 *   long read, or a return after the token's life, ended at the account portal's password prompt.
 *   And the account portal, run in the same WebView, refreshes the session on every load: writing
 *   the app's older pair back over it handed the page a revoked token (review of 04f37ce).
 */
class PageSessionTest {

    private val origin = "https://note.example.test"
    private val api = "$origin/api/v1/auth/me"
    private val now = 1_800_000_000L
    private val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
    private val user = "0b6f3d1e-5a4c-4e8b-9f2a-1c3d5e7f9a0b"
    private val sessionId = "7d9e1f3a-2b4c-4d6e-8f0a-1b2c3d4e5f6a"

    /** An access token as auth mints them (services/jwt.rs): `sub`, `sid`, `iat`, `exp`. */
    private fun token(iat: Long, exp: Long, tag: String, sid: String = sessionId, sub: String = user) =
        b64.encodeToString("""{"alg":"EdDSA"}""".toByteArray()) + "." +
            b64.encodeToString("""{"sub":"$sub","sid":"$sid","iat":$iat,"exp":$exp,"t":"$tag"}""".toByteArray()) + ".c2ln"

    // The app's own, a native token's 15 minutes each, oldest first.
    private val expired = token(now - 960, now - 60, "expired")
    private val nearly = token(now - 870, now + 30, "nearly")
    private val fresh = token(now - 10, now + 890, "fresh")
    private val renewed = token(now, now + 900, "renewed")

    /** Refreshed in the page after `fresh` (the account portal's: a browser's token, a day). */
    private val portal = token(now - 5, now + 86_395, "portal")

    private var stored: String? = fresh
    private var storedReads = 0
    private var jar: String? = fresh
    private var jarRefresh: String? = "portal-refresh"

    /** The one-read pair, when a test makes it differ from [jar] (the jar moved between reads). */
    private var onePairRead: (() -> PageSessionJar.Held)? = null
    private val jarReads = mutableListOf<String>()
    private val refreshes = mutableListOf<Pair<String, Long>>()
    private var answer: () -> RefreshOutcome = {
        stored = renewed
        RefreshOutcome.REFRESHED
    }
    private val adoptions = mutableListOf<Triple<String, String, Long>>()
    private var adoptAnswer: (access: String) -> AdoptOutcome = { access ->
        stored = access
        AdoptOutcome.ADOPTED
    }
    private val reseeds = mutableListOf<Pair<String, Long>>()
    private var reseedConfirms = true
    private var signIns = 0

    private fun session() = PageSession(
        noteOrigin = origin,
        storedToken = {
            storedReads++
            stored
        },
        jarToken = { url ->
            jarReads += url
            jar
        },
        // One read of both halves, as the shell reads them: whatever the jar holds now.
        jarPair = { onePairRead?.invoke() ?: PageSessionJar.Held(jar, jarRefresh) },
        refresh = { stale, waitMs ->
            refreshes += stale to waitMs
            answer()
        },
        adopt = { access, refresh, waitMs ->
            adoptions += Triple(access, refresh, waitMs)
            adoptAnswer(access)
        },
        reseed = { access, waitMs ->
            reseeds += access to waitMs
            if (reseedConfirms) jar = access
            reseedConfirms
        },
        signIn = { signIns++ },
        nowSeconds = { now },
    )

    /** [check]'s log line, its timing made fixed. */
    private fun line(check: PageSession.Check, parked: Boolean = false) =
        check.copy(tookMs = 7).describe(parked)

    @Test
    fun `fresh and already in the jar, a request costs the comparison and nothing else`() {
        val s = session()
        repeat(3) { assertSame(PageSession.FRESH, s.beforeRequest(api)) }
        assertEquals(emptyList<Pair<String, Long>>(), refreshes)
        assertEquals(emptyList<Pair<String, Long>>(), reseeds)
        assertEquals(emptyList<Triple<String, String, Long>>(), adoptions)
        assertEquals(0, signIns)
        assertEquals("one jar read a request", List(3) { api }, jarReads)
        assertNull(PageSession.FRESH.describe())
    }

    @Test
    fun `a jar behind a fresh stored token is brought up to it, with no refresh`() {
        for (behind in listOf(expired, nearly, "an unreadable one", null)) {
            jar = behind
            reseeds.clear()
            val check = session().beforeRequest("$origin/api/v1/items?since=1")!!
            assertEquals(PageSession.Check(jar = PageSession.Jar.BEHIND, cookie = PageSession.Cookie.RESEEDED, tookMs = check.tookMs), check)
            assertEquals(listOf(fresh to PageSession.RESEED_WAIT_MS), reseeds)
            assertEquals("page cookie behind the stored session: cookie re-seeded (7ms)", line(check))
        }
        assertEquals(emptyList<Pair<String, Long>>(), refreshes)
        assertEquals(emptyList<Triple<String, String, Long>>(), adoptions)
    }

    @Test
    fun `an expired, nearly expired or unreadable stored token is refreshed first, then the jar brought up`() {
        for (stale in listOf(expired, nearly, "unreadable")) {
            stored = stale
            jar = stale
            refreshes.clear()
            reseeds.clear()
            val check = session().beforeRequest(api)!!
            assertEquals(RefreshOutcome.REFRESHED, check.refreshed)
            assertEquals(PageSession.Cookie.RESEEDED, check.cookie)
            assertEquals(listOf(stale to PageSession.REFRESH_WAIT_MS), refreshes)
            assertEquals("the stored pair after the refresh", listOf(renewed to PageSession.RESEED_WAIT_MS), reseeds)
            assertEquals("stale session before a page request: refresh REFRESHED, cookie re-seeded (7ms)", line(check))
        }
        assertEquals(5_000L, PageSession.REFRESH_WAIT_MS)
        assertEquals(5_000L, PageSession.ADOPT_WAIT_MS)
        assertEquals(1_000L, PageSession.RESEED_WAIT_MS)
    }

    /** Review of 04f37ce: a stale token written into the jar can't help the request. */
    @Test
    fun `a refresh with no answer writes nothing, whatever the jar holds`() {
        stored = expired
        answer = { RefreshOutcome.UNREACHABLE }
        for (inJar in listOf(expired, "an unreadable one", null)) {
            jar = inJar
            val check = session().beforeRequest(api)!!
            assertEquals(PageSession.Check(jar = check.jar, refreshed = RefreshOutcome.UNREACHABLE, cookie = PageSession.Cookie.LEFT, tookMs = check.tookMs), check)
            assertEquals("stale session before a page request: refresh UNREACHABLE, cookie left as it is (7ms)", line(check))
        }
        assertEquals(emptyList<Pair<String, Long>>(), reseeds)
        assertEquals(0, signIns)
    }

    @Test
    fun `a refused refresh asks for the native sign-in, writes nothing, and the request is answered here`() {
        stored = expired
        jar = expired
        answer = {
            stored = null
            RefreshOutcome.REFUSED
        }
        val check = session().beforeRequest(api)!!
        assertEquals(RefreshOutcome.REFUSED, check.refreshed)
        assertTrue(check.refused)
        assertEquals(1, signIns)
        assertEquals(emptyList<Pair<String, Long>>(), reseeds)
        assertEquals("stale session before a page request: refresh REFUSED, native sign-in, answered 503 (7ms)", line(check))
        assertEquals(
            "stale session before a page request: refresh REFUSED, answered 503, sign-in at the next open (parked, 7ms)",
            line(check, parked = true),
        )
    }

    /** Review of 04f37ce: the page takes a 503 for a passing failure (offlineSession.ts
     *  classifyFailure: only a JSON 401 or 403 is "denied"), so it doesn't wipe and reload. */
    @Test
    fun `the answer to a refused request is a 503 in auth's error shape`() {
        assertEquals(503, PageSession.SESSION_ENDED_STATUS)
        val body = org.json.JSONObject(PageSession.SESSION_ENDED_BODY)
        assertEquals(false, body.getBoolean("success"))
        assertTrue(body.has("error"))
        val source = File("src/main/java/com/pingolin/note/ui/web/PageSession.kt").readText()
        assertTrue(source.contains("\"application/json\",\n                \"utf-8\",\n                SESSION_ENDED_STATUS,"))
        assertTrue(source.contains("mapOf(\"Cache-Control\" to \"no-store\")"))
        for (refused in listOf(RefreshOutcome.REFUSED)) assertTrue(PageSession.Check(refreshed = refused).refused)
        for (other in listOf(null, RefreshOutcome.REFRESHED, RefreshOutcome.UNREACHABLE)) {
            assertFalse("$other", PageSession.Check(refreshed = other).refused)
        }
    }

    @Test
    fun `a re-seed not confirmed in time lets the request go on`() {
        jar = "an unreadable one"
        reseedConfirms = false
        val check = session().beforeRequest(api)!!
        assertTrue(check.reseeded)
        assertEquals(PageSession.Cookie.UNCONFIRMED, check.cookie)
        assertEquals("page cookie behind the stored session: cookie re-seed unconfirmed (7ms)", line(check))
    }

    /** Review of 04f37ce, HIGH: the account portal in this WebView refreshed the session; its pair
     *  is the session's now, and the stored access token was revoked by that refresh. */
    @Test
    fun `a jar refreshed in the page is adopted, and nothing is written over it`() {
        jar = portal
        val check = session().beforeRequest(api)!!
        assertEquals(PageSession.Check(jar = PageSession.Jar.AHEAD, adopted = AdoptOutcome.ADOPTED, cookie = PageSession.Cookie.CURRENT, tookMs = check.tookMs), check)
        assertEquals(listOf(Triple(portal, "portal-refresh", PageSession.ADOPT_WAIT_MS)), adoptions)
        assertEquals(portal, stored)
        assertEquals(emptyList<Pair<String, Long>>(), reseeds)
        assertEquals(emptyList<Pair<String, Long>>(), refreshes)
        assertEquals("page cookie ahead of the stored session: its pair adopted, cookie kept (7ms)", line(check))
        assertSame("the next request finds them the same", PageSession.FRESH, session().beforeRequest(api))
    }

    @Test
    fun `a stale stored token with the jar ahead is adopted first, never refreshed with the session's older refresh token`() {
        stored = expired
        jar = portal
        val check = session().beforeRequest(api)!!
        assertEquals(AdoptOutcome.ADOPTED, check.adopted)
        assertEquals(emptyList<Pair<String, Long>>(), refreshes)
        assertEquals(emptyList<Pair<String, Long>>(), reseeds)
        assertEquals(portal, stored)
    }

    @Test
    fun `an adopted pair that is stale itself is refreshed, then written in`() {
        val portalStale = token(now - 900, now - 1, "portal, since lapsed")
        stored = expired
        jar = portalStale
        val check = session().beforeRequest(api)!!
        assertEquals(PageSession.Check(PageSession.Jar.AHEAD, AdoptOutcome.ADOPTED, RefreshOutcome.REFRESHED, PageSession.Cookie.RESEEDED, check.tookMs), check)
        assertEquals("refreshed with the adopted pair", listOf(portalStale to PageSession.REFRESH_WAIT_MS), refreshes)
        assertEquals(listOf(renewed to PageSession.RESEED_WAIT_MS), reseeds)
        assertEquals("page cookie ahead of the stored session: its pair adopted, then stale: refresh REFRESHED, cookie re-seeded (7ms)", line(check))
    }

    @Test
    fun `a jar ahead with no refresh cookie, or with a refresh in flight, is left as it is`() {
        stored = expired
        jar = portal
        for (none in listOf(null, "")) {
            jarRefresh = none
            val check = session().beforeRequest(api)!!
            assertEquals(PageSession.Check(jar = PageSession.Jar.AHEAD, cookie = PageSession.Cookie.LEFT, tookMs = check.tookMs), check)
            assertEquals("page cookie ahead of the stored session: no refresh cookie with it, cookie left as it is (7ms)", line(check))
        }
        assertEquals(emptyList<Triple<String, String, Long>>(), adoptions)

        jarRefresh = "portal-refresh"
        adoptAnswer = { AdoptOutcome.BUSY }
        val busy = session().beforeRequest(api)!!
        assertEquals(PageSession.Check(jar = PageSession.Jar.AHEAD, adopted = AdoptOutcome.BUSY, cookie = PageSession.Cookie.LEFT, tookMs = busy.tookMs), busy)
        assertEquals("page cookie ahead of the stored session: a refresh in flight, cookie left as it is (7ms)", line(busy))

        assertEquals("no refresh with the stored, older refresh token", emptyList<Pair<String, Long>>(), refreshes)
        assertEquals("nothing written over the newer cookie", emptyList<Pair<String, Long>>(), reseeds)
        assertEquals(expired, stored)
    }

    /** Review of 8e7739c: the pair adopted is of one read. When the jar moved between the request's
     *  read and the pair's, the pair's own access token decides, still ahead or nothing. */
    @Test
    fun `the pair adopted is of one read, and decides for itself`() {
        jar = portal
        onePairRead = { PageSessionJar.Held(expired, "older-refresh") }
        val moved = session().beforeRequest(api)!!
        assertEquals(PageSession.Check(jar = PageSession.Jar.AHEAD, cookie = PageSession.Cookie.LEFT, tookMs = moved.tookMs), moved)
        assertEquals("not ahead in its own read: nothing adopted", emptyList<Triple<String, String, Long>>(), adoptions)

        val later = token(now - 2, now + 86_398, "portal, a second time")
        onePairRead = { PageSessionJar.Held(later, "later-refresh") }
        val again = session().beforeRequest(api)!!
        assertEquals(AdoptOutcome.ADOPTED, again.adopted)
        assertEquals("the pair of the one read, both halves", listOf(Triple(later, "later-refresh", PageSession.ADOPT_WAIT_MS)), adoptions)
    }

    @Test
    fun `a store that moved past the jar meanwhile has the jar brought up to it`() {
        jar = portal
        adoptAnswer = {
            stored = renewed
            AdoptOutcome.NOT_NEWER
        }
        val check = session().beforeRequest(api)!!
        assertEquals(PageSession.Check(jar = PageSession.Jar.AHEAD, adopted = AdoptOutcome.NOT_NEWER, cookie = PageSession.Cookie.RESEEDED, tookMs = check.tookMs), check)
        assertEquals(listOf(renewed to PageSession.RESEED_WAIT_MS), reseeds)
        assertEquals("page cookie ahead of the stored session: the stored session moved on meanwhile, cookie re-seeded (7ms)", line(check))
    }

    /** Only a later pair of the stored session is adopted: a sign-in in the page (another session,
     *  or another account) never becomes the app's session. */
    @Test
    fun `a jar of another session or account is never adopted, and the app's session is written in`() {
        val others = listOf(
            token(now, now + 86_400, "another session", sid = "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9"),
            token(now, now + 86_400, "another account", sub = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"),
        )
        for (other in others) {
            jar = other
            reseeds.clear()
            val check = session().beforeRequest(api)!!
            assertEquals(PageSession.Check(jar = PageSession.Jar.OTHER, cookie = PageSession.Cookie.RESEEDED, tookMs = check.tookMs), check)
            assertEquals(listOf(fresh to PageSession.RESEED_WAIT_MS), reseeds)
            assertEquals("page cookie of another session: cookie re-seeded (7ms)", line(check))
        }
        assertEquals(emptyList<Triple<String, String, Long>>(), adoptions)
        assertEquals(fresh, stored)
    }

    @Test
    fun `a jar issued in the same second is left as it is, with no line`() {
        jar = token(now - 10, now + 890, "a sibling")
        val check = session().beforeRequest(api)!!
        assertEquals(PageSession.Check(jar = PageSession.Jar.SAME_ISSUE, cookie = PageSession.Cookie.LEFT, tookMs = check.tookMs), check)
        assertNull(check.describe())
        assertEquals(emptyList<Pair<String, Long>>(), reseeds)
        assertEquals(emptyList<Triple<String, String, Long>>(), adoptions)
    }

    @Test
    fun `a cookie refreshed in the page during a refresh is not written over`() {
        stored = expired
        jar = expired
        answer = {
            stored = renewed
            jar = token(now + 5, now + 86_405, "portal, during the refresh")
            RefreshOutcome.REFRESHED
        }
        val check = session().beforeRequest(api)!!
        assertEquals(PageSession.Check(refreshed = RefreshOutcome.REFRESHED, cookie = PageSession.Cookie.LEFT, tookMs = check.tookMs), check)
        assertEquals(emptyList<Pair<String, Long>>(), reseeds)
        assertEquals("stale session before a page request: refresh REFRESHED, cookie left as it is (7ms)", line(check))
    }

    @Test
    fun `the refresh endpoint, other origins and other paths are never touched`() {
        val untouched = listOf(
            "$origin/api/v1/auth/refresh",
            "$origin/api/v1/auth/refresh?x=1",
            "$origin/",
            "$origin/index.html",
            "$origin/assets/index-abc.js",
            "$origin/apix/v1/items",
            "$origin/notes/api/v1",
            "https://account.example.test/api/v1/auth/me",
            "https://note.example.test.evil.test/api/v1/items",
            "http://note.example.test/api/v1/items",
        )
        stored = expired
        jar = portal
        for (url in untouched) assertNull(url, session().beforeRequest(url))
        assertEquals("not even the stored token is read", 0, storedReads)
        assertEquals(emptyList<String>(), jarReads)
        assertEquals(emptyList<Pair<String, Long>>(), refreshes)
        assertEquals(emptyList<Triple<String, String, Long>>(), adoptions)
        assertEquals(emptyList<Pair<String, Long>>(), reseeds)
    }

    @Test
    fun `nothing at all when no session is stored`() {
        jar = portal
        for (none in listOf(null, "", "  ")) {
            stored = none
            assertNull(session().beforeRequest(api))
        }
        assertEquals(emptyList<String>(), jarReads)
        assertEquals(emptyList<Pair<String, Long>>(), refreshes)
        assertEquals("a cleared session is never brought back from the jar", emptyList<Triple<String, String, Long>>(), adoptions)
        assertEquals(emptyList<Pair<String, Long>>(), reseeds)
    }

    @Test
    fun `the auth_token and the refresh_token are read out of the jar's cookie line`() {
        assertEquals("abc.def.ghi", PageSession.authTokenIn("theme=dark; auth_token=abc.def.ghi; other=1"))
        assertEquals("abc", PageSession.authTokenIn("auth_token=abc"))
        assertNull(PageSession.authTokenIn("refresh_token=xyz; theme=dark"))
        assertNull(PageSession.authTokenIn(null))
        assertNull(PageSession.authTokenIn(""))
        assertEquals("", PageSession.authTokenIn("auth_token="))
        assertEquals("xyz", PageSession.refreshTokenIn("auth_token=abc.def.ghi; refresh_token=xyz"))
        assertEquals("xyz", PageSession.refreshTokenIn("refresh_token=xyz"))
        assertNull(PageSession.refreshTokenIn("auth_token=abc.def.ghi; theme=dark"))
        assertNull(PageSession.refreshTokenIn(null))
    }

    /**
     * The cold open's refresh and several page requests at once, through the REAL refresher: one
     * request to the server, every page request goes out on the new token, and nothing deadlocks.
     */
    @Test(timeout = 20_000)
    fun `a cold open and page requests on a stale session at once make one refresh`() {
        val sessionManager = mockk<SessionManager>()
        val authApi = mockk<AuthApi>()
        val tokens = AtomicReference<String?>(expired)
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } answers { tokens.get() }
        every { sessionManager.saveTokens(any(), any()) } answers { tokens.set(firstArg()) }
        every { sessionManager.clearAuthOnly() } just Runs
        val serverAnswers = CountDownLatch(1)
        coEvery { authApi.refreshToken(any(), any()) } coAnswers {
            serverAnswers.await(5, TimeUnit.SECONDS)
            Response.success(ApiResponse(success = true, data = TokenResponse(accessToken = renewed, refreshToken = "refresh-token-2")))
        }
        val authenticator = TokenAuthenticator(sessionManager, Provider { authApi }, setOf("note.example.test"))
        val jarNow = AtomicReference<String?>(expired)
        val pages = PageSession(
            noteOrigin = origin,
            storedToken = { tokens.get() },
            jarToken = { jarNow.get() },
            jarPair = { null },
            refresh = { stale, waitMs -> authenticator.refreshSession(stale, waitMs) },
            adopt = { access, refresh, waitMs -> authenticator.adoptSession(access, refresh, waitMs) },
            reseed = { access, _ ->
                jarNow.set(access)
                true
            },
            signIn = {},
            nowSeconds = { now },
        )
        var coldOpen: ColdOpenSession.Next? = null
        val launch = thread {
            coldOpen = runBlocking {
                ColdOpenSession.settle(expired, { stale, waitMs -> authenticator.refreshSession(stale, waitMs) })
            }
        }
        val checks = ConcurrentLinkedQueue<PageSession.Check>()
        val requests = List(4) { i -> thread { pages.beforeRequest("$origin/api/v1/items/$i")?.let { checks += it } } }
        Thread.sleep(200)
        serverAnswers.countDown()
        launch.join(10_000)
        requests.forEach { it.join(10_000) }

        coVerify(exactly = 1) { authApi.refreshToken(any(), any()) }
        assertEquals(ColdOpenSession.Next.LOAD, coldOpen)
        assertEquals(4, checks.size)
        assertTrue(checks.all { it.refreshed == RefreshOutcome.REFRESHED })
        assertEquals(renewed, jarNow.get())
    }

    /**
     * The portal's refresh, end to end through the REAL TokenAuthenticator: the page's pair is
     * stored in one write, nothing is asked of the server, the cookie is left as the portal set it,
     * and the next refresh sends the adopted refresh token, not the session's older one.
     */
    @Test(timeout = 20_000)
    fun `a pair refreshed in the page is adopted through the real refresher, and the next refresh sends it`() {
        val sessionManager = mockk<SessionManager>()
        val authApi = mockk<AuthApi>()
        val access = AtomicReference<String?>(fresh)
        val refreshToken = AtomicReference<String?>("app-refresh")
        every { sessionManager.getAccessToken() } answers { access.get() }
        every { sessionManager.getRefreshToken() } answers { refreshToken.get() }
        every { sessionManager.saveTokens(any(), any()) } answers {
            access.set(firstArg())
            refreshToken.set(secondArg())
        }
        every { sessionManager.clearAuthOnly() } just Runs
        val authenticator = TokenAuthenticator(sessionManager, Provider { authApi }, setOf("note.example.test"))
        val jarNow = AtomicReference<String?>(portal)
        val reseeded = mutableListOf<String>()
        val pages = PageSession(
            noteOrigin = origin,
            storedToken = { access.get() },
            jarToken = { jarNow.get() },
            jarPair = { PageSessionJar.Held(jarNow.get(), "portal-refresh") },
            refresh = { stale, waitMs -> authenticator.refreshSession(stale, waitMs) },
            adopt = { a, r, waitMs -> authenticator.adoptSession(a, r, waitMs) },
            reseed = { a, _ ->
                reseeded += a
                jarNow.set(a)
                true
            },
            signIn = {},
            nowSeconds = { now },
        )

        assertEquals(AdoptOutcome.ADOPTED, pages.beforeRequest(api)!!.adopted)
        assertEquals(portal, access.get())
        assertEquals("portal-refresh", refreshToken.get())
        assertEquals(emptyList<String>(), reseeded)
        coVerify(exactly = 0) { authApi.refreshToken(any(), any()) }

        val next = token(now + 60, now + 960, "after the portal's")
        coEvery { authApi.refreshToken(any(), any()) } returns
            Response.success(ApiResponse(success = true, data = TokenResponse(accessToken = next, refreshToken = "next-refresh")))
        assertEquals(RefreshOutcome.REFRESHED, authenticator.refreshSession(portal, 5_000))
        coVerify(exactly = 1) { authApi.refreshToken(eq("portal-refresh"), any()) }
    }

    /** The activity's side, read from the source: the hook runs on every request that reaches the
     *  network, before the /auth/me prefetch reads the token; a refusal is answered there; its
     *  sign-in only posts to main; and the session is built where no Activity can be captured. */
    @Test
    fun `the activity runs the hook before the prefetch, answers a refusal, and only posts the sign-in (read from the source)`() {
        val activity = File("src/main/java/com/pingolin/note/ui/web/WebAppActivity.kt").readText()
        // The hook and the refusal's answer are ShellRequests', the one path of both clients.
        val hook = activity.indexOf("ShellRequests.answer(req, shellServer, pageSession, parked = false)?.let { return it }")
        val prefetch = activity.indexOf("AuthMePrefetch.take(")
        assertTrue(hook in 0 until prefetch)
        val shared = File("src/main/java/com/pingolin/note/ui/web/ShellRequests.kt").readText()
        val shell = shared.indexOf("server.intercept(req)?.let { return it }")
        val check = shared.indexOf("val check = session?.beforeRequest(req.url.toString()) ?: return null")
        val answered = shared.indexOf("return if (check.refused) PageSession.sessionEndedResponse() else null")
        assertTrue(shell in 0 until check)
        assertTrue(check < answered)
        assertTrue(shared.contains("check.describe(parked)?.let { android.util.Log.w(WebAppActivity.PAGE_SESSION_TAG, it) }"))
        assertTrue(activity.contains("shellPageSession(sessionManager, tokenAuthenticator, signIn = { runOnUiThread { toNativeSignIn() } })"))
        // The factory lives in the companion: its lambdas have no Activity to capture.
        val companion = activity.indexOf("    companion object {")
        val factory = activity.indexOf("internal fun shellPageSession(")
        assertTrue(companion in 0 until factory)
        assertTrue(activity.contains("refresh = { stale, waitMs -> tokenAuthenticator.get().refreshSession(stale, waitMs) },"))
        assertTrue(activity.contains("adopt = { access, refresh, waitMs -> tokenAuthenticator.get().adoptSession(access, refresh, waitMs) },"))
        assertTrue(activity.contains("jarPair = { readJarPair(CookieManager.getInstance()) },"))
        // One read of the refresh endpoint's cookies gives both halves: never a torn pair.
        assertTrue(activity.contains("val line = cookies.getCookie(\"\${originOf(BuildConfig.NOTE_API_BASE_URL)}/api/v1/auth/refresh\")\n" +
            "            return com.pingolin.note.util.PageSessionJar.Held(PageSession.authTokenIn(line), PageSession.refreshTokenIn(line))"))
        assertTrue(activity.contains("return CookieWriter.write(base, cookies, waitMs)"))
        assertEquals("PageSession", WebAppActivity.PAGE_SESSION_TAG)
    }

    /** The launch's and every resume's seed never writes the stored pair over a later one of the
     *  session either: a Home and back during a visit to the portal would have done just that. */
    @Test
    fun `the launch and resume seed leaves a later pair of the session for the page to adopt (read from the source)`() {
        val activity = File("src/main/java/com/pingolin/note/ui/web/WebAppActivity.kt").readText()
        val seed = activity.indexOf("    private fun seedSessionCookies() {")
        val guard = activity.indexOf(
            "if (PageSession.Jar.of(PageSession.authTokenIn(cm.getCookie(base)), access) == PageSession.Jar.AHEAD) {",
        )
        val firstWrite = activity.indexOf("cm.setCookie(base, authCookieHeader(access, domain))")
        assertTrue(seed in 0 until guard)
        assertTrue(guard < firstWrite)
        assertEquals("one seed function, one guard", 1, Regex("""fun seedSessionCookies\(""").findAll(activity).count())
        // What the guard decides, on the tokens the seed sees.
        assertEquals(PageSession.Jar.AHEAD, PageSession.Jar.of(portal, fresh))
        for (written in listOf(null, expired, fresh, "an unreadable one")) {
            assertTrue("$written", PageSession.Jar.of(written, fresh) != PageSession.Jar.AHEAD)
        }
    }

    /** Review of 04f37ce, MEDIUM: parked, the page's timers run on, and its account check with them. */
    @Test
    fun `the parked WebView's requests go through the same guard, with no sign-in (read from the source)`() {
        val activity = File("src/main/java/com/pingolin/note/ui/web/WebAppActivity.kt").readText()
        val handed = activity.indexOf("RetainedWebView.parkedSessionOnce {\n            shellPageSession(sessionManager, tokenAuthenticator, signIn = {})\n        }")
        val obtained = activity.indexOf("web = RetainedWebView.obtain(this) { w ->")
        assertTrue(handed in 0 until obtained)
        val retained = File("src/main/java/com/pingolin/note/ui/web/RetainedWebView.kt").readText()
        val idle = retained.indexOf("private val idleClient = object : WebViewClient() {")
        val intercept = retained.indexOf("override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? =")
        assertTrue(idle in 0 until intercept)
        assertTrue(retained.contains("ShellRequests.answer(req, WebAppActivity.shellServerFor(v.context), parkedSession, parked = true)"))
        // The parked client is the one release() installs.
        assertTrue(retained.contains("w.webViewClient = idleClient"))
    }
}
