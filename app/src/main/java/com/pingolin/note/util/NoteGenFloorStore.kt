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
 * Per-item content-key rollback floor. For each shared item we persist the HIGHEST
 * content-key generation we've ever accepted from a pin-verified wrap — keyed on
 * the generation the signature ACTUALLY verified under (not the server-claimed
 * one). A pin-verified wrap whose generation is BELOW the floor is a rollback —
 * the server replaying an older wrapped content key (e.g. a stale key a
 * since-removed member still holds, with its still-valid signature) — and is
 * dropped by [com.pingolin.note.domain.repository.SharesInboxRepository].
 *
 * The floor only ever advances, and only on kept ('verified') shares. Legitimate
 * generations are monotonic (rotate-shares bumps by exactly 1), so a lower one
 * from a pin-verified sender is an attack, not reordering.
 *
 * Android parallel of the web client's gen-floor map (backed there by
 * localStorage). Persisted in its own [EncryptedSharedPreferences] file
 * (AES-256-GCM + AES-256-SIV), exactly like [SenderKeyPinStore], because the floor
 * is integrity-relevant security state — its whole point is to survive and resist
 * a later server rollback. A storage wipe just re-baselines at first use (TOFU);
 * the blind server can't tamper with it. The map is serialized as one JSON object
 * of itemId -> generation, keyed by the item id's canonical form ([AccountIds]):
 * the SDK binds the parsed id into the signature, so a floor under the raw
 * spelling would read 0 for another spelling of the same item.
 *
 * PER-ACCOUNT NAMESPACING (same shape as [SenderKeyPinStore]): the floor map is
 * stored under a per-account key ([floorsKeyFor], `<KEY_FLOORS>:<userId>`) so
 * account A's floors never satisfy account B on a shared device — the isolation is
 * by construction, not by a wipe. The authoritative current account comes from
 * [SessionManager.getUserId] (which survives a transient
 * [SessionManager.clearAuthOnly] refresh failure and is only cleared by a full
 * manual-logout [SessionManager.clear]), so the right namespace is resolved even
 * in background sync workers that never call [switchUser]. Crucially this means a
 * same-account logout→login RETAINS its floors: the namespaced blob is
 * deliberately kept across the wipe. Dropping it would hand the server a one-time
 * rollback window at the next login, because an empty store re-baselines every
 * item at whatever generation is served next.
 *
 * FAIL-SAFE: when no account id is available (logged out) [floor] returns
 * [Long.MAX_VALUE] so every wrap is treated as below-floor and DROPPED — fail
 * CLOSED rather than read a wrong-account (or empty) store — and writes are
 * no-ops. In practice share processing only runs while authenticated, so this is a
 * defensive backstop.
 *
 * ITEM OWNERS: beside the floors, in the same file and the same namespace
 * (`<KEY_OWNERS>:<userId>`), each shared item's owner — the sender of the first
 * share of it this account accepted. Only an item's owner can write a share of it
 * (the server checks ownership before every wrap), so a share of a bound item from
 * anyone else is refused by the inbox. A separate key, never inside the floor map:
 * an older build would drop a value it doesn't know from the floors at its next
 * write. A binding is never replaced or removed here; only an erasure removes one.
 */
class NoteGenFloorStore internal constructor(
    /** Opens the store's file. Tests pass plain preferences: EncryptedSharedPreferences needs the
     *  Android Keystore, which Robolectric doesn't have. */
    private val openPrefs: () -> SharedPreferences,
    private val sessionManager: SessionManager,
) {

    constructor(context: Context, sessionManager: SessionManager) :
        this({ SecurePrefs.open(context, "note_gen_floors") }, sessionManager)

    /** Opened on first use. `lazy` rethrows an open that failed and tries again next time. */
    private val prefs: SharedPreferences by lazy { openPrefs() }

    /** In-memory cache, loaded lazily from [prefs] on first access. [loaded]
     *  tracks whether [floors] reflects [loadedForUserId]'s namespaced blob;
     *  a change of account (or [switchUser]) forces a reload. All access is
     *  guarded by `this`. */
    private var loaded = false
    private var loadedForUserId: String? = null
    private val floors: MutableMap<String, Long> = mutableMapOf()
    /** Canonical item id → canonical owner id, or [OWNER_CONFLICT]. Loaded with [floors]. */
    private val owners: MutableMap<String, String> = mutableMapOf()

    /**
     * The account whose maps in memory hold something the file does not: shares recorded with
     * [acceptUnsaved], or a save that failed. Null when memory and file agree.
     *
     * It means something only while the maps are still the ones it was set for. Two places keep
     * it so, and nothing else needs to: [ensureLoaded] clears it whenever it replaces the maps
     * (another account, or a reload after [wipe] or [forget] emptied them), and [saveAccepted]
     * writes nothing, and clears it, when the maps are not loaded for that account.
     */
    private var unsavedFor: String? = null

    /**
     * The items whose floor or owner is in memory and not in the file: recorded since the last
     * save that went through. For the inbox refresh, which withholds exactly these when a save
     * fails ([unsaved]).
     */
    private val unsavedItems: MutableSet<String> = mutableSetOf()

    /** Authoritative current account id (lowercased), or null when logged out
     *  / unavailable. Read fresh from [SessionManager] so background workers
     *  resolve the right namespace without a [switchUser] call. */
    private fun currentUserId(): String? = sessionManager.getUserId()

    /**
     * The account a call works for: the one signed in. A caller that names the account it STARTED
     * for ([expected]: an inbox refresh, which runs for a while and holds no lock across its
     * checks) is refused once another account, or none, is signed in, with [StoreUnreadable],
     * which the refresh stops on. What it fetched for the first account is then never judged
     * against, or written into, the next one's records: a switch in the middle of a refresh would
     * otherwise bind and floor the old account's shares under the new account's name.
     *
     * Read once per call, inside the call's own lock: there is no moment between this check and
     * the work at which the account can change under it.
     */
    private fun accountFor(expected: String?): String? {
        val uid = currentUserId()
        if (expected != null && AccountIds.canonical(uid) != AccountIds.canonical(expected)) {
            throw StoreUnreadable("$STORE of an account that is no longer the one signed in")
        }
        return uid
    }

    /** Per-account storage key so one account's floors never satisfy another. */
    private fun floorsKeyFor(userId: String): String = "$KEY_FLOORS:$userId"

    /** Per-account storage key of the item owners. */
    private fun ownersKeyFor(userId: String): String = "$KEY_OWNERS:$userId"

    /**
     * Make [floors] hold [userId]'s map. Read first: only a map that read whole replaces what is
     * held, and one that can't be read (the Keystore refused it, or it doesn't parse) throws
     * [StoreUnreadable] and leaves the store unloaded, so the next use reads it again. It is never
     * "floor 0".
     */
    @Synchronized
    private fun ensureLoaded(userId: String?) {
        if (loaded && loadedForUserId == userId) return
        // Logged out / unknown account → keep the map EMPTY and read nothing
        // from disk (never a wrong-account blob).
        val read = if (userId == null) emptyMap() else readNamespace(userId)
        val readOwners = if (userId == null) emptyMap() else readOwners(userId)
        floors.clear()
        floors.putAll(read)
        owners.clear()
        owners.putAll(readOwners)
        loaded = true
        loadedForUserId = userId
        // What was held unsaved was the maps just replaced: there is nothing of it left to save.
        unsavedFor = null
        unsavedItems.clear()
    }

    /** [userId]'s item owners ([foldOwners]). No older build or other spelling of the account ever wrote
     * this key. One that can't be read throws [StoreUnreadable]. */
    private fun readOwners(userId: String): Map<String, String> {
        val raw = StoreUnreadable.reading(STORE) { prefs.getString(ownersKeyFor(userId), null) } ?: return emptyMap()
        return StoreUnreadable.reading(STORE) { foldOwners(JSONObject(raw)) }
    }

    /**
     * [userId]'s floors: its own map and every map an older build kept under another spelling of
     * its id, folded into one ([fold]). One-time migration off the pre-namespacing global blob,
     * which only ever held the signed-in account's floors, when its own map is absent — itemIds
     * are globally-unique UUIDs, so even a mis-adoption can't lower a real floor. A fold that
     * changed anything is written back canonical and read back before any map it came from is
     * removed; a write that fails leaves them all as they were, and the next load folds them
     * again.
     */
    private fun readNamespace(userId: String): Map<String, Long> {
        val key = floorsKeyFor(userId)
        val (own, spellings, legacy) = StoreUnreadable.reading(STORE) {
            val all = prefs.all
            val spellings = all.filter { (k, v) ->
                v is String && k != key && k.startsWith("$KEY_FLOORS:") && AccountIds.same(k.substringAfter(':'), userId)
            }.mapValues { it.value as String }
            val own = all[key] as? String
            Triple(own, spellings, if (own == null) all[KEY_FLOORS] as? String else null)
        }
        val sources = listOfNotNull(own ?: legacy) + spellings.values
        if (sources.isEmpty()) return emptyMap()
        val (folded, changed) = StoreUnreadable.reading(STORE) { fold(*sources.map { JSONObject(it) }.toTypedArray()) }
        val moved = spellings.keys + listOfNotNull(KEY_FLOORS.takeIf { legacy != null })
        if (changed || moved.isNotEmpty()) {
            val kept = runCatching {
                prefs.edit().putString(key, encode(folded)).commit() &&
                    prefs.getString(key, null)?.let { fold(JSONObject(it)).first } == folded
            }.getOrDefault(false)
            if (kept && moved.isNotEmpty()) {
                runCatching { prefs.edit().apply { moved.forEach { remove(it) } }.commit() }
            }
            if (!kept) android.util.Log.w(TAG, "gen floors not rewritten canonical; the next load folds them again")
        }
        return folded
    }

    private fun encode(map: Map<String, Long>): String {
        val root = JSONObject()
        for ((k, v) in map) root.put(k, v)
        return root.toString()
    }

    private fun encodeOwners(map: Map<String, String>): String {
        val root = JSONObject()
        for ((k, v) in map) root.put(k, v)
        return root.toString()
    }

    /**
     * Establish (or switch to) the local account whose floors are active,
     * eagerly (re)loading its namespaced blob. Called on login + session
     * restore. Reads independently re-resolve the account from
     * [SessionManager], so this is a warm-up / explicit signal, not the sole
     * source of truth. A null [userId] parks the store on the fail-closed
     * (empty) namespace.
     */
    @Synchronized
    fun switchUser(userId: String?) {
        // A warm-up: a store that can't be read now stays unloaded, and its
        // next use reads it again (and throws there).
        try {
            ensureLoaded(AccountIds.canonical(userId))
        } catch (e: StoreUnreadable) {
            android.util.Log.w(TAG, "gen floors can't be read now", e)
        }
    }

    /** Load the signed-in account's floors now: [StoreUnreadable] when they can't be read. The
     *  inbox refresh calls this before it decides anything, naming the account it is for
     *  ([accountFor]). */
    @Synchronized
    fun ensureReadable(forAccount: String? = null) {
        ensureLoaded(accountFor(forAccount))
    }

    /**
     * The highest generation ever accepted for [itemId] from a pin-verified
     * wrap. Returns 0 when we've never accepted one (TOFU baseline — any
     * verified generation clears a floor of 0).
     *
     * FAIL-CLOSED: with no current account id, returns [Long.MAX_VALUE] so the
     * caller drops the wrap (treats it as below-floor) rather than reading a
     * wrong/empty store. Keyed by the canonical id ([AccountIds]): an [itemId]
     * that is no UUID throws [AccountIds.NotAnAccountId], and a store that can't
     * be read throws [StoreUnreadable] — never 0.
     */
    @Synchronized
    fun floor(itemId: String, forAccount: String? = null): Long {
        val key = AccountIds.parse(itemId)
        val uid = accountFor(forAccount)
        ensureLoaded(uid)
        if (uid == null) return Long.MAX_VALUE
        return floors[key] ?: 0L
    }

    /**
     * Advance the floor for [itemId] to [generation] iff it is strictly
     * higher than the current one. The floor ONLY moves up; a lower or
     * equal value is a no-op (idempotent). Non-positive generations are
     * ignored (a generation-0 verified sig must never establish a floor). A
     * no-op when logged out (never writes to an unknown account's namespace).
     */
    @Synchronized
    fun bump(itemId: String, generation: Long) {
        if (generation <= 0L) return
        val key = AccountIds.parse(itemId)
        val uid = currentUserId()
        ensureLoaded(uid)
        if (uid == null) return
        val current = floors[key] ?: 0L
        if (generation <= current) return
        floors[key] = generation
        // Through the one save there is: floors and owners together. Written on its own, the
        // floors map would carry the floors of shares [acceptUnsaved] holds, without their owners.
        unsavedFor = uid
        unsavedItems += key
        if (!saveAccepted()) android.util.Log.w(TAG, "a raised floor was not saved; held in memory, saved with the next")
    }

    /**
     * The owner [itemId] is bound to: the canonical id of the sender of the first share of it this
     * account accepted, [OWNER_CONFLICT] when two stored spellings of it named two owners, or null
     * while it is unbound. Throws like [floor]; with no account it is [OWNER_CONFLICT], so every
     * share is refused (fail closed).
     */
    @Synchronized
    fun owner(itemId: String, forAccount: String? = null): String? {
        val key = AccountIds.parse(itemId)
        val uid = accountFor(forAccount)
        ensureLoaded(uid)
        if (uid == null) return OWNER_CONFLICT
        return owners[key]
    }

    /**
     * Record an accepted share of [itemId]: bind the item to [ownerId] while it is unbound, and
     * raise its floor to [generation] when that is higher (a positive generation, from a
     * pin-verified wrap). Both go in ONE commit, so a floor is never saved without its binding. A
     * binding is never replaced: an item already bound, to anyone, keeps its owner. A no-op when
     * logged out.
     */
    @Synchronized
    fun accept(itemId: String, ownerId: String, generation: Long = 0L) {
        acceptUnsaved(itemId, ownerId, generation)
        if (!saveAccepted()) android.util.Log.w(TAG, "an accepted share's floor and owner were not saved; held in memory, saved with the next")
    }

    /**
     * [accept] without the save: the binding and the floor are in force at once (every read here
     * is of the maps in memory), and they reach the file with the next [saveAccepted].
     *
     * For the inbox refresh, which records every share it accepts and saves them all ONCE, before
     * it publishes any key it opened. [accept] writes the WHOLE of both maps at each call: a
     * refresh that meets N items for the first time would write them N times, each larger than
     * the last, synchronously — for the 2,000 shares one large notebook brings, about 1,500
     * writes and 223 MB; for 10,000, about 5.9 GB.
     *
     * Nothing is weaker for it. What a refresh accepts is never used before the refresh ends (its
     * keys are published at its end, after the save), and a process that dies in between loses
     * the maps in memory and the keys with them: the next refresh accepts them again.
     */
    @Synchronized
    fun acceptUnsaved(itemId: String, ownerId: String, generation: Long = 0L, forAccount: String? = null) {
        val key = AccountIds.parse(itemId)
        val owner = AccountIds.parse(ownerId)
        val uid = accountFor(forAccount)
        ensureLoaded(uid)
        if (uid == null) return
        var changed = false
        if (owners[key] == null) {
            owners[key] = owner
            changed = true
        }
        if (generation > 0L && generation > (floors[key] ?: 0L)) {
            floors[key] = generation
            changed = true
        }
        if (changed) {
            unsavedFor = uid
            unsavedItems += key
        }
    }

    /**
     * The items whose floor or owner [saveAccepted] has not got into the file, canonical. Empty
     * when the file holds what memory holds.
     *
     * A floor that is only in memory dies with the process, and the older key it refused would
     * be taken again afterwards. So what a refresh opened for one of THESE items is not used
     * until a save has gone through; everything else is as safe as it was.
     */
    @Synchronized
    fun unsaved(forAccount: String? = null): Set<String> {
        // Of the account signed in NOW, like every read here: another account's load empties
        // what the one before it held unsaved.
        ensureLoaded(accountFor(forAccount))
        return if (unsavedFor == null) emptySet() else unsavedItems.toSet()
    }

    /**
     * Save what [acceptUnsaved] recorded: floors and owners in ONE commit, so a floor is never
     * saved without its binding.
     *
     * @return true when the file now holds what memory holds (or already did). False when the
     *         commit failed: both maps stay in memory and stay marked, and the next call (the
     *         next refresh's, or the next [accept]) tries again, whether or not anything new was
     *         accepted by then.
     */
    @Synchronized
    fun saveAccepted(forAccount: String? = null): Boolean {
        // A refresh saves for the account it started for, and for no other ([accountFor]).
        accountFor(forAccount)
        val uid = unsavedFor ?: return true
        // The maps were emptied since (a wipe, an erasure) and nothing has loaded them again:
        // what was unsaved went with them, and there is nothing to write. Never one account's
        // maps under another's name either: a load for another account has cleared the mark
        // before this could run, and it is checked all the same.
        if (!loaded || loadedForUserId != uid) {
            unsavedFor = null
            unsavedItems.clear()
            return true
        }
        val saved = runCatching {
            prefs.edit()
                .putString(floorsKeyFor(uid), encode(floors))
                .putString(ownersKeyFor(uid), encodeOwners(owners))
                .commit()
        }.getOrDefault(false)
        if (saved) {
            unsavedFor = null
            unsavedItems.clear()
        }
        return saved
    }

    /**
     * Logout / account-switch cleanup. Drops the in-memory cache (defense in
     * depth — a different account logging in within this live process can't
     * observe the prior account's floors before the namespaced reload) and
     * sweeps the legacy pre-namespacing global blob. The current account's
     * PER-ACCOUNT namespaced blob is deliberately RETAINED so a same-account
     * re-login re-reads its own anti-rollback floors; wiping it would open a
     * one-time rollback window across logout→login. Cross-account isolation
     * does not depend on this wipe; it is guaranteed by per-account
     * namespacing. Called from
     * [com.pingolin.note.domain.repository.LocalDataWiper.wipeBlocking];
     * best-effort.
     */
    @Synchronized
    fun wipe() {
        floors.clear()
        owners.clear()
        unsavedItems.clear()
        loaded = false
        loadedForUserId = null
        try {
            prefs.edit().remove(KEY_FLOORS).apply()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "failed to wipe gen floors", e)
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
            prefs.all.keys.filter { (it.startsWith("$KEY_FLOORS:") || it.startsWith("$KEY_OWNERS:")) && AccountIds.same(it.substringAfter(':'), id) }
        }
        if (keys.isNotEmpty()) {
            val edit = prefs.edit()
            keys.forEach { edit.remove(it) }
            if (!edit.commit()) throw StoreUnreadable(STORE)
        }
        if (loadedForUserId == id) {
            floors.clear()
            owners.clear()
            loaded = false
            loadedForUserId = null
        }
    }

    companion object {
        private const val TAG = "NoteGenFloorStore"
        private const val STORE = "gen floors"
        private const val KEY_FLOORS = "gen_floors"
        private const val KEY_OWNERS = "item_owners"

        /** Two stored spellings of one item bound to two owners: nobody's, every share refused. */
        const val OWNER_CONFLICT = "conflict"

        /**
         * A stored owners map by canonical ids: an entry whose item or owner is no UUID is dropped,
         * and two spellings of one item bound to different owners fold to [OWNER_CONFLICT].
         */
        internal fun foldOwners(root: JSONObject): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = root.optString(k, "")
                val id = AccountIds.canonical(k) ?: continue
                val owner = if (v == OWNER_CONFLICT) OWNER_CONFLICT else AccountIds.canonical(v) ?: continue
                val held = out[id]
                out[id] = if (held == null || held == owner) owner else OWNER_CONFLICT
            }
            return out
        }

        /**
         * Stored maps by canonical item id — one map, or one account's maps kept under several
         * spellings of its id: another spelling of one item keeps the higher generation (a floor
         * only moves up), and a key that is no UUID, or a generation that isn't positive, is
         * dropped. The second value says whether the maps as stored differ from the result.
         */
        internal fun fold(vararg roots: JSONObject): Pair<Map<String, Long>, Boolean> {
            val out = LinkedHashMap<String, Long>()
            var changed = roots.size > 1
            for (root in roots) {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = root.optLong(k, 0L)
                    val id = AccountIds.canonical(k)
                    // Only positive generations are meaningful floors (base 1).
                    if (id == null || v <= 0L) {
                        changed = true
                        continue
                    }
                    if (id != k) changed = true
                    val held = out[id]
                    if (held != null) changed = true
                    out[id] = maxOf(held ?: 0L, v)
                }
            }
            return out to changed
        }
    }
}
