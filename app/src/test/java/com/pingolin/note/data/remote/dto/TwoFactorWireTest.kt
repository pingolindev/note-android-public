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

package com.pingolin.note.data.remote.dto

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-contract coverage for the two-factor login exchange.
 *
 * Getting this wrong is not a degraded experience, it is a permanent lockout:
 * the only alternative to signing in is not signing in. There are two ways to
 * lose it — posting to `auth/2fa/verify`, a route the server does not
 * register, and decoding without the `requires_2fa` field, so the challenge
 * decodes to nothing and the login dies claiming no token was received.
 */
class TwoFactorWireTest {

    private val gson = Gson()

    @Test
    fun `step2 surfaces the challenge instead of looking like a missing token`() {
        val json = """{"server_proof":"sp","requires_2fa":true,"two_factor_token":"tok-123"}"""
        val step2 = gson.fromJson(json, SrpStep2Response::class.java)
        assertEquals(true, step2.requires2fa)
        assertEquals("tok-123", step2.twoFactorToken)
        assertNull("the challenge branch issues no access token", step2.accessToken)
    }

    @Test
    fun `an ordinary step2 is unaffected`() {
        val json = """{"server_proof":"sp","access_token":"at","refresh_token":"rt"}"""
        val step2 = gson.fromJson(json, SrpStep2Response::class.java)
        assertNull(step2.requires2fa)
        assertNull(step2.twoFactorToken)
        assertEquals("at", step2.accessToken)
    }

    @Test
    fun `verify request uses the field name the server reads`() {
        val json = gson.toJson(TwoFactorVerifyRequest("tok-123", "654321", "totp"))
        assertTrue("server reads two_factor_token; temp_token is dropped: $json",
            json.contains("\"two_factor_token\":\"tok-123\""))
        assertTrue(json, json.contains("\"code\":\"654321\""))
        assertTrue("method is required, not optional: $json", json.contains("\"method\":\"totp\""))
        assertTrue("temp_token is not a name the server reads: $json",
            !json.contains("temp_token"))
    }

    /**
     * Note needs the key material back from this call: the vault key is derived
     * from kdf_params + encrypted_keys plus the password, and a response that
     * dropped them would sign the user in and leave every note unreadable.
     */
    fun responseJson(withRefresh: Boolean) = """
        {"access_token":"at"${if (withRefresh) ""","refresh_token":"rt"""" else ""},
         "user":{"id":"u1","email":"a@b.c","display_name":"A"},
         "kdf_params":{"salt":"73","memory_kib":65536,"iterations":2,"parallelism":1},
         "encrypted_keys":{"master_key":{"nonce":"bb","ciphertext":"aa"}},
         "signing_public_key":"ff"}
    """.trimIndent()

    @Test
    fun `verify response carries what the vault key derivation needs`() {
        val r = gson.fromJson(responseJson(withRefresh = true), TwoFactorVerifyResponse::class.java)
        assertEquals("at", r.accessToken)
        assertEquals("rt", r.refreshToken)
        assertEquals("u1", r.user?.id)
        assertNotNull("without kdf_params the vault key cannot be derived", r.kdfParams)
        assertNotNull("without encrypted_keys the vault key cannot be derived", r.encryptedKeys)
    }

    @Test
    fun `a missing refresh token does not fail the decode`() {
        val r = gson.fromJson(responseJson(withRefresh = false), TwoFactorVerifyResponse::class.java)
        assertEquals("at", r.accessToken)
        assertNull(r.refreshToken)
    }
}
