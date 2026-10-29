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

        coEvery { authApi.refreshToken(any(), any()) } returns
            RetrofitResponse.error(401, "Unauthorized".toResponseBody())

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
}
