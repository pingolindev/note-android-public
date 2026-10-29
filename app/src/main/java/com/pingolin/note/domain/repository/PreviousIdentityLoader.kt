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

package com.pingolin.note.domain.repository

import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.util.SessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.SealedPreviousIdentity
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identities from before a password reset.
 *
 * Note's vault key is DERIVED from the account keypair, so an email password
 * reset changes it and EVERY note stops opening at once. Nothing is lost — the
 * ciphertext is untouched — but the device holds a valid session and a library
 * it cannot read, which is the worst version of this failure.
 *
 * If the user still had their recovery key they can recover that identity in the
 * account portal, which seals it under a value the SDK derives from the key we
 * already hold. All the crypto is in the SDK; this fetches and hands it over,
 * then exposes the vault key of each recovered era.
 */
@Singleton
class PreviousIdentityLoader @Inject constructor(
    private val authApi: AuthApi,
    private val cryptoManager: CryptoManager,
    private val sessionManager: SessionManager,
) {
    private companion object { const val TAG = "PreviousIdentity" }

    @Volatile
    private var loaded = false

    /** Serialises the fetch+import. `loaded` alone is a check-then-act: a
     * foreground refresh and the sync worker can both clear it within
     * milliseconds of each other and each do the whole round-trip. Harmless
     * while the import is idempotent — it overwrites the same vec — which is
     * exactly the kind of thing that stops being true quietly. Same shape as
     * QuotaManager's refreshLock. */
    private val loadLock = Mutex()

    /** Bumped by [reset] (logout / account switch). A load snapshots it before
     * its network round-trip and refuses to latch if it changed meanwhile, so
     * an in-flight load for the PRIOR account cannot re-set `loaded` on top of
     * the reset and send the INCOMING account back to skipping its own fetch —
     * the very failure [reset] exists to prevent, arriving a few milliseconds
     * late. Mirrors QuotaManager.generation. */
    private val generation = AtomicLong(0L)

    /** Every sealed blob this session has taken in. The SDK import REPLACES its
     * set, so each import passes the union with what came before: a later
     * answer — empty, shorter, or a lie — can only add. Replaced whole, never
     * mutated, so [reset] can drop it without taking [loadLock]. */
    @Volatile
    private var held: List<SealedPreviousIdentity> = emptyList()

    /** Makes "is this still the same account?" and the SDK import one step, so a
     *  [reset] cannot slip between them. Held only for the local import, never
     *  across the network. */
    private val importLock = Any()

    /**
     * Load once per session, before anything is decrypted.
     *
     * Never throws: for almost every account this returns 0, and a failure must
     * only mean older notes stay unreadable — not that syncing stops.
     */
    suspend fun ensureLoaded(): Int {
        if (loaded) return cryptoManager.previousVaultKeys().size
        return loadLock.withLock { loadLocked() }
    }

    /** [ensureLoaded]'s body, under [loadLock]. */
    private suspend fun loadLocked(): Int {
        // Re-check inside the lock: whoever lost the race has already done all
        // of this, and repeating it would be the double-fetch by another name.
        if (loaded) return cryptoManager.previousVaultKeys().size
        val gen = generation.get()
        return try {
            val sealed = fetchSealed()
            if (sealed.isEmpty()) {
                // The answer for almost every account. Nothing to open, nothing
                // to retry, and nothing worth a line in the log.
                latchIfSameAccount(gen)
                return 0
            }
            // Null: the account changed while this was on the
            // network. Its answer is dropped, not imported into the
            // next session.
            val imported = importWithCurrentKey(sealed, gen) ?: return 0
            // Only latch AFTER the import: `loaded` means "we have
            // taken in what this account has", not "we asked once".
            // Setting it before the import would make a failed
            // import permanent for the whole process.
            latchIfSameAccount(gen)
            // WARN, not INFO: proguard-rules.pro strips v/d/i from release via
            // -assumenosideeffects, so an INFO line here prints nothing on the
            // build users actually run and its absence tells you nothing. This
            // fires only for an account that HAS recovered identities, so it is
            // rare by construction — including when `imported` is 0, which is
            // the state where the user stares at a library of "Untitled" notes
            // and someone has to know whether the keys arrived.
            android.util.Log.w(TAG, "previous identities: $imported of ${sealed.size} sealed opened")
            imported
        } catch (e: Exception) {
            // `loaded` stays false so a later sync retries — a transient network
            // failure must not hide recovered notes for the whole session.
            android.util.Log.w(TAG, "previous identities unavailable: ${e.message}")
            0
        }
    }

    /**
     * Ask again, after a restore elsewhere, and import the UNION with what this
     * session already took in. True only when more earlier identities open than
     * before; the caller re-decrypts then, and nothing else happens when this is
     * false. The push that asks for this carries nothing: this is the same
     * signed-in fetch and SDK import as [ensureLoaded], and the SDK opens a blob
     * only with a key derived from this device's own private key.
     *
     * Never throws: a failure only means older notes stay locked until the next
     * re-check.
     */
    suspend fun recheck(): Boolean = loadLock.withLock {
        val gen = generation.get()
        val before = vaultKeys().size
        try {
            val sealed = fetchSealed()
            if (sealed.isEmpty() && held.isEmpty()) {
                latchIfSameAccount(gen)
                return@withLock false
            }
            val imported = importWithCurrentKey(sealed, gen) ?: return@withLock false
            latchIfSameAccount(gen)
            android.util.Log.w(TAG, "previous identities re-check: $imported open")
            vaultKeys().size > before
        } catch (e: Exception) {
            android.util.Log.w(TAG, "previous identities re-check failed: ${e.message}")
            false
        }
    }

    private suspend fun fetchSealed(): List<SealedPreviousIdentity> =
        authApi.getPreviousIdentities().body()?.data?.identities.orEmpty().mapNotNull { row ->
            row.appSealedPrivateKey?.let {
                SealedPreviousIdentity(nonce = it.nonce, ciphertext = it.ciphertext)
            }
        }

    /**
     * Latch, unless the account changed under us while we were on the network.
     *
     * The import itself is conditional too ([importWithCurrentKey] checks the
     * same generation under [importLock]), and the wipe resets this loader
     * BEFORE `clearAllKeys`, so a load that raced it imports nothing into the
     * next session.
     */
    private fun latchIfSameAccount(gen: Long) {
        if (generation.get() == gen) loaded = true
    }

    /**
     * Open the sealed blobs, restoring the account private key first if the SDK
     * is not holding it.
     *
     * The seal is keyed by `BLAKE2b(current private key, ...)`, so the import
     * needs the SDK's in-memory key slot — and this app only fills that slot at
     * login/registration (`CryptoManager.importPrivateKey`). It registers no
     * `KeyStorageCallback`, so `loadPersistedKeys()` is a no-op and nothing
     * refills the slot when the process restarts on a stored session. Without
     * the restore, every launch after the one that signed in would throw
     * `KeyNotFound` here, and a user who recovered their identity AFTER their
     * phone had already synced would keep a library of unreadable notes: the
     * recovered vault keys would never exist for the decrypt to try.
     *
     * The key itself is not lost — login persists it
     * ([SessionManager.saveSharingPrivateKey]) — so hand it back and retry.
     * Import-first-ask-later on purpose: the happy path never touches the key
     * slot, so a live in-memory key is never overwritten by a persisted copy.
     *
     * This restore is deliberately HERE rather than at app startup:
     * `import_previous_identities` is the only thing in this app that reads the
     * SDK's X25519 slot at all — every other CryptoManager call the app makes
     * takes its key as a parameter, and `persist_keys` returns early because no
     * KeyStorageCallback is registered — so a startup seed would duplicate this
     * and nothing else. A second consumer changes that calculus: seed the slot
     * from [SessionManager] then, never by registering secure storage, which
     * would put a second copy of the key at rest on a different backend with its
     * own lifetime and its own wipe.
     */
    private fun importWithCurrentKey(sealed: List<SealedPreviousIdentity>, gen: Long): Int? =
        synchronized(importLock) {
            // [gen] was taken BEFORE the fetch. A sign-out or
            // account switch since then means these blobs are the
            // departed account's: import nothing and keep nothing
            // (the rule for every key load that awaits the network).
            if (generation.get() != gen) return@synchronized null
            val union = (held + sealed).distinct()
            val imported = try {
                cryptoManager.importPreviousIdentities(union).toInt()
            } catch (e: MobileException.KeyNotFound) {
                val persisted = sessionManager.getSharingPrivateKey() ?: throw e
                cryptoManager.importPrivateKey(persisted)
                cryptoManager.importPreviousIdentities(union).toInt()
            }
            held = union
            imported
        }

    /**
     * Vault keys for each recovered era, newest first. Tried only AFTER the
     * current vault key has failed — preferring one would keep the app reading
     * through a superseded identity long after the user moved off it.
     */
    fun vaultKeys(): List<String> = try {
        cryptoManager.previousVaultKeys()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Forget that this account's identities were taken in, so the NEXT account
     * fetches its own. Called from [LocalDataWiper]'s account wipe.
     *
     * Deliberately not suspending and deliberately not taking [loadLock]: the
     * wipe must never block on an in-flight network round-trip. The generation
     * bump is what makes that safe — a load already past its fetch finds the
     * generation moved and declines to import or latch. [importLock] is held
     * only across the local import, never the network.
     */
    fun reset() {
        synchronized(importLock) {
            generation.incrementAndGet()
            loaded = false
            held = emptyList()
        }
    }

    /** Bumped by every [reset]: [IdentityRecheck] drops a pending re-check that
     *  belonged to the account before it. */
    fun generation(): Long = generation.get()
}
