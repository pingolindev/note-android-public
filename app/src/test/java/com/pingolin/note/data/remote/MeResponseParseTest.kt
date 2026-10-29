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

package com.pingolin.note.data.remote

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.UserResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire shape of `GET /api/v1/auth/me` — the envelope, and the two
 * recovery fields the Account recovery row reads.
 *
 * This exists because the failure it guards against is silent and looks
 * identical from the UI in every direction:
 *
 *  - Declaring the endpoint as a bare `Response<UserResponse>` while the
 *    server returns an `ApiResponse` envelope puts the ENVELOPE into
 *    UserResponse and leaves every field null — and a caller that only checks
 *    the body is non-null (it always is) never notices.
 *  - A snake_case mismatch on `recovery_email` would do the same one layer
 *    down: null address, rendered as "not set up" for every account that has
 *    one.
 *  - A mismatch on `recovery_email_verified` is worse and quieter still,
 *    because `recoveryEmailVerified` has a `false` default: the field would
 *    simply never be read, and a VERIFIED address would render as awaiting
 *    verification — or, if the default were ever flipped, an unverified one
 *    would render as verified, which is telling someone they are protected
 *    when they are not.
 *
 * The bodies below are responses in the shape the auth server sends, trimmed
 * of key material.
 */
class MeResponseParseTest {

    private val type = object : TypeToken<ApiResponse<UserResponse>>() {}.type

    private fun parse(json: String): UserResponse {
        val envelope: ApiResponse<UserResponse> = Gson().fromJson(json, type)
        assertTrue("success should be true", envelope.success)
        return requireNotNull(envelope.data) { "data missing — the envelope is not being parsed" }
    }

    /** An account with a verified recovery address. */
    private val verifiedBody = """
        {"success":true,"data":{
          "id":"6f1d3c2a-0000-4000-8000-000000000001",
          "email":"probe@example.test",
          "display_name":"Recovery Probe",
          "email_verified":true,
          "status":"active",
          "registration_status":"approved",
          "recovery_email":"probe-backup@example.test",
          "recovery_email_verified":true,
          "created_at":"2026-08-13T16:00:00Z",
          "subscriptions":[],
          "entitled_products":[]
        }}
    """.trimIndent()

    /** The same account before the address was verified. */
    private val pendingBody = verifiedBody.replace(
        "\"recovery_email_verified\":true",
        "\"recovery_email_verified\":false",
    )

    /**
     * An account with no recovery address. `recovery_email` is
     * `skip_serializing_if = "Option::is_none"` server-side, so the key is
     * ABSENT rather than null — this is the real body, not a hypothetical one.
     */
    private val noneBody = """
        {"success":true,"data":{
          "id":"6f1d3c2a-0000-4000-8000-000000000001",
          "email":"probe@example.test",
          "display_name":"Recovery Probe",
          "email_verified":true,
          "status":"active",
          "registration_status":"approved",
          "recovery_email_verified":false,
          "created_at":"2026-08-13T16:00:00Z",
          "subscriptions":[],
          "entitled_products":[]
        }}
    """.trimIndent()

    @Test
    fun `the response is wrapped in the ApiResponse envelope`() {
        val user = parse(verifiedBody)
        assertEquals("probe@example.test", user.email)
    }

    @Test
    fun `a verified recovery address parses as verified`() {
        val user = parse(verifiedBody)
        assertEquals("probe-backup@example.test", user.recoveryEmail)
        assertTrue(
            "recovery_email_verified was not read — a verified address would render as pending",
            user.recoveryEmailVerified,
        )
    }

    @Test
    fun `an unverified recovery address parses as unverified`() {
        val user = parse(pendingBody)
        assertEquals("probe-backup@example.test", user.recoveryEmail)
        assertFalse(user.recoveryEmailVerified)
    }

    @Test
    fun `an absent recovery_email key parses as null, not as a blank address`() {
        val user = parse(noneBody)
        assertNull(user.recoveryEmail)
        assertFalse(user.recoveryEmailVerified)
    }

    /**
     * The end-to-end mapping the row actually performs. Fails if either field
     * name drifts, which is the whole point: statusOf's own tests supply their
     * inputs and so can never catch a rename on the wire.
     */
    @Test
    fun `parsed fields drive the right recovery status`() {
        val verified = parse(verifiedBody)
        assertEquals(
            com.pingolin.note.util.AccountRecovery.Status.Verified("probe-backup@example.test"),
            com.pingolin.note.util.AccountRecovery.statusOf(
                verified.recoveryEmail, verified.recoveryEmailVerified,
            ),
        )

        val pending = parse(pendingBody)
        assertEquals(
            com.pingolin.note.util.AccountRecovery.Status.Pending("probe-backup@example.test"),
            com.pingolin.note.util.AccountRecovery.statusOf(
                pending.recoveryEmail, pending.recoveryEmailVerified,
            ),
        )

        val none = parse(noneBody)
        assertEquals(
            com.pingolin.note.util.AccountRecovery.Status.NotSet,
            com.pingolin.note.util.AccountRecovery.statusOf(
                none.recoveryEmail, none.recoveryEmailVerified,
            ),
        )
    }
}
