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

import com.pingolin.note.util.AccessTokenExpiry
import com.pingolin.note.util.PageSessionJar
import com.pingolin.note.util.TokenAuthenticator.AdoptOutcome
import com.pingolin.note.util.TokenAuthenticator.RefreshOutcome

/**
 * The page's session, kept good under its feet (owner: users are never asked for their password).
 *
 * WHY THIS EXISTS
 *   The page never refreshes a token itself (nothing in the web editor calls auth/refresh): its
 *   cookie is whatever the app seeded, and the app seeds only at a launch and on each resume. A
 *   native access token can live 15 minutes (a finite session). The page re-checks its account on
 *   coming into view and once a minute (accountRecheck.ts); a JSON 401 there wipes and reloads,
 *   and the reload's boot sends the person to the account portal's password prompt. A long read,
 *   or a return after the token's life, did exactly that: a native refresh (on a sync's 401) never
 *   reached the cookie.
 *
 * So every request the page makes to its own API passes through here first (shouldInterceptRequest
 * on the WebView's IO thread: WebAppActivity's client, and the parked WebView's in RetainedWebView,
 * whose page keeps its timers), and goes out on a good session:
 *  - a cookie jar AHEAD of the store, holding a later pair of the same session ([Jar.AHEAD]): the
 *    session was refreshed in the page. The account portal runs in this WebView (the bounce lands
 *    there, and so does Settings' "Manage" link) and refreshes on every load with the jar's refresh
 *    cookie; the server then revokes the stored access token's id. Writing the stored pair back
 *    would hand the page that revoked token, so the store takes the jar's pair instead
 *    (TokenAuthenticator.adoptSession, under the refresher's lock), before any refresh with the
 *    stored refresh token, the session's older one;
 *  - a stored access token that has expired, expires within a minute, or can't be read is
 *    refreshed through TokenAuthenticator's one refresher (coalesced with every other caller,
 *    waiting at most [REFRESH_WAIT_MS]). Refused: the session is over, the native sign-in is asked
 *    for, and the request is answered here ([sessionEndedResponse]). No answer: the request goes on
 *    as it is, as a stale token written into the jar couldn't help it;
 *  - a jar BEHIND the store (no `auth_token`, an unreadable one, an older one of the session, or
 *    one of another session) gets the stored pair written in, waited for at most [RESEED_WAIT_MS],
 *    before the request goes on. Never over a cookie of the session issued as late or later.
 * With the stored token fresh and already in the jar, as nearly every request finds it, this costs
 * the comparison: the token's expiry is read once per token, not per request.
 *
 * Only the note origin's `/api/`; never the refresh endpoint itself, never another origin, and
 * nothing at all when no session is stored.
 */
internal class PageSession(
    noteOrigin: String,
    /** The stored access token (SessionManager). */
    private val storedToken: () -> String?,
    /** The `auth_token` the cookie jar holds for this URL, which the request will carry. */
    private val jarToken: (url: String) -> String?,
    /** The jar's pair in ONE read (the refresh endpoint's cookies: its `refresh_token`, Path the
     *  endpoint, and the `auth_token`, Path `/`), so the two halves are never of two moments. */
    private val jarPair: () -> PageSessionJar.Held?,
    /** The one refresher: TokenAuthenticator.refreshSession. Blocking, bounded by its wait. */
    private val refresh: (staleToken: String, waitMs: Long) -> RefreshOutcome,
    /** Store the jar's pair: TokenAuthenticator.adoptSession. Blocking, bounded by its wait. */
    private val adopt: (accessToken: String, refreshToken: String, waitMs: Long) -> AdoptOutcome,
    /** Write the stored pair, [accessToken] with its refresh token, into the jar; true once the
     *  writes are confirmed within the wait. */
    private val reseed: (accessToken: String, waitMs: Long) -> Boolean,
    /** Ask for the native sign-in (posted to the main thread); nothing where there's no screen. */
    private val signIn: () -> Unit,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    private val apiPrefix = "$noteOrigin/api/"
    private val refreshPath = "$noteOrigin/api/v1/auth/refresh"

    /** The jar's `auth_token` against the stored one, when they differ. */
    enum class Jar {
        /** None, unreadable, or an earlier one of the stored session: brought up to the store. */
        BEHIND,
        /** A later one of the stored session, by its `iat`: the session was refreshed in the page,
         *  and its pair is adopted. */
        AHEAD,
        /** One of the stored session issued in the same second: left as it is. */
        SAME_ISSUE,
        /** Not of the stored session as far as can be read (another session or account, or a stored
         *  token that can't be read): the stored session is the app's, and the jar is brought to it. */
        OTHER;

        companion object {
            /** How [jar] stands against [stored] ([AccessTokenExpiry.compareIssue]). */
            fun of(jar: String?, stored: String): Jar {
                if (AccessTokenExpiry.issueOf(jar)?.issuedAt == null) return BEHIND
                return when (AccessTokenExpiry.compareIssue(jar, stored)) {
                    1 -> AHEAD
                    0 -> SAME_ISSUE
                    -1 -> BEHIND
                    else -> OTHER
                }
            }
        }
    }

    /** What became of the jar's `auth_token` cookie. */
    enum class Cookie {
        /** It holds the stored token: nothing was written. */
        CURRENT,
        /** The stored pair was written in, and confirmed within [RESEED_WAIT_MS]. */
        RESEEDED,
        /** The stored pair was written in, but not confirmed in time; the request went on. */
        UNCONFIRMED,
        /** Left as it is: nothing that could be written would help (a refusal, a stale token with no
         *  answer, a refresh still in flight), or it is as late as the stored one or later. */
        LEFT,
    }

    /** What a guarded request found and did. */
    data class Check(
        /** The jar against the store, when its `auth_token` wasn't the stored one; else null. */
        val jar: Jar? = null,
        /** Adopting the jar's pair, when the jar was [Jar.AHEAD]; null when that wasn't tried (no
         *  refresh token in the jar). */
        val adopted: AdoptOutcome? = null,
        /** The refresh asked for, if one was ([RefreshOutcome]); null when the token was fresh. */
        val refreshed: RefreshOutcome? = null,
        val cookie: Cookie = Cookie.CURRENT,
        val tookMs: Long = 0,
    ) {
        /** The session was refused: the request is answered with [sessionEndedResponse]. */
        val refused: Boolean get() = refreshed == RefreshOutcome.REFUSED

        /** The stored pair was written into the jar. */
        val reseeded: Boolean get() = cookie == Cookie.RESEEDED || cookie == Cookie.UNCONFIRMED

        /** Anything beyond the comparison: what is logged ([describe]). */
        val didSomething: Boolean get() = refreshed != null || reseeded || jar == Jar.AHEAD

        /**
         * The WARN line for this check (`adb logcat -s PageSession:W`), or null when it did nothing
         * worth one. [parked]: the parked WebView's, where nothing signs in until the next open. No
         * token and no URL in it.
         */
        fun describe(parked: Boolean = false): String? {
            if (!didSomething) return null
            val found = when {
                jar == Jar.AHEAD -> "page cookie ahead of the stored session"
                refreshed != null -> "stale session before a page request"
                jar == Jar.OTHER -> "page cookie of another session"
                else -> "page cookie behind the stored session"
            }
            val done = mutableListOf<String>()
            if (jar == Jar.AHEAD) {
                done += when (adopted) {
                    AdoptOutcome.ADOPTED -> "its pair adopted"
                    AdoptOutcome.NOT_NEWER -> "the stored session moved on meanwhile"
                    AdoptOutcome.BUSY -> "a refresh in flight"
                    null -> "no refresh cookie with it"
                }
            }
            if (refreshed != null) done += if (jar == Jar.AHEAD) "then stale: refresh $refreshed" else "refresh $refreshed"
            done += when {
                refused && parked -> "answered 503, sign-in at the next open"
                refused -> "native sign-in, answered 503"
                cookie == Cookie.RESEEDED -> "cookie re-seeded"
                cookie == Cookie.UNCONFIRMED -> "cookie re-seed unconfirmed"
                cookie == Cookie.CURRENT && adopted == AdoptOutcome.ADOPTED && refreshed == null -> "cookie kept"
                cookie == Cookie.CURRENT -> "cookie already current"
                else -> "cookie left as it is"
            }
            return "$found: ${done.joinToString(", ")} (${if (parked) "parked, " else ""}${tookMs}ms)"
        }
    }

    /** The token whose `exp` was last read, with that `exp`. */
    @Volatile
    private var lastRead: Pair<String, Long?>? = null

    /** Whether [url] is a request this guards: the note origin's API, but not its refresh. */
    fun guards(url: String): Boolean =
        url.startsWith(apiPrefix) && !(url == refreshPath || url.startsWith("$refreshPath?") || url.startsWith("$refreshPath/"))

    /**
     * Make the session good for the request to [url], before it goes out. Null when this doesn't
     * concern it: not a guarded URL, or no session stored. A [Check.refused] check's request must
     * be answered with [sessionEndedResponse], not sent.
     */
    fun beforeRequest(url: String): Check? {
        if (!guards(url)) return null
        val seen = storedToken()?.takeIf { it.isNotBlank() } ?: return null
        val inJar = jarToken(url)
        if (!isStale(seen) && inJar == seen) return FRESH
        val started = System.nanoTime()
        fun took() = (System.nanoTime() - started) / 1_000_000
        val jar = if (inJar == seen) null else Jar.of(inJar, seen)
        var stored = seen
        var adopted: AdoptOutcome? = null
        if (jar == Jar.AHEAD) {
            // Before any refresh: one with the stored refresh token, the session's older one, would
            // take the session back from the page's pair (or, once it is retired, end it). The pair
            // in one read, still ahead of the stored token, or nothing is adopted.
            val pair = jarPair()
            val pairAccess = pair?.accessToken
            val pairRefresh = pair?.refreshToken?.takeIf { it.isNotBlank() }
            if (pairAccess == null || pairRefresh == null || Jar.of(pairAccess, seen) != Jar.AHEAD) {
                return Check(jar = jar, cookie = Cookie.LEFT, tookMs = took())
            }
            adopted = adopt(pairAccess, pairRefresh, ADOPT_WAIT_MS)
            when (adopted) {
                AdoptOutcome.ADOPTED -> stored = pairAccess
                // The refresh running stores a pair newer than either when it lands; until then the
                // page's own pair is the best there is.
                AdoptOutcome.BUSY -> return Check(jar = jar, adopted = adopted, cookie = Cookie.LEFT, tookMs = took())
                // The store moved past it meanwhile (a refresh landed): the jar is brought up below.
                AdoptOutcome.NOT_NEWER -> stored = storedToken()?.takeIf { it.isNotBlank() }
                    ?: return Check(jar = jar, adopted = adopted, cookie = Cookie.LEFT, tookMs = took())
            }
        }
        var refreshed: RefreshOutcome? = null
        if (isStale(stored)) {
            refreshed = refresh(stored, REFRESH_WAIT_MS)
            when (refreshed) {
                RefreshOutcome.REFUSED -> {
                    signIn()
                    return Check(jar, adopted, refreshed, Cookie.LEFT, took())
                }
                // No answer: the request goes on as it is. A stale token written into the jar
                // couldn't help it.
                RefreshOutcome.UNREACHABLE -> return Check(jar, adopted, refreshed, Cookie.LEFT, took())
                RefreshOutcome.REFRESHED -> stored = storedToken()?.takeIf { it.isNotBlank() }
                    ?: return Check(jar, adopted, refreshed, Cookie.LEFT, took())
            }
        }
        val jarNow = if (stored == seen) inJar else jarToken(url)
        if (jarNow == stored) return Check(jar, adopted, refreshed, Cookie.CURRENT, took())
        when (Jar.of(jarNow, stored)) {
            // Never over a cookie of the session issued as late or later.
            Jar.AHEAD, Jar.SAME_ISSUE -> return Check(jar, adopted, refreshed, Cookie.LEFT, took())
            Jar.BEHIND, Jar.OTHER -> {}
        }
        val confirmed = reseed(stored, RESEED_WAIT_MS)
        return Check(jar, adopted, refreshed, if (confirmed) Cookie.RESEEDED else Cookie.UNCONFIRMED, took())
    }

    /** [AccessTokenExpiry.needsRefresh], with the `exp` of the token last seen remembered. */
    private fun isStale(token: String): Boolean {
        val read = lastRead
        val exp = if (read != null && read.first == token) {
            read.second
        } else {
            AccessTokenExpiry.expiresAt(token).also { lastRead = token to it }
        }
        return AccessTokenExpiry.isStale(exp, nowSeconds())
    }

    companion object {
        /** How long a page request waits for a refresh: a cold open's wait. */
        const val REFRESH_WAIT_MS = ColdOpenSession.WAIT_MS

        /** How long a page request waits for a refresh in flight before adopting the jar's pair. */
        const val ADOPT_WAIT_MS = REFRESH_WAIT_MS

        /** How long a page request waits for its cookies to be written. */
        const val RESEED_WAIT_MS = 1_000L

        /** Fresh, and already in the jar: nothing done. */
        val FRESH = Check()

        /** The status of [sessionEndedResponse]: not a 401 or 403, so never "denied" to the page. */
        const val SESSION_ENDED_STATUS = 503

        /** The body of [sessionEndedResponse]: auth's error shape. */
        const val SESSION_ENDED_BODY = """{"success":false,"error":"Service Unavailable"}"""

        /** The `auth_token` value in a `Cookie` header as CookieManager.getCookie gives it, or
         *  null when it holds none. */
        fun authTokenIn(cookies: String?): String? = cookieIn(cookies, "auth_token")

        /** The `refresh_token` value in such a header (CookieManager.getCookie for the refresh
         *  endpoint, its cookie's Path), or null when it holds none. */
        fun refreshTokenIn(cookies: String?): String? = cookieIn(cookies, "refresh_token")

        private fun cookieIn(cookies: String?, name: String): String? {
            if (cookies.isNullOrEmpty()) return null
            for (part in cookies.split(';')) {
                val pair = part.trim()
                if (pair.startsWith("$name=")) return pair.substring(name.length + 1)
            }
            return null
        }

        /**
         * The answer to a request whose session was just refused ([Check.refused]), in place of the
         * server's: a 503, which the page takes for a passing failure (offlineSession.ts
         * `classifyFailure`: only a JSON 401 or 403 is "denied"). The server's JSON 401 on the
         * refused token would make its account check wipe and reload to the account portal,
         * racing the native sign-in. Not cached.
         */
        fun sessionEndedResponse(): android.webkit.WebResourceResponse =
            android.webkit.WebResourceResponse(
                "application/json",
                "utf-8",
                SESSION_ENDED_STATUS,
                "Service Unavailable",
                mapOf("Cache-Control" to "no-store"),
                java.io.ByteArrayInputStream(SESSION_ENDED_BODY.toByteArray(Charsets.UTF_8)),
            )
    }
}

/**
 * Writes cookies from any thread but the main one, and says when they are in (PageSession's
 * re-seed, on the WebView's IO thread). CookieManager.setCookie answers on the calling thread's
 * Looper, which that thread doesn't run, so the writes are made on this one's own thread and the
 * caller waits on a latch: the main thread is never touched.
 */
internal object CookieWriter {

    private val handler: android.os.Handler by lazy {
        val thread = android.os.HandlerThread("PageSessionCookies").apply { start() }
        android.os.Handler(thread.looper)
    }

    /** Set each of [cookies] for [url], then flush; true once every write is confirmed set within
     *  [waitMs]. */
    fun write(url: String, cookies: List<String>, waitMs: Long): Boolean {
        if (cookies.isEmpty()) return true
        val done = java.util.concurrent.CountDownLatch(cookies.size)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        handler.post {
            val cm = android.webkit.CookieManager.getInstance()
            for (cookie in cookies) {
                cm.setCookie(url, cookie) { set ->
                    if (set != true) failed.set(true)
                    done.countDown()
                }
            }
            cm.flush()
        }
        return try {
            done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) && !failed.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
