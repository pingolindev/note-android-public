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
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Skip SQLCipher's 256,000-round PBKDF2 on every database open, WITHOUT ever
 * rewriting the database.
 *
 * ## The cost
 *
 * `DatabasePassphraseManager` hands Room a random 256-bit passphrase, and
 * SQLCipher then stretches it with PBKDF2-HMAC-SHA512 at its 4.x default of
 * 256,000 iterations — on a key that is already full-entropy, so the stretching
 * buys precisely nothing. Measured at ~509ms of CPU per connection on a Galaxy
 * A50 (`NoteListRemoteViewsFactory` carries the same figure), and the pool
 * opens two.
 *
 * ## Why this does not rekey
 *
 * The obvious approach is `PRAGMA rekey` to a fresh raw key, and it is the
 * wrong one here: it re-encrypts every page of a database that holds the
 * OFFLINE OUTBOX — edits made with no signal, which exist nowhere else until
 * they sync. An interrupted rekey can take those with it.
 *
 * So this never writes to the database at all. SQLCipher's derived key is a
 * pure function of (passphrase, salt, iterations), and the salt is the first 16
 * bytes of the file. Compute the SAME key once, hand it back as a raw key
 * (`x'<64 hex>'`, which SQLCipher uses directly instead of deriving), and the
 * ciphertext is untouched — the bytes on disk are identical either way. The
 * worst case is that we compute the wrong key and fall back, having modified
 * nothing.
 *
 * ## The rollback path
 *
 * Three layers, in order:
 *
 *  1. **Never cache an unverified key.** [ensureCached] derives, then actually
 *     OPENS the database with the result and reads from it. Only a key that
 *     demonstrably works is stored. If our understanding of SQLCipher's KDF is
 *     wrong in any respect — algorithm, iteration count, salt, how the byte[]
 *     passphrase is consumed — verification fails and nothing is written.
 *  2. **A cached key that stops working is discarded.** [keyFor] re-verifies on
 *     every use (a raw-key open costs no KDF, so this is cheap) and clears the
 *     cache on failure, returning the passphrase.
 *  3. **The passphrase always remains valid.** It is never rotated or removed,
 *     so "fall back" is always "do exactly what the app does without the
 *     cache".
 *
 * Clearing the cache is therefore always safe, and is the remedy for anything
 * unexpected: the next open pays the KDF again, with no migration to undo.
 */
object DatabaseRawKey {

    private const val TAG = "DatabaseRawKey"
    private const val PREF_NAME = "note_db_key"
    private const val KEY_RAW = "db_raw_key_v1"

    /** SQLCipher 4.x defaults. Not configured anywhere, so these are what the
     *  existing databases were written with. If a future version changes them,
     *  verification fails and we fall back — it cannot silently mis-key. */
    private const val KDF_ITERATIONS = 256_000
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16

    /**
     * The key material to open the database with: a verified raw key when we
     * have one, otherwise the passphrase exactly as before.
     */
    fun keyFor(context: Context, dbFile: File, passphrase: ByteArray): ByteArray {
        val cached = read(context) ?: return passphrase
        val raw = rawKeyLiteral(cached)
        // Re-verify every time. A raw-key open skips the KDF, so this costs
        // almost nothing, and it means a database restored from a backup, or
        // one whose salt changed under us, degrades to the passphrase instead
        // of throwing out of Room's first query.
        if (opens(dbFile, raw)) return raw
        Log.w(TAG, "cached raw key no longer opens the database; clearing it and using the passphrase")
        clear(context)
        return passphrase
    }

    /**
     * Derive, verify and cache the raw key. Safe to call repeatedly; does
     * nothing once cached.
     *
     * Call this OFF the critical path. The one derivation is itself a full
     * 256,000-round PBKDF2 — the very thing being removed, and slower here than
     * in SQLCipher's C — so it must not sit on a launch. Every launch after it
     * is free.
     */
    fun ensureCached(context: Context, dbFile: File, passphrase: ByteArray) {
        if (read(context) != null) return
        // A BRAND-NEW INSTALL LANDS HERE AND DOES NOTHING, BY DESIGN.
        //
        // Room creates the file lazily, on the first query — not in
        // `databaseBuilder().build()` — so on a fresh install this runs
        // before the database exists and there is no salt to derive
        // from. The provider is a @Singleton, so it will not be
        // re-entered this process: install, sign in, no cache; the next
        // launch caches. So a new install pays the KDF for exactly ONE
        // launch and never again.
        //
        // Do NOT "fix" this by forcing the database open here to make
        // the file appear. That is the 509ms KDF, and forcing it would
        // drag it back onto the launch this is trying to keep clear —
        // to save one launch, once, per install.
        if (!dbFile.exists()) return

        val salt = readSalt(dbFile) ?: run {
            Log.w(TAG, "could not read the database salt; leaving the passphrase in place")
            return
        }
        val derived = try {
            derive(passphrase, salt)
        } catch (e: Exception) {
            Log.w(TAG, "PBKDF2 failed (${e.javaClass.simpleName}); leaving the passphrase in place")
            return
        }
        // THE GATE. Only a key that actually opens the database is ever stored.
        if (!opens(dbFile, rawKeyLiteral(derived))) {
            Log.w(TAG, "derived key did not open the database; leaving the passphrase in place")
            return
        }
        write(context, derived)
        Log.w(TAG, "raw database key cached; SQLCipher will skip the 256k-round KDF from the next open")
    }

    /** Drop the cached key. The next open pays the KDF and behaves as before. */
    fun clear(context: Context) {
        SecurePrefs.open(context, PREF_NAME).edit().remove(KEY_RAW).apply()
    }

    // ---------------------------------------------------------------------

    /** SQLCipher reads `x'<64 hex>'` as the key itself and derives nothing. */
    private fun rawKeyLiteral(key: ByteArray): ByteArray =
        "x'${key.joinToString("") { "%02x".format(it) }}'".toByteArray(Charsets.US_ASCII)

    /** The salt SQLCipher used: the first 16 bytes of the database file. */
    private fun readSalt(dbFile: File): ByteArray? = try {
        dbFile.inputStream().use { input ->
            val salt = ByteArray(SALT_BYTES)
            if (input.read(salt) == SALT_BYTES) salt else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * PBKDF2-HMAC-SHA512 over the passphrase BYTES, written out longhand
     * instead of going through `SecretKeyFactory`.
     *
     * The JCE route needs a `PBEKeySpec`, which takes a `char[]` — and how a
     * provider turns those chars back into bytes is exactly the detail that
     * would silently break this. Our passphrase is 32 random bytes, so roughly
     * half of them are >= 0x80; encoded as UTF-8 those become TWO bytes each
     * and the derivation hashes a different input than SQLCipher did. It would
     * not corrupt anything — verification would simply fail and we would fall
     * back forever — but it would be a silent no-op, which is worse than a
     * loud failure. Hashing the bytes directly removes the question.
     *
     * Only one output block is needed: SHA-512 gives 64 bytes and the key is
     * 32, so DK = T_1 truncated (RFC 8018 §5.2).
     */
    private fun derive(passphrase: ByteArray, salt: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA512")
        mac.init(javax.crypto.spec.SecretKeySpec(passphrase, "HmacSHA512"))
        // U_1 = PRF(P, S || INT_32_BE(1))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 2..KDF_ITERATIONS) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t.copyOf(KEY_BYTES)
    }

    /**
     * Does this key open the database? Read-only, and closed immediately.
     *
     * `Throwable`, not `Exception`: a missing native library surfaces as
     * `UnsatisfiedLinkError`, which is an `Error`, so a `catch (Exception)`
     * here would let it escape and kill `SyncWorker` construction instead of
     * degrading to "this key does not open it". Everything this function can
     * throw means the same thing to the caller: do not use this key.
     */
    private fun opens(dbFile: File, key: ByteArray): Boolean = try {
        // Before ANY SQLCipher call: this runs ahead of NoteDatabase.create(),
        // so nothing else has loaded the library yet.
        SqlCipherNative.ensure()
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath, key, null, SQLiteDatabase.OPEN_READONLY, null, null,
        ).use { db ->
            // Touch it: a successful open is not proof on its own, because
            // SQLCipher only discovers a wrong key when it fails to decrypt and
            // verify the first page.
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
        true
    } catch (t: Throwable) {
        false
    }

    private fun read(context: Context): ByteArray? = try {
        SecurePrefs.open(context, PREF_NAME).getString(KEY_RAW, null)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
    } catch (e: Exception) {
        null
    }

    private fun write(context: Context, key: ByteArray) {
        SecurePrefs.open(context, PREF_NAME).edit()
            .putString(KEY_RAW, android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP))
            .apply()
    }
}
