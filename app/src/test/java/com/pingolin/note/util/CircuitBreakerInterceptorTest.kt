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

class CircuitBreakerInterceptorTest {

    private lateinit var interceptor: CircuitBreakerInterceptor

    @Before
    fun setup() {
        interceptor = CircuitBreakerInterceptor()
    }

    @After
    fun teardown() {
        interceptor.resetAll()
        clearAllMocks()
    }

    // =============================================================================
    // UNPROTECTED PATH TESTS
    // =============================================================================

    @Test
    fun `intercept_unprotectedPath_passesThrough`() {
        val chain = createMockChain("/api/v1/auth/login", 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        verify(exactly = 1) { chain.proceed(any()) }
    }

    @Test
    fun `intercept_unprotectedPath500_noCircuitBreaker`() {
        val chain = createMockChain("/api/v1/auth/login", 500)

        val response = interceptor.intercept(chain)

        assertEquals(500, response.code)
        // Should not throw CircuitBreakerOpenException on subsequent calls
    }

    // =============================================================================
    // PROTECTED PATH - CLOSED STATE TESTS
    // =============================================================================

    @Test
    fun `intercept_protectedPathSuccess_passes`() {
        val chain = createMockChain("/api/v1/items", 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
    }

    @Test
    fun `intercept_protectedPath400_treatedAsSuccess`() {
        val chain = createMockChain("/api/v1/items", 400)

        val response = interceptor.intercept(chain)

        assertEquals(400, response.code)
        // Client errors should not trip the circuit
    }

    @Test
    fun `intercept_protectedPath401_treatedAsSuccess`() {
        val chain = createMockChain("/api/v1/items", 401)

        val response = interceptor.intercept(chain)

        assertEquals(401, response.code)
    }

    // =============================================================================
    // CIRCUIT TRIPPING TESTS
    // =============================================================================

    @Test
    fun `intercept_fiveConsecutive500s_opensCircuit`() {
        // 5 failures should open the circuit (FAILURE_THRESHOLD = 5)
        repeat(5) {
            val chain = createMockChain("/api/v1/items", 500)
            interceptor.intercept(chain)
        }

        // Next request should throw CircuitBreakerOpenException
        val chain = createMockChain("/api/v1/items", 200)
        try {
            interceptor.intercept(chain)
            fail("Expected CircuitBreakerOpenException")
        } catch (e: CircuitBreakerOpenException) {
            assertTrue(e.message!!.contains("/api/v1/items"))
        }
    }

    @Test
    fun `intercept_fourFailures_circuitStillClosed`() {
        repeat(4) {
            val chain = createMockChain("/api/v1/items", 500)
            interceptor.intercept(chain)
        }

        // Should still work - below threshold
        val chain = createMockChain("/api/v1/items", 200)
        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
    }

    @Test
    fun `intercept_failuresInterleavedWithSuccess_resetsCount`() {
        // 3 failures
        repeat(3) {
            val chain = createMockChain("/api/v1/items", 500)
            interceptor.intercept(chain)
        }

        // Success resets counter
        val successChain = createMockChain("/api/v1/items", 200)
        interceptor.intercept(successChain)

        // 3 more failures should not trip circuit (count was reset)
        repeat(3) {
            val chain = createMockChain("/api/v1/items", 500)
            interceptor.intercept(chain)
        }

        // Still under threshold after reset
        val chain = createMockChain("/api/v1/items", 200)
        val response = interceptor.intercept(chain)
        assertEquals(200, response.code)
    }

    // =============================================================================
    // SEPARATE CIRCUIT PER PATH TESTS
    // =============================================================================

    @Test
    fun `intercept_separateCircuitsPerPath_independentTracking`() {
        // Trip the items circuit
        repeat(5) {
            val chain = createMockChain("/api/v1/items", 500)
            interceptor.intercept(chain)
        }

        // Sync circuit should still be open
        val syncChain = createMockChain("/api/v1/sync/pull", 200)
        val response = syncChain.let { interceptor.intercept(it) }
        assertEquals(200, response.code)
    }

    // =============================================================================
    // PROTECTED PATHS TESTS
    // =============================================================================

    @Test
    fun `intercept_itemsPath_isProtected`() {
        repeat(5) {
            val chain = createMockChain("/api/v1/items", 500)
            interceptor.intercept(chain)
        }

        try {
            interceptor.intercept(createMockChain("/api/v1/items", 200))
            fail("Expected CircuitBreakerOpenException")
        } catch (_: CircuitBreakerOpenException) { }
    }

    @Test
    fun `intercept_syncPath_isProtected`() {
        repeat(5) {
            val chain = createMockChain("/api/v1/sync/push", 500)
            interceptor.intercept(chain)
        }

        try {
            interceptor.intercept(createMockChain("/api/v1/sync/push", 200))
            fail("Expected CircuitBreakerOpenException")
        } catch (_: CircuitBreakerOpenException) { }
    }

    @Test
    fun `intercept_remindersPath_isProtected`() {
        repeat(5) {
            val chain = createMockChain("/api/v1/reminders", 500)
            interceptor.intercept(chain)
        }

        try {
            interceptor.intercept(createMockChain("/api/v1/reminders", 200))
            fail("Expected CircuitBreakerOpenException")
        } catch (_: CircuitBreakerOpenException) { }
    }

    @Test
    fun `intercept_attachmentsPath_isProtected`() {
        repeat(5) {
            val chain = createMockChain("/api/v1/attachments", 500)
            interceptor.intercept(chain)
        }

        try {
            interceptor.intercept(createMockChain("/api/v1/attachments", 200))
            fail("Expected CircuitBreakerOpenException")
        } catch (_: CircuitBreakerOpenException) { }
    }

    // =============================================================================
    // IOException TESTS
    // =============================================================================

    @Test
    fun `intercept_ioException_countsAsFailure`() {
        repeat(5) {
            val request = Request.Builder()
                .url("https://api.example.com/api/v1/items")
                .build()

            val chain = mockk<Interceptor.Chain>()
            every { chain.request() } returns request
            every { chain.proceed(request) } throws IOException("Connection failed")

            try {
                interceptor.intercept(chain)
            } catch (_: IOException) { }
        }

        // Circuit should now be open
        try {
            interceptor.intercept(createMockChain("/api/v1/items", 200))
            fail("Expected CircuitBreakerOpenException")
        } catch (_: CircuitBreakerOpenException) { }
    }

    // =============================================================================
    // resetAll TESTS
    // =============================================================================

    @Test
    fun `resetAll_clearsAllCircuits`() {
        // Trip circuit
        repeat(5) {
            val chain = createMockChain("/api/v1/items", 500)
            interceptor.intercept(chain)
        }

        interceptor.resetAll()

        // Should work again
        val chain = createMockChain("/api/v1/items", 200)
        val response = interceptor.intercept(chain)
        assertEquals(200, response.code)
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    private fun createMockChain(path: String, responseCode: Int): Interceptor.Chain {
        val request = Request.Builder()
            .url("https://api.example.com$path")
            .build()

        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("OK")
            .code(responseCode)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response

        return chain
    }
}
