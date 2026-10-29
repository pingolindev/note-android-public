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

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The kept trust of accounts that aren't signed in. A sign-out keeps each account's sender pins,
 * rollback floors, item owners and retained wraps, namespaced by its id, so that account's next
 * sign-in finds them. Two things remove them:
 * - that account's erasure ([erase]): every blob of its id, in every store, with its e-mail record;
 * - the typed-email rule ([afterSignIn]): at a successful sign-in, another account's blobs go only
 *   when this sign-in's typed e-mail and that account's saved one both exist and differ.
 * A store that can't be read keeps everything the tidy hasn't decided yet.
 */
@Singleton
class AccountTrustTidy @Inject constructor(
    private val pins: SenderKeyPinStore,
    private val floors: NoteGenFloorStore,
    private val retained: RetainedContentKeyStore,
    private val emails: AccountEmailStore,
    /** The web page's own kept trust, which the app holds for it. */
    private val keptWebTrust: KeptWebTrustStore,
) {

    /**
     * After [accountId] signed in with [typedEmail]: forget every other account whose saved e-mail
     * differs from it, then save it as this account's. No typed e-mail, or another account with
     * none saved, forgets nothing: nothing says they're different people. So the accounts to look at
     * are the ones with a saved e-mail, and every one is read before anything is removed: a record
     * that can't be read removes nothing.
     */
    fun afterSignIn(accountId: String, typedEmail: String) {
        val me = AccountIds.canonical(accountId) ?: return
        val typed = AccountEmailStore.normalized(typedEmail).takeIf { it.isNotEmpty() } ?: return
        try {
            val others = emails.accounts() - me
            val differ = others.filter { other -> emails.emailOf(other)?.let { it != typed } == true }
            for (other in differ) forgetEverywhere(other)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Other accounts' kept trust was left as it was: ${e.message}")
        }
        runCatching { emails.save(me, typed) }
            .onFailure { android.util.Log.w(TAG, "This account's sign-in e-mail wasn't saved: ${it.message}") }
    }

    /** [accountId]'s erasure: every blob of that id goes, in every store, with its e-mail record.
     *  Each store on its own: one that can't be written doesn't keep the others. */
    fun erase(accountId: String) {
        val id = AccountIds.canonical(accountId) ?: return
        for (forget in listOf(pins::forget, floors::forget, retained::forget, keptWebTrust::forget, emails::forget)) {
            runCatching { forget(id) }
                .onFailure { android.util.Log.w(TAG, "An erased account's store wasn't cleared: ${it.message}") }
        }
    }

    /** Throws when a store can't be written; the caller stops there. */
    private fun forgetEverywhere(id: String) {
        pins.forget(id)
        floors.forget(id)
        retained.forget(id)
        keptWebTrust.forget(id)
        emails.forget(id)
    }

    private companion object {
        private const val TAG = "AccountTrustTidy"
    }
}
