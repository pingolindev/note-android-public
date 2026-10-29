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

import retrofit2.Response

/**
 * What a person is told about a request the server refused, in this app's words: by the
 * refusal's `error_code`, else its HTTP status, else a line saying what didn't happen. Never
 * the server's own `error` text, which can talk about keys and other internals, or say
 * whatever a compromised server wants a person to read: that is for logs. The same words as
 * the Track Android app's ApiErrors and the iOS apps'.
 */
object ApiErrors {

    const val PLAN_LIMIT = "That's beyond what your plan includes."
    const val TOO_MANY = "Too many tries just now. Please wait a moment and try again."
    const val TROUBLE = "Pingolin is having trouble just now. Please try again in a moment."
    const val NOT_ALLOWED = "You don't have permission to do that."
    const val GONE = "That isn't there any more."

    /** The line for a refusal with this [status] and [errorCode], else [fallback]. */
    fun plainLine(status: Int, errorCode: String?, fallback: String): String {
        val code = errorCode.orEmpty()
        return when {
            status == 402 || code.startsWith("quota_") || code.startsWith("premium_required") -> PLAN_LIMIT
            // The status first: auth's 503 `rate_limiter_unavailable` is trouble, not too many tries.
            status == 429 -> TOO_MANY
            status >= 500 -> TROUBLE
            code.startsWith("rate_") -> TOO_MANY
            code.startsWith("server_") -> TROUBLE
            status == 403 || code.startsWith("auth_not_") || code == "auth_insufficient_permissions" -> NOT_ALLOWED
            status == 404 || code.endsWith("_not_found") -> GONE
            else -> fallback
        }
    }

    /** [plainLine] for [response]: its status and its error body's `error_code`. Consumes the error body. */
    fun plainLine(response: Response<*>, fallback: String): String =
        plainLine(response.code(), codeOf(response), fallback)

    /**
     * The refusal's `error_code`. A refusal never fills `body()`: its fields are in the error
     * body, which this consumes, so read it once per response.
     */
    fun codeOf(response: Response<*>): String? =
        runCatching { parse(response.errorBody()?.string())?.errorCode }.getOrNull()

    data class Parsed(val error: String?, val errorCode: String?)

    /**
     * A raw error body's fields, or null if it carries none. Gson, not org.json: the JVM tests'
     * org.json is a stub, so a parse through it reads nothing there.
     */
    fun parse(errorBody: String?): Parsed? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val json = com.google.gson.JsonParser.parseString(errorBody).asJsonObject
            fun text(name: String): String? =
                json.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { null }
            val error = text("error")
            val errorCode = text("error_code")
            if (error == null && errorCode == null) null else Parsed(error, errorCode)
        } catch (_: Exception) {
            null
        }
    }
}
