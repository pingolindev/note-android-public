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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a prefetched `/auth/me` may answer a request.
 *
 * ## Why this is the test worth having
 *
 * [AuthMePrefetch] holds a CREDENTIALED, per-user response in a process-wide
 * singleton and hands it to the WebView. The performance win is worth nothing if
 * that response can ever reach the wrong request, and the worst version of that
 * failure — serving one account's identity to another after a switch — would be
 * silent, because the body is a perfectly valid `/auth/me` payload. The check is
 * therefore a pure function, so every refusal can be asserted here rather than
 * inferred from a device log.
 *
 * `mayServe` is written so every clause is a refusal and the only way through is
 * the exact request it was fetched for. These cases are one per clause.
 */
class AuthMePrefetchPolicyTest {

    private val url = "https://note.pingolindev.com/api/v1/auth/me"
    private val tokenA = "eyJhbGciOiJIUzI1NiJ9.aaaaaaaaaaaaaaaa.signature-a"
    private val tokenB = "eyJhbGciOiJIUzI1NiJ9.bbbbbbbbbbbbbbbb.signature-b"
    private val fpA = AuthMePrefetch.fingerprint(tokenA)
    private val at = 1_700_000_000_000L

    private fun mayServe(
        storedUrl: String = url,
        requestUrl: String = url,
        storedFp: String = fpA,
        liveToken: String? = tokenA,
        fetchedAt: Long = at,
        now: Long = at + 1_000L,
    ) = AuthMePrefetch.mayServe(storedUrl, requestUrl, storedFp, liveToken, fetchedAt, now)

    @Test
    fun `the request it was fetched for is served`() {
        assertTrue(mayServe())
    }

    @Test
    fun `a DIFFERENT token is refused — this is the account-switch case`() {
        assertFalse(
            "a response fetched under one account must never answer another's request",
            mayServe(liveToken = tokenB),
        )
    }

    @Test
    fun `no live token is refused`() {
        assertFalse(mayServe(liveToken = null))
        assertFalse(mayServe(liveToken = ""))
        assertFalse(mayServe(liveToken = "   "))
    }

    @Test
    fun `a different URL is refused`() {
        assertFalse(mayServe(requestUrl = "https://note.pingolindev.com/api/v1/auth/preferences"))
        // Not a prefix match either: /auth/me must not answer /auth/me/anything.
        assertFalse(mayServe(requestUrl = "$url/sessions"))
        assertFalse(mayServe(requestUrl = "https://evil.example.com/api/v1/auth/me"))
    }

    @Test
    fun `a stale result is refused`() {
        assertTrue("20s is the documented ceiling", mayServe(now = at + 20_000L))
        assertFalse(mayServe(now = at + 20_001L))
        assertFalse(mayServe(now = at + 60_000L))
    }

    @Test
    fun `a backwards clock is refused rather than treated as fresh`() {
        // now - fetchedAt would be negative and sail under the ceiling.
        assertFalse(mayServe(now = at - 1L))
        assertFalse(mayServe(now = at - 10_000_000L))
    }

    @Test
    fun `the fingerprint separates tokens and is stable for one`() {
        assertEquals(AuthMePrefetch.fingerprint(tokenA), AuthMePrefetch.fingerprint(tokenA))
        assertNotEquals(AuthMePrefetch.fingerprint(tokenA), AuthMePrefetch.fingerprint(tokenB))
        // A near-miss must not collide: tokens differing in one character are
        // exactly what a rotated token looks like.
        assertNotEquals(
            AuthMePrefetch.fingerprint(tokenA),
            AuthMePrefetch.fingerprint(tokenA.dropLast(1) + "b"),
        )
        // It is a digest, not the token — the token must not be recoverable
        // from, or present in, what we retain.
        assertFalse(AuthMePrefetch.fingerprint(tokenA).contains(tokenA))
        assertEquals(16, AuthMePrefetch.fingerprint(tokenA).length)
    }

    @Test
    fun `the URL is built off the base with or without a trailing slash`() {
        assertEquals(url, AuthMePrefetch.urlFor("https://note.pingolindev.com/"))
        assertEquals(url, AuthMePrefetch.urlFor("https://note.pingolindev.com"))
    }
}
