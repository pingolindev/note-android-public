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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * OkHttp interceptor that handles HTTP 429 (Too Many Requests) responses.
 *
 * When the server returns 429, this interceptor:
 * 1. Parses the Retry-After header (if present)
 * 2. Falls back to exponential backoff with jitter if no header
 * 3. Waits and retries the request up to MAX_RETRIES times
 */
@Singleton
class RateLimitInterceptor @Inject constructor() : Interceptor {

    companion object {
        private const val TAG = "RateLimitInterceptor"
        private const val MAX_RETRIES = 2
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 10_000L
        private const val MAX_RETRY_AFTER_MS = 10_000L
        private const val MAX_TOTAL_RETRY_TIME_MS = 20_000L
        private const val HTTP_TOO_MANY_REQUESTS = 429
        internal const val TWO_STEP_CODE_PATH = "/api/v1/auth/login/2fa"
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        // A two-step code is never sent again: the auth server counts every
        // try, and its 429 after the attempt's 5th wrong code ends the
        // attempt, so a retry would only make the person wait, then read
        // "That sign-in has ended".
        if (request.url.encodedPath == TWO_STEP_CODE_PATH) return response
        var retryCount = 0
        var totalWaitTime = 0L

        while (response.code == HTTP_TOO_MANY_REQUESTS && retryCount < MAX_RETRIES) {
            retryCount++
            val waitTime = calculateWaitTime(response, retryCount)

            if (totalWaitTime + waitTime > MAX_TOTAL_RETRY_TIME_MS) {
                Log.w(TAG, "Rate limit retry would exceed max total time for ${request.url}")
                break
            }

            Log.w(TAG, "Rate limited (429) on ${request.url}. Retry $retryCount/$MAX_RETRIES after ${waitTime}ms")
            response.close()

            try {
                Thread.sleep(waitTime)
                totalWaitTime += waitTime
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Rate limit retry interrupted", e)
            }

            response = chain.proceed(request)
        }

        return response
    }

    private fun calculateWaitTime(response: Response, retryCount: Int): Long {
        val retryAfter = response.header("Retry-After")
        if (retryAfter != null) {
            try {
                val seconds = retryAfter.toLong()
                return min(seconds * 1000, MAX_RETRY_AFTER_MS)
            } catch (e: NumberFormatException) {
                Log.d(TAG, "Could not parse Retry-After header: $retryAfter")
            }
        }
        return calculateExponentialBackoff(retryCount)
    }

    private fun calculateExponentialBackoff(retryCount: Int): Long {
        val exponentialDelay = INITIAL_BACKOFF_MS * 2.0.pow(retryCount - 1).toLong()
        val cappedDelay = min(exponentialDelay, MAX_BACKOFF_MS)
        val jitter = (cappedDelay * Random.nextDouble(0.0, 0.25)).toLong()
        return cappedDelay + jitter
    }
}
