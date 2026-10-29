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
import org.json.JSONObject

/**
 * TOFU (trust-on-first-use) pin store for share counterparties' identity keys.
 * Android parallel of the web client's pinned-keys map.
 *
 * Keyed by the OTHER party's user_id (the sender when we're the recipient of a
 * share). Once a sender's signing key is observed on first successful
 * content-key unwrap, we pin it here and verify every later share-wrap
 * signature against the PINNED key — never against a key the server hands us
 * at verify time. A malicious/compromised server can return an attacker
 * signing key together with a signature made by that same attacker key, which
 * would "verify" against the server-supplied key; pinning defeats that because
 * verification uses the key we observed on first use.
 *
 * Persisted in its own [EncryptedSharedPreferences] file (AES-256-GCM +
 * AES-256-SIV), exactly like [SessionManager], because a pin is
 * security-relevant state (its whole point is to survive a later server
 * key-substitution). The map is serialized as one JSON blob keyed by the owner
 * user_id's canonical form ([AccountIds]); a blob an older build keyed by
 * another spelling is folded into it when it loads ([fold]).
 *
 * PER-ACCOUNT NAMESPACING: the blob is stored under a per-account key
 * ([pinsKeyFor], `<KEY_PINS>:<userId>`) so account A's TOFU trust decisions
 * never carry into account B on a shared device — the isolation is by
 * construction, not by a wipe. The authoritative current account comes from
 * [SessionManager.getUserId] (which survives a transient
 * [SessionManager.clearAuthOnly] refresh failure and is only cleared by a full
 * manual-logout [SessionManager.clear]), so the right namespace is resolved
 * even in background sync workers that never call [switchUser]. A same-account
 * logout→login RETAINS its pins (the persisted namespaced blob is kept across
 * the wipe). When no account id is available (logged out) reads see an empty
 * store and writes don't persist — the store never reads a wrong-account blob.
 *
 * A pin is NEVER silently overwritten: [pin] reports a `Changed*` state when
 * the presented key differs from what's stored but leaves the stored pin
 * intact, and nothing re-pins a changed key.
 */
class SenderKeyPinStore internal constructor(
    /** Opens the store's file. Tests pass plain preferences: EncryptedSharedPreferences needs the
     *  Android Keystore, which Robolectric doesn't have. */
    private val openPrefs: () -> SharedPreferences,
    private val sessionManager: SessionManager,
) {

    constructor(context: Context, sessionManager: SessionManager) :
        this({ SecurePrefs.open(context, "note_sender_pins") }, sessionManager)

    /** One pinned counterparty. [sharingPubHex] is optional because the
     *  signing key is the load-bearing one for wrap-signature verification;
     *  we pin the sharing key too when the directory returns it. */
    data class PinRecord(
        val signingPubHex: String,
        val sharingPubHex: String?,
        /** Unix seconds of the first (TOFU) pin. */
        val pinnedAt: Long,
    ) {
        /** Two records with different keys were found for this counterparty ([fold]): it reads as
         *  changed for any key, and is never matched and never a first contact. */
        val isConflict: Boolean get() = signingPubHex == CONFLICT_KEY_HEX
    }

    /** Outcome of a [pin] call. */
    enum class PinState { NewPinning, Matched, ChangedSharing, ChangedSigning, ChangedBoth }


    /** Opened on first use. `lazy` rethrows an open that failed and tries again next time. */
    private val prefs: SharedPreferences by lazy { openPrefs() }

    /** In-memory cache, loaded lazily from [prefs] on first access. [loaded]
     *  tracks whether [pins] reflects [loadedForUserId]'s namespaced blob; a
     *  change of account (or [switchUser]) forces a reload. All access is
     *  guarded by `this`. */
    private var loaded = false
    private var loadedForUserId: String? = null
    private val pins: MutableMap<String, PinRecord> = mutableMapOf()

    /** Authoritative current account id (canonical, [AccountIds]), or null when
     *  logged out / unavailable. Read fresh from [SessionManager] so background
     *  workers resolve the right namespace without a [switchUser] call. */
    private fun currentUserId(): String? = sessionManager.getUserId()

    /** Per-account storage key so one account's pins never verify another's. */
    private fun pinsKeyFor(userId: String): String = "$KEY_PINS:$userId"

    /**
     * Make [pins] hold [userId]'s blob. Read first: only a blob that read whole replaces what is
     * held, and one that can't be read (the Keystore refused it, or it doesn't parse) throws
     * [StoreUnreadable] and leaves the store unloaded, so the next use reads it again. It is never
     * "nothing pinned".
     */
    @Synchronized
    private fun ensureLoaded(userId: String?) {
        if (loaded && loadedForUserId == userId) return
        // Logged out / unknown account → keep the map EMPTY and read nothing
        // from disk (never a wrong-account blob).
        val read = if (userId == null) Folded(emptyMap(), changed = false) else readNamespace(userId)
        pins.clear()
        pins.putAll(read.pins)
        loaded = true
        loadedForUserId = userId
    }

    /**
     * [userId]'s pins: its own blob and every blob an older build kept under another spelling of
     * its id, folded into one ([fold]). One-time migration off the pre-namespacing global blob,
     * which only ever held the signed-in account's pins, when its own blob is absent — pins are
     * the counterparty's PUBLIC keys, so even a mis-adoption is harmless (matching key = correct
     * pin; mismatch = fail-closed drop until re-pin). A fold that changed anything is written
     * back canonical and read back before any blob it came from is removed; a write that fails
     * leaves them all as they were, and the next load folds them again.
     */
    private fun readNamespace(userId: String): Folded {
        val key = pinsKeyFor(userId)
        val (own, spellings, legacy) = StoreUnreadable.reading(STORE) {
            val all = prefs.all
            val spellings = all.filter { (k, v) ->
                v is String && k != key && k.startsWith("$KEY_PINS:") && AccountIds.same(k.substringAfter(':'), userId)
            }.mapValues { it.value as String }
            val own = all[key] as? String
            Triple(own, spellings, if (own == null) all[KEY_PINS] as? String else null)
        }
        val sources = listOfNotNull(own ?: legacy) + spellings.values
        if (sources.isEmpty()) return Folded(emptyMap(), changed = false)
        val folded = StoreUnreadable.reading(STORE) { fold(*sources.map { JSONObject(it) }.toTypedArray()) }
        val moved = spellings.keys + listOfNotNull(KEY_PINS.takeIf { legacy != null })
        if (folded.changed || moved.isNotEmpty()) {
            val kept = runCatching {
                prefs.edit().putString(key, encode(folded.pins)).commit() &&
                    prefs.getString(key, null)?.let { fold(JSONObject(it)).pins } == folded.pins
            }.getOrDefault(false)
            if (kept && moved.isNotEmpty()) {
                runCatching { prefs.edit().apply { moved.forEach { remove(it) } }.commit() }
            }
            if (!kept) android.util.Log.w(TAG, "sender pins not rewritten canonical; the next load folds them again")
        }
        return folded
    }

    /** A blob's pins by canonical owner id, and whether that changed anything. */
    internal data class Folded(val pins: Map<String, PinRecord>, val changed: Boolean)

    private fun encode(map: Map<String, PinRecord>): String {
        val root = JSONObject()
        for ((k, v) in map) {
            val o = JSONObject()
            o.put(FIELD_SIGNING, v.signingPubHex)
            v.sharingPubHex?.let { o.put(FIELD_SHARING, it) }
            o.put(FIELD_PINNED_AT, v.pinnedAt)
            root.put(k, o)
        }
        return root.toString()
    }

    private fun persist(userId: String) {
        try {
            prefs.edit().putString(pinsKeyFor(userId), encode(pins)).apply()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "failed to persist sender pins", e)
        }
    }

    /**
     * Establish (or switch to) the local account whose pins are active,
     * eagerly (re)loading its namespaced blob. Called on login + session
     * restore. Reads independently re-resolve the account from
     * [SessionManager], so this is a warm-up / explicit signal, not the sole
     * source of truth. A null [userId] parks the store on the empty
     * (logged-out) namespace.
     */
    @Synchronized
    fun switchUser(userId: String?) {
        // A warm-up: a store that can't be read now stays unloaded, and its
        // next use reads it again (and throws there).
        try {
            ensureLoaded(AccountIds.canonical(userId))
        } catch (e: StoreUnreadable) {
            android.util.Log.w(TAG, "sender pins can't be read now", e)
        }
    }

    /**
     * The account a call works for: the one signed in. A caller that names the account it STARTED
     * for ([expected]: an inbox refresh) is refused, with [StoreUnreadable], once another account
     * or none is signed in: the old account's senders are never checked against, or pinned into,
     * the new account's pins (see [NoteGenFloorStore]).
     */
    private fun accountFor(expected: String?): String? {
        val uid = currentUserId()
        if (expected != null && AccountIds.canonical(uid) != AccountIds.canonical(expected)) {
            throw StoreUnreadable("$STORE of an account that is no longer the one signed in")
        }
        return uid
    }

    /** Load the signed-in account's pins now: [StoreUnreadable] when they can't be read. The inbox
     *  refresh calls this before it decides anything, naming the account it is for. */
    @Synchronized
    fun ensureReadable(forAccount: String? = null) {
        ensureLoaded(accountFor(forAccount))
    }

    /** Read a pin (null if this counterparty is unpinned, or when logged out —
     *  which routes the caller to the TOFU/grace path, never a wrong-account
     *  pin). Keyed by the canonical id ([AccountIds]): any spelling of a pinned
     *  account finds its pin, and an [ownerId] that is no UUID throws
     *  [AccountIds.NotAnAccountId] — never "not pinned". A store that can't be
     *  read throws [StoreUnreadable]. */
    @Synchronized
    fun getPinned(ownerId: String, forAccount: String? = null): PinRecord? {
        val key = AccountIds.parse(ownerId)
        ensureLoaded(accountFor(forAccount))
        return pins[key]
    }

    /**
     * Pin (or compare against an existing pin for) a counterparty's
     * signing (and, when available, sharing) public key. Case-insensitive
     * hex compare; the map is keyed by [ownerId]'s canonical form ([getPinned]).
     *
     * - No existing pin → stores it and returns [PinState.NewPinning].
     * - Everything matches → [PinState.Matched] (no write).
     * - Signing key differs → [PinState.ChangedSigning] / [PinState.ChangedBoth]
     *   and the stored pin is left INTACT (never silently overwritten) so
     *   the caller can alarm and gate on out-of-band confirmation.
     * - Only the sharing key differs → [PinState.ChangedSharing] (also no
     *   overwrite).
     */
    @Synchronized
    fun pin(ownerId: String, signingPubHex: String, sharingPubHex: String?, forAccount: String? = null): PinState {
        val key = AccountIds.parse(ownerId)
        val uid = accountFor(forAccount)
        ensureLoaded(uid)
        val signingLower = signingPubHex.lowercase()
        val sharingLower = sharingPubHex?.lowercase()
        val existing = pins[key]
        if (existing == null) {
            pins[key] = PinRecord(signingLower, sharingLower, nowSecs())
            if (uid != null) persist(uid)
            return PinState.NewPinning
        }
        val signingMatch = existing.signingPubHex.equals(signingLower, ignoreCase = true)
        // Can't compare the sharing key when either side is absent — treat
        // as a match so a missing sharing key never masquerades as a change.
        val sharingMatch = when {
            sharingLower == null || existing.sharingPubHex == null -> true
            else -> existing.sharingPubHex.equals(sharingLower, ignoreCase = true)
        }
        // Report the change only: a rotated key is never re-pinned.
        return when {
            signingMatch && sharingMatch -> PinState.Matched
            signingMatch && !sharingMatch -> PinState.ChangedSharing
            !signingMatch && sharingMatch -> PinState.ChangedSigning
            else -> PinState.ChangedBoth
        }
    }

    /**
     * SEND-SIDE gate (TOFU). Decide whether it's safe to seal a content key to
     * [recipientUserId] under the directory-returned [sharingPubHex]. E2EE /
     * blindness-critical and symmetric with the receive-side [pin] check: a
     * malicious or compromised server that substitutes a recipient's sharing
     * key at share time would otherwise make us seal the per-note content key
     * to an attacker-held key.
     *
     *  - No [sharingPubHex] → nothing to seal under, nothing to gate → true.
     *  - [sharingPubHex] present but [recipientUserId] null → we hold a key to
     *    seal to but no identity to check it against → FAIL CLOSED (false). A
     *    real directory response always carries a user_id, so this refuses ONLY
     *    the substitution where the server nulls the id to bypass an existing pin.
     *  - No pin yet → trust-on-first-use: pin the directory keys (needs a
     *    non-blank [signingPubHex] — the load-bearing field; if the server
     *    didn't return one we can't pin yet but still proceed) and return
     *    true.
     *  - Pin exists and its sharing key matches (case-insensitive) → true.
     *  - Pin exists but never recorded a sharing key → can't verify the
     *    presented key against it → FAIL CLOSED (false).
     *  - Pin exists and its sharing key DIFFERS from [sharingPubHex] → false:
     *    the caller MUST skip this recipient and NOT seal under the
     *    substituted key.
     *
     * The rule behind the cases: once a [sharingPubHex] is present, anything
     * that prevents checking it against a verified identity refuses rather than
     * sealing to a possibly server-supplied key. Only a truly absent
     * [sharingPubHex] passes unconditionally. Never overwrites an existing pin.
     */
    @Synchronized
    fun allowSealToRecipient(
        recipientUserId: String?,
        signingPubHex: String?,
        sharingPubHex: String?,
    ): Boolean {
        if (sharingPubHex == null) return true    // nothing to seal → nothing to gate
        // A key to seal to but no identity to verify it against → FAIL CLOSED.
        // A legitimate directory response always carries a user_id, so this only
        // blocks the substitution where the server nulls the id to sidestep an
        // already-established pin.
        if (recipientUserId == null) return false
        // An id that is no UUID names nobody this device can check: refused, and
        // nothing is pinned under it.
        val key = AccountIds.canonical(recipientUserId) ?: return false
        try {
            ensureLoaded(currentUserId())
        } catch (e: StoreUnreadable) {
            // Pins that can't be read can't vouch for this key.
            return false
        }
        val existing = pins[key]
        if (existing == null) {
            // TOFU: record the directory keys so a LATER substitution is
            // caught. pin() is @Synchronized on the same monitor (reentrant).
            if (!signingPubHex.isNullOrBlank()) {
                pin(recipientUserId, signingPubHex, sharingPubHex)
            }
            return true
        }
        // A pin with no stored sharing key can't be verified against the
        // presented key → FAIL CLOSED rather than seal blindly.
        val pinnedSharing = existing.sharingPubHex ?: return false
        return pinnedSharing.equals(sharingPubHex, ignoreCase = true)
    }

    /**
     * Logout / account-switch cleanup. Drops the in-memory cache (defense in
     * depth — a different account logging in within this live process can't
     * observe the prior account's pins before the namespaced reload) and
     * sweeps the legacy pre-namespacing global blob. The current account's
     * PER-ACCOUNT namespaced blob is deliberately RETAINED so a same-account
     * re-login re-reads its own TOFU pins. Cross-account isolation does not
     * depend on this wipe; it is guaranteed by per-account namespacing — a
     * wiped/absent namespace simply re-baselines at first use (TOFU). Called
     * from [com.pingolin.note.domain.repository.LocalDataWiper.wipeBlocking];
     * best-effort.
     */
    @Synchronized
    fun wipe() {
        pins.clear()
        loaded = false
        loadedForUserId = null
        try {
            prefs.edit().remove(KEY_PINS).apply()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "failed to wipe sender pins", e)
        }
    }

    /**
     * Delete every blob of [accountId], under every spelling, in one commit (that account's
     * erasure, or a sign-in under another typed e-mail). A store that can't be read, or a commit
     * that fails, throws, and nothing is removed.
     */
    @Synchronized
    fun forget(accountId: String) {
        val id = AccountIds.parse(accountId)
        val keys = StoreUnreadable.reading(STORE) {
            prefs.all.keys.filter { (it.startsWith("$KEY_PINS:")) && AccountIds.same(it.substringAfter(':'), id) }
        }
        if (keys.isNotEmpty()) {
            val edit = prefs.edit()
            keys.forEach { edit.remove(it) }
            if (!edit.commit()) throw StoreUnreadable(STORE)
        }
        if (loadedForUserId == id) {
            pins.clear()
            loaded = false
            loadedForUserId = null
        }
    }

    private fun nowSecs(): Long = System.currentTimeMillis() / 1000

    companion object {
        private const val TAG = "SenderKeyPinStore"
        private const val STORE = "sender pins"
        private const val KEY_PINS = "sender_pins"
        private const val FIELD_SIGNING = "signing"
        private const val FIELD_SHARING = "sharing"
        private const val FIELD_PINNED_AT = "pinnedAt"

        /** Both keys of a conflict ([PinRecord.isConflict]). No real key is all zero, so an
         *  older build reading the store sees a changed key too (the sentinel the web and track use). */
        const val CONFLICT_KEY_HEX = "0000000000000000000000000000000000000000000000000000000000000000"

        /**
         * Stored blobs' entries by canonical owner id, by the pin-merge rule (the same rule as
         * the web editor's pinMerge.ts and the Track Android app's MemberPinBlobs) — within one
         * blob and across the blobs of one account kept under several spellings of its id:
         *  - an entry whose key is no UUID, or that holds no signing key, is dropped;
         *  - two entries for one account with the same keys are one, with the earliest first pin
         *    (a sharing key one of them lacks is no difference, as in [pin]);
         *  - with different keys they are a conflict ([CONFLICT_KEY_HEX] for both keys), which
         *    reads as changed.
         * `changed` says whether the blobs as stored differ from the result.
         */
        internal fun fold(vararg roots: JSONObject): Folded {
            val out = LinkedHashMap<String, PinRecord>()
            var changed = roots.size > 1
            for (root in roots) {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val o = root.optJSONObject(k)
                    val signing = o?.optString(FIELD_SIGNING, "").orEmpty()
                    val id = AccountIds.canonical(k)
                    if (o == null || signing.isEmpty() || id == null) {
                        changed = true
                        continue
                    }
                    if (id != k) changed = true
                    val record = PinRecord(
                        signingPubHex = signing,
                        sharingPubHex = o.optString(FIELD_SHARING, "").takeIf { it.isNotEmpty() },
                        pinnedAt = o.optLong(FIELD_PINNED_AT, 0L),
                    )
                    val held = out[id]
                    if (held != null) changed = true
                    out[id] = if (held == null) record else combined(held, record)
                }
            }
            return Folded(out, changed)
        }

        private fun combined(a: PinRecord, b: PinRecord): PinRecord {
            val first = listOf(a.pinnedAt, b.pinnedAt).filter { it > 0 }.minOrNull() ?: 0L
            val sameSigning = !a.isConflict && !b.isConflict &&
                a.signingPubHex.equals(b.signingPubHex, ignoreCase = true)
            val sameSharing = a.sharingPubHex == null || b.sharingPubHex == null ||
                a.sharingPubHex.equals(b.sharingPubHex, ignoreCase = true)
            return if (sameSigning && sameSharing) {
                PinRecord(a.signingPubHex, a.sharingPubHex ?: b.sharingPubHex, first)
            } else {
                PinRecord(CONFLICT_KEY_HEX, CONFLICT_KEY_HEX, first)
            }
        }
    }
}
