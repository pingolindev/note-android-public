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

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.pingolin.note.data.local.NoteDatabase
import com.pingolin.note.util.KeystoreEncryption
import com.pingolin.note.util.NoteGenFloorStore
import com.pingolin.note.util.RetainedContentKeyStore
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import com.pingolin.note.widget.WidgetPreferences
import com.pingolin.note.widget.WidgetUpdateHelper
import com.pingolin.note.worker.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Erases every local trace of the signed-in account on sign-out.
 *
 * Without this, switching accounts on a shared device leaks data: the
 * SQLCipher DB stores per-row plaintext (`decrypted_content` + decrypted
 * `title`) and the DAO queries carry no per-account filter, so the next
 * account that opens the same DB reads the previous account's notes. The
 * SDK's long-term keys, the Keystore AES keys, and decrypted attachment /
 * camera temp files in `cacheDir` likewise survive a plain session clear.
 *
 * The wipe runs on an **application-scoped** coroutine on purpose: the logout
 * UI navigates to AuthActivity immediately (`CLEAR_TASK` + `finish()`), which
 * cancels the caller's `viewModelScope` almost at once — a wipe launched there
 * would be aborted mid-way. Every step is isolated (`runCatching`) so one
 * failure never skips the rest.
 */
@Singleton
class LocalDataWiper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: NoteDatabase,
    private val keystoreEncryption: KeystoreEncryption,
    private val cryptoManager: CryptoManager,
    private val sessionManager: SessionManager,
    private val sharesInbox: SharesInboxRepository,
    private val pinStore: SenderKeyPinStore,
    private val genFloorStore: NoteGenFloorStore,
    private val retainedContentKeyStore: RetainedContentKeyStore,
    private val quotaManager: QuotaManager,
    private val previousIdentityLoader: PreviousIdentityLoader,
    private val identityRecheck: IdentityRecheck,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget the full local wipe on the application-scoped coroutine,
     * then run [onComplete] on that same background context (for example, to
     * flip the session state only after the secrets are gone). Best-effort
     * throughout.
     */
    fun wipe(
        preWipe: suspend () -> Unit = {},
        onComplete: () -> Unit = {},
    ) {
        scope.launch {
            // Stop any scheduled / in-flight background sync BEFORE the optional
            // pre-wipe flush so the two can't double-push. wipeBlocking() also
            // cancels at (a0); this just moves the cancel ahead of preWipe.
            runCatching { SyncWorker.cancelAllSync(context) }
                .onFailure { Log.w(TAG, "pre-wipe sync cancel failed", it) }
            // Optional step run while the session token + vault key still exist
            // (wipeBlocking clears them at c2) — e.g. flush unsynced edits to the
            // server, then revoke the session. Best-effort; never blocks the wipe.
            runCatching { preWipe() }.onFailure { Log.w(TAG, "pre-wipe step failed", it) }
            wipeBlocking()
            runCatching { onComplete() }.onFailure { Log.w(TAG, "wipe onComplete failed", it) }
        }
    }

    /**
     * The wipe body. `internal` + `suspend` so unit tests can await it directly
     * instead of racing the fire-and-forget [wipe] scope.
     */
    internal suspend fun wipeBlocking() {
        // The shares inbox is told FIRST that a wipe is under way, and LAST that it is
        // over. A refresh that is running stores and writes nothing more once it knows,
        // and none starts until the wipe has ended. `sharesInbox.wipe()` itself comes
        // only after the tables and the web view are cleared; were the inbox told only
        // then, a refresh running in the meantime, or one that started in between,
        // would write the outgoing account's rows into what had just been emptied.
        //
        // Said off the caller's thread, and whatever has become of the caller: it takes
        // a lock a refresh holds while it saves to disk (an account switch calls this
        // from the view model's scope, which may be the main thread), and a caller
        // already cancelled would otherwise say nothing here and still say "over"
        // below, ending the count of another wipe that IS under way.
        runCatching { withContext(NonCancellable + Dispatchers.IO) { sharesInbox.wipeIsStarting() } }
        try {
            wipeSteps()
        } finally {
            runCatching { sharesInbox.wipeIsOver() }
        }
    }

    private suspend fun wipeSteps() = withContext(Dispatchers.IO) {
        // (a0) Cancel any scheduled / in-flight background sync FIRST. A
        //      SyncWorker started for the outgoing account could otherwise fetch
        //      a page and upsert its decrypted plaintext into the DB right after
        //      we clear it (or after the incoming account signs in), leaking
        //      account A's notes into account B's session. Cancellation is
        //      cooperative, so SyncRepository re-checks the acting user at the
        //      upsert too.
        runCatching { SyncWorker.cancelAllSync(context) }
            .onFailure { Log.w(TAG, "sync cancel failed", it) }

        // (a) Room rows hold the decrypted note body + title (no per-account
        //     filter) — the actual cross-account read vector. clearAllTables
        //     empties them but keeps the (device-level) DB usable for the next
        //     account, which repopulates from its own /sync/pull.
        runCatching { database.clearAllTables() }
            .onFailure { Log.w(TAG, "clearAllTables failed", it) }

        // (a2) The WEB EDITOR's storage, which is a SEPARATE copy of the
        //      same notes and has to be wiped as well.
        //
        //      WebAppActivity hosts the Note web editor and is the
        //      DEFAULT editor. That app caches notes in IndexedDB as
        //      DECRYPTED PLAINTEXT — a deliberate, documented tradeoff on
        //      the web (the web editor's noteDb.ts: "Records here are
        //      stored as decrypted plaintext, NOT wrapped with a session
        //      key"), because the grid has to paint before the vault key
        //      arrives.
        //
        //      On Android that tradeoff lands differently: the native
        //      side keeps notes under SQLCipher and wipes them here,
        //      while the same notes would otherwise sit in the WebView's
        //      IndexedDB in the clear, unwiped, for the next account on a
        //      shared device to read. The page's own clearLocalCache()
        //      cannot help — at logout the WebView is gone.
        //
        //      Cookies go too: they carry the session that authorises a
        //      refetch. Both APIs are static and need no live WebView,
        //      but they touch the WebView provider, which requires the
        //      main thread.
        //
        //      The RETAINED WebView has to go first, and it has to go
        //      here. WebAppActivity does not destroy its WebView on close
        //      — it hands it back to RetainedWebView so the next note
        //      open is a route change instead of a 3.2-second cold boot.
        //      That means a booted, authenticated document — cookies, the
        //      cached vault key in DOM storage, and the last note that
        //      was open — stays resident for the life of the PROCESS.
        //      Clearing the cookie jar underneath a live document does
        //      not unload the document, so a sign-out that skipped this
        //      would leave the outgoing account's note text on screen
        //      behind the login, and hand the next account a session that
        //      is not theirs. Destroying it before the storage wipe also
        //      means nothing can re-populate storage from a still-running
        //      page.
        runCatching {
            withContext(Dispatchers.Main) {
                // Give the live document one bounded chance to upload its
                // offline queue before it is destroyed. The queue lives in the
                // WEB app's IndexedDB — the native hasUnsyncedChanges guard
                // never sees it — so the native store alone cannot back the
                // logout dialog's "any unsynced changes will be uploaded
                // first". Best-effort by design (matching how other note apps
                // treat sign-out): a failure is WARNED, never blocks the wipe,
                // and a web app without the hook reports "no-hook" and the
                // wipe proceeds.
                when (val left = com.pingolin.note.ui.web.RetainedWebView.flushWebPending(4_000)) {
                    null, "no-hook" ->
                        Log.w(TAG, "web pending flush unavailable (${left ?: "no document"})")
                    "0" -> {}
                    else ->
                        Log.w(TAG, "sign-out proceeding with $left unsynced web edit(s) — about to be wiped")
                }
                // The page's kept trust: its pins, floors and
                // owners go with the storage below, so it gets
                // one last bounded chance to hand them to the app
                // (KeptWebTrustStore), which gives them back at
                // this account's next sign-in. Every sign-out and
                // switch wipe comes through here. Best-effort:
                // keeps follow every change, so the copy the app
                // holds is at worst one change behind.
                val kept = com.pingolin.note.ui.web.RetainedWebView.keepWebTrustNow(1_000)
                if (kept != "true") Log.w(TAG, "the page's last keep of its trust: ${kept ?: "no answer"}")
                com.pingolin.note.ui.web.RetainedWebView.destroy()
                android.webkit.WebStorage.getInstance().deleteAllData()
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
            }
        }.onFailure { Log.w(TAG, "WebView storage wipe failed", it) }

        // (d1) The Kotlin-side "we already took this account's recovered
        //      identities in" latch. The latch is a field on a @Singleton
        //      that outlives the account: left set, the INCOMING
        //      account's first sync short-circuits on it and never
        //      fetches its own /previous-identities, so someone who
        //      recovered their identity and then signed in here would
        //      find the recovery had silently done nothing until the
        //      process restarted. BEFORE (d): the reset is also what
        //      makes a load still on the network drop its answer, so
        //      nothing it imports can land after the key wipe below.
        runCatching { previousIdentityLoader.reset() }
            .onFailure { Log.w(TAG, "previous-identity loader reset failed", it) }
        // (d1b) ...and the identity re-check's pending flag and window,
        //       so the outgoing account's refused nudge never runs for
        //       the incoming one.
        runCatching { identityRecheck.clear() }
            .onFailure { Log.w(TAG, "identity re-check clear failed", it) }

        // (d) SDK-persisted long-term keys (X25519 sharing + Ed25519 signing)
        //     plus any in-memory key state. This is also what drops the
        //     RECOVERED pre-reset keys — clear_all_keys() clears
        //     previous_private_keys with the rest.
        runCatching { cryptoManager.clearAllKeys() }
            .onFailure { Log.w(TAG, "SDK clearAllKeys failed", it) }

        // (d2) In-memory receive-side share state held by the @Singleton
        //      SharesInboxRepository: decrypted per-note content keys. It
        //      survives the account switch and only a successful refresh()
        //      clears it, so without this account A's shares can render in
        //      account B's UI.
        runCatching { sharesInbox.wipe() }
            .onFailure { Log.w(TAG, "shares inbox wipe failed", it) }

        // (d2b) Cached subscription tier + usage held by the @Singleton
        //       QuotaManager. Its isLoggedInFlow observer only nulls the cache
        //       on a logged-OUT transition; an account SWITCH keeps isLoggedIn
        //       true (this wipe runs instead of a full session clear), so without
        //       an explicit clear here account A's tier/usage can render into
        //       account B's Settings until the next refresh lands.
        runCatching { quotaManager.clear() }
            .onFailure { Log.w(TAG, "quota clear failed", it) }

        // (d3) Persisted receive-side TOFU security state, each in its
        //      own EncryptedSharedPreferences file: the sender-key PINS
        //      (note_sender_pins — which sender signing/sharing keys we
        //      trust) and the per-item content-key rollback FLOORS
        //      (note_gen_floors). These stores are PER-ACCOUNT
        //      NAMESPACED (blob keyed by user id), so cross-account
        //      isolation is by construction, NOT by this wipe: account
        //      B reads its own namespace and never sees account A's
        //      pins or floors. wipe() therefore only drops the
        //      in-memory cache and sweeps the obsolete pre-namespacing
        //      GLOBAL blob; it deliberately RETAINS the current
        //      account's namespaced blob so a same-account logout→login
        //      re-reads its own pins and — crucially — its
        //      anti-rollback floors. DO NOT turn this into a full
        //      per-account wipe: that would open the one-time
        //      content-key rollback window the retained floors exist to
        //      close (see NoteGenFloorStore.wipe).
        runCatching { pinStore.wipe() }
            .onFailure { Log.w(TAG, "sender pin store wipe failed", it) }
        runCatching { genFloorStore.wipe() }
            .onFailure { Log.w(TAG, "gen floor store wipe failed", it) }
        // (d4) Retired (pre-rotation) vault-wrapped content keys, retained so the
        //      owner can still decrypt pre-rotation attachments
        //      (RetainedContentKeyStore). Same per-account namespacing as (d3):
        //      wipe() drops only the in-memory cache.
        runCatching { retainedContentKeyStore.wipe() }
            .onFailure { Log.w(TAG, "retained content key store wipe failed", it) }

        // (c) Keystore-held AES keys — a session clear never reaches them.
        runCatching { keystoreEncryption.deleteAllKeys() }
            .onFailure { Log.w(TAG, "keystore deleteAllKeys failed", it) }

        // (c2) Account-scoped values in the EncryptedSharedPreferences session
        //      store that clearAllTables/clearAllKeys don't reach: the vault +
        //      private keys, and the decrypted notebook NAMES / share-recipient
        //      emails cached for display. clearAllTables only empties Room, and
        //      the account-switch path calls this wipe (not the full
        //      SessionManager.clear), so without this the prior account's
        //      notebook names would be served to the next account. Runs before
        //      the incoming session is persisted, so it clobbers nothing new.
        runCatching { sessionManager.clearAccountScopedData() }
            .onFailure { Log.w(TAG, "session account-scoped clear failed", it) }

        // (e) Decrypted attachment + camera temp files written to cacheDir.
        runCatching { purgeCacheFiles() }
            .onFailure { Log.w(TAG, "cache purge failed", it) }

        // (f) Persisted collab CRDT state (encrypted under per-note keys, but
        //     account-scoped) in filesDir/collab.
        runCatching { java.io.File(context.filesDir, "collab").deleteRecursively() }
            .onFailure { Log.w(TAG, "collab state purge failed", it) }

        // (f2) Opt-in local crash/exception logs (CrashReporter →
        //      filesDir/crash_logs). Off by default and PII-free by design, but
        //      swept on logout for hygiene — purgeCacheFiles only reaches
        //      cacheDir, so this filesDir subtree needs its own sweep.
        runCatching { java.io.File(context.filesDir, "crash_logs").deleteRecursively() }
            .onFailure { Log.w(TAG, "crash log purge failed", it) }

        // (g) The ongoing pinned-note notification (and reminders) render the
        //     decrypted title + a 100-char body preview drawn from the rows
        //     just cleared; their only other cancel path is per-note and
        //     unreachable once the row is gone. Cancel every app notification.
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
            .onFailure { Log.w(TAG, "notification cancelAll failed", it) }

        // (h0) The widget→noteId map (WidgetPreferences, "pingolin_note_widgets")
        //      binds each home-screen widget to a specific note id. Not decrypted
        //      content, but on an account switch those ids belong to the prior
        //      account — clear them so a widget can't re-bind to a stale id. Low
        //      urgency (the forced re-read below renders widgets blank), included
        //      for account-switch cleanliness.
        runCatching { WidgetPreferences.clearAll(context) }
            .onFailure { Log.w(TAG, "widget map clear failed", it) }

        // (h) Home-screen widgets cache decrypted note content and normally
        //     only refresh on a non-empty sync — force a re-read so they render
        //     the now-empty DB instead of the previous account's notes.
        runCatching { WidgetUpdateHelper.notifyDataChanged(context) }
            .onFailure { Log.w(TAG, "widget refresh failed", it) }

        // NOTE on (b): the SQLCipher passphrase (DatabasePassphraseManager,
        // "note_db_key") is deliberately RETAINED. It's a device-level key,
        // not account-linked, and useless once (a) has cleared the rows.
        // Clearing it WITHOUT deleting the on-disk note.db (encrypted under it)
        // would leave the next login unable to open the DB — and we can't
        // safely delete/close the file here: the @Singleton NoteDatabase
        // survives logout (same process; navigateToAuth uses CLEAR_TASK, not a
        // process restart), so a re-login would reuse a closed/stale handle.
    }

    private fun purgeCacheFiles() {
        context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
    }

    private companion object {
        private const val TAG = "LocalDataWiper"
    }
}
