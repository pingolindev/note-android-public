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
import org.junit.Test

/**
 * The cookie domain has to match the one the auth server sets exactly, or
 * the seeded session silently fails to authenticate `account.` — and the
 * failure mode is not an error, it is the E2EE client-key handoff quietly
 * re-federating (or bouncing the user to login) on every launch.
 */
class WebAppActivityCookieDomainTest {

    @Test
    fun `dev base url yields the deployed dev cookie domain`() {
        assertEquals(
            ".pingolindev.com",
            WebAppActivity.cookieDomainFor("https://note.pingolindev.com/"),
        )
    }

    @Test
    fun `prod base url yields the deployed prod cookie domain`() {
        assertEquals(
            ".pingolin.com",
            WebAppActivity.cookieDomainFor("https://note.pingolin.com/"),
        )
    }

    @Test
    fun `a path on the base url does not leak into the domain`() {
        assertEquals(
            ".pingolindev.com",
            WebAppActivity.cookieDomainFor("https://note.pingolindev.com/api/v1"),
        )
    }

    /**
     * A bare host has no registrable parent to widen to. Returning it unchanged
     * scopes the cookie to that exact host rather than emitting a leading-dot
     * domain the browser would reject outright — which would drop the session
     * with no visible cause.
     */
    @Test
    fun `a single-label host is not widened`() {
        assertEquals("localhost", WebAppActivity.cookieDomainFor("http://localhost:3003/"))
    }
}
