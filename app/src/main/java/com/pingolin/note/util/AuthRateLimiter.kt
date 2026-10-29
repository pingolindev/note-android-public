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

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side rate limiter for authentication attempts.
 *
 * Prevents brute force attacks by limiting login attempts with
 * exponential backoff. Complements server-side rate limiting and gives
 * immediate feedback to the UI ("locked out for 30s") without waiting
 * for the server to refuse — important on cellular where each refused
 * attempt would otherwise spin the login button for several seconds.
 *
 * The same semantics as the Track Android app's AuthRateLimiter — the
 * two clients deliberately share this contract.
 *
 * Security properties:
 * - 5 failed attempts triggers lockout
 * - Exponential backoff: 30s, 60s, 120s, 240s, 480s (max)
 * - Per-email tracking prevents lockout bypass with different emails
 * - Automatically clears after successful login
 *
 * Thread safety:
 * - Uses ConcurrentHashMap for lock-free reads
 * - Uses compute() for atomic updates to prevent race conditions
 */
@Singleton
class AuthRateLimiter @Inject constructor() {

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 480_000L
        private const val ATTEMPT_WINDOW_MS = 900_000L
    }

    private data class AttemptRecord(
        val failedAttempts: Int,
        val lastAttemptTime: Long,
        val lockoutUntil: Long,
    )

    private val attemptRecords = ConcurrentHashMap<String, AttemptRecord>()

    fun checkAttempt(email: String): RateLimitResult {
        val key = email.lowercase().trim()
        val now = System.currentTimeMillis()
        val record = attemptRecords[key]

        if (record == null || (now - record.lastAttemptTime) > ATTEMPT_WINDOW_MS) {
            return RateLimitResult.Allowed
        }
        if (now < record.lockoutUntil) {
            val waitSeconds = ((record.lockoutUntil - now) / 1000).toInt()
            return RateLimitResult.RateLimited(waitSeconds)
        }
        return RateLimitResult.Allowed
    }

    fun recordFailedAttempt(email: String) {
        val key = email.lowercase().trim()
        val now = System.currentTimeMillis()
        attemptRecords.compute(key) { _, existing ->
            val newAttempts: Int
            val lockoutUntil: Long
            if (existing == null || (now - existing.lastAttemptTime) > ATTEMPT_WINDOW_MS) {
                newAttempts = 1
                lockoutUntil = 0L
            } else {
                newAttempts = existing.failedAttempts + 1
                lockoutUntil = if (newAttempts >= MAX_ATTEMPTS) {
                    now + calculateLockoutDuration(newAttempts)
                } else 0L
            }
            AttemptRecord(newAttempts, now, lockoutUntil)
        }
    }

    fun recordSuccess(email: String) {
        attemptRecords.remove(email.lowercase().trim())
    }

    fun clearAll() {
        attemptRecords.clear()
    }

    fun getRemainingAttempts(email: String): Int {
        val key = email.lowercase().trim()
        val now = System.currentTimeMillis()
        val record = attemptRecords[key]
        if (record == null || (now - record.lastAttemptTime) > ATTEMPT_WINDOW_MS) {
            return MAX_ATTEMPTS
        }
        return (MAX_ATTEMPTS - record.failedAttempts).coerceAtLeast(0)
    }

    /** Exponential backoff: 30s, 60s, 120s, 240s, 480s capped. */
    private fun calculateLockoutDuration(attempts: Int): Long {
        val overLimit = (attempts - MAX_ATTEMPTS).coerceIn(0, 4)
        val multiplier = (1 shl overLimit)
        return (INITIAL_LOCKOUT_MS * multiplier).coerceAtMost(MAX_LOCKOUT_MS)
    }
}

sealed class RateLimitResult {
    object Allowed : RateLimitResult()
    data class RateLimited(val waitSeconds: Int) : RateLimitResult()
}
