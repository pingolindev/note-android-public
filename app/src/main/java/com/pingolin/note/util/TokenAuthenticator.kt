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

import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.di.ApiHosts
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * OkHttp Authenticator that automatically refreshes expired tokens, and THE one refresher of the
 * stored session ([refreshSession]).
 *
 * When a 401 response is received:
 * 1. Attempts to refresh the token using the refresh token
 * 2. If successful, retries the original request with the new token
 * 3. If the auth server refuses the refresh token, clears the session's credentials (triggers the
 *    sign-in); if the refresh got no answer (offline, a timeout, a 5xx or a 429), keeps them and
 *    returns null, so the request fails and the next one tries again.
 *
 * The cold open refreshes through [refreshSession] too, before the page loads (WebAppActivity: a
 * native access token lives 15 minutes). One refresh at a time, whoever asks: a caller that finds
 * one in flight waits for it instead of starting another, so the launch and its first sync are
 * one request to the server, never two racing with the same refresh token.
 *
 * And the one writer of a pair refreshed outside the app's own requests ([adoptSession]): the page's
 * cookie jar, refreshed in the page, under the same lock as a refresh.
 *
 * Uses Provider<AuthApi> to break circular dependency:
 * AuthApi -> Retrofit -> OkHttp -> TokenAuthenticator -> AuthApi
 */
@Singleton
class TokenAuthenticator internal constructor(
    private val sessionManager: SessionManager,
    private val authApiProvider: Provider<AuthApi>,
    private val apiHosts: Set<String>,
    /** The page's cookie jar ([PageSessionJar]), read before a refresh picks its token. */
    private val pageJar: () -> PageSessionJar.Held?,
    private val nowSeconds: () -> Long,
    /** Writes a refreshed pair into the page's jar ([PageSessionJar.write]). */
    private val pageJarWrite: (accessToken: String, refreshToken: String) -> Unit = { _, _ -> },
) : Authenticator {

    @Inject
    constructor(
        sessionManager: SessionManager,
        authApiProvider: Provider<AuthApi>,
        @ApiHosts apiHosts: Set<String>,
    ) : this(
        sessionManager,
        authApiProvider,
        apiHosts,
        { PageSessionJar.read(JAR_WAIT_MS) },
        { System.currentTimeMillis() / 1000 },
        PageSessionJar::write,
    )

    private val refreshExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TokenRefresh").apply { isDaemon = true }
    }

    private val refreshLock = Any()

    /** The refresh in flight, while one is (guarded by [refreshLock]). It stays in flight until its
     *  request to the server is over, however long its callers wait. */
    private var inFlight: Refresh? = null

    /** A refresh in flight: [storedRefresh] is the stored refresh token when it started (what its
     *  reply may replace, [storeReply]); [fromJar] when it sends the page jar's refresh token. */
    private class Refresh(val storedRefresh: String?, val fromJar: Boolean) {
        val done = CountDownLatch(1)
        @Volatile var outcome = RefreshOutcome.UNREACHABLE
    }

    /** What a refresh came to. */
    enum class RefreshOutcome {
        /** A fresh token is stored: by this refresh, by one that ran meanwhile, or by a sign-in. */
        REFRESHED,
        /** The auth server refused the refresh token (a 401 with its JSON error, [isRefusal]): the
         *  session is over, and its credentials were cleared ([SessionManager.clearAuthOnly]). */
        REFUSED,
        /** Nothing settled: offline, a timeout, a 5xx or a 429, an answer from the edge in front of
         *  auth (a 400 or 403, or a 401 that isn't JSON), the circuit below open, no refresh token,
         *  or a refresh still running when the caller stopped waiting. The stored tokens stay as
         *  they are. */
        UNREACHABLE,
    }

    /** What an adoption came to ([adoptSession]). */
    enum class AdoptOutcome {
        /** The pair is stored: nothing stored was issued as late for the session. */
        ADOPTED,
        /** Nothing stored: the store holds a token of the session issued as late or later (a refresh
         *  landed meanwhile), or none, or one of another session. */
        NOT_NEWER,
        /** Nothing stored: a refresh was still in flight when the wait ran out; its answer decides. */
        BUSY,
    }

    private val failureCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)
    @Volatile private var circuitState = CircuitState.CLOSED

    companion object {
        private const val FAILURE_THRESHOLD = 3
        private const val RECOVERY_TIMEOUT_MS = 60_000L
        private const val TAG = "TokenAuthenticator"

        /** How long a 401 waits for its refresh. */
        private const val AUTHENTICATE_WAIT_MS = 30_000L

        /** How long a refresh waits for the page's jar to be installed, at process start only. */
        internal const val JAR_WAIT_MS = 2_000L

        /**
         * Is this answer the auth server refusing the refresh token itself? Only a 401 with its JSON
         * error: every refusal of auth's refresh route is `err(StatusCode::UNAUTHORIZED, …)`, a JSON
         * body, and the route sends no 400 or 403. Those, or a 401 that isn't JSON, come from the
         * edge in front of it (Cloudflare, a WAF, a proxy) and say nothing about the session. The
         * page draws the same line (its offlineSession.ts `classifyFailure`).
         */
        internal fun isRefusal(code: Int, contentType: String?): Boolean =
            code == 401 && contentType?.contains("application/json", ignoreCase = true) == true
    }

    private enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    private fun isCircuitAllowed(): Boolean {
        return when (circuitState) {
            CircuitState.CLOSED -> true
            CircuitState.OPEN -> {
                val timeSinceFailure = System.currentTimeMillis() - lastFailureTime.get()
                if (timeSinceFailure >= RECOVERY_TIMEOUT_MS) {
                    circuitState = CircuitState.HALF_OPEN
                    android.util.Log.i(TAG, "Circuit breaker transitioning to HALF_OPEN")
                    true
                } else {
                    false
                }
            }
            CircuitState.HALF_OPEN -> true
        }
    }

    private fun recordSuccess() {
        failureCount.set(0)
        if (circuitState != CircuitState.CLOSED) {
            circuitState = CircuitState.CLOSED
        }
    }

    private fun recordFailure() {
        val failures = failureCount.incrementAndGet()
        lastFailureTime.set(System.currentTimeMillis())

        if (circuitState == CircuitState.HALF_OPEN) {
            circuitState = CircuitState.OPEN
        } else if (failures >= FAILURE_THRESHOLD) {
            circuitState = CircuitState.OPEN
        }
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        // Never attach — or refresh-and-re-attach — the session bearer to a
        // host outside our own API backends. The presigned R2 attachment PUT
        // (AttachmentUploader) reuses this authenticator's OkHttpClient; the
        // token-attach interceptor omits the Authorization header for that
        // host, so a 401 from R2 would otherwise fall through to the "token
        // rotated" branch below and get retried to the storage/attacker host
        // WITH the JWT. Same canonicalized host set the interceptor uses
        // (HttpUrl.host, lowercased) — one source of truth via @ApiHosts.
        if (response.request.url.host !in apiHosts) return null

        // A deferred push-unregister replay (PushUnregisterWorker) carries the
        // DEPARTED account's own bearer plus this sentinel header; it must NEVER be
        // refreshed-and-retried onto the CURRENT session's token — that would delete
        // a different account's push device row. Nothing to refresh onto → bail.
        if (response.request.header("X-Pingolin-No-Refresh") != null) return null

        if (responseCount(response) >= 2) return null

        val path = response.request.url.encodedPath
        if (path.contains("/auth/login") ||
            path.contains("/auth/register") ||
            path.contains("/auth/refresh") ||
            path.contains("/auth/srp/")) {
            return null
        }

        // Session-less anonymous requests (e.g. the E2EE crash reporter, tagged
        // X-Pingolin-Anon) are sent with NO Authorization on purpose — the
        // token-attach interceptor omits the bearer for them. A 401 there is not a
        // token expiry we can fix by refreshing, and re-attaching a Bearer would
        // relink a deliberately anonymous request to the user account. Nothing to
        // refresh onto → bail.
        if (response.request.header("Authorization") == null) {
            return null
        }

        val requestToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.removePrefix("bearer ")
        if (refreshSession(requestToken, AUTHENTICATE_WAIT_MS) != RefreshOutcome.REFRESHED) return null
        // Retry with the token stored now, never with the exact dead token the
        // request already 401'd on.
        val newToken = sessionManager.getAccessToken()
        // And never on another account's token: a switch can land between the refresh's answer
        // and this read (mayStandIn).
        return if (newToken != null && newToken != requestToken && mayStandIn(newToken, requestToken)) {
            response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
        } else null
    }

    /**
     * Refresh the stored session, which the caller found stale in [staleAccessToken] (the token it
     * was about to use, or null). THE one refresher: [authenticate] (a 401) and the cold open
     * (WebAppActivity) both come here.
     *  - The stored token already differs from [staleAccessToken] (a refresh or a sign-in landed
     *    since the caller read it): [RefreshOutcome.REFRESHED], and nothing is asked; but only when
     *    it is the caller's own account's ([mayStandIn]). Another account's (a switch while the
     *    caller was out) is [RefreshOutcome.UNREACHABLE] for it, so a 401's request is never
     *    retried on another account's token.
     *  - A refresh in flight: waited for, not doubled.
     *  - The page's cookie jar holds a later pair of the stored session ([PageSessionJar]: same
     *    account and session, issued later, [AccessTokenExpiry.compareIssue]), with its refresh
     *    token: the session was refreshed in the page (the account portal, run in the shell's
     *    WebView, refreshes on every load), and the stored refresh token is the session's older
     *    one. Sent, it would take the session back from the page's pair, or, retired for longer
     *    than the server's grace (a second refresh in the page, 15 minutes before), revoke the
     *    session. So, before a token is picked and under the same lock: the jar's pair fresh, it
     *    is stored, as a refresh stores its own, and [RefreshOutcome.REFRESHED] with no request;
     *    stale, the refresh sends ITS refresh token (the stored pair stays until the answer lands,
     *    so callers meanwhile join this refresh).
     *  - Else one starts, on [refreshExecutor], unless there is no refresh token or the circuit is
     *    open ([RefreshOutcome.UNREACHABLE]).
     * Waits at most [waitMs]: a refresh that takes longer goes on without the caller, who gets
     * [RefreshOutcome.UNREACHABLE]. Blocking: call it off the main thread.
     */
    fun refreshSession(staleAccessToken: String?, waitMs: Long): RefreshOutcome {
        // The page's jar, read before the lock: at process start the read can wait for the shell's
        // install (PageSessionJar), and nothing waits under the lock. Weighed against the stored
        // token under it.
        val jar = try {
            pageJar()
        } catch (e: Exception) {
            null
        }
        val refresh: Refresh
        synchronized(refreshLock) {
            val currentToken = sessionManager.getAccessToken()
            if (currentToken != null && currentToken != staleAccessToken) return standIn(currentToken, staleAccessToken)
            refresh = inFlight ?: run {
                val ahead = laterPair(jar, currentToken)
                if (ahead != null && !AccessTokenExpiry.needsRefresh(ahead.first, nowSeconds())) {
                    sessionManager.saveTokens(ahead.first, ahead.second)
                    android.util.Log.w(TAG, "page jar ahead of the stored session: its pair adopted, no refresh sent")
                    return RefreshOutcome.REFRESHED
                }
                if (ahead != null) {
                    android.util.Log.w(TAG, "page jar ahead of the stored session, its pair stale: refreshing with its refresh token")
                }
                val storedRefresh = sessionManager.getRefreshToken()
                val refreshToken = ahead?.second ?: storedRefresh ?: return RefreshOutcome.UNREACHABLE
                if (!isCircuitAllowed()) return RefreshOutcome.UNREACHABLE
                Refresh(storedRefresh = storedRefresh, fromJar = ahead != null).also { started ->
                    inFlight = started
                    refreshExecutor.execute { runRefresh(refreshToken, staleAccessToken, started) }
                }
            }
        }
        val finished = try {
            refresh.done.await(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) return RefreshOutcome.UNREACHABLE
        // A token stored by anyone since (this refresh, or a parallel SRP sign-in) is a refreshed
        // session, whatever this refresh itself came to.
        val tokenNow = sessionManager.getAccessToken()
        return if (tokenNow != null && tokenNow != staleAccessToken) standIn(tokenNow, staleAccessToken) else refresh.outcome
    }

    /**
     * Store [accessToken] with [refreshToken]: a pair of the stored session that the page's cookie
     * jar holds, issued after the stored one. Another client of that jar refreshed the session (the
     * account portal, run in the shell's WebView, refreshes on every load with the jar's refresh
     * cookie), and the server moved the session's access-token id to that pair: the stored access
     * token is revoked, and the stored refresh token is the session's older one. The pair to keep is
     * the jar's; writing the stored one back over it would hand the page the revoked token.
     *
     * Under [refreshLock], and never while a refresh is in flight: one running is waited for, at
     * most [waitMs] (else [AdoptOutcome.BUSY]), as its answer, stored when it lands, is newer than
     * either pair. The pair is stored only if its access token was issued later than the one stored
     * when the lock is held, for the same account and session ([AccessTokenExpiry.compareIssue]),
     * and in one write, as a refresh stores its own ([SessionManager.saveTokens]). A refresh that
     * starts after it reads the adopted refresh token, since a refresh starts only while the stored
     * access token is still its caller's stale one ([refreshSession]). Blocking: off the main thread.
     */
    fun adoptSession(accessToken: String, refreshToken: String, waitMs: Long): AdoptOutcome {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        while (true) {
            val running: Refresh
            synchronized(refreshLock) {
                val flight = inFlight
                if (flight == null) {
                    if (AccessTokenExpiry.compareIssue(accessToken, sessionManager.getAccessToken()) != 1) {
                        return AdoptOutcome.NOT_NEWER
                    }
                    sessionManager.saveTokens(accessToken, refreshToken)
                    return AdoptOutcome.ADOPTED
                }
                running = flight
            }
            val left = deadline - System.nanoTime()
            val landed = left > 0 && try {
                running.done.await(left, TimeUnit.NANOSECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!landed) return AdoptOutcome.BUSY
        }
    }

    /**
     * What a caller whose token was [staleAccessToken] gets when [stored] took its place: a refreshed
     * session only when [stored] may stand in for it ([mayStandIn]); else no answer, logged.
     */
    private fun standIn(stored: String, staleAccessToken: String?): RefreshOutcome {
        if (mayStandIn(stored, staleAccessToken)) return RefreshOutcome.REFRESHED
        android.util.Log.w(TAG, "the stored session is another account's: the caller's request is not retried on it")
        return RefreshOutcome.UNREACHABLE
    }

    /**
     * Whether [stored] may stand in for the caller's [stale] token. Native requests carry no account
     * header, so after an account switch a 401 from the old account's context would otherwise be
     * retried, and answered, as the new account. When the caller's account can be read (its `sub`,
     * [AccessTokenExpiry.issueOf]), only a token of that same account may (ids compared as UUIDs,
     * case aside). When it can't (no token, or one that can't be read, which the shell treats as
     * expired and refreshes), nothing tells the accounts apart, and the refresh stands as before.
     */
    private fun mayStandIn(stored: String, stale: String?): Boolean {
        val callers = AccessTokenExpiry.issueOf(stale)?.subject ?: return true
        return AccessTokenExpiry.issueOf(stored)?.subject?.equals(callers, ignoreCase = true) == true
    }

    /**
     * The page jar's pair ([held], read before the lock), when it is a later pair of the stored
     * session ([stored]: same account and session, issued later; [AccessTokenExpiry.compareIssue])
     * and holds its refresh token; else null, and never with nothing stored. Under [refreshLock].
     */
    private fun laterPair(held: PageSessionJar.Held?, stored: String?): Pair<String, String>? {
        if (held == null) return null
        val access = held.accessToken ?: return null
        val refresh = held.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        return if (AccessTokenExpiry.compareIssue(access, stored) == 1) access to refresh else null
    }

    /** The request half of a refresh, on [refreshExecutor]. Always ends [refresh], and with it the
     *  refresh in flight. */
    private fun runRefresh(refreshToken: String, staleAccessToken: String?, refresh: Refresh) {
        var outcome = RefreshOutcome.UNREACHABLE
        var dropped = false
        try {
            val answer = try {
                kotlinx.coroutines.runBlocking {
                    val authApi = authApiProvider.get()
                    // The refresh credential goes in the X-Refresh-Token
                    // HEADER, not the body — the server reads it only
                    // from that header or from the session cookie. A
                    // body-only refresh falls through to the legacy JWT
                    // path and is rejected with "Please log in again to
                    // upgrade session".
                    val resp = authApi.refreshToken(refreshToken = refreshToken)
                    val tokens = resp.body()?.data
                    if (resp.isSuccessful && tokens != null) {
                        if (storeReply(tokens.accessToken, tokens.refreshToken, refresh)) {
                            intoTheJar(tokens.accessToken, tokens.refreshToken)
                            RefreshOutcome.REFRESHED
                        } else {
                            dropped = true
                            RefreshOutcome.UNREACHABLE
                        }
                    } else {
                        // The refusal decision reads the body's type (isRefusal), never its
                        // text; before the message below, whose read consumes the body.
                        val contentType = resp.errorBody()?.contentType()?.toString()
                        // Capture *why* — server-side messages explain
                        // which check tripped (session not found /
                        // revoked / expired / signature failure).
                        // Without this the operator can't distinguish a
                        // stale jti from a revoked session from a misc
                        // 401, and the logcat just shows "401".
                        val serverMsg = resp.body()?.error
                            ?: try { resp.errorBody()?.string() } catch (_: Exception) { null }
                            ?: "(no body)"
                        android.util.Log.w(
                            TAG,
                            "refreshToken: failed code=${resp.code()} server=\"$serverMsg\""
                        )
                        if (isRefusal(resp.code(), contentType)) RefreshOutcome.REFUSED else RefreshOutcome.UNREACHABLE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    TAG,
                    "refreshToken: exception ${e.javaClass.simpleName}: ${e.message}"
                )
                RefreshOutcome.UNREACHABLE
            }
            // A dropped reply settles nothing: the server answered (the circuit closes), and the
            // store is whatever the sign-out, sign-in or switch left it.
            outcome = if (dropped) {
                recordSuccess()
                RefreshOutcome.UNREACHABLE
            } else {
                settle(answer, staleAccessToken)
            }
        } finally {
            refresh.outcome = outcome
            synchronized(refreshLock) {
                if (inFlight === refresh) inFlight = null
            }
            refresh.done.countDown()
        }
    }

    /**
     * Store a refresh's reply, compare-and-swap under [refreshLock]: only if the stored refresh token
     * is still the one [refresh] started from, so a sign-out, a sign-in or an account switch that
     * landed while it was out is never overwritten by the old session's reply. And a refresh that
     * sent the page jar's refresh token ([Refresh.fromJar]) stores only a later pair of the stored
     * session ([AccessTokenExpiry.compareIssue]), never another session's or account's. False: the
     * reply is dropped.
     */
    private fun storeReply(accessToken: String, refreshToken: String, refresh: Refresh): Boolean {
        synchronized(refreshLock) {
            if (sessionManager.getRefreshToken() != refresh.storedRefresh) {
                android.util.Log.w(TAG, "refresh reply dropped: the stored session changed while it was out")
                return false
            }
            if (refresh.fromJar && AccessTokenExpiry.compareIssue(accessToken, sessionManager.getAccessToken()) != 1) {
                android.util.Log.w(TAG, "refresh reply dropped: not a later pair of the stored session")
                return false
            }
            sessionManager.saveTokens(accessToken, refreshToken)
            return true
        }
    }

    /**
     * A refreshed pair, stored, into the page's jar at once (when this process has one), as the
     * shell seeds it: a page that stays long (the portal, Track web) then never holds a token the
     * server has retired, and the page's next request finds its cookie current. Never over a later
     * pair of the session the jar holds. Before the refresh ends, so callers that joined it find
     * the jar written too.
     */
    private fun intoTheJar(accessToken: String, refreshToken: String) {
        val held = try {
            pageJar()
        } catch (e: Exception) {
            null
        }
        if (AccessTokenExpiry.compareIssue(held?.accessToken, accessToken) == 1) return
        try {
            pageJarWrite(accessToken, refreshToken)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "refreshed pair not written into the page's jar (${e.javaClass.simpleName})")
        }
    }

    /** What a refresh's [answer] leaves the session as. */
    private fun settle(answer: RefreshOutcome, staleAccessToken: String?): RefreshOutcome = when (answer) {
        RefreshOutcome.REFRESHED -> {
            recordSuccess()
            if (sessionManager.getAccessToken() != null) {
                RefreshOutcome.REFRESHED
            } else {
                android.util.Log.w(TAG, "No token after refresh, clearing session")
                sessionManager.clearAuthOnly()
                RefreshOutcome.REFUSED
            }
        }
        RefreshOutcome.REFUSED -> {
            recordFailure()
            // Before clearing the session, check whether a parallel sign-in
            // (or another refresh) replaced the stored token while we were
            // talking to /auth/refresh with a stale one. If it did, the
            // user is currently authenticated — wiping their tokens here
            // would cause the user-visible "signed in, then immediately
            // logged out" symptom.
            val tokenAfterRefresh = sessionManager.getAccessToken()
            if (tokenAfterRefresh != null && tokenAfterRefresh != staleAccessToken) {
                android.util.Log.i(TAG, "Refresh failed but stored token rotated; keeping it instead of clearing session")
                RefreshOutcome.REFRESHED
            } else {
                android.util.Log.w(TAG, "Token refresh refused, clearing session")
                sessionManager.clearAuthOnly()
                RefreshOutcome.REFUSED
            }
        }
        RefreshOutcome.UNREACHABLE -> {
            recordFailure()
            // No answer about the session is no reason to end it: a sign-out
            // here would ask for the password over a dropped connection. The
            // tokens stay; the next request (or launch) refreshes again.
            android.util.Log.w(TAG, "Token refresh got no answer; the session is kept")
            RefreshOutcome.UNREACHABLE
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
