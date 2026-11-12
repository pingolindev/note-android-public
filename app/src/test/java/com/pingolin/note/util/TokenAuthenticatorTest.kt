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
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.TokenResponse
import io.mockk.*
import io.mockk.impl.annotations.MockK
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response as RetrofitResponse
import javax.inject.Provider

class TokenAuthenticatorTest {

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var authApi: AuthApi

    private lateinit var authApiProvider: Provider<AuthApi>
    private lateinit var authenticator: TokenAuthenticator

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        authApiProvider = mockk()
        every { authApiProvider.get() } returns authApi

        // The production ctor takes apiHosts so the bearer is host-gated; the
        // test's requests all target api.example.com, so scope the set to that
        // host to keep the refresh cases eligible.
        authenticator = TokenAuthenticator(sessionManager, authApiProvider, setOf("api.example.com"))
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    // =============================================================================
    // SKIP AUTH PATHS TESTS
    // =============================================================================

    @Test
    fun `authenticate_loginPath_returnsNull`() {
        val response = createUnauthorizedResponse("/api/v1/auth/login")

        val result = authenticator.authenticate(null, response)

        assertNull(result)
    }

    @Test
    fun `authenticate_registerPath_returnsNull`() {
        val response = createUnauthorizedResponse("/api/v1/auth/register")

        val result = authenticator.authenticate(null, response)

        assertNull(result)
    }

    @Test
    fun `authenticate_refreshPath_returnsNull`() {
        val response = createUnauthorizedResponse("/api/v1/auth/refresh")

        val result = authenticator.authenticate(null, response)

        assertNull(result)
    }

    @Test
    fun `authenticate_srpPath_returnsNull`() {
        val response = createUnauthorizedResponse("/api/v1/auth/srp/challenge")

        val result = authenticator.authenticate(null, response)

        assertNull(result)
    }

    // =============================================================================
    // NO REFRESH TOKEN TESTS
    // =============================================================================

    @Test
    fun `authenticate_noRefreshToken_returnsNull`() {
        every { sessionManager.getRefreshToken() } returns null

        val response = createUnauthorizedResponse("/api/v1/items")

        val result = authenticator.authenticate(null, response)

        assertNull(result)
    }

    // =============================================================================
    // RETRY COUNT TESTS
    // =============================================================================

    @Test
    fun `authenticate_tooManyRetries_returnsNull`() {
        // Build a response chain with 2 prior responses (total count >= 2)
        val request = Request.Builder()
            .url("https://api.example.com/api/v1/items")
            .header("Authorization", "Bearer old-token")
            .build()

        // okhttp's Response.Builder requires priorResponse.body == null.
        val priorResponse1 = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .code(401)
            .build()

        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .code(401)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .priorResponse(priorResponse1)
            .build()

        val result = authenticator.authenticate(null, response)

        assertNull(result)
    }

    // =============================================================================
    // TOKEN ALREADY UPDATED TESTS
    // =============================================================================

    @Test
    fun `authenticate_tokenAlreadyRefreshed_usesNewToken`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } returns "new-token"

        val request = Request.Builder()
            .url("https://api.example.com/api/v1/items")
            .header("Authorization", "Bearer old-token")
            .build()

        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .code(401)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

        val result = authenticator.authenticate(null, response)

        assertNotNull(result)
        assertEquals("Bearer new-token", result?.header("Authorization"))
    }

    // =============================================================================
    // SUCCESSFUL REFRESH TESTS
    // =============================================================================

    @Test
    fun `authenticate_successfulRefresh_returnsNewRequest`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        // First call returns current token (same as request), second returns new token after refresh
        every { sessionManager.getAccessToken() } returnsMany listOf("old-token", "new-access-token")
        every { sessionManager.saveTokens(any(), any()) } just Runs

        // Refresh credential travels in the X-Refresh-Token header — the
        // body is an empty map, not a request DTO; a body-borne token
        // hits the server's legacy JWT-only path.
        coEvery { authApi.refreshToken(eq("refresh-token"), any()) } returns
            RetrofitResponse.success(ApiResponse(
                success = true,
                data = TokenResponse(
                    accessToken = "new-access-token",
                    refreshToken = "new-refresh-token",
                ),
            ))

        val request = Request.Builder()
            .url("https://api.example.com/api/v1/items")
            .header("Authorization", "Bearer old-token")
            .build()

        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .code(401)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

        val result = authenticator.authenticate(null, response)

        assertNotNull(result)
        assertEquals("Bearer new-access-token", result?.header("Authorization"))
        verify { sessionManager.saveTokens("new-access-token", "new-refresh-token") }
    }

    /** The sign-out's revoke carries the sentinel. A 401 to it (an expired or
     * revoked bearer) must not refresh: that would mint new tokens right
     * before the wipe. */
    @Test
    fun `authenticate_signOutRevoke_neverRefreshes`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } returns "old-token"
        val request = Request.Builder()
            .url("https://api.example.com/api/v1/auth/logout")
            .header("Authorization", "Bearer old-token")
            .header("X-Pingolin-No-Refresh", "1")
            .build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .code(401)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

        assertNull(authenticator.authenticate(null, response))
        coVerify(exactly = 0) { authApi.refreshToken(any(), any()) }
    }

    // =============================================================================
    // FAILED REFRESH TESTS
    // =============================================================================

    @Test
    fun `authenticate_failedRefresh_clearsSessionAndReturnsNull`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        // Stays "old-token" through the post-failure re-read, so the
        // parallel-login rescue path (token rotated while refreshing)
        // doesn't kick in and the clear path runs.
        every { sessionManager.getAccessToken() } returns "old-token"
        // Production clears auth credentials only — identity, theme and
        // crypto material survive a forced re-login (clearAuthOnly split).
        every { sessionManager.clearAuthOnly() } just Runs

        // What auth's refresh route answers a refused refresh token with:
        // `err(StatusCode::UNAUTHORIZED, "Session expired")`, a JSON 401.
        coEvery { authApi.refreshToken(any(), any()) } returns
            RetrofitResponse.error(401, authRefusal("Session expired"))

        val request = Request.Builder()
            .url("https://api.example.com/api/v1/items")
            .header("Authorization", "Bearer old-token")
            .build()

        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .code(401)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

        val result = authenticator.authenticate(null, response)

        assertNull(result)
        verify { sessionManager.clearAuthOnly() }
    }

    /** auth's refusal body: `{"success":false,"error":…}`, application/json. */
    private fun authRefusal(message: String, type: String = "application/json") =
        """{"success":false,"error":"$message"}""".toResponseBody(type.toMediaType())

    // =============================================================================
    // CIRCUIT BREAKER TESTS
    // =============================================================================

    @Test
    fun `authenticate_afterMultipleFailures_circuitOpens`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } returns "old-token"
        every { sessionManager.clearAuthOnly() } just Runs

        coEvery { authApi.refreshToken(any(), any()) } returns
            RetrofitResponse.error(500, "Server error".toResponseBody())

        val request = Request.Builder()
            .url("https://api.example.com/api/v1/items")
            .header("Authorization", "Bearer old-token")
            .build()

        // Fail 3 times (FAILURE_THRESHOLD = 3)
        repeat(3) {
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .message("Unauthorized")
                .code(401)
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()

            authenticator.authenticate(null, response)
        }

        // After 3 failures, circuit should be open; next call should return null immediately
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .code(401)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

        val result = authenticator.authenticate(null, response)

        assertNull(result)
    }

    // =============================================================================
    // THE ONE REFRESHER: refreshSession, shared by a 401 and the cold open
    // =============================================================================

    /** What SessionManager holds, as the real one would: written by saveTokens, cleared by
     *  clearAuthOnly. */
    private val stored = java.util.concurrent.atomic.AtomicReference<String?>("old-token")

    private fun storing() {
        stored.set("old-token")
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } answers { stored.get() }
        every { sessionManager.saveTokens(any(), any()) } answers { stored.set(firstArg()) }
        every { sessionManager.clearAuthOnly() } answers { stored.set(null) }
    }

    private fun refreshedTokens() = RetrofitResponse.success(
        ApiResponse(
            success = true,
            data = TokenResponse(accessToken = "new-access-token", refreshToken = "new-refresh-token"),
        ),
    )

    private fun unauthorizedWith(token: String): Response = Response.Builder()
        .request(
            Request.Builder()
                .url("https://api.example.com/api/v1/quota")
                .header("Authorization", "Bearer $token")
                .build(),
        )
        .protocol(Protocol.HTTP_1_1)
        .message("Unauthorized")
        .code(401)
        .body("{}".toResponseBody("application/json".toMediaType()))
        .build()

    private fun waitUntil(ms: Long, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(done())
    }

    /** The cold open's refresh and the first API call's 401 at the same time: one request to the
     *  server, never two racing with the same refresh token. */
    @Test
    fun `refreshSession_coldOpenAndA401AtOnce_areOneRequest`() {
        storing()
        val answer = java.util.concurrent.CountDownLatch(1)
        coEvery { authApi.refreshToken(any(), any()) } coAnswers {
            answer.await(5, java.util.concurrent.TimeUnit.SECONDS)
            refreshedTokens()
        }
        var coldOpen: TokenAuthenticator.RefreshOutcome? = null
        var retried: Request? = null
        val launch = kotlin.concurrent.thread { coldOpen = authenticator.refreshSession("old-token", 10_000) }
        Thread.sleep(100)
        val sync = kotlin.concurrent.thread { retried = authenticator.authenticate(null, unauthorizedWith("old-token")) }
        Thread.sleep(100)
        answer.countDown()
        launch.join(5_000)
        sync.join(5_000)

        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, coldOpen)
        assertEquals("Bearer new-access-token", retried?.header("Authorization"))
        coVerify(exactly = 1) { authApi.refreshToken(any(), any()) }
    }

    @Test
    fun `refreshSession_outlastingTheWait_goesOnWithoutIt_startsNoSecond_andLandsForTheNext`() {
        storing()
        val requested = java.util.concurrent.CountDownLatch(1)
        val answer = java.util.concurrent.CountDownLatch(1)
        coEvery { authApi.refreshToken(any(), any()) } coAnswers {
            requested.countDown()
            answer.await(5, java.util.concurrent.TimeUnit.SECONDS)
            refreshedTokens()
        }
        assertEquals(TokenAuthenticator.RefreshOutcome.UNREACHABLE, authenticator.refreshSession("old-token", 100))
        assertTrue(requested.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // Still in flight: another caller waits on it, and nothing new is asked.
        assertEquals(TokenAuthenticator.RefreshOutcome.UNREACHABLE, authenticator.refreshSession("old-token", 100))
        coVerify(exactly = 1) { authApi.refreshToken(any(), any()) }

        answer.countDown()
        waitUntil(5_000) { stored.get() == "new-access-token" }
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, authenticator.refreshSession("old-token", 100))
        coVerify(exactly = 1) { authApi.refreshToken(any(), any()) }
        verify(exactly = 0) { sessionManager.clearAuthOnly() }
    }

    @Test
    fun `refreshSession_alreadyRotated_asksNothing`() {
        storing()
        stored.set("rotated-meanwhile")
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, authenticator.refreshSession("old-token", 100))
        coVerify(exactly = 0) { authApi.refreshToken(any(), any()) }
    }

    /** No answer about the session is no reason to end it: offline, a 5xx or a 429 keep the
     *  stored tokens (before, every failed refresh cleared them, and the sign-in asked for the
     *  password over a dropped connection). */
    @Test
    fun `refreshSession_noAnswer_keepsTheSession`() {
        val failures = listOf<suspend () -> RetrofitResponse<ApiResponse<TokenResponse>>>(
            { throw java.io.IOException("offline") },
            { RetrofitResponse.error(500, "oops".toResponseBody()) },
            { RetrofitResponse.error(503, "down".toResponseBody()) },
            { RetrofitResponse.error(429, "slow down".toResponseBody()) },
            { RetrofitResponse.success(ApiResponse<TokenResponse>(success = false)) },
        )
        for (fail in failures) {
            storing()
            val fresh = TokenAuthenticator(sessionManager, authApiProvider, setOf("api.example.com"))
            coEvery { authApi.refreshToken(any(), any()) } coAnswers { fail() }
            assertEquals(TokenAuthenticator.RefreshOutcome.UNREACHABLE, fresh.refreshSession("old-token", 5_000))
            assertEquals("old-token", stored.get())
        }
        verify(exactly = 0) { sessionManager.clearAuthOnly() }
    }

    @Test
    fun `refreshSession_refused_clearsTheSession`() {
        for (type in listOf("application/json", "application/json; charset=utf-8")) {
            storing()
            val fresh = TokenAuthenticator(sessionManager, authApiProvider, setOf("api.example.com"))
            coEvery { authApi.refreshToken(any(), any()) } returns RetrofitResponse.error(401, authRefusal("Session revoked", type))
            assertEquals(type, TokenAuthenticator.RefreshOutcome.REFUSED, fresh.refreshSession("old-token", 5_000))
            assertEquals(null, stored.get())
        }
        verify(exactly = 2) { sessionManager.clearAuthOnly() }
    }

    /** Review of d21b3b7: auth's refresh route refuses only with a JSON 401, and sends no 400 or
     *  403. Those, and a 401 that isn't JSON, come from the edge in front of it (a Cloudflare
     *  challenge, a WAF, a proxy): signing someone out over one would be the password prompt
     *  again. No answer: the session is kept. */
    @Test
    fun `refreshSession_anEdgeAnswer_isNoAnswer_andKeepsTheSession`() {
        val edge = listOf(
            RetrofitResponse.error<ApiResponse<TokenResponse>>(400, authRefusal("Bad request")),
            RetrofitResponse.error<ApiResponse<TokenResponse>>(403, authRefusal("Forbidden")),
            RetrofitResponse.error<ApiResponse<TokenResponse>>(403, "<html>Attention Required!</html>".toResponseBody("text/html".toMediaType())),
            RetrofitResponse.error<ApiResponse<TokenResponse>>(401, "<html>Unauthorized</html>".toResponseBody("text/html; charset=UTF-8".toMediaType())),
            RetrofitResponse.error<ApiResponse<TokenResponse>>(401, "Unauthorized".toResponseBody()),
        )
        for (answer in edge) {
            storing()
            val fresh = TokenAuthenticator(sessionManager, authApiProvider, setOf("api.example.com"))
            coEvery { authApi.refreshToken(any(), any()) } returns answer
            assertEquals("${answer.code()}", TokenAuthenticator.RefreshOutcome.UNREACHABLE, fresh.refreshSession("old-token", 5_000))
            assertEquals("old-token", stored.get())
        }
        verify(exactly = 0) { sessionManager.clearAuthOnly() }
    }

    @Test
    fun `isRefusal_onlyA401WithJson`() {
        assertTrue(TokenAuthenticator.isRefusal(401, "application/json"))
        assertTrue(TokenAuthenticator.isRefusal(401, "Application/JSON; charset=utf-8"))
        assertFalse(TokenAuthenticator.isRefusal(401, "text/html"))
        assertFalse(TokenAuthenticator.isRefusal(401, null))
        assertFalse(TokenAuthenticator.isRefusal(400, "application/json"))
        assertFalse(TokenAuthenticator.isRefusal(403, "application/json"))
        assertFalse(TokenAuthenticator.isRefusal(500, "application/json"))
    }

    @Test
    fun `authenticate_refreshWithoutAnswer_keepsTheSessionAndReturnsNull`() {
        storing()
        coEvery { authApi.refreshToken(any(), any()) } returns RetrofitResponse.error(503, "down".toResponseBody())
        assertNull(authenticator.authenticate(null, unauthorizedWith("old-token")))
        verify(exactly = 0) { sessionManager.clearAuthOnly() }
        assertEquals("old-token", stored.get())
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    private fun createUnauthorizedResponse(path: String): Response {
        val request = Request.Builder()
            .url("https://api.example.com$path")
            .build()

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .code(401)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
    }

    // =============================================================================
    // ADOPTING A PAIR REFRESHED IN THE PAGE (review of 04f37ce)
    // =============================================================================

    private val user = "0b6f3d1e-5a4c-4e8b-9f2a-1c3d5e7f9a0b"
    private val sessionId = "7d9e1f3a-2b4c-4d6e-8f0a-1b2c3d4e5f6a"
    private val t = 1_800_000_000L
    private val b64 = java.util.Base64.getUrlEncoder().withoutPadding()

    /** An access token as auth mints them (services/jwt.rs): `sub`, `sid`, `iat`, `exp`. */
    private fun jwt(iat: Long, sid: String = sessionId, sub: String = user) =
        b64.encodeToString("""{"alg":"EdDSA"}""".toByteArray()) + "." +
            b64.encodeToString("""{"sub":"$sub","sid":"$sid","iat":$iat,"exp":${iat + 900}}""".toByteArray()) + ".c2ln"

    private val storedRefresh = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** The store as SessionManager keeps it: both tokens, written together. */
    private fun storingPair(access: String?, refresh: String?) {
        stored.set(access)
        storedRefresh.set(refresh)
        every { sessionManager.getAccessToken() } answers { stored.get() }
        every { sessionManager.getRefreshToken() } answers { storedRefresh.get() }
        every { sessionManager.saveTokens(any(), any()) } answers {
            stored.set(firstArg())
            storedRefresh.set(secondArg())
        }
        every { sessionManager.clearAuthOnly() } answers {
            stored.set(null)
            storedRefresh.set(null)
        }
    }

    @Test
    fun `adoptSession_aLaterPairOfTheSameSession_isStoredInOneWrite`() {
        storingPair(jwt(t), "stored-refresh")
        val page = jwt(t + 30)
        assertEquals(TokenAuthenticator.AdoptOutcome.ADOPTED, authenticator.adoptSession(page, "page-refresh", 1_000))
        verify(exactly = 1) { sessionManager.saveTokens(page, "page-refresh") }
        assertEquals(page, stored.get())
        assertEquals("page-refresh", storedRefresh.get())
    }

    @Test
    fun `adoptSession_nothingNewerOrNotOfThisSession_isNeverStored`() {
        val storedToken = jwt(t)
        val notStored = listOf(
            "issued in the same second" to jwt(t),
            "issued earlier" to jwt(t - 60),
            "another session" to jwt(t + 30, sid = "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9"),
            "another account" to jwt(t + 30, sub = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"),
            "unreadable" to "not.a.jwt",
        )
        for ((what, page) in notStored) {
            storingPair(storedToken, "stored-refresh")
            assertEquals(what, TokenAuthenticator.AdoptOutcome.NOT_NEWER, authenticator.adoptSession(page, "page-refresh", 1_000))
            assertEquals(what, storedToken, stored.get())
            assertEquals(what, "stored-refresh", storedRefresh.get())
        }
        // Nothing stored (a refusal cleared the session): never brought back from the jar.
        storingPair(null, null)
        assertEquals(TokenAuthenticator.AdoptOutcome.NOT_NEWER, authenticator.adoptSession(jwt(t + 30), "page-refresh", 1_000))
        assertNull(stored.get())
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }
    }

    /** Under the refresher's lock: nothing is stored while a refresh is out, and once it lands the
     *  adoption is weighed against what it stored. */
    @Test(timeout = 20_000)
    fun `adoptSession_whileARefreshIsInFlight_storesNothing_thenWeighsWhatItStored`() {
        val staleToken = jwt(t)
        storingPair(staleToken, "stored-refresh")
        val renewed = jwt(t + 60)
        val asked = java.util.concurrent.CountDownLatch(1)
        val serverAnswers = java.util.concurrent.CountDownLatch(1)
        coEvery { authApi.refreshToken(any(), any()) } coAnswers {
            asked.countDown()
            serverAnswers.await(10, java.util.concurrent.TimeUnit.SECONDS)
            RetrofitResponse.success(ApiResponse(success = true, data = TokenResponse(accessToken = renewed, refreshToken = "renewed-refresh")))
        }
        val refresher = kotlin.concurrent.thread { authenticator.refreshSession(staleToken, 10_000) }
        assertTrue(asked.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val page = jwt(t + 30)
        assertEquals(TokenAuthenticator.AdoptOutcome.BUSY, authenticator.adoptSession(page, "page-refresh", 200))
        assertEquals(staleToken, stored.get())

        var outcome: TokenAuthenticator.AdoptOutcome? = null
        val adoption = kotlin.concurrent.thread { outcome = authenticator.adoptSession(page, "page-refresh", 10_000) }
        serverAnswers.countDown()
        adoption.join(10_000)
        refresher.join(10_000)
        assertEquals(TokenAuthenticator.AdoptOutcome.NOT_NEWER, outcome)
        assertEquals(renewed, stored.get())
        assertEquals("renewed-refresh", storedRefresh.get())
        verify(exactly = 0) { sessionManager.saveTokens(page, any()) }
    }

    @Test
    fun `adoptSession_thenARefresh_sendsTheAdoptedRefreshToken_neverTheOlderOne`() {
        val staleToken = jwt(t)
        storingPair(staleToken, "stored-refresh")
        val page = jwt(t + 30)
        assertEquals(TokenAuthenticator.AdoptOutcome.ADOPTED, authenticator.adoptSession(page, "page-refresh", 1_000))

        // A caller that read the stored token before the adoption: answered, and nothing is sent.
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, authenticator.refreshSession(staleToken, 5_000))
        coVerify(exactly = 0) { authApi.refreshToken(any(), any()) }

        coEvery { authApi.refreshToken(any(), any()) } returns
            RetrofitResponse.success(ApiResponse(success = true, data = TokenResponse(accessToken = jwt(t + 90), refreshToken = "next-refresh")))
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, authenticator.refreshSession(page, 5_000))
        coVerify(exactly = 1) { authApi.refreshToken(eq("page-refresh"), any()) }
        coVerify(exactly = 0) { authApi.refreshToken(eq("stored-refresh"), any()) }
    }

    // =============================================================================
    // A LATER PAIR IN THE PAGE'S JAR, ADOPTED BY THE REFRESHER ITSELF (review of 1008a92)
    // =============================================================================

    /** The refresher with the page's jar holding [held] (or a reader that throws), at [now]. */
    private fun withJar(held: PageSessionJar.Held?, now: Long, throwing: Boolean = false) = TokenAuthenticator(
        sessionManager,
        authApiProvider,
        setOf("api.example.com"),
        { if (throwing) throw IllegalStateException("no jar") else held },
        { now },
    )

    private val sentRefreshTokens = mutableListOf<String>()

    private fun serverRenews(access: String, refresh: String) {
        coEvery { authApi.refreshToken(any(), any()) } coAnswers {
            sentRefreshTokens += firstArg<String>()
            RetrofitResponse.success(ApiResponse(success = true, data = TokenResponse(accessToken = access, refreshToken = refresh)))
        }
    }

    private fun unauthorized(token: String): Response {
        val request = Request.Builder()
            .url("https://api.example.com/api/v1/items")
            .header("Authorization", "Bearer $token")
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .code(401)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
    }

    @Test
    fun `refreshSession_aLaterFreshPairInThePageJar_isAdopted_andNothingIsSent`() {
        storingPair(jwt(t), "stored-refresh")
        val page = jwt(t + 30)
        val auth = withJar(PageSessionJar.Held(page, "page-refresh"), now = t + 60)
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, auth.refreshSession(jwt(t), 5_000))
        assertEquals(page, stored.get())
        assertEquals("page-refresh", storedRefresh.get())
        coVerify(exactly = 0) { authApi.refreshToken(any(), any()) }
    }

    @Test
    fun `refreshSession_aLaterStalePairInThePageJar_isRefreshedWithItsRefreshToken_neverTheStoredOne`() {
        storingPair(jwt(t), "stored-refresh")
        val renewed = jwt(t + 900)
        serverRenews(renewed, "next-refresh")
        val auth = withJar(PageSessionJar.Held(jwt(t + 30), "page-refresh"), now = t + 900)
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, auth.refreshSession(jwt(t), 5_000))
        assertEquals(listOf("page-refresh"), sentRefreshTokens)
        assertEquals(renewed, stored.get())
        assertEquals("next-refresh", storedRefresh.get())
    }

    @Test
    fun `refreshSession_aJarThatIsNotALaterPairOfTheSession_changesNothing`() {
        val notAhead = listOf(
            "no jar" to null,
            "no access token" to PageSessionJar.Held(null, "page-refresh"),
            "no refresh token" to PageSessionJar.Held(jwt(t + 30), null),
            "a blank refresh token" to PageSessionJar.Held(jwt(t + 30), " "),
            "issued in the same second" to PageSessionJar.Held(jwt(t), "page-refresh"),
            "issued earlier" to PageSessionJar.Held(jwt(t - 60), "page-refresh"),
            "another session" to PageSessionJar.Held(jwt(t + 30, sid = "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9"), "page-refresh"),
            "another account" to PageSessionJar.Held(jwt(t + 30, sub = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"), "page-refresh"),
            "unreadable" to PageSessionJar.Held("not.a.jwt", "page-refresh"),
        )
        for ((what, held) in notAhead + listOf("a reader that throws" to null)) {
            storingPair(jwt(t), "stored-refresh")
            sentRefreshTokens.clear()
            serverRenews(jwt(t + 900), "next-refresh")
            val auth = withJar(held, now = t + 60, throwing = what == "a reader that throws")
            assertEquals(what, TokenAuthenticator.RefreshOutcome.REFRESHED, auth.refreshSession(jwt(t), 5_000))
            assertEquals(what, listOf("stored-refresh"), sentRefreshTokens)
        }
        verify(exactly = 0) { sessionManager.saveTokens(any(), eq("page-refresh")) }
    }

    @Test
    fun `refreshSession_withNothingStored_neverBringsTheSessionBackFromTheJar`() {
        storingPair(null, null)
        val auth = withJar(PageSessionJar.Held(jwt(t + 30), "page-refresh"), now = t + 60)
        assertEquals(TokenAuthenticator.RefreshOutcome.UNREACHABLE, auth.refreshSession(null, 5_000))
        assertNull(stored.get())
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }
        coVerify(exactly = 0) { authApi.refreshToken(any(), any()) }
    }

    /** The native 401 path (a sync's request): the same adoption, before any refresh token is
     *  picked, and the request retried on the page's pair. */
    @Test
    fun `authenticate_a401WithALaterFreshPairInThePageJar_retriesOnItWithoutARefresh`() {
        storingPair(jwt(t), "stored-refresh")
        val page = jwt(t + 30)
        val auth = withJar(PageSessionJar.Held(page, "page-refresh"), now = t + 60)
        val retry = auth.authenticate(null, unauthorized(jwt(t)))
        assertEquals("Bearer $page", retry?.header("Authorization"))
        assertEquals("page-refresh", storedRefresh.get())
        coVerify(exactly = 0) { authApi.refreshToken(any(), any()) }
    }

    @Test
    fun `authenticate_a401WithALaterStalePairInThePageJar_refreshesWithItsRefreshToken`() {
        storingPair(jwt(t), "stored-refresh")
        val renewed = jwt(t + 900)
        serverRenews(renewed, "next-refresh")
        val auth = withJar(PageSessionJar.Held(jwt(t + 30), "page-refresh"), now = t + 900)
        val retry = auth.authenticate(null, unauthorized(jwt(t)))
        assertEquals("Bearer $renewed", retry?.header("Authorization"))
        assertEquals(listOf("page-refresh"), sentRefreshTokens)
    }

    /** Review of 8e7739c: the jar is read BEFORE the refresher's lock (its read can wait for the
     *  install at process start), and weighed against the stored token under it. */
    @Test
    fun `refreshSession_readsThePageJarOutsideTheRefreshersLock`() {
        storingPair(jwt(t), "stored-refresh")
        var auth: TokenAuthenticator? = null
        val heldDuringRead = mutableListOf<Boolean>()
        auth = TokenAuthenticator(
            sessionManager,
            authApiProvider,
            setOf("api.example.com"),
            {
                val lock = TokenAuthenticator::class.java.getDeclaredField("refreshLock").apply { isAccessible = true }.get(auth)!!
                heldDuringRead += Thread.holdsLock(lock)
                PageSessionJar.Held(jwt(t + 30), "page-refresh")
            },
            { t + 60 },
        )
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, auth.refreshSession(jwt(t), 5_000))
        assertEquals("read once, with the lock free", listOf(false), heldDuringRead)
        assertEquals("page-refresh", storedRefresh.get())
    }

    // =============================================================================
    // A REPLY STORED ONLY OVER THE STORE IT STARTED FROM (review of 8e7739c)
    // =============================================================================

    /** Runs [during] while a refresh is out (the server held), then lets the server answer
     *  [access]/[refresh]; gives the refresh's outcome. */
    private fun whileARefreshIsOut(
        auth: TokenAuthenticator,
        stale: String,
        access: String,
        refresh: String,
        during: () -> Unit,
    ): TokenAuthenticator.RefreshOutcome {
        val asked = java.util.concurrent.CountDownLatch(1)
        val answer = java.util.concurrent.CountDownLatch(1)
        coEvery { authApi.refreshToken(any(), any()) } coAnswers {
            sentRefreshTokens += firstArg<String>()
            asked.countDown()
            answer.await(10, java.util.concurrent.TimeUnit.SECONDS)
            RetrofitResponse.success(ApiResponse(success = true, data = TokenResponse(accessToken = access, refreshToken = refresh)))
        }
        var outcome: TokenAuthenticator.RefreshOutcome? = null
        val refresher = kotlin.concurrent.thread { outcome = auth.refreshSession(stale, 10_000) }
        assertTrue(asked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        during()
        answer.countDown()
        refresher.join(10_000)
        return outcome!!
    }

    @Test(timeout = 20_000)
    fun `a sign-out while a refresh is out is never undone by its reply`() {
        storingPair(jwt(t), "stored-refresh")
        val outcome = whileARefreshIsOut(withJar(null, now = t + 60), jwt(t), jwt(t + 900), "next-refresh") {
            sessionManager.clearAuthOnly()
        }
        assertEquals(TokenAuthenticator.RefreshOutcome.UNREACHABLE, outcome)
        assertNull("signed out, and staying so", stored.get())
        assertNull(storedRefresh.get())
        verify(exactly = 0) { sessionManager.saveTokens(jwt(t + 900), any()) }
    }

    @Test(timeout = 20_000)
    fun `an account switch while a refresh is out is never overwritten by the old session's reply`() {
        storingPair(jwt(t), "stored-refresh")
        val other = jwt(t + 100, sid = "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9", sub = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d")
        whileARefreshIsOut(withJar(null, now = t + 60), jwt(t), jwt(t + 900), "next-refresh") {
            sessionManager.saveTokens(other, "other-refresh")
        }
        assertEquals("the other account's session stays", other, stored.get())
        assertEquals("other-refresh", storedRefresh.get())
        verify(exactly = 0) { sessionManager.saveTokens(jwt(t + 900), any()) }
    }

    @Test
    fun `a refresh sent with the jar's token stores only a later pair of the stored session`() {
        storingPair(jwt(t), "stored-refresh")
        // The jar's stale pair is ahead; its refresh token answers with another session's pair.
        serverRenews(jwt(t + 900, sid = "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9"), "foreign-refresh")
        val auth = withJar(PageSessionJar.Held(jwt(t + 30), "page-refresh"), now = t + 900)
        assertEquals(TokenAuthenticator.RefreshOutcome.UNREACHABLE, auth.refreshSession(jwt(t), 5_000))
        assertEquals(listOf("page-refresh"), sentRefreshTokens)
        assertEquals("the stored session kept", jwt(t), stored.get())
        assertEquals("stored-refresh", storedRefresh.get())
    }

    // =============================================================================
    // A REFRESHED PAIR INTO THE PAGE'S JAR AT ONCE (review of 8e7739c)
    // =============================================================================

    private val jarWrites = mutableListOf<Pair<String, String>>()

    private fun withJarWrites(held: PageSessionJar.Held?, now: Long) = TokenAuthenticator(
        sessionManager,
        authApiProvider,
        setOf("api.example.com"),
        { held },
        { now },
        { access, refresh -> jarWrites += access to refresh },
    )

    @Test
    fun `a stored reply goes into the page's jar at once, on the refresh and the 401 paths`() {
        storingPair(jwt(t), "stored-refresh")
        serverRenews(jwt(t + 900), "next-refresh")
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, withJarWrites(PageSessionJar.Held(jwt(t), "stored-refresh"), now = t + 900).refreshSession(jwt(t), 5_000))
        assertEquals(listOf(jwt(t + 900) to "next-refresh"), jarWrites)

        jarWrites.clear()
        storingPair(jwt(t), "stored-refresh")
        serverRenews(jwt(t + 901), "after-401-refresh")
        val retry = withJarWrites(null, now = t + 900).authenticate(null, unauthorized(jwt(t)))
        assertEquals("Bearer ${jwt(t + 901)}", retry?.header("Authorization"))
        assertEquals(listOf(jwt(t + 901) to "after-401-refresh"), jarWrites)
    }

    @Test
    fun `nothing is written into the jar for an adoption, a dropped reply, or over a later pair there`() {
        // An adoption: the jar already holds the pair.
        storingPair(jwt(t), "stored-refresh")
        withJarWrites(PageSessionJar.Held(jwt(t + 30), "page-refresh"), now = t + 60).refreshSession(jwt(t), 5_000)
        assertEquals(emptyList<Pair<String, String>>(), jarWrites)
        // A dropped reply (signed out meanwhile).
        storingPair(jwt(t), "stored-refresh")
        whileARefreshIsOut(withJarWrites(null, now = t + 60), jwt(t), jwt(t + 900), "next-refresh") {
            sessionManager.clearAuthOnly()
        }
        assertEquals(emptyList<Pair<String, String>>(), jarWrites)
        // The jar holds a later pair of the session than the reply (refreshed in the page meanwhile).
        storingPair(jwt(t), "stored-refresh")
        serverRenews(jwt(t + 900), "next-refresh")
        val laterInJar = PageSessionJar.Held(jwt(t + 950), "page-refresh")
        var calls = 0
        val auth = TokenAuthenticator(
            sessionManager,
            authApiProvider,
            setOf("api.example.com"),
            { if (calls++ == 0) PageSessionJar.Held(jwt(t), "stored-refresh") else laterInJar },
            { t + 900 },
            { access, refresh -> jarWrites += access to refresh },
        )
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, auth.refreshSession(jwt(t), 5_000))
        assertEquals(emptyList<Pair<String, String>>(), jarWrites)
    }

    // =============================================================================
    // NEVER RETRIED ON ANOTHER ACCOUNT'S TOKEN (after 0b11d8b)
    // =============================================================================

    private val otherUser = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"

    /** An account switch while a 401's refresh is out: the reply is dropped (0b11d8b), and the
     *  401's request is not retried on the new account's token. */
    @Test(timeout = 20_000)
    fun `a switch while a 401 is out is never retried on the new account's token`() {
        storingPair(jwt(t), "stored-refresh")
        val other = jwt(t + 100, sid = "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9", sub = otherUser)
        val asked = java.util.concurrent.CountDownLatch(1)
        val answer = java.util.concurrent.CountDownLatch(1)
        coEvery { authApi.refreshToken(any(), any()) } coAnswers {
            asked.countDown()
            answer.await(10, java.util.concurrent.TimeUnit.SECONDS)
            RetrofitResponse.success(ApiResponse(success = true, data = TokenResponse(accessToken = jwt(t + 900), refreshToken = "next-refresh")))
        }
        val auth = withJar(null, now = t + 60)
        var retry: Request? = Request.Builder().url("https://api.example.com/").build()
        val sync = kotlin.concurrent.thread { retry = auth.authenticate(null, unauthorized(jwt(t))) }
        assertTrue(asked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        sessionManager.saveTokens(other, "other-refresh")
        answer.countDown()
        sync.join(10_000)
        assertNull("not retried on another account's token", retry)
        assertEquals(other, stored.get())
    }

    /** The refresher's own answer, for every caller that acts on REFRESHED (the page session
     *  re-seeds, the cold open loads): a switch while its refresh is out is no answer for it. */
    @Test(timeout = 20_000)
    fun `refreshSession_aSwitchWhileItIsOut_isNoAnswerForTheCaller`() {
        storingPair(jwt(t), "stored-refresh")
        val other = jwt(t + 100, sid = "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9", sub = otherUser)
        val outcome = whileARefreshIsOut(withJar(null, now = t + 60), jwt(t), jwt(t + 900), "next-refresh") {
            sessionManager.saveTokens(other, "other-refresh")
        }
        assertEquals(TokenAuthenticator.RefreshOutcome.UNREACHABLE, outcome)
        assertEquals(other, stored.get())
    }

    @Test
    fun `a 401 from the old account after a switch is answered with no refresh, and not retried`() {
        storingPair(jwt(t + 100, sid = "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9", sub = otherUser), "other-refresh")
        val auth = withJar(null, now = t + 60)
        assertEquals(TokenAuthenticator.RefreshOutcome.UNREACHABLE, auth.refreshSession(jwt(t), 5_000))
        assertNull(auth.authenticate(null, unauthorized(jwt(t))))
        coVerify(exactly = 0) { authApi.refreshToken(any(), any()) }
        // The same account's newer token still stands in, as before.
        storingPair(jwt(t + 100), "stored-refresh")
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, auth.refreshSession(jwt(t), 5_000))
        assertEquals("Bearer ${jwt(t + 100)}", auth.authenticate(null, unauthorized(jwt(t)))?.header("Authorization"))
        // Upper-case ids are the same account.
        storingPair(jwt(t + 100, sub = user.uppercase()), "stored-refresh")
        assertEquals(TokenAuthenticator.RefreshOutcome.REFRESHED, auth.refreshSession(jwt(t), 5_000))
    }

    @Test
    fun `a switch between the refresh's answer and the retry is not retried either`() {
        val sameAccountNewer = jwt(t + 100)
        val other = jwt(t + 200, sid = "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9", sub = otherUser)
        every { sessionManager.getRefreshToken() } returns "stored-refresh"
        // The refresher's read finds the same account's newer token; the retry's, the other's.
        every { sessionManager.getAccessToken() } returnsMany listOf(sameAccountNewer, other)
        val auth = withJar(null, now = t + 60)
        assertNull(auth.authenticate(null, unauthorized(jwt(t))))
    }
}

