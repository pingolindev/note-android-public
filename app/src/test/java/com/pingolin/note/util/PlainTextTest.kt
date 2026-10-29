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

import com.pingolin.note.data.remote.dto.ApiErrors
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.MobileException

/**
 * What a person reads about a failure is this app's own words (as the Track Android app's
 * PlainTextTest and ApiErrors): never the server's `error` text, the SDK's, a socket's or a
 * parser's.
 */
class PlainTextTest {

    private val fallback = "Couldn't do that just now."

    @Test
    fun `the SDK's own words never show`() {
        assertEquals(fallback, MobileException.KeyNotFound("Circle key not found: c-1").plainText(fallback))
        assertEquals(fallback, MobileException.DecryptionFailed("wrong key for this backup").plainText(fallback))
        assertEquals(UNREACHABLE_TEXT, MobileException.NetworkException("status: Unavailable, message: \"upstream says hi\"").plainText(fallback))
    }

    @Test
    fun `a socket's or a parser's words never show`() {
        assertEquals(UNREACHABLE_TEXT, java.net.UnknownHostException("Unable to resolve host \"auth.pingolin.com\"").plainText(fallback))
        assertEquals(UNREACHABLE_TEXT, java.net.SocketTimeoutException("timeout").plainText(fallback))
        assertEquals(fallback, IllegalStateException("Expected BEGIN_OBJECT but was STRING").plainText(fallback))
        assertEquals(fallback, com.google.gson.JsonSyntaxException("malformed").plainText(fallback))
    }

    @Test
    fun `what this app said stays said`() {
        assertEquals("Couldn't sign in. Please try again.", Exception("Couldn't sign in. Please try again.").plainText(fallback))
        assertEquals(
            "Note limit reached. Upgrade your plan.",
            com.pingolin.note.domain.QuotaExceededException("Note limit reached. Upgrade your plan.").plainText(fallback),
        )
        assertEquals(fallback, Exception().plainText(fallback))
        assertEquals(fallback, Exception("  ").plainText(fallback))
    }

    @Test
    fun `a refusal is told by its code, else by its status, else by what didn't happen`() {
        val cases = listOf(
            Triple(402, null, ApiErrors.PLAN_LIMIT),
            Triple(400, "quota_notes_exceeded", ApiErrors.PLAN_LIMIT),
            Triple(403, "premium_required_sharing", ApiErrors.PLAN_LIMIT),
            Triple(429, null, ApiErrors.TOO_MANY),
            Triple(400, "rate_limited", ApiErrors.TOO_MANY),
            Triple(500, null, ApiErrors.TROUBLE),
            Triple(503, "server_unavailable", ApiErrors.TROUBLE),
            Triple(409, "server_timeout", ApiErrors.TROUBLE),
            // The status first: auth's rate limiter being down is trouble, not too many tries.
            Triple(503, "rate_limiter_unavailable", ApiErrors.TROUBLE),
            Triple(403, null, ApiErrors.NOT_ALLOWED),
            Triple(400, "auth_not_owner", ApiErrors.NOT_ALLOWED),
            Triple(400, "auth_insufficient_permissions", ApiErrors.NOT_ALLOWED),
            Triple(404, null, ApiErrors.GONE),
            Triple(400, "note_not_found", ApiErrors.GONE),
            Triple(400, null, fallback),
            Triple(409, "unspent_credit_balance", fallback),
        )
        for ((status, code, line) in cases) {
            assertEquals("$status $code", line, ApiErrors.plainLine(status, code, fallback))
        }
    }

    @Test
    fun `a refusal's code is read from its error body, never from body()`() {
        val said = "Invalid membership statement: at_version is not this circle's current key version. Call 0800 555 0100"
        val refused = Response.error<Unit>(
            409,
            """{"success":false,"error":"$said","error_code":"unspent_credit_balance"}""".toResponseBody(null),
        )
        assertNull(refused.body())
        assertEquals("unspent_credit_balance", ApiErrors.codeOf(refused))

        val notFound = Response.error<Unit>(404, """{"success":false,"error":"$said"}""".toResponseBody(null))
        assertEquals(ApiErrors.GONE, ApiErrors.plainLine(notFound, fallback))

        // org.json is a stub under the JVM tests; the parse goes through Gson.
        assertEquals(ApiErrors.Parsed("x", "email_not_verified"),
            ApiErrors.parse("""{"success":false,"error":"x","error_code":"email_not_verified"}"""))
        assertNull(ApiErrors.parse("not json"))
        assertNull(ApiErrors.parse("""{"success":false}"""))
        assertNull(ApiErrors.parse(null))
    }
}
