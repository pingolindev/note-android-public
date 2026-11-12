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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored access token's expiry, read from its own `exp`, which decides whether a cold open
 * refreshes before the page loads (WebAppActivity, ColdOpenSession).
 *
 * WHY THESE EXIST
 *   A dead token handed to the page's boot ends at the account portal asking for the password.
 *   Anything this can't read must count as expired: one refresh too many costs a round trip,
 *   one too few costs the password prompt.
 */
class AccessTokenExpiryTest {

    private val now = 1_800_000_000L

    private val b64 = java.util.Base64.getUrlEncoder().withoutPadding()

    private fun token(payload: String, header: String = """{"alg":"EdDSA","typ":"JWT"}"""): String =
        b64.encodeToString(header.toByteArray()) + "." + b64.encodeToString(payload.toByteArray()) + ".c2lnbmF0dXJl"

    private fun expiringAt(exp: Long) = token("""{"sub":"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","exp":$exp,"iat":${exp - 900}}""")

    @Test
    fun `reads the exp of a token`() {
        assertEquals(now + 900, AccessTokenExpiry.expiresAt(expiringAt(now + 900)))
        assertEquals("a fractional NumericDate", now + 900, AccessTokenExpiry.expiresAt(token("""{"exp":${now + 900}.75}""")))
    }

    @Test
    fun `an expired token needs a refresh`() {
        assertTrue(AccessTokenExpiry.needsRefresh(expiringAt(now - 3_600), now))
        assertTrue(AccessTokenExpiry.needsRefresh(expiringAt(now - 1), now))
        assertTrue(AccessTokenExpiry.needsRefresh(expiringAt(now), now))
    }

    @Test
    fun `a token expiring within the minute needs one too`() {
        assertTrue(AccessTokenExpiry.needsRefresh(expiringAt(now + 1), now))
        assertTrue(AccessTokenExpiry.needsRefresh(expiringAt(now + AccessTokenExpiry.MARGIN_SECONDS), now))
    }

    @Test
    fun `a valid token needs none`() {
        assertFalse(AccessTokenExpiry.needsRefresh(expiringAt(now + AccessTokenExpiry.MARGIN_SECONDS + 1), now))
        assertFalse(AccessTokenExpiry.needsRefresh(expiringAt(now + 900), now))
    }

    @Test
    fun `a token that can't be read counts as expired`() {
        val unreadable = listOf(
            null,
            "",
            "   ",
            "not-a-jwt",
            "only.two",
            "one.two.three.four",
            "header..signature",
            "header.!!!not*base64.signature",
            token("not json"),
            token("""["exp", 1800000900]"""),
            token("""{"sub":"x"}"""),
            token("""{"exp":"1800000900"}"""),
            token("""{"exp":null}"""),
            token("""{"exp":true}"""),
        )
        for (t in unreadable) {
            assertNull("$t", AccessTokenExpiry.expiresAt(t))
            assertTrue("$t", AccessTokenExpiry.needsRefresh(t, now))
        }
    }

    @Test
    fun `a payload in base64url, with no padding, reads`() {
        // Chosen so its base64 holds both characters base64url replaces ('+' and '/').
        val payload = """{"exp":${now + 900},"n":"?>>~"}"""
        val standard = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())
        assertTrue(standard.contains('+') || standard.contains('/'))
        assertEquals(now + 900, AccessTokenExpiry.expiresAt(token(payload)))
    }

    private val user = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val sessionId = "7d9e1f3a-2b4c-4d6e-8f0a-1b2c3d4e5f6a"

    private fun issued(iat: Long, sid: String = sessionId, sub: String = user) =
        token("""{"sub":"$sub","sid":"$sid","iat":$iat,"exp":${iat + 900}}""")

    @Test
    fun `reads who a token was issued to, for which session, and when`() {
        assertEquals(AccessTokenExpiry.Issue(user, sessionId, now), AccessTokenExpiry.issueOf(issued(now)))
        assertEquals(AccessTokenExpiry.Issue(null, null, now), AccessTokenExpiry.issueOf(token("""{"iat":$now}""")))
        assertEquals(AccessTokenExpiry.Issue(null, null, null), AccessTokenExpiry.issueOf(token("""{"sub":"","sid":7,"iat":"soon"}""")))
        for (unreadable in listOf(null, "", "a.b", "a..c", "a.!!!.c", "a.${b64.encodeToString("[1]".toByteArray())}.c")) {
            assertNull("$unreadable", AccessTokenExpiry.issueOf(unreadable))
        }
    }

    /** Review of 04f37ce: which of two tokens of one session is newer, by the auth server's own
     *  clock (`iat`); never a token of another session or account. */
    @Test
    fun `compares the issue of two tokens of one session, and nothing else`() {
        assertEquals(1, AccessTokenExpiry.compareIssue(issued(now + 30), issued(now)))
        assertEquals(0, AccessTokenExpiry.compareIssue(issued(now), issued(now)))
        assertEquals(-1, AccessTokenExpiry.compareIssue(issued(now - 30), issued(now)))
        assertEquals("ids compared as UUIDs", 1, AccessTokenExpiry.compareIssue(issued(now + 30, sessionId.uppercase(), user.uppercase()), issued(now)))
        val notComparable = listOf(
            "another session" to issued(now + 30, sid = "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9"),
            "another account" to issued(now + 30, sub = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"),
            "no sid" to token("""{"sub":"$user","iat":${now + 30}}"""),
            "no sub" to token("""{"sid":"$sessionId","iat":${now + 30}}"""),
            "no iat" to token("""{"sub":"$user","sid":"$sessionId"}"""),
            "unreadable" to "not.a.jwt",
            "none" to null,
        )
        for ((what, candidate) in notComparable) {
            assertNull(what, AccessTokenExpiry.compareIssue(candidate, issued(now)))
            assertNull("$what, the other way", AccessTokenExpiry.compareIssue(issued(now), candidate))
        }
    }
}

