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

import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ITEM_TYPE_NOTE
import com.pingolin.note.data.remote.dto.InboxShareDto
import com.pingolin.note.data.remote.dto.PublicKeyLookupRequest
import com.pingolin.note.util.AccountIds
import com.pingolin.note.util.NoteGenFloorStore
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import com.pingolin.note.util.StoreUnreadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receive-side of E2EE note sharing. Parallel to the web's
 * `sharesInboxStore`.
 *
 * Pulls `/api/v1/shares/inbox`, opens each sealed-box `wrapped_key`
 * with the user's X25519 private scalar, and keeps the resulting
 * per-note content keys in memory. The main sync decrypt path
 * consults [getContentKey] when it can't unwrap an item under the
 * vault key — that's how shared notes end up in `notesStore.notes()`
 * alongside owned ones.
 *
 * Kept in-memory (volatile) because the content keys are extremely
 * sensitive and shouldn't hit the SQLCipher disk alongside anything
 * else. Re-fetched on every sync.
 */
@Singleton
class SharesInboxRepository @Inject constructor(
    private val noteApi: NoteApi,
    private val sessionManager: SessionManager,
    private val noteCrypto: NoteCrypto,
    private val itemDao: ItemDao,
    private val pinStore: SenderKeyPinStore,
    private val genFloorStore: NoteGenFloorStore,
    /** The vault keys of this account's recovered identities, for telling one of its own rows. */
    private val previousIdentities: PreviousIdentityLoader,
    /** Which shared notebooks this account has said yes to (see [acceptedNotebooks]). A Provider:
     *  it is read at each refresh, and nothing here needs it built before that. */
    private val notebookInvites: javax.inject.Provider<NotebookInvites>,
) {
    /** itemId → unwrapped content key. Empty until `refresh()` runs. */
    private val contentKeys: MutableMap<String, ByteArray> = mutableMapOf()

    /** A served share whose owner and item ids are UUIDs, with their canonical forms ([AccountIds]):
     *  everything the inbox keys, checks or writes uses those, never the DTO's spelling. */
    private data class Admitted(val share: InboxShareDto, val owner: String, val item: String)

    /**
     * A notebook somebody has shared with this user.
     *
     * Notebooks are deliberately kept OUT of the notes table (they carry a
     * collection's name and roster, not a document), so they need their own
     * channel to reach the UI. The content key is retained by the inbox either
     * way, which is what makes the name readable here at all.
     */
    data class SharedNotebook(
        val shareId: String,
        val itemId: String,
        /** The notebook's name, or null when this client cannot open it. */
        val name: String?,
        val ownerEmail: String?,
        /** `view` or `contribute` — the collection right the owner granted. */
        val permission: String,
    )

    private val _sharedNotebooksFlow = MutableStateFlow<List<SharedNotebook>>(emptyList())
    val sharedNotebooksFlow: StateFlow<List<SharedNotebook>> = _sharedNotebooksFlow.asStateFlow()

    /**
     * Every notebook the server's list NAMED at the last [refresh], the rows this device refused
     * to open included; null when the last refresh applied no list (it failed, stopped early, or
     * none has run in this process, or the inbox could not be read to its end).
     *
     * [sharedNotebooksFlow] is the notebooks that passed every check, and it is the wrong list
     * to prune the reader's acceptances against. In a cold process it is the empty list it
     * starts as until a refresh succeeds, so one failed inbox request in a background sync would
     * strike every acceptance, in the metadata every device shares. And a sender's key change,
     * whose rows are then refused, would strike theirs after a refresh
     * that SUCCEEDED. Whether a notebook is still offered is a different question from whether
     * its row opens today. This answers the first: it keeps an acceptance alive and opens
     * nothing.
     */
    @Volatile
    var listedNotebookIds: Set<String>? = null
        private set

    /**
     * How many materialised rows this repository's refreshes have taken out of the database since
     * the process started. It only grows.
     *
     * A row goes when its share stops being one this device shows: the share left an inbox read to
     * its end (withdrawn by its owner, dismissed, or declined with its notebook); this device
     * refused it this time and the id was known before; or it is a note of a notebook this account
     * declined ([DeclinedNotebooks]).
     *
     * The home-screen widgets read that database. A refresh that only REMOVES rows is followed by
     * a pull that brings nothing, so telling the widgets of a change only when the pull brought
     * something would leave a notebook the reader had just declined on the home screen, titles and
     * first lines, until something else changed ("If you decline a share, nothing should show
     * up"). SyncRepository.sync compares this with what it read the last time it told the widgets,
     * right after the refresh.
     *
     * A count that only grows, not "what the last refresh removed": two syncs can overlap (the
     * periodic and the one-off are separate works), and a count that the second refresh set back
     * to zero would lose the first one's removals.
     */
    val rowsRemoved: Long get() = rowsRemovedCount.get()
    private val rowsRemovedCount = java.util.concurrent.atomic.AtomicLong(0)

    /** The notebooks this account has declined, on the server's word: canonical id → since when
     *  ([DeclinedNotebooks]). Kept between refreshes for an answer that does not say. */
    @Volatile private var declinedNotebooks: Map<String, Instant> = emptyMap()

    /** The items of the shared notes that are kept away, canonical, as the last refresh that
     *  got that far found them: a note of a notebook that was declined ([DeclinedNotebooks]),
     *  or of one whose invitation is not answered yet. */
    @Volatile private var keptAwayItems: Set<String> = emptySet()

    /**
     * The items whose rollback floor or owner the last refresh could not SAVE
     * ([NoteGenFloorStore.unsaved]), canonical. What it opened for them is withheld: no key
     * given out, no row written. Everything else of that refresh goes on.
     *
     * Stopping the whole refresh would be too much: the store keeps trying to save what it
     * holds, so one failed save would stop EVERY later refresh too, and shares that had been
     * withdrawn, dismissed or declined would stay on the widgets, with only a log line to say
     * so. What must not happen is narrower: a key opened under a floor that is only in memory
     * must not be used, because that floor dies with the process and the older key it refused
     * would be taken again afterwards.
     */
    @Volatile private var unsavedItems: Set<String> = emptySet()

    /**
     * Is [itemId] a note this device keeps away (its notebook was declined, its invitation is
     * not answered, or what the last refresh opened for it is withheld for want of a save)? The
     * sync's pull and backfill ask before they write a row: the server serves the item in a pull
     * too, as it does any note with a live share.
     */
    fun isKeptAway(itemId: String): Boolean =
        AccountIds.canonical(itemId)?.let { it in keptAwayItems || it in unsavedItems } == true

    /**
     * One refresh at a time.
     *
     * Two can be asked for at once (the periodic sync and the one-off sync are separate works,
     * and the one-off is started by a push, which is exactly when new shares arrive).
     * Overlapping refreshes that shared one map of notes to notebooks would clear, refill and read it
     * under each other: one that read it just after the other had cleared it would find no
     * note in any notebook, keep nothing away, and write a DECLINED notebook's note into the
     * database the widgets read. Each refresh builds that map for itself, and they run one
     * after the other, which also orders what each publishes and stores at its end.
     */
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * How many times [wipe] has run. Read and written only under the `contentKeys` lock.
     *
     * A refresh notes it when it starts and looks again, under that lock, at the moment it would
     * publish: if a wipe came in between, the keys it opened are the signed-out account's, and
     * nothing of them is published, stored or written. Holding the lock for the whole
     * verification instead would not do: a wipe that came before it (while the inbox was being
     * fetched) would be followed by that refresh publishing the old account's keys over the
     * wipe.
     */
    private var wipes = 0

    /**
     * How many wipes have begun and not ended ([wipeIsStarting], [wipeIsOver]). Under the
     * `contentKeys` lock, like [wipes]. No refresh STARTS while there is one: the account is
     * still the outgoing one until the wipe is over (the session is changed after it), so a
     * refresh that started in the middle would find nothing changed under it and write that
     * account's rows into the emptied tables.
     *
     * A count, not a flag: two can overlap (a sign-out while an account erasure is still
     * clearing), and with a flag the end of the first would let refreshes start again while
     * the second was still emptying the tables.
     */
    private var wipesUnderWay = 0

    /**
     * One row of the notes table is checked and then written or removed at a time, and a wipe's
     * start takes its turn with them ([wipeIsStarting]).
     *
     * Each write asks first whether a wipe has begun, and something has to hold the answer
     * still: a wipe that began between the question and the write would empty the table, and the
     * write would then land in it — one row of the account that had just signed out, in the next
     * one's database.
     */
    private val rowWrites = kotlinx.coroutines.sync.Mutex()

    /** How many refreshes have got to their end ([refreshIfAnswersChanged] says whether its own did). */
    @Volatile private var refreshesApplied = 0L

    /** The notebooks the last refresh found ON OFFER, canonical; null until one got that far. */
    @Volatile private var offeredAtLastRefresh: Set<String>? = null

    /** Of those, the ones this account had not said yes to: their notes were held back. */
    @Volatile private var unansweredAtLastRefresh: Set<String> = emptySet()

    /** The last refresh could not tell what was on offer ([NotebooksOnOffer.unknown]): it held
     *  every note of a notebook that was not among [acceptedAtLastRefresh]. */
    @Volatile private var unknownAtLastRefresh: Boolean = false
    @Volatile private var acceptedAtLastRefresh: Set<String> = emptySet()

    /**
     * Of [keptAwayItems], the notes held because of an INVITATION: their notebook was on offer
     * and not answered, or was declined after that ([NotebooksOnOffer.heldForAnInvitation]).
     * Null until this process has read what the last one left ([SessionManager.getHeldForAnInvitation]):
     * the hold has to outlive the process.
     */
    @Volatile private var heldForAnInvitation: Set<String>? = null

    /**
     * A wipe is about to begin ([LocalDataWiper.wipeBlocking], first thing). From here a refresh
     * that is under way publishes, stores and writes nothing more. [wipe] itself comes later, after
     * the tables and the web view are cleared: a refresh told only then would have the whole time
     * in between to write.
     */
    suspend fun wipeIsStarting() {
        // BEGUN first, before anything that can wait, be cancelled or time out: from
        // this line on, every row write finds the wipe begun at its own question and
        // writes nothing.
        //
        // Beginning INSIDE the turn it waits for, with a limit on the wait, goes wrong
        // three ways:
        // - when the limit runs out, the row it waited for lands AFTER the wipe's
        //   clear: the one thing the wait is there to prevent;
        // - the limit can run out just as the turn is won, and the wipe is then begun
        //   twice and ended once, so that no refresh ever starts again in that
        //   process;
        // - a caller cancelled while it waits has begun nothing, and its `finally`
        //   still calls [wipeIsOver], ending the count of ANOTHER wipe.
        synchronized(contentKeys) {
            wipes++
            wipesUnderWay++
        }
        // Then its turn, with NO limit: the one row write that had already passed its question
        // holds [rowWrites] until its row is in the table, so when this returns that row is
        // there for the wipe's clear to take with the rest. A database that never answers
        // that write would not answer the clear either.
        //
        // WAITED for, not blocked on. (The lock above IS blocked on, for as long as a refresh
        // saves under it: [LocalDataWiper] calls this off the main thread.)
        rowWrites.withLock { }
    }

    /** Is a wipe under way ([wipeIsStarting] without its [wipeIsOver])? For tests: a wipe begun on another thread. */
    internal val wipeUnderWay: Boolean
        get() = synchronized(contentKeys) { wipesUnderWay > 0 }

    /** A wipe [wipeIsStarting] announced has ended, whatever became of its steps: refreshes may start again once none is left. */
    fun wipeIsOver() {
        synchronized(contentKeys) { if (wipesUnderWay > 0) wipesUnderWay-- }
    }

    /**
     * Drop ALL in-memory receive-side share state on logout / account switch.
     * This @Singleton survives the switch (same process; navigateToAuth uses
     * CLEAR_TASK, not a process restart), and only a SUCCESSFUL [refresh]
     * otherwise clears this state — it early-returns on any failure — so
     * without an explicit wipe account A's decrypted content keys linger and
     * can surface in account B's UI. Called from [LocalDataWiper.wipeBlocking];
     * best-effort.
     */
    fun wipe() {
        // Counted under the same lock a refresh publishes under: a refresh that started before
        // this wipe finds the count moved and publishes nothing (see [refreshOneAtATime]).
        synchronized(contentKeys) {
            wipes++
            contentKeys.clear()
        }
        // The shared-notebook state too.
        _sharedNotebooksFlow.value = emptyList()
        listedNotebookIds = null
        // And what one account declined, or was offered, is not the next one's.
        declinedNotebooks = emptyMap()
        keptAwayItems = emptySet()
        unsavedItems = emptySet()
        // Empty, not "unread": what the outgoing account's hold was is not the next one's, and
        // the wiper clears the stored copy with the rest of the account's state.
        heldForAnInvitation = emptySet()
        offeredAtLastRefresh = null
        unansweredAtLastRefresh = emptySet()
        unknownAtLastRefresh = false
        acceptedAtLastRefresh = emptySet()
    }

    /** The inbox's maps are keyed by canonical item ids ([AccountIds]); an id that is no UUID has
     *  no entry. */
    fun getContentKey(itemId: String): ByteArray? {
        val key = AccountIds.canonical(itemId) ?: return null
        return synchronized(contentKeys) { contentKeys[key] }
    }

    /**
     * The inbox's key for [row], only while [row] isn't ours: a note is sealed with the key that
     * opened it, so one of ours never goes under a share's key, whatever the inbox holds. The
     * refresh refuses a share of a row it can see, but the sync refreshes the inbox BEFORE the
     * pull, so one of ours that lands after it would find the share's key here until the next
     * refresh. Every SEALING use of an inbox key asks this; the pull's decrypt doesn't need to
     * (a share's key opens nothing our vault sealed).
     */
    fun sharedKeyFor(row: ItemEntity): ByteArray? {
        val key = getContentKey(row.id) ?: return null
        val me = AccountIds.canonical(sessionManager.getUserId()) ?: return null
        return key.takeUnless { AccountIds.same(row.userId, me) || opensUnder(row, ourVaultKeys()) }
    }

    /**
     * Refresh the inbox. Safe to call multiple times; later content
     * keys overwrite earlier ones. Returns the number of shares whose
     * content key we successfully unwrapped (best-effort — shares with
     * missing wrapped_key or wrong-recipient seals are dropped).
     */
    suspend fun refresh(): Int = refreshMutex.withLock { refreshOneAtATime() }

    private suspend fun refreshOneAtATime(): Int {
        // Until this refresh has applied the server's list, there is none: every early return
        // below leaves it so, and the acceptance prune in SyncRepository.sync does not run.
        listedNotebookIds = null
        val wipesAtStart = synchronized(contentKeys) {
            if (wipesUnderWay > 0) return 0
            wipes
        }
        // This account's own id first, canonical: the pins and floors
        // below are its, and the SDK binds it into every wrap
        // signature. Without one nothing is fetched, decided or
        // written.
        val myUserId = AccountIds.canonical(sessionManager.getUserId()) ?: return 0
        val privB64 = sessionManager.getSharingPrivateKey() ?: return 0
        val priv = try {
            android.util.Base64.decode(privB64, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { return 0 }
        if (priv.size != 32) return 0

        // Both trust stores are read before anything is decided. One
        // that can't be read now is never "nothing pinned" or "floor
        // 0": the refresh stops here and the inbox stays as it was.
        try {
            pinStore.ensureReadable(myUserId)
            genFloorStore.ensureReadable(myUserId)
        } catch (e: StoreUnreadable) {
            // Never silent: an error line of its own.
            android.util.Log.e("SharesInboxRepository", "INBOX NOT REFRESHED: a stored trust record (pins, rollback floors or item owners) can't be read; nothing was fetched, opened or changed: ${e.message}")
            return 0
        }

        // The inbox to its END, page after page. The server answers with at most
        // INBOX_PAGE_LIMIT rows, newest first, and takes a cursor for the next ones (the
        // last row's created_at and share_id). One page is not the whole list: a
        // notebook's own row, among the oldest, is the first to fall off it, so taking
        // the first page for the list would strike its acceptance on every device and
        // remove the shares past the cut here as if they had been withdrawn.
        //
        // Only an inbox read to its end is the list. One that could not be (a later page
        // failed, the server sent the same page again, or it runs past INBOX_MAX_PAGES)
        // is used as far as it was read, and nothing is pruned or removed on it.
        //
        // WHERE the end is, is the server's word (`has_more`), not a guess from the
        // page's length; see InboxAnswer, which also reads the bare list an older server
        // sends. An answer in neither shape is a page that was not read, never an empty
        // one.
        val served = mutableListOf<com.pingolin.note.data.remote.dto.InboxShareDto>()
        val seenShareIds = HashSet<String>()
        var inboxComplete = false
        // The notebooks the server says this account declined: the first page's word, null when
        // it does not say (see DeclinedNotebooks).
        var declinedSaid: Map<String, String>? = null
        // And every notebook it says is on offer to it: the same page's word (NotebooksOnOffer).
        var offeredSaid: Set<String>? = null
        var before: String? = null
        var beforeId: String? = null
        for (pageNo in 0 until INBOX_MAX_PAGES) {
            val response = try { noteApi.listInbox(before, beforeId) } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
            // An answer that says it FAILED is a page that was not read, whatever
            // it carries: a 2xx `{"success":false,"data":[]}` read as an empty
            // inbox read to its end would prune every acceptance and remove every
            // share's row.
            val page = response?.takeIf { it.isSuccessful }?.body()?.takeIf { it.success }?.data
                ?.let { com.pingolin.note.data.remote.dto.InboxAnswer.read(it, INBOX_PAGE_LIMIT) }
            if (page == null) {
                if (pageNo == 0) return 0
                break
            }
            if (pageNo == 0) {
                declinedSaid = page.declined
                offeredSaid = page.offered
            }
            val fresh = page.shares.filter { seenShareIds.add(it.shareId) }
            served += fresh
            if (!page.more) {
                inboxComplete = true
                break
            }
            // A page that brought nothing new: a server that does not know the cursor answers
            // every request with the first page.
            if (fresh.isEmpty()) break
            before = page.shares.last().createdAt
            beforeId = page.shares.last().shareId
        }
        if (!inboxComplete) {
            android.util.Log.w("SharesInboxRepository", "the inbox was not read to its end (${served.size} shares read): nothing is pruned or removed on it")
        }
        // Named by the server, whatever becomes of each row below (see listedNotebookIds).
        // An id that is no UUID names no notebook anyone could have accepted.
        val listedNotebooks = if (!inboxComplete) null
            else served.filter { it.isNotebook }.mapNotNull { AccountIds.canonical(it.itemId) }.toSet()

        // Every share's owner and item ids in their canonical form, before
        // anything reads them: the SDK binds both ids' parsed bytes into the
        // wrap signature, so the pin, the floor and every map below must be
        // keyed the same way or another spelling of a pinned sender or of a
        // floored item would read as new. A share naming either by no UUID
        // is dropped here — never shown, unwrapped, pinned or materialised.
        val admitted = served.mapNotNull { s ->
            val owner = AccountIds.canonical(s.ownerId)
            val item = AccountIds.canonical(s.itemId)
            if (owner == null || item == null) null else Admitted(s, owner, item)
        }

        // A share naming a row this device holds that isn't that owner's — one of ours,
        // or another owner's — is refused before anything is unwrapped, pinned, bound or
        // materialised, and so is a share naming this account as its sender. The rows
        // are read here: the crypto section below can't suspend.
        val vaults = ourVaultKeys()
        val rowByItem = try {
            admitted.map { it.item }.distinct().chunked(500)
                .flatMap { itemDao.getByIds(it) }
                .mapNotNull { row -> AccountIds.canonical(row.id)?.let { it to row } }
                .toMap()
        } catch (e: Exception) {
            android.util.Log.w("SharesInboxRepository", "inbox refresh stopped: this device's rows can't be read", e)
            return 0
        }
        val notTheirs = admitted.filter { a ->
            a.owner == myUserId || rowByItem[a.item]?.let { !isThatOwnersRow(it, a.owner, myUserId, vaults) } == true
        }.map { it.share.shareId }.toSet()

        // Best-effort batch lookup of every sender's directory (signing +
        // sharing) pubkeys, so the verification step inside the loop doesn't
        // fan out to N round-trips. These server-supplied keys are used ONLY to
        // TOFU-bootstrap a pin on first use and to detect a server
        // key-substitution against an existing pin — NEVER as the key a wrap
        // signature is verified against; see the pin check in the loop below.
        // Senders we can't resolve fall through as unverified: their wraps
        // still flow, but the cryptographic check didn't run.
        val senderEmails = admitted.mapNotNull { it.share.ownerEmail }.distinct()
        val dirKeysByEmail: Map<String, SenderDirKeys> = if (senderEmails.isNotEmpty()) {
            try {
                val r = noteApi.lookupPublicKeys(PublicKeyLookupRequest(senderEmails))
                r.body()?.data?.users
                    ?.filter { it.signingPublicKey != null }
                    ?.associate {
                        it.email.lowercase() to
                            SenderDirKeys(it.signingPublicKey!!, it.sharingPublicKey, it.userId)
                    }
                    ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        // Shares whose content key we unwrapped this round, paired with
        // the key, for the post-lock Room materialization pass. Built
        // inside the lock (crypto only — no suspend/IO), drained after.
        val materializable = mutableListOf<Pair<Admitted, ByteArray>>()

        // Per-share Ed25519 verify + sealed-box open is CPU-bound JNI crypto; a
        // foreground refresh runs sync() on Main (viewModelScope), so do it off-main to
        // avoid ANRs, mirroring pullRemoteChanges' decrypt wrap.
        //
        // NOT under the `contentKeys` lock: holding it from here to the swap would make
        // whoever asks for a share's key meanwhile (saving a note, opening one, the pull)
        // wait for every signature of the inbox to be checked, which is seconds for a
        // large one. Nothing in the loop needs it: the keys are built aside, refreshes
        // run one at a time ([refreshMutex]), and the two trust stores lock themselves.
        // The lock is taken for the swap alone.
        val count = try { withContext(Dispatchers.Default) {
            // Built aside and swapped in at the end, so a refresh that stops
            // part-way (a trust store that can't be read) leaves the keys as
            // they were.
            val freshKeys = HashMap<String, ByteArray>()
            // Items the user is authorized to see: the live inbox, which departed
            // shares' rows are removed against (liveInboxIds, below).
            // GRADUATED FAIL-CLOSED: a share from a PINNED sender whose wrap
            // signature is missing (stripped) or fails verification against the
            // pinned key is DROPPED here (never added to visibleIds). A missing
            // wrapped_key (legacy entry) and an UNPINNED sender's missing/legacy
            // signature (first-use TOFU grace) still flow through.
            val visibleIds = mutableSetOf<String>()
            // Pair rule: an item no share has been accepted for yet, named by shares from two
            // different owners in this one refresh, is bound to nobody this time, and each of those
            // shares is refused — nothing says which came first.
            val ownersNaming = admitted.filter { genFloorStore.owner(it.item, myUserId) == null }
                .groupBy { it.item }
                .mapValues { (_, shares) -> shares.map { it.owner }.toSet() }
            for (a in admitted) {
                val s = a.share
                val wrapped = s.wrappedKey
                val sig = s.wrapSignature

                // A share of an item this device holds as someone else's,
                // or naming this account as its sender: see notTheirs
                // above.
                if (s.shareId in notTheirs) {
                    android.util.Log.w(
                        "SharesInboxRepository",
                        "REFUSING a share of an item this device holds as someone else's, or naming this account as its sender shareId=${s.shareId} itemId=${a.item}",
                    )
                    continue
                }

                // Owner binding: an item bound to its owner takes shares from that owner only,
                // and an unbound one named by two owners in this refresh takes none. Refused
                // before any pin, verify or unwrap: no floor moves, no pin is taken.
                val bound = genFloorStore.owner(a.item, myUserId)
                if (if (bound != null) bound != a.owner else (ownersNaming[a.item]?.size ?: 0) > 1) {
                    android.util.Log.w(
                        "SharesInboxRepository",
                        "REFUSING a share from someone other than the owner this item is bound to shareId=${s.shareId} itemId=${a.item}",
                    )
                    continue
                }

                // The directory keys count only when the entry the share's e-mail found names the
                // share's owner: anyone else's are nobody's to pin.
                val directory = s.ownerEmail?.lowercase()?.let { dirKeysByEmail[it] }
                val dirKeys = directory?.takeIf { AccountIds.same(it.userId, a.owner) }
                val senderSigningPub = dirKeys?.signingPub
                val senderSharingPub = dirKeys?.sharingPub

                // Verify a wrap signature ONLY against the sender's TOFU-PINNED
                // signing key — NEVER a key the server just handed us. A
                // malicious server can return an attacker signing key together
                // with a signature made by that same attacker key, which would
                // "verify" perfectly against the server-supplied key. Pinning
                // defeats that: verification uses the key we observed on first
                // use. The other clients hold the same posture.
                val pinned = pinStore.getPinned(a.owner, myUserId)
                if (directory != null && dirKeys == null && pinned == null) {
                    android.util.Log.w(
                        "SharesInboxRepository",
                        "REFUSING a first share whose directory entry names another account shareId=${s.shareId} itemId=${a.item}",
                    )
                    continue
                }
                if (pinned != null) {
                    // GRADUATED FAIL-CLOSED. Once we hold this sender's pinned
                    // signing key, a legitimate wrap from them WOULD carry a
                    // signature that verifies against it. So from a PINNED
                    // signer, a MISSING signature (a relay stripped it) or one
                    // that FAILS verification (forged / tampered / substituted
                    // key) is unambiguously bad → DROP the share entirely: no
                    // visibleId, no unwrap, no materialize. Grace is reserved
                    // for an UNPINNED sender, handled after this block.
                    //
                    // Accepted cost: a sender's legitimate key ROTATION drops
                    // their shares, because the rotated key isn't the pinned one.
                    // Failing closed there beats trusting a key the server could
                    // have swapped.

                    // If the server-returned signing key differs from the pin,
                    // that's a key-change alarm: we deliberately keep verifying
                    // against the PIN (so a substituted server key can't make
                    // the wrap verify) and warn.
                    val signingChanged = senderSigningPub != null &&
                        !senderSigningPub.equals(pinned.signingPubHex, ignoreCase = true)
                    val sharingChanged = senderSharingPub != null && pinned.sharingPubHex != null &&
                        !senderSharingPub.equals(pinned.sharingPubHex, ignoreCase = true)
                    if (signingChanged || sharingChanged) {
                        android.util.Log.w(
                            "SharesInboxRepository",
                            "KEY-CHANGE ALARM: server key for sender ${a.owner} differs from " +
                                "pinned key — verifying against the PIN, not the server key",
                        )
                    }

                    if (sig == null) {
                        // Stripped signature from a signer we hold a pin for.
                        android.util.Log.w(
                            "SharesInboxRepository",
                            "REJECTING share from pinned signer, bad/stripped signature (missing) shareId=${s.shareId} itemId=${a.item}",
                        )
                        continue
                    }

                    // We hold a pin AND a signature is present (sig != null,
                    // checked above). If we ALSO have the inputs the check needs
                    // — the wrapped key and our own user_id — we MUST run
                    // verification and DROP on anything that isn't an explicit
                    // `true`. verifyNoteShareWrap returns:
                    //   true  → valid → keep.
                    //   false → structurally-well-formed but cryptographically
                    //           invalid (forged / tampered / rotated / server-
                    //           substituted key) → DROP.
                    //   null  → it couldn't PARSE its inputs (NoteCrypto maps a
                    //           bad-hex / wrong-length error to null). The only
                    //           attacker-controlled input in this branch is the
                    //           SIGNATURE (the pubkey is our PINNED one), so a null
                    //           means the server handed us a MALFORMED signature.
                    //           Fail OPEN on that and a malicious server bypasses
                    //           verification for a PINNED (trusted) sender simply
                    //           by sending un-parseable signature bytes → so a null
                    //           is a DROP here, NOT a grace.
                    // A legacy entry with no wrapped key skips this block and is
                    // shown as incomplete below. (Our own user_id is never unknown
                    // here: without one the refresh stopped before the fetch.)
                    if (wrapped != null) {
                        // Verify AND capture the generation the signature ACTUALLY
                        // verified under — never the server-claimed one. A real-gen
                        // signature verifies directly; a legacy signature verifies
                        // via the SDK's fallback and reports effectiveGeneration 0,
                        // so it cannot launder a high claim.
                        // A conflict (SenderKeyPinStore.fold) verifies nothing: the
                        // same drop as a bad signature.
                        val vr = if (pinned.isConflict) null else try {
                            noteCrypto.verifyNoteShareWrapGen(
                                signingPubkeyHex = pinned.signingPubHex,
                                signatureHex = sig,
                                senderUserId = a.owner,
                                recipientUserId = myUserId,
                                itemId = a.item,
                                // Pass the note's server-emitted content-key generation as
                                // the CLAIMED version. Gson defaults an absent field to 0L;
                                // treat <= 0 as base 1 (the server always emits >= 1) so a
                                // real gen-1 sig verifies rather than only the legacy-0
                                // fallback being tried. coerceIn's upper bound guards
                                // Long.toUInt()'s low-32-bit truncation: a poisoned
                                // negative / >2^32 generation must not wrap back down to 0.
                                contentKeyVersion = s.contentKeyGeneration.coerceIn(1L, 0xFFFF_FFFFL).toUInt(),
                                wrappedKeyB64 = wrapped,
                            )
                        } catch (_: Exception) {
                            // An exception escaping the crypto wrapper is itself a
                            // failed check — fail closed, never grace.
                            null
                        }
                        if (vr == null || !vr.verified) {
                            // null (unparseable inputs — the only attacker-controlled
                            // input here is the signature, so a null means the server
                            // handed us a malformed sig) or a present-but-invalid
                            // signature → forged / tampered / rotated-key / garbage
                            // wrap.
                            android.util.Log.w(
                                "SharesInboxRepository",
                                "REJECTING share from pinned signer, signature failed verification (invalid or malformed) shareId=${s.shareId} itemId=${a.item}",
                            )
                            continue
                        }
                        // ROLLBACK CHECK: a validly-signed wrap
                        // whose generation is BELOW the highest
                        // we've ever accepted for this item is
                        // the server replaying an older wrapped
                        // content key — e.g. a stale key a
                        // since-removed member still holds,
                        // with its still-valid signature.
                        // Legitimate generations only increase
                        // (rotate-shares bumps by exactly 1),
                        // so a lower one from a pin-verified
                        // sender is an attack, not reordering →
                        // DROP without unwrapping.
                        //
                        // Only a REAL (>=1) generation is
                        // floored. effectiveGeneration 0 is the
                        // SDK's legacy-fallback verdict, which
                        // an owner device on an older build
                        // emits — flooring those would
                        // false-drop a legitimate rotation and
                        // strand the recipient on stale
                        // content. Gen-0 is therefore exempt,
                        // and never advances the floor either:
                        // a gen-0 replay is outside what this
                        // check covers, not a hole it opens.
                        val floor = genFloorStore.floor(a.item, myUserId)
                        val effGen = vr.effectiveGeneration.toLong()
                        if (effGen > 0L && effGen < floor) {
                            android.util.Log.w(
                                "SharesInboxRepository",
                                "ROLLBACK DROP: wrap verified but generation ${vr.effectiveGeneration} is below the per-item floor $floor — replayed old content key shareId=${s.shareId} itemId=${a.item}",
                            )
                            continue
                        }
                        // Kept (pin-verified) → advance the per-item floor, keyed on the
                        // generation the signature ACTUALLY verified under, and bind the item
                        // to this owner, in one commit. Only pin-verified shares move the
                        // floor; a legacy-0 verify moves none. Held in memory, in force for the
                        // rows that follow, and saved ONCE below, before any key is published
                        // (NoteGenFloorStore.acceptUnsaved).
                        genFloorStore.acceptUnsaved(a.item, a.owner, vr.effectiveGeneration.toLong(), myUserId)
                    }
                }
                // No pin yet (TOFU first use) → GRACE: we can't verify against a
                // pin, and we must NOT verify against a server-supplied key
                // (that proves nothing). Stay unverified; we pin the observed
                // sender keys below on first successful unwrap, so subsequent
                // shares are checked against them. A never-signed legacy wrap
                // from an unpinned sender graces here too.

                visibleIds.add(a.item)
                if (wrapped == null) {
                    continue
                }
                val key = try { noteCrypto.openSeal(wrapped, priv) } catch (_: Exception) { null }
                if (key == null) {
                    continue
                }
                if (key.size == 32) {
                    freshKeys[a.item] = key
                    materializable.add(a to key)
                    // TOFU-pin the sender's directory keys on first successful
                    // unwrap, when no pin existed yet. Future shares from this
                    // sender then verify against this pin, so a later "verified"
                    // means exactly that. This never overwrites an existing pin:
                    // pin() reports a changed key without mutating it.
                    if (pinned == null && senderSigningPub != null) {
                        pinStore.pin(a.owner, senderSigningPub, senderSharingPub, myUserId)
                    }
                    // A first contact that opened (its content decrypts under the key) binds the
                    // item to its owner; a pinned one was bound when it verified.
                    if (pinned == null && runCatching {
                            noteCrypto.decryptNoteWithContentKeyV2(s.contentNonce, s.encryptedContent, key, a.item)
                        }.getOrNull() != null
                    ) {
                        genFloorStore.acceptUnsaved(a.item, a.owner, forAccount = myUserId)
                    }
                }
                // Otherwise it opened to something that isn't a content key: nothing to
                // decrypt with, so this share renders as nothing.
            }
            // ── What is HELD BACK is decided here, BEFORE anything is published ──
            //
            // Two kinds of shared note are KEPT AWAY: never written to the database
            // the widgets read, taken out of it when an earlier refresh or build put
            // them there, and their keys never handed out.
            //
            //  - A note of a notebook this account DECLINED (DeclinedNotebooks): one
            //    whose share is later than the decline.
            //  - A note of a notebook that is ON OFFER and not answered ("Hide
            //    unanswered invitations"). The page holds those back until the
            //    invitation is accepted. What "on offer" is when the inbox was not
            //    read to its end, and what becomes of such a note when the
            //    invitation is then declined, is NotebooksOnOffer.
            //
            // An item goes only when EVERY share of it that opens is one to keep
            // away: a second share, older than a decline, is the reader's in its own
            // right.
            //
            // Decided before the swap: publishing the keys of held notes with the
            // rest and taking them back afterwards would leave a window in which an
            // overlapping pull could open the note and write its title.
            //
            // Which notebook each shared NOTE claims, from its own decrypted
            // content. Built for this refresh alone (see refreshMutex).
            val notebookOf = HashMap<String, String>()
            for ((a, key) in materializable) {
                val dto = a.share
                if (dto.isNotebook) continue
                runCatching {
                    val plain = noteCrypto.decryptNoteWithContentKeyV2(
                        dto.contentNonce, dto.encryptedContent, key, a.item,
                    ) ?: return@runCatching
                    JSONObject(plain).optString("notebook_id").takeIf { it.isNotBlank() }
                        ?.let { notebookOf[dto.shareId] = it }
                }
            }
            // The notebooks whose own row THIS read reached.
            val servedNotebooks = served.filter { it.isNotebook }.mapNotNull { AccountIds.canonical(it.itemId) }.toSet()
            // What the server says is on offer, whatever this read reached: an
            // invitation's own row is the oldest of the invitation, and a read that
            // stops early does not come to it.
            val offeredOnServer = NotebooksOnOffer.said(offeredSaid) { AccountIds.canonical(it) }
            // "Declined" is the server's word, and a notebook this answer offers is not declined.
            val declined = DeclinedNotebooks.onTheServersWord(
                declinedSaid, servedNotebooks + offeredOnServer.orEmpty(), declinedNotebooks,
            )
            val lastOffered = sessionManager.getOfferedNotebooks()?.let { canonicalIds(it) }
            val offered = NotebooksOnOffer.of(servedNotebooks, inboxComplete, lastOffered, declined.keys, offeredOnServer)
            val offerUnknown = NotebooksOnOffer.unknown(inboxComplete, lastOffered, offeredOnServer)
            // A read that stopped early, with no word from the server on what is on
            // offer. What an earlier read found says nothing of an invitation made
            // SINCE: its notes are the newest rows, its own row the oldest, on a
            // page this read did not come to (see
            // NotebooksOnOffer.heldForArrivingUnseen).
            val stoppedEarlyUnsaid = offeredOnServer == null && !inboxComplete
            val offersKnownAsOf = sessionManager.getOffersKnownAsOf()
            val (accepted, acceptedToRemember) = acceptedNotebooks(servedNotebooks, inboxComplete)
            val unanswered = offered - accepted
            // Every notebook something is known about.
            val knownNotebooks = accepted + offered + declined.keys
            val notes = materializable.filterNot { (a, _) -> a.share.isNotebook }
            // What the last refresh held, in this process or the one before it.
            val heldForAnInvitationBefore = heldForAnInvitation ?: canonicalIds(sessionManager.getHeldForAnInvitation())
            // Per item: one verdict per share of it that opens, (held, held for an invitation).
            val verdicts = notes.groupBy({ (a, _) -> a.item }) { (a, _) ->
                val said = notebookOf[a.share.shareId]
                val notebook = said?.let { AccountIds.canonical(it) }
                // Arrived since anything last knew what was on offer, in a notebook nothing is
                // known about: it is held as what it most likely is, a new invitation's note
                // whose own row this read did not come to.
                val arrivedUnseen = NotebooksOnOffer.heldForArrivingUnseen(
                    notebook, stoppedEarlyUnsaid, knownNotebooks, a.share.createdAt, offersKnownAsOf,
                )
                // AND REMEMBERED as held for an invitation. Held but not
                // remembered, the note would not be "held for one before" when
                // the reader then DECLINED that invitation; the rule for a
                // declined notebook keeps away only what was shared after the
                // decline, so the note would be written to the database the
                // widgets read. (Not while nothing at all says what is on
                // offer: that hold is `heldForNotKnowing`, of everything not
                // accepted, and is no invitation's.)
                val forAnInvitation = NotebooksOnOffer.heldForAnInvitation(
                    notebook, unanswered, declined.keys, a.item in heldForAnInvitationBefore,
                ) || (!offerUnknown && arrivedUnseen)
                val held = forAnInvitation ||
                    DeclinedNotebooks.keepsAway(declined, said, a.share.createdAt) ||
                    NotebooksOnOffer.heldForNotKnowing(notebook, offerUnknown, accepted) ||
                    arrivedUnseen
                held to forAnInvitation
            }
            val heldNow = verdicts.filterValues { shares -> shares.all { it.first } }.keys
            val heldForAnInvitationNow = heldNow.filter { id -> verdicts.getValue(id).any { it.second } }.toSet()
            val decidedNow = verdicts.keys
            val keptAway = NotebooksOnOffer.held(heldNow, decidedNow, keptAwayItems, inboxComplete)
            val keptForAnInvitation = NotebooksOnOffer.held(heldForAnInvitationNow, decidedNow, heldForAnInvitationBefore, inboxComplete)

            // The notebooks for the Shared Notebooks section. Deduped by item id, newest row
            // winning: re-sharing a notebook (to change a permission, say) writes a second share
            // row, and two entries for one notebook would read as two invitations to the same
            // place.
            val sharedNotebooks = materializable
                .filter { (a, _) -> a.share.isNotebook }
                .associate { (a, key) ->
                    val dto = a.share
                    val name = runCatching {
                        val plain = noteCrypto.decryptNoteWithContentKeyV2(
                            dto.contentNonce, dto.encryptedContent, key, a.item,
                        )
                        plain?.let { JSONObject(it).optString("title").ifBlank { null } }
                    }.getOrNull()
                    a.item to SharedNotebook(
                        shareId = dto.shareId,
                        itemId = a.item,
                        name = name,
                        ownerEmail = dto.ownerEmail,
                        permission = dto.permission,
                    )
                }
                .values.toList()

            // ── The swap ────────────────────────────────────────────────────────────────────
            // The items whose floor or owner could not be saved: decided in the swap, and
            // asked again where the rows are written.
            var withheld: Set<String> = emptySet()
            val published = synchronized(contentKeys) {
                // A wipe since this refresh started (a sign-out), or another account signed in:
                // what it opened is the outgoing account's. Nothing is saved or published.
                if (overtaken(wipesAtStart, myUserId)) return@withContext STOPPED_BY_A_WIPE
                // The floors and owners of everything accepted above, in ONE
                // save (not one per accepted share, each of the whole record),
                // BEFORE any key opened above is published, and only now: after
                // the check, for the account still signed in (saved before it,
                // a switch in the middle would write the old account's records
                // under the new one's name).
                //
                // Tried a few times. What could not be saved is WITHHELD, item
                // by item: a floor that is only in memory dies with the
                // process, and the older key it refused would be taken again
                // afterwards, so nothing opened under one is used. The rest of
                // the refresh goes on, so a save that keeps failing does not
                // stop the removals below at every refresh. What was accepted
                // stays in memory, and the next refresh saves it and gives it
                // out.
                val saved = (1..FLOOR_SAVE_ATTEMPTS).any { genFloorStore.saveAccepted(myUserId) }
                val unsaved = if (saved) emptySet() else genFloorStore.unsaved(myUserId)
                withheld = unsaved
                if (!saved) {
                    // Never silent: an error line of its own, as for a store that cannot be read.
                    android.util.Log.e(
                        "SharesInboxRepository",
                        "the rollback floors and item owners of ${unsaved.size} shared item(s) could not be saved: what was opened for them is withheld until they are",
                    )
                }
                contentKeys.clear()
                // Never the key of a note that is held, or
                // withheld.
                contentKeys.putAll(freshKeys.filterKeys { it !in keptAway && it !in unsaved })
                // What is held goes out WITH the keys, in the same step.
                declinedNotebooks = declined
                offeredAtLastRefresh = offered
                unansweredAtLastRefresh = unanswered
                unknownAtLastRefresh = offerUnknown
                acceptedAtLastRefresh = accepted
                keptAwayItems = keptAway
                unsavedItems = unsaved
                heldForAnInvitation = keptForAnInvitation
                _sharedNotebooksFlow.value = sharedNotebooks.filterNot { it.itemId in unsaved }
                // And what this device remembers of the answers and of what is
                // on offer: in the same step, under the lock a wipe's start
                // takes, so neither is ever written after a wipe has begun.
                acceptedToRemember?.let { sessionManager.setSeenAcceptedNotebooks(it) }
                // The hold too, so that it outlives this process. Only when
                // it changed: it is rewritten whole.
                if (keptForAnInvitation != heldForAnInvitationBefore) sessionManager.setHeldForAnInvitation(keptForAnInvitation)
                // What is on offer, for a later read that stops early against a server that
                // does not say: the server's own list when it gave one, otherwise only what a
                // read to the end found.
                if (offeredOnServer != null || inboxComplete) {
                    sessionManager.setOfferedNotebooks(offered)
                    // And as of when: the newest share this read saw (the first page holds
                    // it). A share newer than that arrived after anything knew what was on
                    // offer (NotebooksOnOffer.heldForArrivingUnseen). An empty inbox moves
                    // nothing: whatever comes later is newer than the mark there was.
                    NotebooksOnOffer.newest(served.map { it.createdAt })?.let { sessionManager.setOffersKnownAsOf(it) }
                }
                contentKeys.size
            }

            // ── After the swap: what is stored and written ─────────────────────
            // Each step asks first whether a wipe has begun, or the account has
            // changed, since this refresh started: the wipe empties what these
            // steps write, so asking once, before the rows only, is not enough.
            val stopped = { overtaken(wipesAtStart, myUserId) }

            // Un-share / owner-delete propagation: any id in the inbox at the last
            // refresh but absent now has left — the share was revoked or the owner
            // deleted the note, and the backend drops it from the inbox listing
            // (the same source-of-truth the whole materialization path assumes).
            // Remove its materialized Room row so the recipient stops seeing a note
            // they've lost access to. Hard-delete by id is safe: an inbox-share id
            // is the owner's item, never one we own (you don't share a note to
            // yourself), so the only local row with that id is the materialization
            // — there's nothing of ours to trash. The id set is persisted rather
            // than in-memory so a revoke that happens while the app is down is
            // still caught on next launch. Runs only after a successful inbox fetch
            // — an unreachable inbox returned 0 above without reaching here. Both
            // sides canonical: the set an earlier build stored can hold the
            // server's spelling, and the live one is canonical.
            //
            // Not on an inbox that was not read to its END. A share missing from it
            // has not left: it is past where the reading stopped. Nothing is
            // removed then, and the ids known before are kept beside the ones read,
            // so the next refresh that DOES reach the end still has them to
            // compare.
            val liveInboxIds: Set<String> = visibleIds
            val knownInboxIds = canonicalIds(sessionManager.getInboxShareIds())
            val removed = if (!inboxComplete) emptySet() else sharesToRemove(knownInboxIds, liveInboxIds)
            // A row whose removal FAILED is still in the database, and on the
            // widgets. Its id stays in the stored set, so the next refresh that
            // reads the inbox to its end tries again; forgotten here, the row would
            // stay for good. A refresh that is cancelled or overtaken part-way
            // stores nothing, which keeps every id it had not come to.
            val notRemoved = mutableSetOf<String>()
            for (id in removed) {
                // The question and the removal in one turn ([rowWrites]).
                val gone = rowWrites.withLock { if (stopped()) null else removeShareRow(id, myUserId, vaults) }
                    ?: return@withContext STOPPED_BY_A_WIPE
                if (!gone) notRemoved += id
            }
            val idsStored = unlessOvertaken(wipesAtStart, myUserId) {
                sessionManager.setInboxShareIds(if (!inboxComplete) knownInboxIds + liveInboxIds else liveInboxIds + notRemoved)
            }
            if (!idsStored) return@withContext STOPPED_BY_A_WIPE

            // The rows of what is held leave the database (an earlier refresh, or an older
            // build, may have written them).
            for (id in keptAway) {
                rowWrites.withLock { if (stopped()) null else removeShareRow(id, myUserId, vaults) }
                    ?: return@withContext STOPPED_BY_A_WIPE
            }

            // Materialize shared notes into Room so they render in the list. The inbox response
            // already carries each note's encrypted_content + content_nonce, and we just
            // unwrapped its content key — so the recipient can decrypt and store the row
            // directly, WITHOUT waiting on /sync/pull.
            //
            // NOTEBOOKS never become rows in the notes table. The notebook object carries a
            // collection's name and its roster, not a document — writing it here puts a phantom
            // note titled with the notebook's name in the recipient's list. Its content key is
            // still held by the inbox, which is what a Shared Notebooks view reads.
            //
            // Not what is held, and not what is withheld for want of a save: an item of that
            // kind keeps the row it has (written under a floor that IS saved), untouched.
            val written = materializeSharedNotes(
                notes.filterNot { (a, _) -> a.item in keptAway || a.item in withheld }, myUserId, vaults, stopped,
            )
            if (!written || stopped()) return@withContext STOPPED_BY_A_WIPE
            published
        } } catch (e: StoreUnreadable) {
            // The store read before the fetch and not now, or the account changed under the
            // refresh (the stores refuse a refresh that is for another account): stop, as above.
            android.util.Log.w("SharesInboxRepository", "inbox refresh stopped: ${e.message}")
            return 0
        }
        if (count == STOPPED_BY_A_WIPE) {
            android.util.Log.w("SharesInboxRepository", "inbox refresh stopped: this device was wiped, or the account changed, while it ran; nothing more was published or written")
            return 0
        }

        // The list has been applied: only now is it one to prune against. Not after a wipe.
        unlessOvertaken(wipesAtStart, myUserId) { listedNotebookIds = listedNotebooks }
        refreshesApplied++
        return count
    }

    /** A wipe has begun, or another account (or none) is signed in, since the refresh that asks started. */
    private fun overtaken(wipesAtStart: Int, account: String): Boolean =
        synchronized(contentKeys) { wipes != wipesAtStart } || AccountIds.canonical(sessionManager.getUserId()) != account

    /**
     * Runs [write] unless the refresh that asks has been [overtaken], and says whether it ran.
     * Under the lock a wipe's start takes ([wipeIsStarting]): the write is before the wipe began
     * (and the wipe then clears it) or it does not happen.
     */
    private inline fun unlessOvertaken(wipesAtStart: Int, account: String, write: () -> Unit): Boolean =
        synchronized(contentKeys) {
            if (overtaken(wipesAtStart, account)) return false
            write()
            true
        }

    /**
     * The notebooks this account has said YES to, canonical: the list in its own metadata
     * ([NotebookInvites]), and what this device remembers of it.
     *
     * The memory is there for the same reason the page keeps one (the web editor's
     * `pingolin_note_seen_accepted`). The list is one document for every device, and older app
     * builds can empty it after a single failed inbox request. Without a memory, an emptied list
     * would take every accepted notebook's notes off this device's widgets. Learned whenever the
     * list names a notebook; forgotten only when an inbox READ TO ITS END stops offering it (the
     * owner withdrew it, or the reader declined).
     *
     * A list that cannot be read (no metadata on this device yet, or no key) names nothing, so
     * on a first run nothing is "accepted" until the pull has brought the metadata;
     * [refreshIfAnswersChanged] is what then lets the notes in.
     */
    private suspend fun acceptedNotebooks(offered: Set<String>, inboxComplete: Boolean): Pair<Set<String>, Set<String>?> {
        val listed = currentlyAccepted(includeRemembered = false)
        val remembered = canonicalIds(sessionManager.getSeenAcceptedNotebooks())
        val known = remembered + listed
        val kept = if (inboxComplete) known.intersect(offered) else known
        // What to remember from now on, when that is not what is remembered already.
        // The caller stores it AFTER its swap and its wipe check, not here.
        return known to kept.takeIf { it != remembered }
    }

    private suspend fun currentlyAccepted(includeRemembered: Boolean): Set<String> {
        val listed = try {
            notebookInvites.get().accepted().mapNotNull { AccountIds.canonical(it) }.toSet()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SharesInboxRepository", "the accepted notebooks can't be read: ${e.message}")
            emptySet()
        }
        return if (includeRemembered) listed + canonicalIds(sessionManager.getSeenAcceptedNotebooks()) else listed
    }

    /**
     * Refresh again if an invitation has been ANSWERED since the last refresh.
     *
     * The sync refreshes the inbox before its pull, and the acceptances come with the pull (they
     * are in the account's metadata). So a notebook accepted on another device, or on this one a
     * moment ago, is still "unanswered" when the refresh runs, and its notes would be held back
     * until the NEXT sync. Called after the pull: when what is on offer and not accepted differs
     * from what the last refresh held back, the inbox is read once more.
     *
     * @return true when it refreshed
     */
    suspend fun refreshIfAnswersChanged(): Boolean {
        val offered = offeredAtLastRefresh ?: return false
        val accepted = currentlyAccepted(includeRemembered = true)
        // When the last refresh could not tell what was on offer, it held by what was ACCEPTED:
        // any change in that is an answer.
        val answered = offered - accepted != unansweredAtLastRefresh ||
            (unknownAtLastRefresh && accepted != acceptedAtLastRefresh)
        if (!answered) return false
        // True only for a refresh that got to its end: one that a wipe overtook, or
        // that could not read the inbox, has let nothing in.
        val before = refreshesApplied
        refresh()
        return refreshesApplied != before
    }

    /** This account's vault keys: the current one and every recovered identity's (the keys the
     *  sync's decrypt tries), for telling one of its own rows. */
    private fun ourVaultKeys(): List<ByteArray> =
        (listOfNotNull(sessionManager.getMasterKey()) + previousIdentities.vaultKeys()).mapNotNull {
            runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()?.takeIf { k -> k.size == 32 }
        }

    /** One of [vaults] opens [row]: a wrap on it, or, for a row with no wrap (a note from before
     *  per-note keys, which is never shared), its body itself. It is ours, whatever its user id
     *  says. */
    private fun opensUnder(row: ItemEntity, vaults: List<ByteArray>): Boolean {
        val wrap = row.wrappedContentKey
        val nonce = row.wrappedContentKeyNonce
        if (wrap == null || nonce == null) {
            return vaults.any { v ->
                runCatching {
                    noteCrypto.decryptNoteWithContentKeyV2(row.contentNonce, row.encryptedContent, v, row.id)
                }.getOrNull() != null
            }
        }
        return vaults.any { v ->
            runCatching { noteCrypto.unwrapWithVault(wrap, nonce, v) }.getOrNull()?.also { it.fill(0) } != null
        }
    }

    /** [row] is [owner]'s: it names that owner, and it is not ours — neither by its user id
     * nor by what our vault opens ([opensUnder]). */
    private fun isThatOwnersRow(row: ItemEntity, owner: String, me: String, vaults: List<ByteArray>): Boolean =
        AccountIds.same(row.userId, owner) && !AccountIds.same(row.userId, me) && !opensUnder(row, vaults)

    /**
     * Take the materialised row of a share this device has stopped showing out of the database.
     *
     * Only a row shaped like another owner's materialised share goes: its owner a UUID that isn't
     * this account, not dirty, and no wrap our vault opens. An own row, an unsynced edit or a row
     * of unknown owner stays, whatever the server says left.
     *
     * @return false ONLY when the row may still be there and should not be: reading or deleting
     *         it failed. True when it is gone, was never there, or is not a share's row.
     */
    private suspend fun removeShareRow(id: String, myUserId: String, vaults: List<ByteArray>): Boolean {
        try {
            val row = itemDao.getById(id) ?: return true
            val owner = AccountIds.canonical(row.userId)
            if (row.isDirty || owner == null || owner == myUserId || opensUnder(row, vaults)) {
                android.util.Log.w("SharesInboxRepository", "un-share cleanup kept $id: not a share's row")
                return true
            }
            itemDao.deleteById(id)
            rowsRemovedCount.incrementAndGet()
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A sync that is being stopped stops here too, rather than logging it
            // and carrying on.
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SharesInboxRepository", "un-share cleanup failed for $id", e)
            return false
        }
    }

    /**
     * Upsert each freshly-keyed shared note into Room from its inbox
     * payload. Skips rows with unsynced local edits (isDirty) so a
     * recipient who has edit access and pending changes isn't clobbered
     * by the owner's copy on the next refresh — otherwise the owner's
     * version wins (parity with sync's server-wins posture). Shared rows
     * carry NO wrapped_content_key: the recipient decrypts via the
     * inbox-held content key ([getContentKey]), not the vault.
     *
     * @param stopped asked before each row is written: true when the refresh has been overtaken
     *   by a wipe or another account
     * @return false when it stopped for that
     */
    private suspend fun materializeSharedNotes(
        shares: List<Pair<Admitted, ByteArray>>,
        me: String,
        vaults: List<ByteArray>,
        stopped: () -> Boolean,
    ): Boolean {
        for ((a, key) in shares) {
            val s = a.share
            val plain = try {
                noteCrypto.decryptNoteWithContentKeyV2(s.contentNonce, s.encryptedContent, key, a.item)
            } catch (_: Exception) { null } ?: continue

            val existing = try { itemDao.getById(a.item) } catch (_: Exception) { null }
            // Don't overwrite local edits the recipient hasn't pushed yet. (Whether the row may be
            // written over at all is asked below: only when there is something to write.)
            if (existing != null && existing.isDirty) continue

            val parsed = try { JSONObject(plain) } catch (_: Exception) { null }
            val title = parsed?.optString("title", "")?.takeIf { it.isNotEmpty() }
            val color = parsed?.optString("color", "")?.takeIf { it.isNotEmpty() }
            val isPinned = parsed?.optBoolean("is_pinned", false) ?: false
            val isArchived = parsed?.optBoolean("is_archived", false) ?: false
            val sortOrder = parsed?.optInt("sort_order", 0) ?: 0
            val createdAt = try { Instant.parse(s.createdAt) } catch (_: Exception) { Instant.now() }
            val updatedAt = try { Instant.parse(s.updatedAt) } catch (_: Exception) { createdAt }

            val entity = ItemEntity(
                id = a.item,
                // The item is owned by the sharer; mirrors what /sync/pull
                // would store (i.user_id). Display queries don't filter on
                // user_id, so this only affects round-trip fidelity.
                userId = a.owner,
                // Notebook shares are filtered out earlier,
                // so anything materialized here is a note.
                itemType = ITEM_TYPE_NOTE,
                encryptedContent = s.encryptedContent,
                contentNonce = s.contentNonce,
                decryptedContent = plain,
                title = title,
                version = existing?.version ?: 1,
                createdAt = createdAt,
                updatedAt = updatedAt,
                deleted = false,
                circleId = null,
                notebookId = existing?.notebookId,
                contentSize = s.encryptedContent.length,
                color = color,
                isPinned = isPinned,
                isArchived = isArchived,
                isDirty = false,
                // Recipient has no vault-wrapped key for a shared note;
                // decryption goes through the inbox content key.
                wrappedContentKey = null,
                wrappedContentKeyNonce = null,
                sortOrder = sortOrder,
            )
            if (existing != null) {
                // The row is already exactly what this share would write:
                // nothing to do. Rewriting every shared note on every refresh
                // (the background sync alone runs one every 15 minutes) would
                // replace a row per note and tell every reader of the table it
                // had changed. Compared as the database stores it: see
                // [ItemEntity.asStored].
                if (existing == entity.asStored()) continue
                // Write only into an absent row or that owner's own. Asked for a
                // row that would change, and not before the comparison: the
                // answer tries each vault key on the row's body, and for a row
                // that stays as it is nothing hangs on it.
                if (!isThatOwnersRow(existing, a.owner, me, vaults)) continue
            }
            // Asked at the write itself, and the two in ONE turn ([rowWrites]): a
            // wipe that has begun empties the table this goes into, and one that
            // begins now waits for this row and clears it with the rest.
            val goOn = rowWrites.withLock {
                if (stopped()) return@withLock false
                try { itemDao.upsert(entity) } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("SharesInboxRepository", "materialize upsert failed for ${a.item}", e)
                }
                true
            }
            if (!goOn) return false
        }
        return true
    }

    companion object {
        /**
         * The most rows one inbox answer carries (the server's page limit). Only for a server
         * that sends the bare list, where a page shorter than this is taken for the last one; a
         * server that says `has_more` is believed instead (InboxAnswer).
         */
        const val INBOX_PAGE_LIMIT = 500

        /** No refresh reads further than this many pages (10,000 shares). */
        const val INBOX_MAX_PAGES = 20

        /** How often a refresh tries to save its floors and owners before it withholds what they are of. */
        const val FLOOR_SAVE_ATTEMPTS = 3

        /** What a refresh's inner block answers instead of a count when a wipe (or a change of account) stopped it. */
        private const val STOPPED_BY_A_WIPE = -1

        /** [ids] in their canonical form; one that is no UUID is no share's. */
        fun canonicalIds(ids: Set<String>): Set<String> = ids.mapNotNull { AccountIds.canonical(it) }.toSet()

        /**
         * Inbox-share ids that vanished since the previous refresh — a
         * revoked share or an owner-deleted note (the backend drops both
         * from the inbox listing). Their materialized rows should be
         * removed. Pure so the un-share/delete propagation rule is
         * unit-testable.
         */
        fun sharesToRemove(previous: Set<String>, live: Set<String>): Set<String> =
            previous - live
    }
}

/**
 * A sender's directory public keys as returned by `/users/public-keys`.
 * Used ONLY to TOFU-bootstrap a pin and to detect a server key-substitution
 * against an existing pin — never as a verification key. [sharingPub] is
 * nullable because only the signing key is required for wrap-signature
 * verification; we pin the sharing key too when the directory returns it.
 */
private data class SenderDirKeys(val signingPub: String, val sharingPub: String?, val userId: String?)
