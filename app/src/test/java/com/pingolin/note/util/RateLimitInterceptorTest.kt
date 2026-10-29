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

import io.mockk.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class RateLimitInterceptorTest {

    private lateinit var interceptor: RateLimitInterceptor

    @Before
    fun setup() {
        interceptor = RateLimitInterceptor()
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    // =============================================================================
    // NON-429 RESPONSE TESTS
    // =============================================================================

    @Test
    fun `intercept_successResponse_returnsImmediately`() {
        val chain = createMockChain(responseCode = 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        verify(exactly = 1) { chain.proceed(any()) }
    }

    @Test
    fun `intercept_400Response_returnsImmediately`() {
        val chain = createMockChain(responseCode = 400)

        val response = interceptor.intercept(chain)

        assertEquals(400, response.code)
        verify(exactly = 1) { chain.proceed(any()) }
    }

    @Test
    fun `intercept_500Response_returnsImmediately`() {
        val chain = createMockChain(responseCode = 500)

        val response = interceptor.intercept(chain)

        assertEquals(500, response.code)
        verify(exactly = 1) { chain.proceed(any()) }
    }

    // =============================================================================
    // 429 RESPONSE TESTS
    // =============================================================================

    @Test
    fun `a two-step code's 429 is never sent again`() {
        // After the attempt's 5th wrong code the auth server answers 429 and ends
        // the attempt: a retry would only make the person wait, then read "That
        // sign-in has ended".
        val request = Request.Builder().url("https://api-auth.pingolindev.com/api/v1/auth/login/2fa").build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns createResponse(request, 429)

        val result = interceptor.intercept(chain)

        assertEquals(429, result.code)
        verify(exactly = 1) { chain.proceed(request) }
    }

    @Test
    fun `only the two-step code route is left alone - the password step's 429 is still retried`() {
        val request = Request.Builder().url("https://api-auth.pingolindev.com/api/v1/auth/srp/step2").build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returnsMany listOf(createResponse(request, 429, retryAfter = "0"), createResponse(request, 200))

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        verify(exactly = 2) { chain.proceed(request) }
    }

    @Test
    fun `intercept_429ThenSuccess_retriesAndReturnsSuccess`() {
        val request = Request.Builder().url("https://api.example.com/test").build()
        val response429 = createResponse(request, 429)
        val response200 = createResponse(request, 200)

        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returnsMany listOf(response429, response200)

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        verify(exactly = 2) { chain.proceed(request) }
    }

    @Test
    fun `intercept_persistent429_stopsAfterMaxRetries`() {
        val request = Request.Builder().url("https://api.example.com/test").build()
        val response429_1 = createResponse(request, 429)
        val response429_2 = createResponse(request, 429)
        val response429_3 = createResponse(request, 429)

        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returnsMany listOf(response429_1, response429_2, response429_3)

        val result = interceptor.intercept(chain)

        assertEquals(429, result.code)
        // MAX_RETRIES is 2, so 1 initial + 2 retries = 3 calls
        verify(exactly = 3) { chain.proceed(request) }
    }

    // =============================================================================
    // RETRY-AFTER HEADER TESTS
    // =============================================================================

    @Test
    fun `intercept_429WithRetryAfterHeader_usesHeaderValue`() {
        val request = Request.Builder().url("https://api.example.com/test").build()
        val response429 = createResponse(request, 429, retryAfter = "1")
        val response200 = createResponse(request, 200)

        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returnsMany listOf(response429, response200)

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
    }

    @Test
    fun `intercept_429WithLargeRetryAfter_cappedToMax`() {
        val request = Request.Builder().url("https://api.example.com/test").build()
        // Retry-After = 120 seconds, should be capped to 10 seconds
        val response429 = createResponse(request, 429, retryAfter = "120")
        val response200 = createResponse(request, 200)

        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returnsMany listOf(response429, response200)

        // This should not block for 120s, should be capped
        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    private fun createMockChain(responseCode: Int): Interceptor.Chain {
        val request = Request.Builder().url("https://api.example.com/test").build()
        val response = createResponse(request, responseCode)

        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response

        return chain
    }

    private fun createResponse(
        request: Request,
        code: Int,
        retryAfter: String? = null,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("OK")
            .code(code)
            .body("{}".toResponseBody("application/json".toMediaType()))

        retryAfter?.let { builder.header("Retry-After", it) }

        return builder.build()
    }
}
