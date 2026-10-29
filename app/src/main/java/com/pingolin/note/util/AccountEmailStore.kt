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

import android.content.Context
import android.content.SharedPreferences

/**
 * Each account's typed sign-in e-mail on this device, by its canonical id (the typed-email rule;
 * as the Track iOS app's SignInEmails). One key per account, `email:<id>`, in its own
 * EncryptedSharedPreferences file: the addresses are personal data, and a sign-out keeps them with
 * the account's other kept trust. An erasure deletes that account's record ([forget]).
 *
 * The rule compares these: another account's kept trust is forgotten only when this sign-in's
 * typed e-mail and that account's saved one both exist and differ. A record that can't be read is
 * never "none": it throws [StoreUnreadable], and the rule then forgets nothing.
 */
class AccountEmailStore internal constructor(
    /** Opens the store's file. Tests pass plain preferences: EncryptedSharedPreferences needs the
     *  Android Keystore, which Robolectric doesn't have. */
    private val openPrefs: () -> SharedPreferences,
) {

    constructor(context: Context) : this({ SecurePrefs.open(context, "note_account_emails") })

    /** Opened on first use. `lazy` rethrows an open that failed and tries again next time. */
    private val prefs: SharedPreferences by lazy { openPrefs() }

    /** [accountId]'s saved e-mail, trimmed and lower-cased, or null when none is saved. */
    @Synchronized
    fun emailOf(accountId: String): String? {
        val id = AccountIds.parse(accountId)
        return StoreUnreadable.reading(STORE) {
            prefs.all.entries.firstOrNull { (k, v) -> v is String && k.startsWith("$KEY_EMAIL:") && AccountIds.same(k.substringAfter(':'), id) }
                ?.value as String?
        }?.let(::normalized)?.takeIf { it.isNotEmpty() }
    }

    /** The accounts with a saved e-mail, by their canonical ids. */
    @Synchronized
    fun accounts(): Set<String> = StoreUnreadable.reading(STORE) {
        prefs.all.keys.filter { it.startsWith("$KEY_EMAIL:") }
            .mapNotNull { AccountIds.canonical(it.substringAfter(':')) }
            .toSet()
    }

    /** Save [typedEmail] (trimmed, lower-cased) as [accountId]'s, under its canonical id only. */
    @Synchronized
    fun save(accountId: String, typedEmail: String) {
        val id = AccountIds.parse(accountId)
        val email = normalized(typedEmail).takeIf { it.isNotEmpty() } ?: return
        val others = StoreUnreadable.reading(STORE) {
            prefs.all.keys.filter { it.startsWith("$KEY_EMAIL:") && it != keyFor(id) && AccountIds.same(it.substringAfter(':'), id) }
        }
        val edit = prefs.edit().putString(keyFor(id), email)
        others.forEach { edit.remove(it) }
        if (!edit.commit()) throw StoreUnreadable(STORE)
    }

    /** Delete [accountId]'s record, under every spelling. */
    @Synchronized
    fun forget(accountId: String) {
        val id = AccountIds.parse(accountId)
        val keys = StoreUnreadable.reading(STORE) {
            prefs.all.keys.filter { it.startsWith("$KEY_EMAIL:") && AccountIds.same(it.substringAfter(':'), id) }
        }
        if (keys.isEmpty()) return
        val edit = prefs.edit()
        keys.forEach { edit.remove(it) }
        if (!edit.commit()) throw StoreUnreadable(STORE)
    }

    private fun keyFor(id: String) = "$KEY_EMAIL:$id"

    companion object {
        private const val STORE = "account e-mails"
        private const val KEY_EMAIL = "email"

        /** The one form an address is compared in: trimmed, lower-cased. */
        fun normalized(email: String): String = email.trim().lowercase()
    }
}
