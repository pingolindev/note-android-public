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

/**
 * Where the web account portal lives, and how to describe the recovery address
 * it manages.
 *
 * This file is deliberately inert. It performs no reset, derives nothing from a
 * password or an address, and holds no crypto: it turns a base URL into a page
 * URL and turns two fields of `GET /api/v1/auth/me` into something a screen can
 * render. Everything dangerous stays in the single web implementation.
 *
 * That is a rule, not a preference. Password reset binds SRP credentials to a
 * specific identity string and hashes an address into a token; a second
 * implementation that disagrees with the first by one byte does not fail
 * loudly, it locks the account out. Adding a reset here would create exactly
 * that second implementation.
 *
 * The base URL comes from `BuildConfig.ACCOUNT_BASE_URL`, which the product
 * flavor sets (`account.pingolindev.com` for dev, `account.pingolin.com` for
 * prod) — the same field the sign-in screen's /register link uses. A prod build
 * therefore cannot point at the dev portal.
 */
object AccountRecovery {

    /** The portal page that starts a password reset. */
    fun forgotPasswordUrl(accountBaseUrl: String): String = page(accountBaseUrl, "forgot-password")

    /**
     * The portal page where a recovery address is added, verified or removed.
     * Adding one requires being signed in and confirming the password, which is
     * why it is worth nothing unless it is set up BEFORE the lockout.
     */
    fun recoverySettingsUrl(accountBaseUrl: String): String = page(accountBaseUrl, "settings")

    private fun page(accountBaseUrl: String, path: String): String =
        accountBaseUrl.trimEnd('/') + "/" + path

    /**
     * What the server says about this account's recovery address.
     *
     * [Unknown] is a separate state on purpose. Reporting "not set up" when the
     * server simply could not be reached sends someone to add an address they
     * already have — and, worse, tells someone who genuinely has none that the
     * question has been answered.
     */
    sealed class Status {
        object Unknown : Status()
        object NotSet : Status()
        data class Pending(val address: String) : Status()
        data class Verified(val address: String) : Status()
    }

    /**
     * Read the two `/me` fields.
     *
     * The server omits `recovery_email` entirely for an account with no
     * recovery address, so the parser hands back null. A blank string is
     * treated the same way rather than rendered as an address.
     */
    fun statusOf(recoveryEmail: String?, verified: Boolean): Status {
        val address = recoveryEmail?.trim().orEmpty()
        if (address.isEmpty()) return Status.NotSet
        return if (verified) Status.Verified(address) else Status.Pending(address)
    }
}
