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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-note history of RETIRED (pre-rotation) vault-wrapped content keys.
 *
 * WHY: attachments are sealed under the per-note CONTENT key (the same key the
 * note body/collab use) so share recipients can decrypt them. When
 * [com.pingolin.note.domain.repository.ContentKeyRotator] rotates that key (an
 * unshare / notebook-move) it re-encrypts the note BODY under the fresh key and
 * overwrites the item row's `wrapped_content_key`; the attachment BLOBS in R2 are
 * re-keyed afterwards, best-effort and bounded, so some can stay sealed under the
 * retired key. Without retaining the old key the OWNER would then be unable to
 * decrypt those pre-rotation attachments (decrypt would only try the new content
 * key + vault), a data-availability loss. This store keeps the retired wrap so the
 * attachment decrypt path can fall back to it.
 *
 * AVAILABILITY, NOT forward secrecy: the residual forward-secrecy exposure of a
 * blob left under the retired key is bounded — a revoked recipient LOSES
 * attachment fetch access at revocation regardless (download is gated on CURRENT
 * ownership-or-share), so the only residual is a recipient who cached the
 * ciphertext before revocation — the same inherent E2EE limit as the note body
 * they had already read.
 *
 * STORAGE POSTURE: entries are the exact WRAPPED material the item row already
 * persists (`wrapped_content_key` + nonce), NOT raw content keys, so at rest each
 * is vault-wrapped AND then in [EncryptedSharedPreferences] (AES-256-GCM +
 * AES-256-SIV, Keystore-backed) — no new class of plaintext secret is introduced.
 * Retaining the retired wrap on the OWNER's own device is forward-secrecy-neutral
 * w.r.t. the revoked recipient (who never receives it, and whose own vault could
 * not unwrap it anyway).
 *
 * PER-ACCOUNT NAMESPACING (parity with [NoteGenFloorStore] / [SenderKeyPinStore]):
 * the map is stored under a per-account key ([keyFor], `<KEY_RETIRED>:<userId>`)
 * so account A's retired wraps live in a separate blob from account B on a shared
 * device — isolation is by construction. The authoritative account comes from
 * [SessionManager.getUserId], read fresh so background paths resolve the right
 * namespace. A same-account logout→login RETAINS its blob (see [wipe]) so the
 * owner can still decrypt pre-rotation attachments after re-login.
 *
 * FAIL-SAFE: with no account id (logged out) reads return empty and writes are
 * no-ops — never touch a wrong-account blob.
 */
class RetainedContentKeyStore internal constructor(
    /** Opens the store's file. Tests pass plain preferences: EncryptedSharedPreferences needs the
     *  Android Keystore, which Robolectric doesn't have. */
    private val openPrefs: () -> SharedPreferences,
    private val sessionManager: SessionManager,
) {

    constructor(context: Context, sessionManager: SessionManager) :
        this({ SecurePrefs.open(context, "note_retired_content_keys") }, sessionManager)

    /** One retired vault-wrapped content key: base64 wrap + base64 24-byte nonce. */
    data class WrappedKey(val wrap: String, val nonce: String)

    /** Opened on first use. `lazy` rethrows an open that failed and tries again next time. */
    private val prefs: SharedPreferences by lazy { openPrefs() }

    /** In-memory cache (noteId -> ordered retired wraps, oldest first). Guarded
     *  by `this`; reloaded on account change. */
    private var loaded = false
    private var loadedForUserId: String? = null
    private val retired: MutableMap<String, MutableList<WrappedKey>> = mutableMapOf()

    /** The signed-in account's canonical id ([AccountIds]), or null. */
    private fun currentUserId(): String? = sessionManager.getUserId()

    private fun keyFor(userId: String): String = "$KEY_RETIRED:$userId"

    @Synchronized
    private fun ensureLoaded(userId: String?) {
        if (loaded && loadedForUserId == userId) return
        retired.clear()
        loaded = true
        loadedForUserId = userId
        if (userId == null) return
        val maps = try {
            readNamespace(userId)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "failed to load retired content keys", e)
            return
        }
        retired.putAll(maps)
    }

    /**
     * [userId]'s retired wraps: its own map and every map an older build kept under another
     * spelling of its id, merged — per note, the union, deduplicated on (wrap, nonce), this
     * account's own entries last, the newest [MAX_PER_NOTE] kept. A merge is written and read
     * back before any map it came from is removed; a write that fails leaves them all as they
     * were, and the next load merges them again. Throws when a map can't be read.
     */
    private fun readNamespace(userId: String): Map<String, MutableList<WrappedKey>> {
        val key = keyFor(userId)
        val all = prefs.all
        val spellings = all.filter { (k, v) ->
            v is String && k != key && k.startsWith("$KEY_RETIRED:") && AccountIds.same(k.substringAfter(':'), userId)
        }.mapValues { it.value as String }
        val own = all[key] as? String
        val sources = spellings.values + listOfNotNull(own)
        if (sources.isEmpty()) return emptyMap()
        val merged = merge(sources.map { JSONObject(it) })
        if (spellings.isNotEmpty()) {
            val kept = runCatching {
                prefs.edit().putString(key, encode(merged)).commit() &&
                    prefs.getString(key, null)?.let { merge(listOf(JSONObject(it))) } == merged
            }.getOrDefault(false)
            if (kept) runCatching { prefs.edit().apply { spellings.keys.forEach { remove(it) } }.commit() }
            else android.util.Log.w(TAG, "retired content keys not merged; the next load merges them again")
        }
        return merged
    }

    private fun encode(map: Map<String, List<WrappedKey>>): String {
        val root = JSONObject()
        for ((noteId, list) in map) {
            val arr = JSONArray()
            for (wk in list) arr.put(JSONObject().put("w", wk.wrap).put("n", wk.nonce))
            root.put(noteId, arr)
        }
        return root.toString()
    }

    private fun persist(userId: String) {
        try {
            prefs.edit().putString(keyFor(userId), encode(retired)).apply()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "failed to persist retired content keys", e)
        }
    }

    /**
     * Record a retired (pre-rotation) vault-wrapped content key for [noteId].
     * Idempotent on (wrap, nonce); keeps at most [MAX_PER_NOTE] most-recent
     * entries (dropping the oldest) to bound growth under pathological
     * repeated-rotation. No-op when logged out. Call BEFORE overwriting the item
     * row's wrap with the fresh one.
     */
    @Synchronized
    fun retain(noteId: String, wrap: String, nonce: String) {
        if (wrap.isEmpty() || nonce.isEmpty()) return
        val uid = currentUserId() ?: return
        ensureLoaded(uid)
        val list = retired.getOrPut(noteId) { mutableListOf() }
        if (list.any { it.wrap == wrap && it.nonce == nonce }) return
        list.add(WrappedKey(wrap, nonce))
        while (list.size > MAX_PER_NOTE) list.removeAt(0)
        persist(uid)
    }

    /** Retired wrapped content keys for [noteId], oldest first. Empty when logged
     *  out (fail-safe). The caller unwraps each with the vault at decrypt time. */
    @Synchronized
    fun retained(noteId: String): List<WrappedKey> {
        val uid = currentUserId() ?: return emptyList()
        ensureLoaded(uid)
        return retired[noteId]?.toList() ?: emptyList()
    }

    /**
     * Logout / account-switch cleanup. Drops the in-memory cache (defense in
     * depth — a different account signing in within this live process can't read
     * the prior account's cache before the namespaced reload). The current
     * account's PER-ACCOUNT namespaced blob is deliberately RETAINED so a
     * same-account re-login can still decrypt its pre-rotation attachments;
     * cross-account isolation is guaranteed by namespacing, not by this wipe.
     * Called from [com.pingolin.note.domain.repository.LocalDataWiper]; best-effort.
     */
    @Synchronized
    fun wipe() {
        retired.clear()
        loaded = false
        loadedForUserId = null
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
            prefs.all.keys.filter { (it.startsWith("$KEY_RETIRED:")) && AccountIds.same(it.substringAfter(':'), id) }
        }
        if (keys.isNotEmpty()) {
            val edit = prefs.edit()
            keys.forEach { edit.remove(it) }
            if (!edit.commit()) throw StoreUnreadable(STORE)
        }
        if (loadedForUserId == id) {
            retired.clear()
            loaded = false
            loadedForUserId = null
        }
    }

    companion object {
        private const val TAG = "RetainedContentKeyStore"
        private const val STORE = "retired content keys"
        private const val KEY_RETIRED = "retired_keys"
        private const val MAX_PER_NOTE = 32

        /** [roots] (maps of noteId → [{w, n}]) as one: per note, the union in order, a repeated
         *  (wrap, nonce) moved to its latest place, the newest [MAX_PER_NOTE] kept. */
        internal fun merge(roots: List<JSONObject>): Map<String, MutableList<WrappedKey>> {
            val out = LinkedHashMap<String, LinkedHashSet<WrappedKey>>()
            for (root in roots) {
                val notes = root.keys()
                while (notes.hasNext()) {
                    val noteId = notes.next()
                    val arr = root.optJSONArray(noteId) ?: continue
                    val set = out.getOrPut(noteId) { LinkedHashSet() }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val w = o.optString("w", "")
                        val n = o.optString("n", "")
                        if (w.isEmpty() || n.isEmpty()) continue
                        val wk = WrappedKey(w, n)
                        set.remove(wk)
                        set.add(wk)
                    }
                }
            }
            return out.filterValues { it.isNotEmpty() }
                .mapValuesTo(LinkedHashMap()) { (_, set) -> set.toList().takeLast(MAX_PER_NOTE).toMutableList() }
        }
    }
}
