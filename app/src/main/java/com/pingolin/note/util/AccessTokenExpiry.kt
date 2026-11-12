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

import org.json.JSONObject

/**
 * When the stored access token stops being good, read from its own `exp` claim (RFC 7519).
 *
 * WHY THIS EXISTS
 *   A native client gets 15-minute access tokens (auth's `ACCESS_TOKEN_TTL` for a native user
 *   agent). A cold open more than a quarter of an hour after the app last refreshed would hand
 *   the page a dead one: the page's boot asks `/auth/me` once (and once more), and on the 401
 *   signs out to the account portal, which asks for the password. So the shell refreshes such a
 *   token natively BEFORE the page loads (WebAppActivity), and doesn't prefetch `/auth/me` with
 *   it (NoteApplication).
 *
 * Only the payload's `exp` is read; the signature isn't checked, and needn't be: this only decides
 * whether to refresh first. A token it can't read (not three parts, a payload that isn't base64url
 * JSON, no numeric `exp`) counts as expired: the refresh that causes costs one round trip, and
 * handing the page a dead token costs the password prompt.
 *
 * And which of two tokens of one session was issued later ([compareIssue]: `sub`, `sid`, `iat`),
 * for PageSession: the page's cookie jar can hold a newer pair than the app's store when the
 * session was refreshed in the page (the account portal, run in the shell's WebView, refreshes on
 * every load). `iat` is the auth server's clock for both tokens, so no device clock is involved.
 */
object AccessTokenExpiry {

    /** A token this close to its `exp` is refreshed too: it could lapse before the page's boot
     *  reaches the server. */
    const val MARGIN_SECONDS = 60L

    /** [token]'s `exp` (Unix seconds), or null when it can't be read. */
    fun expiresAt(token: String?): Long? = seconds(claimsOf(token)?.opt("exp"))

    /** Who a token was issued to, for which session, and when: its `sub`, `sid` and `iat` (Unix
     *  seconds), each null when it can't be read. */
    data class Issue(val subject: String?, val sessionId: String?, val issuedAt: Long?)

    /** [token]'s [Issue], or null when its payload can't be read at all. */
    fun issueOf(token: String?): Issue? {
        val claims = claimsOf(token) ?: return null
        return Issue(
            subject = (claims.opt("sub") as? String)?.takeIf { it.isNotBlank() },
            sessionId = (claims.opt("sid") as? String)?.takeIf { it.isNotBlank() },
            issuedAt = seconds(claims.opt("iat")),
        )
    }

    /**
     * When [candidate] was issued against [current], both tokens of ONE account and ONE session
     * (equal `sub` and equal `sid`): later (1), in the same second (0) or earlier (-1). Null when
     * that can't be said: either can't be read, lacks one of the three claims, or they are of
     * another account or session. The ids are UUIDs, compared as such (case aside).
     */
    fun compareIssue(candidate: String?, current: String?): Int? {
        val a = issueOf(candidate) ?: return null
        val b = issueOf(current) ?: return null
        val aIat = a.issuedAt ?: return null
        val bIat = b.issuedAt ?: return null
        if (a.subject == null || a.sessionId == null) return null
        if (!a.subject.equals(b.subject, ignoreCase = true)) return null
        if (!a.sessionId.equals(b.sessionId, ignoreCase = true)) return null
        return aIat.compareTo(bIat).coerceIn(-1, 1)
    }

    /** The payload of a JWT ([token]: three parts, the middle one base64url JSON), or null. */
    private fun claimsOf(token: String?): JSONObject? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size != 3 || parts[1].isEmpty()) return null
        val payload = try {
            java.util.Base64.getUrlDecoder().decode(parts[1])
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /** A claim in Unix seconds, or null when it isn't a finite number. */
    private fun seconds(claim: Any?): Long? {
        val value = (claim as? Number)?.toDouble() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return value.toLong()
    }

    /** Whether [token] must be refreshed before it is handed to the page at [nowSeconds]: it has
     *  expired, expires within [MARGIN_SECONDS], or can't be read. */
    fun needsRefresh(token: String?, nowSeconds: Long): Boolean = isStale(expiresAt(token), nowSeconds)

    /** [needsRefresh] for an `exp` already read ([expiresAt]; null when it couldn't be). */
    fun isStale(exp: Long?, nowSeconds: Long): Boolean = exp == null || exp - nowSeconds <= MARGIN_SECONDS
}
