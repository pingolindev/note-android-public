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

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Circuit breaker interceptor to protect against cascading failures.
 *
 * Implements the circuit breaker pattern for API endpoints:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Too many failures, requests fail fast without hitting the server
 * - HALF_OPEN: Testing if service has recovered
 */
@Singleton
class CircuitBreakerInterceptor @Inject constructor() : Interceptor {

    companion object {
        private const val TAG = "CircuitBreaker"
        private const val FAILURE_THRESHOLD = 5
        private const val RECOVERY_TIMEOUT_MS = 30_000L

        private val PROTECTED_PATHS = listOf(
            "/api/v1/items",
            "/api/v1/sync/",
            "/api/v1/reminders",
            "/api/v1/attachments"
        )
    }

    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private class CircuitState(
        @Volatile var state: State = State.CLOSED,
        val failureCount: AtomicInteger = AtomicInteger(0),
        val lastFailureTime: AtomicLong = AtomicLong(0),
        val openedAt: AtomicLong = AtomicLong(0)
    )

    private val circuits = ConcurrentHashMap<String, CircuitState>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        val circuitKey = getCircuitKey(path) ?: return chain.proceed(request)
        val circuit = circuits.getOrPut(circuitKey) { CircuitState() }

        return when (getCurrentState(circuit)) {
            State.OPEN -> {
                Log.w(TAG, "Circuit OPEN for $circuitKey, failing fast")
                throw CircuitBreakerOpenException(
                    "Circuit breaker is open for $circuitKey. Service appears to be unavailable."
                )
            }
            State.HALF_OPEN -> {
                Log.d(TAG, "Circuit HALF_OPEN for $circuitKey, attempting recovery")
                executeWithTracking(chain, circuit, circuitKey)
            }
            State.CLOSED -> {
                executeWithTracking(chain, circuit, circuitKey)
            }
        }
    }

    private fun executeWithTracking(
        chain: Interceptor.Chain,
        circuit: CircuitState,
        circuitKey: String
    ): Response {
        return try {
            val response = chain.proceed(chain.request())

            if (response.isSuccessful || response.code in 400..499) {
                onSuccess(circuit, circuitKey)
            } else if (response.code >= 500) {
                onFailure(circuit, circuitKey)
            }

            response
        } catch (e: IOException) {
            onFailure(circuit, circuitKey)
            throw e
        }
    }

    private fun getCircuitKey(path: String): String? {
        return PROTECTED_PATHS.find { path.contains(it) }
    }

    private fun getCurrentState(circuit: CircuitState): State {
        return when (circuit.state) {
            State.OPEN -> {
                val timeSinceOpen = System.currentTimeMillis() - circuit.openedAt.get()
                if (timeSinceOpen >= RECOVERY_TIMEOUT_MS) State.HALF_OPEN else State.OPEN
            }
            else -> circuit.state
        }
    }

    private fun onSuccess(circuit: CircuitState, circuitKey: String) {
        if (circuit.failureCount.get() > 0) {
            Log.d(TAG, "Circuit $circuitKey: success, resetting failure count")
        }
        circuit.failureCount.set(0)
        circuit.state = State.CLOSED
    }

    private fun onFailure(circuit: CircuitState, circuitKey: String) {
        val failures = circuit.failureCount.incrementAndGet()
        circuit.lastFailureTime.set(System.currentTimeMillis())

        Log.w(TAG, "Circuit $circuitKey: failure #$failures")

        if (failures >= FAILURE_THRESHOLD) {
            circuit.openedAt.set(System.currentTimeMillis())
            circuit.state = State.OPEN
        }
    }

    fun resetAll() {
        circuits.clear()
    }
}

class CircuitBreakerOpenException(message: String) : IOException(message)
