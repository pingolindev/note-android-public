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
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

/**
 * A finish on the wire, as Retrofit's Gson writes it and as the server reads it
 * (note-backend `ShareByEmailRequest`): `finish` and `content_key_generation` beside the wrap,
 * its signature and the sharer's key as hex; and an ordinary share's body exactly as before, with
 * neither. Then `GET /shares/unfinished` read from the server's own field names, and what each
 * answer to a share comes to.
 */
class FinishShareWireTest {

    private val gson = Gson()

    private fun json(request: ShareNoteRequest) = JsonParser.parseString(gson.toJson(request)).asJsonObject

    @Test
    fun `a finish carries finish, its generation, the wrap, its signature and the sharer's hex key`() {
        val body = json(
            ShareNoteRequest(
                email = "Zoe@Example.test",
                permission = "view",
                wrappedKey = "c2VhbGVk",
                sharerPublicKey = "ab".repeat(32),
                wrapSignature = "cd".repeat(64),
                finish = true,
                contentKeyGeneration = 7L,
            ),
        )
        assertEquals(
            setOf("email", "permission", "wrapped_key", "sharer_public_key", "wrap_signature", "finish", "content_key_generation"),
            body.keySet(),
        )
        assertEquals("Zoe@Example.test", body["email"].asString)
        assertEquals(true, body["finish"].asBoolean)
        assertEquals(7L, body["content_key_generation"].asLong)
        assertEquals("ab".repeat(32), body["sharer_public_key"].asString)
        assertEquals("cd".repeat(64), body["wrap_signature"].asString)
        assertEquals("c2VhbGVk", body["wrapped_key"].asString)
    }

    @Test
    fun `an ordinary share's body is unchanged, with no finish and no generation`() {
        val body = json(ShareNoteRequest(email = "a@b.c", permission = "edit", wrappedKey = "w", sharerPublicKey = "k", wrapSignature = "s"))
        assertEquals(setOf("email", "permission", "wrapped_key", "sharer_public_key", "wrap_signature"), body.keySet())
        assertFalse(body.has("finish"))
        assertFalse(body.has("content_key_generation"))
    }

    @Test
    fun `an unfinished share reads from the server's field names`() {
        val row = gson.fromJson(
            """{"share_id":"7b1c0f6e-2f35-4d4e-9a77-0c4f8f8d1a01","item_id":"8b1c0f6e-2f35-4d4e-9a77-0c4f8f8d1a01",
               "item_type":1,"notebook_id":"9b1c0f6e-2f35-4d4e-9a77-0c4f8f8d1a01","recipient_email":"Zoe@Example.test",
               "permission":"edit","single":true,"claimed":true,"invite_expires_at":null,"expired":false,
               "created_at":"2026-11-12T09:00:00.123456Z"}""",
            UnfinishedShareDto::class.java,
        )
        assertEquals("7b1c0f6e-2f35-4d4e-9a77-0c4f8f8d1a01", row.shareId)
        assertEquals("8b1c0f6e-2f35-4d4e-9a77-0c4f8f8d1a01", row.itemId)
        assertEquals(1, row.itemType)
        assertEquals("9b1c0f6e-2f35-4d4e-9a77-0c4f8f8d1a01", row.notebookId)
        assertEquals("Zoe@Example.test", row.recipientEmail)
        assertEquals("edit", row.permission)
        assertEquals(true, row.single)
        assertEquals(true, row.claimed)
        assertEquals(false, row.expired)
        assertNull(row.inviteExpiresAt)
    }

    @Test
    fun `a server that does not say single leaves it unknown, and so not finished`() {
        val row = gson.fromJson("""{"share_id":"s","item_id":"i","item_type":1,"claimed":true,"expired":false}""", UnfinishedShareDto::class.java)
        assertNull(row.single)
        assertNull(row.notebookId)
    }

    private fun error(status: Int, code: String?): Response<ApiResponse<Unit>> {
        val body = if (code == null) "{\"success\":false}" else "{\"success\":false,\"error\":\"x\",\"error_code\":\"$code\"}"
        return Response.error(status, body.toResponseBody("application/json".toMediaType()))
    }

    @Test
    fun `each answer to a share comes to what the finisher acts on`() {
        assertEquals(ShareCallResult.DONE, ShareCallResult.of(Response.success(ApiResponse<Unit>(success = true))))
        assertEquals(ShareCallResult.FAILED, ShareCallResult.of(Response.success(ApiResponse<Unit>(success = false))))
        assertEquals(ShareCallResult.NOT_UNFINISHED, ShareCallResult.of(error(409, "share_not_unfinished")))
        assertEquals(ShareCallResult.GENERATION_STALE, ShareCallResult.of(error(409, "share_generation_stale")))
        assertEquals(ShareCallResult.FAILED, ShareCallResult.of(error(409, "something_else")))
        assertEquals(ShareCallResult.UPGRADE, ShareCallResult.of(error(402, "premium_required_sharing")))
        assertEquals(ShareCallResult.RATE_LIMITED, ShareCallResult.of(error(429, "rate_limited")))
        assertEquals(ShareCallResult.LATER, ShareCallResult.of(error(503, null)))
        assertEquals(ShareCallResult.LATER, ShareCallResult.of(error(500, null)))
        assertEquals(ShareCallResult.FAILED, ShareCallResult.of(error(403, null)))
        assertEquals(ShareCallResult.FAILED, ShareCallResult.of(error(400, "validation_missing_field")))
    }
}
