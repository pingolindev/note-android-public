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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The ONE place this app builds an [EncryptedSharedPreferences].
 *
 * WHY THIS EXISTS
 *   `EncryptedSharedPreferences` and `MasterKey` are deprecated: androidx.security is
 *   retired and will not get fixes. They back the most sensitive storage the app has
 *   (session tokens, retained content keys, pinned sender keys, the SQLCipher
 *   passphrase), so every store goes through this one seam: a replacement is one
 *   implementation behind this function, not a hunt through the app.
 *
 *   This is deliberately NOT a migration: same file names, same default master key,
 *   same AES256_SIV/AES256_GCM schemes, so every existing install reads exactly the
 *   bytes it wrote. Nothing to migrate, nothing to lose.
 *
 * WHY THAT CAUTION MATTERS
 *   Bulk-migrating this storage and clearing the old key file on first launch
 *   destroys users' E2EE keys — they then have to sign out and back in to re-derive
 *   them from the server. Any change here migrates LAZILY on read, never
 *   bulk-converts-then-clears, and never deletes the old file.
 *
 * CALLERS KEEP THEIR OWN ERROR HANDLING
 *   This only constructs. It does not catch: SessionManager turns a failure into a
 *   user-facing SecurityException, DatabasePassphraseManager distinguishes
 *   device-locked from real corruption. Swallowing exceptions here would erase
 *   distinctions the callers depend on.
 */
object SecurePrefs {

    /**
     * The app-wide master key, resolved once. Built with no alias, so it is the
     * default `_androidx_security_master_key_` every store on disk was written
     * under — caching one instance is purely to stop rebuilding it per store.
     */
    @Volatile
    private var cachedMasterKey: MasterKey? = null

    /**
     * The shared master key. Public so the few callers that need a [MasterKey]
     * directly (Hilt providers, an injectable test override) take THIS one rather
     * than building their own — otherwise `MasterKey.Builder` leaks back out and
     * the single seam is gone again.
     */
    fun masterKey(context: Context): MasterKey =
        cachedMasterKey ?: synchronized(this) {
            cachedMasterKey ?: MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
                .also { cachedMasterKey = it }
        }

    /**
     * Open (or create) the encrypted preferences file [fileName]. Passing a
     * [masterKey] overrides the shared one — tests inject their own; production
     * callers omit it and get the default every existing file was written under.
     */
    fun open(
        context: Context,
        fileName: String,
        masterKey: MasterKey? = null,
    ): SharedPreferences {
        // CACHED PER FILE, because create() is not cheap and callers
        // assume it is. Every call unwraps the store's TWO keysets (key
        // scheme + value scheme) through the Keystore — 6 TEE round
        // trips, ~45ms on an A50 — and [cachedMasterKey] above does NOT
        // help with that: it caches the MasterKey object, not the
        // per-store unwrap. Opening the same file twice would therefore
        // pay twice, and it happens: DatabasePassphraseManager and
        // DatabaseRawKey both read "note_db_key" while building the
        // database. Measured 42 of these ops across a cold launch.
        //
        // Safe to share: EncryptedSharedPreferences is thread-safe and
        // behaves like any other SharedPreferences, so one instance per
        // file is what callers already expect — two instances of the
        // same file would be the surprising thing.
        //
        // An explicit masterKey BYPASSES the cache. That argument
        // exists for tests injecting their own key, and they must not
        // be handed an instance built under the default one (or poison
        // it for later callers).
        if (masterKey != null) return create(context, fileName, masterKey)
        cache[fileName]?.let { return it }
        // ONE LOCK PER FILE, not one lock for the map.
        //
        // A single process-wide monitor (`synchronized(cache)`) would be
        // held for the whole ~45ms create(), so opening file A would
        // BLOCK a thread opening unrelated file B. There are eight of
        // these stores (note_session, note_db_key, note_pending_share,
        // note_gen_floors, note_sender_pins, note_retired_content_keys,
        // note_pending_push_unregister) and a cold launch touches
        // several on different threads at once; behind one monitor they
        // queue for no reason, since they share no state. atrace on an
        // A16 cold start shows what that costs: 50ms of the main thread
        // inside MainActivity.onCreate, spent waiting on a DIFFERENT
        // file's Keystore round trips.
        //
        // Same file still serialises, which is the point: the second
        // caller must wait and take the cached instance rather than
        // build a second one.
        val lock = locks.computeIfAbsent(fileName) { Any() }
        return synchronized(lock) {
            cache[fileName] ?: create(context, fileName, masterKey(context))
                .also { cache[fileName] = it }
        }
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()

    /** Per-file monitors for [open]. Never removed: there are eight known file
     *  names and the entries are one object each. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun create(
        context: Context,
        fileName: String,
        key: MasterKey,
    ): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        fileName,
        key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
