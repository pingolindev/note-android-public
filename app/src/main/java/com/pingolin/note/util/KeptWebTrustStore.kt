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

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * The web page's kept trust, held by the app.
 *
 * The Note web editor keeps three blobs across a sign-out: its sender pins, its rollback floors
 * and its item owners. In a browser they stay in localStorage. In this shell they can't: every
 * sign-out, switch and erasure wipe deletes the WebView's storage whole (LocalDataWiper). So the
 * page hands them to the app after every change, over `pingolinTrust` (KeptWebTrustChannel), and
 * asks for them back at the same account's next sign-in, before it reads any of them.
 *
 * Held per canonical account as the page's own strings: the app never reads inside them. Only that
 * account's erasure and the typed-email rule remove them (AccountTrustTidy). A store that can't be
 * read is [Kept.Unreadable], never [Kept.None]: the page then keeps nothing that session, so a
 * copy it never folded in isn't overwritten with less.
 */
class KeptWebTrustStore internal constructor(
    /** Opens the store's file. Tests pass plain preferences: EncryptedSharedPreferences needs the
     *  Android Keystore, which Robolectric doesn't have. */
    private val openPrefs: () -> SharedPreferences,
) {

    constructor(context: Context) : this({ SecurePrefs.open(context, STORE) })

    /** What the app holds for one account. */
    sealed interface Kept {
        data class Copy(val pins: String?, val floors: String?, val owners: String?) : Kept
        /** The store reads, and holds nothing for this account. */
        data object None : Kept
        /** The store can't be read now: what it holds isn't known. */
        data object Unreadable : Kept
    }

    /** Opened on first use. `lazy` rethrows an open that failed and tries again next time. */
    private val prefs: SharedPreferences by lazy { openPrefs() }

    fun get(accountId: String): Kept {
        val id = AccountIds.canonical(accountId) ?: return Kept.Unreadable
        return try {
            val blobs = BLOBS.map { prefs.getString(key(id, it), null) }
            if (blobs.all { it == null }) Kept.None else Kept.Copy(blobs[0], blobs[1], blobs[2])
        } catch (e: Exception) {
            Kept.Unreadable
        }
    }

    /**
     * Keep the page's three blobs for [accountId], each a string of at most [MAX_BLOB_BYTES], or
     * null for none (its entry goes). Committed, not applied: the last keep before a sign-out wipe
     * has to be on disk before the wipe goes on. False when nothing was kept.
     */
    // commit() and its answer are the point (see above), which the KTX edit {} doesn't give.
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun keep(accountId: String, pins: String?, floors: String?, owners: String?): Boolean {
        val id = AccountIds.canonical(accountId) ?: return false
        val values = listOf(pins, floors, owners)
        if (values.any { it != null && it.toByteArray(Charsets.UTF_8).size > MAX_BLOB_BYTES }) return false
        return try {
            val edit = prefs.edit()
            BLOBS.zip(values).forEach { (name, value) ->
                if (value == null) edit.remove(key(id, name)) else edit.putString(key(id, name), value)
            }
            edit.commit()
        } catch (e: Exception) {
            false
        }
    }

    /** [accountId]'s erasure or the typed-email rule: all of its entries, any spelling of its id.
     *  Throws [StoreUnreadable] when they can't be removed; the caller stops there. */
    fun forget(accountId: String) {
        val id = AccountIds.parse(accountId)
        val keys = StoreUnreadable.reading(STORE) {
            prefs.all.keys.filter { it.contains('|') && AccountIds.same(it.substringBefore('|'), id) }
        }
        if (keys.isEmpty()) return
        val edit = prefs.edit()
        keys.forEach { edit.remove(it) }
        if (!edit.commit()) throw StoreUnreadable(STORE)
    }

    companion object {
        const val STORE = "note_kept_web_trust"
        /** The shell keeps no blob bigger than this (UTF-8 bytes); the page sends none. */
        const val MAX_BLOB_BYTES = 1024 * 1024
        private val BLOBS = listOf("pins", "floors", "owners")
        private fun key(id: String, blob: String) = "$id|$blob"
    }
}
