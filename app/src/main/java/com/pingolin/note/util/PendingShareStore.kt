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
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text shared into the app while it was SIGNED OUT, held until the sign-in that
 * follows.
 *
 * ## Why this exists
 *
 * A share that arrives signed out has to survive the sign-in.
 * [com.pingolin.note.ui.MainActivity] hands off to
 * [com.pingolin.note.ui.auth.AuthActivity], and `AuthActivity.navigateToMain()`
 * starts a bare MainActivity intent carrying no extras, so nothing would replay
 * the payload: the user's own words would be gone, with a login screen in
 * between that made it look like the system had worked.
 *
 * ## Why a separate encrypted file
 *
 * Same reasoning as [PendingPushUnregisterStore]: this has to survive an
 * Activity that finishes itself, a process death while the user types their
 * password, and the trip through AuthActivity — so an Activity field cannot hold
 * it. It is the user's own text, so it is written to an [SecurePrefs]-backed
 * store rather than plain SharedPreferences, and it lives in its OWN file so the
 * logout wipe of `note_session` neither depends on it nor is complicated by it.
 *
 * ## Why a TTL
 *
 * A share that is never claimed must not surface days later attached to whatever
 * account signs in next. [TTL_MS] is deliberately short: this exists to bridge
 * one sign-in, not to be a drafts folder. Anything older is dropped on read, and
 * reading is destructive ([take]) so the same clipping cannot be replayed twice.
 *
 * The clock is wall time, not [SystemClock.elapsedRealtime]: the record has to
 * outlive a reboot, and elapsed-realtime resets there — which would make every
 * stashed share look brand new after a restart.
 */
@Singleton
class PendingShareStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Opened ON FIRST USE, not in the constructor.
     *
     * Every launch calls [take] (MainActivity drains the stash before routing),
     * and opening this encrypted file unwraps two keysets through the Keystore —
     * ~45ms of TEE round trips on the main thread — to discover, almost always,
     * that nothing was ever shared. [presence] answers that question without
     * touching the Keystore at all, so on the ordinary launch this lazy is never
     * forced.
     */
    private val prefs by lazy { SecurePrefs.open(context, "note_pending_share") }

    /**
     * Plain, unencrypted marker for "is there anything in the encrypted file".
     *
     * Non-secret by construction: it is one boolean, and it says only whether an
     * unclaimed share exists — which the existence of the file on disk already
     * says to anyone who can see the file. The user's TEXT stays in [prefs].
     *
     * FAIL-SAFE IN ONE DIRECTION, deliberately. Every write orders itself so the
     * marker can only ever be over-optimistic (true while the store is empty),
     * never under (false while a draft is waiting). An over-optimistic marker
     * costs one pointless open; an under-optimistic one would strand the user's
     * text forever — the very loss this file exists to prevent. That is also why
     * the writes here are commit() and not apply(): apply() is asynchronous, so
     * a process death between the two stores could persist the encrypted draft
     * and lose the marker. These writes are rare (a share arriving while signed
     * out) and tiny.
     *
     * ABSENT is not FALSE. An install upgrading from a build without this marker
     * may have a draft already stashed, and a fresh install has neither. Both
     * read as "unknown", which falls through to the encrypted store and records
     * the answer on the way out.
     */
    private val presence by lazy {
        context.getSharedPreferences(MARKER_FILE, Context.MODE_PRIVATE)
    }

    /**
     * True only when the marker POSITIVELY says the store is empty.
     *
     * `internal` so [PendingShareStoreMarkerTest] can assert the one property
     * that matters here without a Keystore: absent must not read as empty.
     * Testing it through [take] would force the encrypted lazy, which is
     * exactly what no unit test can do.
     */
    internal fun knownEmpty(): Boolean =
        presence.contains(KEY_PRESENT) && !presence.getBoolean(KEY_PRESENT, false)

    private fun markPresent(present: Boolean) {
        presence.edit().putBoolean(KEY_PRESENT, present).commit()
    }

    data class Draft(val title: String, val body: String)

    /** Replace any held draft with this one. Latest share wins. */
    fun save(title: String, body: String) {
        // Marker FIRST. If the process dies between these two writes the marker
        // is true and the store is empty — one wasted open, nothing lost. The
        // other order could lose the draft.
        markPresent(true)
        prefs.edit()
            .putString(KEY_TITLE, title)
            .putString(KEY_BODY, body)
            .putLong(KEY_AT, System.currentTimeMillis())
            .commit()
    }

    /**
     * Consume the held draft, or null if there is none or it has expired.
     *
     * Destructive on purpose, and destructive even when the record is stale:
     * a draft that is too old to use is also a draft that must not sit at rest.
     */
    fun take(nowMs: Long = System.currentTimeMillis()): Draft? {
        // THE HOT PATH: every launch lands here and almost never has a draft.
        if (knownEmpty()) return null
        val at = prefs.getLong(KEY_AT, 0L)
        if (at == 0L) {
            // Record what we just learned the expensive way, so the next launch
            // answers from the marker instead of the Keystore. This is the line
            // that makes a fresh install pay the open exactly once.
            markPresent(false)
            return null
        }
        val title = prefs.getString(KEY_TITLE, null).orEmpty()
        val body = prefs.getString(KEY_BODY, null).orEmpty()
        clear()
        if (nowMs - at !in 0..TTL_MS) return null
        if (title.isEmpty() && body.isEmpty()) return null
        return Draft(title, body)
    }

    /** True when a share is waiting — for copy on the sign-in screen. Non-destructive. */
    fun hasPending(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (knownEmpty()) return false
        val at = prefs.getLong(KEY_AT, 0L)
        return at != 0L && (nowMs - at) in 0..TTL_MS
    }

    fun clear() {
        // Encrypted store first, marker second: a death in between leaves the
        // marker true over an empty store, which costs one open and loses
        // nothing. See [presence].
        prefs.edit().clear().commit()
        markPresent(false)
    }

    companion object {
        /** 30 minutes: long enough for a password reset detour, short enough
         *  that a forgotten clipping never resurfaces in a later session. */
        const val TTL_MS = 30L * 60L * 1000L

        private const val KEY_TITLE = "title"
        private const val KEY_BODY = "body"
        private const val KEY_AT = "saved_at_ms"

        /** The app's ordinary plain prefs — already open by the time this runs
         *  (CrashReporter injects the same file), so reading the marker costs
         *  nothing on top of what the launch already paid. */
        private const val MARKER_FILE = "note_prefs"
        private const val KEY_PRESENT = "pending_share_present"
    }
}
