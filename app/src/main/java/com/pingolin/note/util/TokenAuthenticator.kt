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
import java.util.concurrent.CompletableFuture
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
 * OkHttp Authenticator that automatically refreshes expired tokens.
 *
 * When a 401 response is received:
 * 1. Attempts to refresh the token using the refresh token
 * 2. If successful, retries the original request with the new token
 * 3. If refresh fails, returns null (triggers logout flow)
 *
 * Uses Provider<AuthApi> to break circular dependency:
 * AuthApi -> Retrofit -> OkHttp -> TokenAuthenticator -> AuthApi
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val sessionManager: SessionManager,
    private val authApiProvider: Provider<AuthApi>,
    @param:ApiHosts private val apiHosts: Set<String>,
) : Authenticator {

    private val refreshExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TokenRefresh").apply { isDaemon = true }
    }

    private val refreshLock = Any()
    @Volatile private var refreshLatch: CountDownLatch? = null
    @Volatile private var refreshResult: Boolean = false

    private val failureCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)
    @Volatile private var circuitState = CircuitState.CLOSED

    companion object {
        private const val FAILURE_THRESHOLD = 3
        private const val RECOVERY_TIMEOUT_MS = 60_000L
        private const val TAG = "TokenAuthenticator"
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

        val refreshToken = sessionManager.getRefreshToken() ?: return null

        if (!isCircuitAllowed()) return null

        val shouldRefresh: Boolean
        var latchToWaitOn: CountDownLatch? = null

        synchronized(refreshLock) {
            val currentToken = sessionManager.getAccessToken()
            val requestToken = response.request.header("Authorization")
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")

            if (currentToken != null && currentToken != requestToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            val existingLatch = refreshLatch
            if (existingLatch != null) {
                latchToWaitOn = existingLatch
                shouldRefresh = false
            } else {
                refreshLatch = CountDownLatch(1)
                shouldRefresh = true
            }
        }

        if (!shouldRefresh) {
            latchToWaitOn?.let { latch ->
                try {
                    if (!latch.await(30, TimeUnit.SECONDS)) return null
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }

            // Refresh completed by another thread, retry with new token if we
            // have one. If `refreshResult` is false we still try the current
            // stored token — a parallel SRP login may have stored a brand-new
            // token while the refresh path was failing on the stale one. The
            // request-token comparison below avoids retrying with the exact
            // same dead token we already 401'd on.
            val newToken = sessionManager.getAccessToken()
            val requestToken = response.request.header("Authorization")
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")
            return if (newToken != null && newToken != requestToken) {
                response.request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
            } else null
        }

        return try {
            val future = CompletableFuture<Result<Unit>>()

            refreshExecutor.execute {
                try {
                    val result = kotlinx.coroutines.runBlocking {
                        val authApi = authApiProvider.get()
                        // The refresh credential goes in the X-Refresh-Token
                        // HEADER, not the body — the server reads it only
                        // from that header or from the session cookie. A
                        // body-only refresh falls through to the legacy JWT
                        // path and is rejected with "Please log in again to
                        // upgrade session".
                        val resp = authApi.refreshToken(refreshToken = refreshToken)
                        if (resp.isSuccessful && resp.body()?.data != null) {
                            val tokens = resp.body()!!.data!!
                            sessionManager.saveTokens(tokens.accessToken, tokens.refreshToken)
                            Result.success(Unit)
                        } else {
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
                            Result.failure(Exception("Refresh failed: HTTP ${resp.code()} $serverMsg"))
                        }
                    }
                    future.complete(result)
                } catch (e: Exception) {
                    android.util.Log.w(
                        TAG,
                        "refreshToken: exception ${e.javaClass.simpleName}: ${e.message}"
                    )
                    future.completeExceptionally(e)
                }
            }

            val result = try {
                future.get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Result.failure(e)
            }

            refreshResult = result.isSuccess

            if (result.isSuccess) {
                recordSuccess()
                val newToken = sessionManager.getAccessToken()
                if (newToken != null) {
                    response.request.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                } else {
                    android.util.Log.w(TAG, "No token after refresh, clearing session")
                    sessionManager.clearAuthOnly()
                    null
                }
            } else {
                recordFailure()

                // Before clearing the session, check whether a parallel sign-in
                // (or another refresh) replaced the stored token while we were
                // talking to /auth/refresh with a stale one. If it did, the
                // user is currently authenticated — wiping their tokens here
                // would cause the user-visible "signed in, then immediately
                // logged out" symptom. Retry the failed request with the
                // fresh token instead.
                val tokenAfterRefresh = sessionManager.getAccessToken()
                val requestToken = response.request.header("Authorization")
                    ?.removePrefix("Bearer ")
                    ?.removePrefix("bearer ")
                if (tokenAfterRefresh != null && tokenAfterRefresh != requestToken) {
                    android.util.Log.i(TAG, "Refresh failed but stored token rotated; retrying with new token instead of clearing session")
                    refreshResult = true  // unblock waiters with the new token
                    response.request.newBuilder()
                        .header("Authorization", "Bearer $tokenAfterRefresh")
                        .build()
                } else {
                    android.util.Log.w(TAG, "Token refresh failed, clearing session")
                    sessionManager.clearAuthOnly()
                    null
                }
            }
        } finally {
            synchronized(refreshLock) {
                refreshLatch?.countDown()
                refreshLatch = null
            }
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
