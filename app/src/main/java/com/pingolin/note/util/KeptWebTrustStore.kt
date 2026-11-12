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
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's kept trust, held by the app.
 *
 * The Note web editor keeps three blobs across a sign-out: its sender pins, its rollback floors
 * and its item owners. In a browser they stay in localStorage. In this shell they can't: every
 * sign-out, switch and erasure wipe deletes the WebView's storage whole (LocalDataWiper). So the
 * page hands them to the app after every change, over `pingolinTrust` (KeptWebTrustChannel), and
 * asks for them back at the same account's next sign-in, before it reads any of them.
 *
 * Held per canonical account as the page's own strings, written only by the page. The app READS
 * one of them, deliberately: the pins blob, which the background invitation finisher
 * (InvitationFinisher) decides whom it may seal a note to by ([pins] for a pass, [trustForSeal]
 * afresh for each person). It never writes into it: the page's next keep would overwrite whatever
 * it wrote.
 *
 * What the app adds goes beside the blobs, under the same account's entries, so that it has the
 * same lifetime:
 *  - the SEALS: the keys the finisher sealed a note to on a first contact ([trustForSeal]), which
 *    the page takes into its pins (`takeSeals`) and then lets go of (`clearSeals`, [clearSeals]:
 *    only the seals whose fold its kept pins show by then);
 *  - when the finisher last tried each share ([triedAt]).
 *
 * Only that account's erasure and the typed-email rule remove any of it (AccountTrustTidy, [forget]:
 * every entry of the account's id). A store that can't be read is [Kept.Unreadable], never
 * [Kept.None]: the page then keeps nothing that session, so a copy it never folded in isn't
 * overwritten with less.
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
        // Under the lock the finisher decides by (trustForSeal), so a keep lands before or after
        // a decision, never inside one.
        return synchronized(LOCK) {
            try {
                val edit = prefs.edit()
                BLOBS.zip(values).forEach { (name, value) ->
                    if (value == null) edit.remove(key(id, name)) else edit.putString(key(id, name), value)
                }
                edit.commit()
            } catch (e: Exception) {
                false
            }
        }
    }

    /** [accountId]'s erasure or the typed-email rule: all of its entries, any spelling of its id:
     *  the page's blobs, the seals and the finisher's tries alike.
     *  Throws [StoreUnreadable] when they can't be removed; the caller stops there. */
    fun forget(accountId: String) {
        val id = AccountIds.parse(accountId)
        synchronized(LOCK) {
            val keys = StoreUnreadable.reading(STORE) {
                prefs.all.keys.filter { it.contains('|') && AccountIds.same(it.substringBefore('|'), id) }
            }
            if (keys.isEmpty()) return
            val edit = prefs.edit()
            keys.forEach { edit.remove(it) }
            if (!edit.commit()) throw StoreUnreadable(STORE)
        }
    }

    // --- The page's pins, read (never written) by the invitation finisher ---

    /** One kept pin, as the page's pinMerge.ts `StoredPin` holds it. */
    data class Pin(val sharingPubkeyHex: String, val signingPubkeyHex: String, val conflict: Boolean)

    /** The page's pins for one account, as the invitation finisher sees them. */
    sealed interface Pins {
        /** By the contact's canonical id. */
        data class Held(val byContact: Map<String, Pin>) : Pins
        /** No pins blob is kept for the account. */
        data object Absent : Pins
        /** The store can't be read, or the blob doesn't parse as a JSON object. */
        data object Unreadable : Pins
    }

    /**
     * The page's pins blob for [accountId], parsed as the page's `foldPins` reads it: keyed by
     * canonical contact id (a key that is no account id, or a record that is no pin, is dropped),
     * two records of one contact with different keys a conflict. Read only: nothing here writes it.
     */
    fun pins(accountId: String): Pins {
        val id = AccountIds.canonical(accountId) ?: return Pins.Unreadable
        val raw = try {
            prefs.getString(key(id, PINS), null)
        } catch (e: Exception) {
            return Pins.Unreadable
        } ?: return Pins.Absent
        return parsePins(raw)?.let { Pins.Held(it) } ?: Pins.Unreadable
    }

    // --- Seals: the keys the finisher sealed a note to on a first contact ---

    /** One first contact the finisher sealed to (the page's `ShellSeal`): canonical user id,
     *  lower-case 64-hex keys, and when, in ms. */
    data class Seal(val userId: String, val sharingPublicKey: String, val signingPublicKey: String, val sealedAt: Long)

    sealed interface Seals {
        data class Held(val seals: List<Seal>) : Seals
        /** The store can't be read, or what it holds doesn't parse. */
        data object Unreadable : Seals
    }

    /** What [trustForSeal] decided. */
    enum class Trust {
        /** A kept pin for them holds this sharing key. */
        PINNED,
        /** No kept pin; a seal for them holds this sharing key. */
        SEALED,
        /** Neither: a first contact, whose seal is now recorded and on disk. */
        RECORDED,
        /** Not to be sealed to: a conflict, another key, no pins kept, or keys or a store that
         *  can't be used. */
        REFUSED,
    }

    /**
     * The invitation finisher's trust decision for one person (the contract's "Trust" 1-4), made
     * against the pins kept NOW and the seals, under the same lock as every keep and clear: a keep
     * or a clear lands before it or after it, never between the read and the record.
     *  - No pins kept for the account, or none that read: [Trust.REFUSED] (finish nothing).
     *  - A kept pin for [userId]: a conflict, or another sharing key, [Trust.REFUSED]; this one,
     *    [Trust.PINNED]. The page's decision: [refuseUnpinned] doesn't count here.
     *  - No kept pin for them, and [refuseUnpinned] (the caller's own knowledge of them refuses):
     *    [Trust.REFUSED].
     *  - Else a seal for them: another sharing key [Trust.REFUSED]; this one [Trust.SEALED].
     *  - Else, with two 64-hex keys, the seal is recorded and committed: [Trust.RECORDED].
     */
    fun trustForSeal(
        accountId: String,
        userId: String,
        sharingHex: String,
        signingHex: String,
        at: Long,
        refuseUnpinned: Boolean = false,
    ): Trust {
        val id = AccountIds.canonical(accountId) ?: return Trust.REFUSED
        val contact = AccountIds.strict(userId) ?: return Trust.REFUSED
        return synchronized(LOCK) {
            val pins = keptPinsLocked(id) ?: return@synchronized Trust.REFUSED
            pins[contact]?.let { pin ->
                return@synchronized if (!pin.conflict && pin.sharingPubkeyHex.equals(sharingHex, ignoreCase = true)) {
                    Trust.PINNED
                } else {
                    Trust.REFUSED
                }
            }
            if (refuseUnpinned) return@synchronized Trust.REFUSED
            when (recordSealLocked(id, contact, sharingHex, signingHex, at)) {
                SealRecord.RECORDED -> Trust.RECORDED
                SealRecord.HELD -> Trust.SEALED
                SealRecord.DIFFERENT, SealRecord.REFUSED -> Trust.REFUSED
            }
        }
    }

    /** What [recordSeal] did. */
    enum class SealRecord {
        /** Written, and on disk. */
        RECORDED,
        /** A seal for this person is held already with this sharing key: nothing written. */
        HELD,
        /** A seal for this person is held with another sharing key: nothing written. */
        DIFFERENT,
        /** Not written: the keys aren't two 64-hex keys, the id is no account id, or the store
         *  can't be read or written. */
        REFUSED,
    }

    /** [accountId]'s seals, oldest first. */
    fun seals(accountId: String): Seals {
        val id = AccountIds.canonical(accountId) ?: return Seals.Unreadable
        return synchronized(LOCK) { readSeals(id) }?.let { Seals.Held(it) } ?: Seals.Unreadable
    }

    /**
     * Record a first contact's seal for [userId] under these keys (the finisher records through
     * [trustForSeal], BEFORE it seals): one seal per person, never replaced. Read and written under
     * one lock, and committed, so the record is on
     * disk before anything is sent. Its `sealed_at` is [at], or just after the newest seal held
     * when that is later: a seal recorded after the page took the others is never inside the
     * `upTo` the page then clears.
     */
    fun recordSeal(accountId: String, userId: String, sharingHex: String, signingHex: String, at: Long): SealRecord {
        val id = AccountIds.canonical(accountId) ?: return SealRecord.REFUSED
        val contact = AccountIds.strict(userId) ?: return SealRecord.REFUSED
        return synchronized(LOCK) { recordSealLocked(id, contact, sharingHex, signingHex, at) }
    }

    /** [recordSeal] for a canonical [id] and [contact]. Callers hold [LOCK]. */
    private fun recordSealLocked(id: String, contact: String, sharingHex: String, signingHex: String, at: Long): SealRecord {
        val held = readSeals(id) ?: return SealRecord.REFUSED
        held.firstOrNull { it.userId == contact }?.let { seal ->
            return if (seal.sharingPublicKey.equals(sharingHex, ignoreCase = true)) SealRecord.HELD else SealRecord.DIFFERENT
        }
        if (!HEX64.matches(sharingHex) || !HEX64.matches(signingHex) || sharingHex == CONFLICT_KEY_HEX) {
            return SealRecord.REFUSED
        }
        val sealedAt = maxOf(at, (held.maxOfOrNull { it.sealedAt } ?: 0L) + 1)
        val next = held + Seal(contact, sharingHex.lowercase(), signingHex.lowercase(), sealedAt)
        return if (writeSeals(id, next)) SealRecord.RECORDED else SealRecord.REFUSED
    }

    /**
     * The page took [accountId]'s seals into its pins: remove those with `sealed_at <= upTo` whose
     * fold the account's kept pins NOW show: a conflict for that person, or a pin at BOTH of the
     * seal's own keys (the page's pinMerge.ts makes a conflict when either key differs). Every
     * other seal stays, whatever its time:
     *  - no kept pin for them: the page's keep of the fold hasn't landed, and the seal is the only
     *    record that the app sealed to them (without it, a pass would take them for a first
     *    contact again);
     *  - a kept pin at another sharing or signing key, and no conflict: the page did NOT fold this
     *    seal (a fold of other keys into a pin makes a conflict), and dropping it would lose that
     *    conflict.
     * False when the store can't be read or written.
     */
    fun clearSeals(accountId: String, upTo: Double): Boolean {
        val id = AccountIds.canonical(accountId) ?: return false
        return synchronized(LOCK) {
            val held = readSeals(id) ?: return@synchronized false
            val pins = keptPinsLocked(id).orEmpty()
            val kept = held.filter { seal ->
                val pin = pins[seal.userId]
                val folded = pin != null && (
                    pin.conflict || (
                        pin.sharingPubkeyHex.equals(seal.sharingPublicKey, ignoreCase = true) &&
                            pin.signingPubkeyHex.equals(seal.signingPublicKey, ignoreCase = true)
                        )
                    )
                seal.sealedAt.toDouble() > upTo || !folded
            }
            kept.size == held.size || writeSeals(id, kept)
        }
    }

    /** The account's kept pins, parsed; null when none are kept or they can't be read. Callers
     *  hold [LOCK]. */
    private fun keptPinsLocked(id: String): Map<String, Pin>? = try {
        prefs.getString(key(id, PINS), null)?.let { parsePins(it) }
    } catch (e: Exception) {
        null
    }

    // --- When the finisher last tried each share ---

    /** When the finisher last tried each of [accountId]'s shares (ms, by share id), or null when
     *  the store can't be read. */
    fun triedAt(accountId: String): Map<String, Long>? {
        val id = AccountIds.canonical(accountId) ?: return null
        return synchronized(LOCK) {
            try {
                val raw = prefs.getString(key(id, TRIED), null) ?: return@synchronized emptyMap()
                val root = JSONObject(raw)
                buildMap { for (k in root.keys()) (root.opt(k) as? Number)?.let { put(k, it.toLong()) } }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Keep [tried] as [accountId]'s tries (an empty map removes the entry). False when nothing
     *  was kept. */
    // commit(): a try is recorded before the request it is for goes out.
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun keepTriedAt(accountId: String, tried: Map<String, Long>): Boolean {
        val id = AccountIds.canonical(accountId) ?: return false
        return synchronized(LOCK) {
            try {
                val edit = prefs.edit()
                if (tried.isEmpty()) {
                    edit.remove(key(id, TRIED))
                } else {
                    val root = JSONObject()
                    tried.forEach { (share, at) -> root.put(share, at) }
                    edit.putString(key(id, TRIED), root.toString())
                }
                edit.commit()
            } catch (e: Exception) {
                false
            }
        }
    }

    /** [id]'s seals, or null when they can't be read. Callers hold [LOCK]. */
    private fun readSeals(id: String): List<Seal>? = try {
        val raw = prefs.getString(key(id, SEALS), null)
        if (raw == null) {
            emptyList()
        } else {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Seal(
                    userId = AccountIds.strict(o.getString(SEAL_USER)) ?: throw IllegalStateException("not an id"),
                    sharingPublicKey = o.getString(SEAL_SHARING).also { check(HEX64.matches(it)) },
                    signingPublicKey = o.getString(SEAL_SIGNING).also { check(HEX64.matches(it)) },
                    sealedAt = o.getLong(SEAL_AT),
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Write [seals] as [id]'s, committed (none removes the entry). Callers hold [LOCK]. */
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun writeSeals(id: String, seals: List<Seal>): Boolean = try {
        val edit = prefs.edit()
        if (seals.isEmpty()) {
            edit.remove(key(id, SEALS))
        } else {
            edit.putString(key(id, SEALS), sealsJson(seals).toString())
        }
        edit.commit()
    } catch (e: Exception) {
        false
    }

    companion object {
        const val STORE = "note_kept_web_trust"
        /** The shell keeps no blob bigger than this (UTF-8 bytes); the page sends none. */
        const val MAX_BLOB_BYTES = 1024 * 1024
        private const val PINS = "pins"
        private val BLOBS = listOf(PINS, "floors", "owners")
        private const val SEALS = "seals"
        private const val TRIED = "finish_tried"
        private const val SEAL_USER = "user_id"
        private const val SEAL_SHARING = "sharing_public_key"
        private const val SEAL_SIGNING = "signing_public_key"
        private const val SEAL_AT = "sealed_at"
        private fun key(id: String, blob: String) = "$id|$blob"

        /** The keys a conflict holds (pinMerge.ts `CONFLICT_KEY_HEX`): no real key is all zero. */
        const val CONFLICT_KEY_HEX = "0000000000000000000000000000000000000000000000000000000000000000"
        private val HEX64 = Regex("[0-9a-fA-F]{64}")

        /** One lock for every read-modify-write of this file, process-wide: the finisher records
         *  seals in a worker while the page's channel clears them on the main thread. */
        private val LOCK = Any()

        /** Seals as the page's `takeSeals` answer carries them. */
        fun sealsJson(seals: List<Seal>): JSONArray = JSONArray().apply {
            seals.forEach { s ->
                put(
                    JSONObject()
                        .put(SEAL_USER, s.userId)
                        .put(SEAL_SHARING, s.sharingPublicKey)
                        .put(SEAL_SIGNING, s.signingPublicKey)
                        .put(SEAL_AT, s.sealedAt),
                )
            }
        }

        /**
         * A pins blob read as the page's `foldPins([blob])`: null when it isn't a JSON object. Keys
         * that are no account id (canonical by the page's rule, [AccountIds.strict]) and records
         * that are no pin (both keys strings) are dropped; a record flagged `conflict: true`, or
         * holding the all-zero conflict keys, is a conflict, and so are two records of one contact
         * with different keys.
         */
        internal fun parsePins(raw: String): Map<String, Pin>? {
            val root = try {
                JSONObject(raw)
            } catch (e: Exception) {
                return null
            }
            val out = HashMap<String, Pin>()
            for (rawId in root.keys()) {
                val contact = AccountIds.strict(rawId) ?: continue
                val record = root.opt(rawId) as? JSONObject ?: continue
                val sharing = record.opt("sharingPubkeyHex") as? String ?: continue
                val signing = record.opt("signingPubkeyHex") as? String ?: continue
                val incoming = Pin(
                    sharingPubkeyHex = sharing.lowercase(),
                    signingPubkeyHex = signing.lowercase(),
                    conflict = record.opt("conflict") == true || sharing == CONFLICT_KEY_HEX,
                )
                val held = out[contact]
                out[contact] = when {
                    held == null -> incoming
                    held.conflict || incoming.conflict || held.sharingPubkeyHex != incoming.sharingPubkeyHex ||
                        held.signingPubkeyHex != incoming.signingPubkeyHex ->
                        Pin(CONFLICT_KEY_HEX, CONFLICT_KEY_HEX, conflict = true)
                    else -> held
                }
            }
            return out
        }
    }
}
