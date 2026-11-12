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

import android.util.Log
import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.PublicKeyLookupRequest
import com.pingolin.note.data.remote.dto.ShareCallResult
import com.pingolin.note.data.remote.dto.ShareNoteRequest
import com.pingolin.note.data.remote.dto.UnfinishedShareDto
import com.pingolin.note.domain.repository.FinishSingleInvitations.DirectoryKeys
import com.pingolin.note.domain.repository.FinishSingleInvitations.Outcome
import com.pingolin.note.util.AccountIds
import com.pingolin.note.util.KeptWebTrustStore
import com.pingolin.note.util.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finishing, in the background, a note the owner shared on its own with an address that had no
 * account (owner, 2026-11-12).
 *
 * When someone signs up with that address, the server claims the keyless share for them and wakes
 * the OWNER's phones (push kind [PUSH_KIND], and a `sync`). Only the owner can seal the note's
 * content key for the new account. The page does it while it runs (the web editor's
 * stores/notes.ts `singleFinishSteps`); this does it at the end of a sync (SyncWorker), with Note
 * closed. Nothing is shown.
 *
 * WHICH ROWS: exactly the page's ([FinishSingleInvitations]). The standing lists are the ones the
 * last sync cached from the account's metadata; when there are none to read, every row of a note
 * in a notebook is left to the page.
 *
 * WHOM IT SEALS TO (the one design point). The page decides whom to trust: its TOFU pins. The app
 * holds the page's own copy of them (KeptWebTrustStore: read, never written). For a person U whose
 * directory keys are (S, G):
 *  1. no pins kept for the account, or unreadable, or not parseable: NOTHING is finished this pass
 *     (fail closed: the page will do it);
 *  2. a kept pin for U: a conflict, or a sharing key other than S, is not sealed to (the page shows
 *     "Confirm it's them"); the same S is;
 *  3. no kept pin, but a seal this app recorded for U: another sharing key is not sealed to; S is;
 *  4. neither: a first contact. Only with S and G both 64-hex: the seal {U, S, G, now} is recorded
 *     FIRST (committed), and only then is the note sealed and sent. The page takes the seals into
 *     its pins (`takeSeals`: a new contact is pinned, other keys for a pinned one a conflict).
 * Steps 2-4 are decided afresh for each person, against the pins kept at that moment, in one call
 * under the store's lock ([KeptWebTrustStore.trustForSeal]): a keep or a clear by the page lands
 * before or after it, never between its read and its record. When the kept pins hold NO record
 * for U, the app's own receive-side pin for U (SenderKeyPinStore, read only, never written from
 * here) can refuse: a conflict, or a sharing key other than S. When they hold a pin for U, the
 * page's decision stands: SenderKeyPinStore never re-pins, so after U reset their keys and the
 * owner approved the new one in the page, its old pin must not refuse U forever.
 *
 * WHAT IT SENDS: what the page sends (shareUtils.ts `shareNoteWithRecipientResult`, `finish`): the
 * note's content key, from ONE fresh `GET /items/{id}` with its generation, unwrapped with the vault
 * key (a recovered identity's after it), sealed to S; the wrap signed under that generation (the
 * same signature the page and NotebookShareFanOut make); `sharer_public_key` as 64 lower-case hex;
 * `finish: true` and the generation. A wrap that can't be signed is not sent. Always a finish: a
 * plain share is an upsert, and would bring back a share the owner removed.
 *
 * WHO FOR, AND HOW OFTEN: the account a sync began as, while it is still the one signed in: a
 * change stops the pass where it is (as SyncRepository stops its writes). One pass at a time, a
 * minute apart (the page's pace). A [PUSH_KIND] push lets a pass skip that wait, at most once a
 * minute, so a burst of pushes can't outrun the server's 30 shares a minute from an owner; a 429
 * stops the pass. A push the once-a-minute cap holds back sets one delayed sync for the end of
 * that minute (SyncWorker.FINISH_LATER), so its share doesn't wait for an unrelated sync. The
 * immediate sync this runs in is unique and KEEP, so a push that lands while a pass runs starts no
 * sync of its own: one more pass runs in the same sync for it (once).
 * Only counts are logged: no address, no key, no id.
 */
@Singleton
class InvitationFinisher @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val noteApi: NoteApi,
    private val itemDao: ItemDao,
    private val noteCrypto: NoteCrypto,
    private val sessionManager: SessionManager,
    private val kept: KeptWebTrustStore,
    private val previousIdentities: PreviousIdentityLoader,
    /** The app's own receive-side pins: read only, a veto for people the kept pins hold no record
     *  of ([PassSteps.receivePinRefuses]). */
    private val senderPins: com.pingolin.note.util.SenderKeyPinStore,
) {
    /** Tests only: the clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Sets the one delayed sync [delayMs] from now (SyncWorker.FINISH_LATER). Tests replace it. */
    internal var syncLater: (delayMs: Long) -> Unit = { delayMs ->
        com.pingolin.note.worker.SyncWorker.enqueueFinishLaterSync(context, delayMs)
    }

    private val passLock = Mutex()

    /** When a pass last asked the server for the unfinished shares, in this process. */
    @Volatile
    private var lookedAt = 0L

    /** When a [PUSH_KIND] push last let a pass skip the minute's wait, in this process. */
    @Volatile
    private var skippedAt = 0L

    /** How many passes have asked the server, in this process. Read and written under [passLock]. */
    private var looks = 0L

    /** How many [PUSH_KIND] pushes had come ([asks]) when the last pass asked the server. Counted,
     *  not timed: a clock that jumps can't make an old push look new. */
    private var asksAtLook = 0L

    /** The tries as this process holds them, for [triesFor]: a store that can't be written still
     *  spaces them out. Read and written under [passLock]. */
    private val tries = HashMap<String, Long>()
    private var triesFor: String? = null

    /** The account signed in now, which a sync names at its start for [afterSync]. */
    fun signedInAccount(): String? = if (sessionManager.isLoggedIn()) sessionManager.getUserId() else null

    /**
     * After a sync that began as [startedFor]: finish that account's due shares, if it is still the
     * one signed in. Never throws but [CancellationException].
     *
     * @return the share ids finished.
     */
    suspend fun afterSync(startedFor: String?): List<String> {
        val me = AccountIds.canonical(startedFor) ?: return emptyList()
        return passLock.withLock {
            if (!signedInAs(me) || !mayStart()) return@withLock emptyList()
            val looksBefore = looks
            val done = guardedPass(me)
            // A push that landed after that pass asked the server had its own sync dropped (this
            // sync is the unique immediate one, KEEP): one more pass for it, in this sync, if a push
            // may still skip the wait. Once.
            val missed = looks != looksBefore && asks.get() > asksAtLook
            if (missed && signedInAs(me) && mayStart()) done + guardedPass(me) else done
        }
    }

    /**
     * Whether a pass may start now: a minute after the last one began; sooner only when a
     * [PUSH_KIND] push came since it asked the server and no push has let a pass skip the wait in
     * the last minute (this records that one has). A clock that went back is no reason to wait.
     *
     * A push held back only by that once-a-minute cap sets the one delayed sync for when the
     * minute since the last pass is up (plus [LATER_MARGIN_MS]): nothing else would come for it
     * but an unrelated sync.
     */
    private fun mayStart(): Boolean {
        val now = clock()
        if (now - lookedAt !in 0 until LOOK_MS) return true
        if (asks.get() <= asksAtLook) return false
        if (now - skippedAt in 0 until LOOK_MS) {
            val delayMs = lookedAt + LOOK_MS + LATER_MARGIN_MS - now
            try {
                syncLater(delayMs)
                Log.i(TAG, "A push waits for the minute: a sync is set for then")
            } catch (e: Exception) {
                Log.w(TAG, "The sync for a push held back wasn't set: ${e.javaClass.simpleName}")
            }
            return false
        }
        skippedAt = now
        return true
    }

    /** [pass], with any failure but a cancellation finishing nothing. */
    private suspend fun guardedPass(me: String): List<String> = try {
        pass(me)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "A pass stopped: ${e.javaClass.simpleName}")
        emptyList()
    }

    private suspend fun pass(me: String): List<String> {
        // 1. The page's pins, as kept for this account. None, or none that can be read: nothing,
        //    and the server isn't asked. (Each person is decided against the pins kept at that
        //    moment, read again: KeptWebTrustStore.trustForSeal.)
        when (kept.pins(me)) {
            is KeptWebTrustStore.Pins.Held -> {}
            KeptWebTrustStore.Pins.Absent -> {
                Log.i(TAG, "No pins kept for this account: the page finishes its shares")
                return emptyList()
            }
            KeptWebTrustStore.Pins.Unreadable -> {
                Log.w(TAG, "The kept pins can't be read: nothing is finished this time")
                return emptyList()
            }
        }
        val owner = ownerKeys() ?: return emptyList()
        try {
            lookedAt = clock()
            asksAtLook = asks.get()
            looks++
            val outcomes = sortedMapOf<Outcome, Int>()
            val steps = PassSteps(me, owner, standingLists(), outcomes)
            val done = FinishSingleInvitations.finishSingleInvitations(steps, triedFor(me))
            if (outcomes.isNotEmpty()) {
                Log.i(TAG, "Shares made before their reader had an account: " + outcomes.entries.joinToString { "${it.key}=${it.value}" })
            }
            return done
        } finally {
            owner.wipe()
        }
    }

    /** The account's own keys for a pass, or null (nothing is done) when any is missing. */
    private fun ownerKeys(): OwnerKeys? {
        // The current vault key first, always; a recovered identity's only after it.
        val vaults = (listOfNotNull(sessionManager.getMasterKey()) + previousIdentities.vaultKeys())
            .mapNotNull { decode(it)?.takeIf { key -> key.size == 32 } }
        val seed = sessionManager.getSigningPrivateKey()?.let(::decode)?.takeIf { it.size == 32 }
        val sharingPrivate = sessionManager.getSharingPrivateKey()?.let(::decode)?.takeIf { it.size == 32 }
        val sharerHex = sharingPrivate?.let { private ->
            try {
                noteCrypto.deriveSharingPublicKey(private)
            } catch (e: Exception) {
                null
            } finally {
                private.fill(0)
            }
        }?.lowercase()?.takeIf { HEX64.matches(it) }
        if (vaults.isEmpty() || seed == null || sharerHex == null) {
            vaults.forEach { it.fill(0) }
            seed?.fill(0)
            // Without a signing key nothing could be signed, and nothing unsigned is ever sent.
            Log.w(TAG, "This account's keys aren't all here: nothing is finished this time")
            return null
        }
        return OwnerKeys(vaults, seed, sharerHex)
    }

    private class OwnerKeys(val vaults: List<ByteArray>, val seed: ByteArray, val sharerHex: String) {
        fun wipe() {
            vaults.forEach { it.fill(0) }
            seed.fill(0)
        }
    }

    /** The tries for [me]: kept in the store (a worker has no page memory), and in this process. */
    private fun triedFor(me: String): FinishSingleInvitations.Tried {
        if (triesFor != me) {
            tries.clear()
            triesFor = me
        }
        kept.triedAt(me)?.forEach { (share, at) -> if ((tries[share] ?: 0L) < at) tries[share] = at }
        return object : FinishSingleInvitations.Tried {
            override fun get(shareId: String): Long? = tries[shareId]

            override fun set(shareId: String, at: Long) {
                tries[shareId] = at
                keepTries(me)
            }

            override fun delete(shareId: String) {
                if (tries.remove(shareId) != null) keepTries(me)
            }
        }
    }

    private fun keepTries(me: String) {
        if (!kept.keepTriedAt(me, tries)) Log.w(TAG, "When the shares were tried wasn't kept")
    }

    /**
     * The standing lists of the account's notebooks (the metadata's `notebookShares`, as the last
     * sync cached it): by canonical notebook id, the lower-cased addresses, or null for a notebook
     * whose list doesn't read. Null when there are none to read.
     */
    private fun standingLists(): Map<String, Set<String>?>? {
        val raw = sessionManager.getNotebookShares() ?: return null
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        val out = HashMap<String, Set<String>?>()
        for (notebook in root.keys()) {
            val key = AccountIds.canonical(notebook) ?: notebook
            val list = root.opt(notebook) as? JSONArray
            val emails = list?.let { array ->
                List(array.length()) { i -> (array.opt(i) as? JSONObject)?.opt("email") as? String }
            }
            out[key] = if (emails == null || emails.any { it == null } || (out.containsKey(key) && out[key] == null)) {
                null
            } else {
                out[key].orEmpty() + emails.filterNotNull().map { it.lowercase() }.filter { it.isNotBlank() }
            }
        }
        return out
    }

    private fun signedInAs(me: String): Boolean =
        sessionManager.isLoggedIn() && AccountIds.same(sessionManager.getUserId(), me)

    /** One pass's steps (the page's `singleFinishSteps`), for [me]. */
    private inner class PassSteps(
        private val me: String,
        private val owner: OwnerKeys,
        private val standing: Map<String, Set<String>?>?,
        private val outcomes: MutableMap<Outcome, Int>,
    ) : FinishSingleInvitations.Steps {

        override suspend fun unfinished(): List<UnfinishedShareDto>? {
            if (!signedInAs(me)) return null
            val response = noteApi.listUnfinishedShares()
            val body = response.body()
            if (!response.isSuccessful || body?.success != true) return null
            val rows = body.data ?: return null
            // A share the server no longer lists has its key, was removed, or its note was deleted:
            // its try is let go of, so the kept tries stay as few as the shares waiting.
            val listed = rows.mapNotNull { it.shareId }.toSet()
            if (tries.keys.retainAll(listed)) keepTries(me)
            return rows
        }

        override fun inStanding(notebookId: String, email: String): Boolean {
            // Standing lists this device can't read: as if every notebook had the address, so a
            // note in a notebook is left to the page (the iOS extension's rule).
            val lists = standing ?: return true
            val key = AccountIds.canonical(notebookId) ?: notebookId
            if (!lists.containsKey(key)) return false
            val emails = lists[key] ?: return true
            return email.lowercase() in emails
        }

        override suspend fun localNotebookOf(itemId: String): String? = itemDao.getById(itemId)?.notebookId

        /** The page's `lookupDirectoryKeys`: lower-cased, at most [LOOKUP_BATCH] a call, an entry
         *  counting only with all three of user id and keys, and any call that fails NO answer. */
        override suspend fun lookup(emails: List<String>): Map<String, DirectoryKeys>? {
            val unique = emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            val out = HashMap<String, DirectoryKeys>()
            for (batch in unique.chunked(LOOKUP_BATCH)) {
                if (!signedInAs(me)) return null
                val response = try {
                    noteApi.lookupPublicKeys(PublicKeyLookupRequest(batch))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return null
                }
                val body = response.body()
                if (!response.isSuccessful || body?.success != true) return null
                val data = body.data ?: return null
                // Gson leaves an absent list null whatever its type says; the page reads it as empty.
                val users: List<com.pingolin.note.data.remote.dto.PublicKeyLookupEntry>? = data.users
                try {
                    for (u in users.orEmpty()) {
                        val userId = u.userId
                        val sharing = u.sharingPublicKey
                        val signing = u.signingPublicKey
                        if (userId.isNullOrEmpty() || sharing.isNullOrEmpty() || signing.isNullOrEmpty()) continue
                        out[u.email.lowercase()] = DirectoryKeys(userId, sharing, signing)
                    }
                } catch (e: Exception) {
                    // Not an answer in the shape the page reads (a field Gson left null): no answer.
                    return null
                }
            }
            return out
        }

        override suspend fun finish(share: UnfinishedShareDto, keys: DirectoryKeys): Outcome =
            finishOne(share, keys).also { outcomes[it] = (outcomes[it] ?: 0) + 1 }

        override fun now(): Long = clock()

        private suspend fun finishOne(share: UnfinishedShareDto, keys: DirectoryKeys): Outcome {
            val itemId = share.itemId ?: return Outcome.FAILED
            val email = share.recipientEmail ?: return Outcome.FAILED
            if (!signedInAs(me)) return Outcome.ABORTED
            // The wrap AND its generation, from one fresh read of the note.
            val response = try {
                noteApi.getItem(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Outcome.FAILED
            }
            // The server takes nothing more just now: stop, as for the share itself.
            if (response.code() == 429) return Outcome.RATE_LIMITED
            val body = response.body()
            val item = body?.data
            if (!response.isSuccessful || body?.success != true || item == null) return Outcome.FAILED
            if (!AccountIds.same(item.id, itemId) || item.itemType != NOTE || !AccountIds.same(item.userId, me)) {
                return Outcome.NOT_HERE
            }
            val wrap = item.wrappedContentKey
            val nonce = item.wrappedContentKeyNonce
            if (wrap.isNullOrEmpty() || nonce.isNullOrEmpty()) return Outcome.NOT_HERE
            // The generation the wrap is sealed and signed under, and stated with it. Absent (which
            // Gson reads as 0) is 1; one outside a u32 is signed under nothing.
            val generation = item.contentKeyGeneration.let { if (it == 0L) 1L else it }
            if (generation !in 1L..MAX_GENERATION) return Outcome.FAILED
            val contentKey = unwrap(wrap, nonce) ?: return Outcome.NOT_HERE
            try {
                if (!signedInAs(me)) return Outcome.ABORTED
                val recipient = trusted(keys) ?: return Outcome.KEY_CHANGED
                val sealed = try {
                    noteCrypto.sealForRecipient(contentKey, keys.sharingPub.lowercase())
                } catch (e: Exception) {
                    null
                } ?: return Outcome.FAILED
                // FAIL CLOSED: an unsigned wrap is dropped by any reader that holds a pin for the
                // owner, a share that silently never arrives. Not sent.
                val signature = try {
                    noteCrypto.signNoteShareWrap(
                        signingSeed = owner.seed,
                        senderUserId = me,
                        recipientUserId = recipient,
                        itemId = itemId,
                        contentKeyVersion = generation.toUInt(),
                        wrappedKeyB64 = sealed,
                    )
                } catch (e: Exception) {
                    null
                }?.takeIf { SIGNATURE.matches(it) } ?: return Outcome.CANNOT_SIGN
                if (!signedInAs(me)) return Outcome.ABORTED
                val sent = try {
                    noteApi.shareNote(
                        itemId,
                        ShareNoteRequest(
                            email = email,
                            permission = if (share.permission == "edit") "edit" else "view",
                            wrappedKey = sealed,
                            sharerPublicKey = owner.sharerHex,
                            wrapSignature = signature,
                            finish = true,
                            contentKeyGeneration = generation,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Outcome.FAILED
                }
                return when (ShareCallResult.of(sent)) {
                    ShareCallResult.DONE -> Outcome.DONE
                    ShareCallResult.NOT_UNFINISHED -> Outcome.NOT_UNFINISHED
                    ShareCallResult.GENERATION_STALE -> Outcome.STALE
                    ShareCallResult.UPGRADE -> Outcome.UPGRADE
                    ShareCallResult.RATE_LIMITED -> Outcome.RATE_LIMITED
                    ShareCallResult.LATER -> Outcome.LATER
                    ShareCallResult.FAILED -> Outcome.FAILED
                }
            } finally {
                contentKey.fill(0)
            }
        }

        /** The content key, under the current vault key or a recovered one. */
        private fun unwrap(wrap: String, nonce: String): ByteArray? {
            for (vault in owner.vaults) {
                val key = try {
                    noteCrypto.unwrapWithVault(wrap, nonce, vault)
                } catch (e: Exception) {
                    null
                }
                if (key != null && key.size == 32) return key
                key?.fill(0)
            }
            return null
        }

        /**
         * The trust rule above: the recipient's canonical id when the note may be sealed to
         * [keys], or null. Steps 2-4 are one call to the store, against the pins kept NOW, under
         * its lock: a kept pin decides (the page's decision); with none, the app's own
         * receive-side pin may refuse, then a seal held for them decides by its sharing key, else
         * a first contact is recorded, committed, before anything is sealed.
         */
        private fun trusted(keys: DirectoryKeys): String? {
            // An id that is no account id has no pin to look up: refused, never a first contact.
            val contact = AccountIds.strict(keys.userId) ?: return null
            val sharing = keys.sharingPub
            if (!HEX64.matches(sharing) || sharing == KeptWebTrustStore.CONFLICT_KEY_HEX) return null
            // Read here, outside the store's lock (nothing the page keeps or clears touches it);
            // it counts only when the kept pins hold no record for them.
            val receiveRefuses = receivePinRefuses(contact, sharing)
            return when (kept.trustForSeal(me, contact, sharing, keys.signingPub, clock(), refuseUnpinned = receiveRefuses)) {
                KeptWebTrustStore.Trust.PINNED, KeptWebTrustStore.Trust.SEALED, KeptWebTrustStore.Trust.RECORDED -> contact
                KeptWebTrustStore.Trust.REFUSED -> null
            }
        }

        /**
         * The app's own receive-side pin for [contact] (SenderKeyPinStore: the keys it pinned when
         * they shared with this account), read only. A conflict, or a sharing key other than
         * [sharing], refuses, as the page's pin would; a pin that holds no sharing key says nothing
         * about it. Pins that can't be read refuse: nothing is known. Never pins anything. Counts
         * only for someone the kept pins hold no record of ([KeptWebTrustStore.trustForSeal]).
         */
        private fun receivePinRefuses(contact: String, sharing: String): Boolean {
            val pin = try {
                senderPins.peekPinned(contact, me)
            } catch (e: Exception) {
                return true
            } ?: return false
            if (pin.isConflict) return true
            val pinnedSharing = pin.sharingPubHex ?: return false
            return !pinnedSharing.equals(sharing, ignoreCase = true)
        }
    }

    companion object {
        private const val TAG = "InvitationFinisher"

        /** The push that wakes the owner's phone when someone they invited has an account. */
        const val PUSH_KIND = "finish_shares"

        /** Passes this far apart (the page's `SINGLE_INVITATIONS_LOOK_MS`); a [PUSH_KIND] push may
         *  start one sooner, at most once in this long. */
        const val LOOK_MS = 60_000L

        /** How long after the minute is up the delayed sync for a held-back push is set: it then
         *  finds the minute up, whatever the job scheduler's clock. */
        private const val LATER_MARGIN_MS = 1_000L

        /** The server's cap on one key lookup. */
        private const val LOOKUP_BATCH = 100
        private const val NOTE = 1
        private const val MAX_GENERATION = 0xFFFF_FFFFL
        private val HEX64 = Regex("[0-9a-fA-F]{64}")
        private val SIGNATURE = Regex("[0-9a-fA-F]{128}")

        /** How many [PUSH_KIND] pushes have come, in this process. */
        private val asks = java.util.concurrent.atomic.AtomicLong(0)

        /** A [PUSH_KIND] push: the pass at the end of the sync it starts may skip the minute's wait
         *  (at most once a minute), and a pass running when it lands is followed by one more. */
        fun wanted() {
            asks.incrementAndGet()
        }

        /** Tests only: how many [PUSH_KIND] pushes have come. */
        internal fun asked(): Long = asks.get()

        private fun decode(b64: String): ByteArray? =
            runCatching { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) }.getOrNull()
    }
}
