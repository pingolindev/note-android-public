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

package com.pingolin.note.ui.settings

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiErrors
import com.pingolin.note.data.remote.dto.DeleteAccountRequest
import com.pingolin.note.data.remote.dto.SrpStep2Request
import com.pingolin.note.data.remote.dto.UpdateProfileRequest
import com.pingolin.note.domain.repository.LocalDataWiper
import com.pingolin.note.domain.repository.LogoutOrchestrator
import com.pingolin.note.domain.repository.NoteRepository
import com.pingolin.note.domain.repository.QuotaManager
import com.pingolin.note.domain.repository.SyncRepository
import com.pingolin.note.importer.BackupFormat
import com.pingolin.note.ui.auth.AuthViewModel
import com.pingolin.note.util.PushNotificationManager
import com.pingolin.note.util.SessionManager
import com.pingolin.note.util.SrpClient
import com.pingolin.note.util.plainText
import com.pingolin.note.worker.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val syncRepository: SyncRepository,
    private val authApi: AuthApi,
    private val noteApi: NoteApi,
    private val noteRepository: NoteRepository,
    private val quotaManager: QuotaManager,
    private val application: Application,
    private val localDataWiper: LocalDataWiper,
    private val logoutOrchestrator: LogoutOrchestrator,
    private val pushNotificationManager: PushNotificationManager,
    private val userMetadata: com.pingolin.note.domain.repository.UserMetadataStore,
    // Both only for naming attachments in a backup: the filenames are sealed in
    // each attachment's metadata under the note's content key.
    private val noteCrypto: com.pingolin.note.collab.NoteCrypto,
    private val retainedContentKeyStore: com.pingolin.note.util.RetainedContentKeyStore,
    private val accountTrustTidy: com.pingolin.note.util.AccountTrustTidy,
) : ViewModel() {

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _theme = MutableStateFlow(sessionManager.getTheme())
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    /**
     * Whether a guardian holds this account's password.
     *
     * Drives the explanatory card in Account. Starts false and is only ever set
     * from a response that actually parsed, for the same reason the recovery
     * address is: "we could not ask" must not render as a statement of fact
     * either way.
     */
    private val _guardianManaged = MutableStateFlow(false)
    val guardianManaged: StateFlow<Boolean> = _guardianManaged.asStateFlow()

    private val _subscriptionTier = MutableStateFlow("Free")
    val subscriptionTier: StateFlow<String> = _subscriptionTier.asStateFlow()

    private val _exportJson = MutableStateFlow<String?>(null)
    val exportJson: StateFlow<String?> = _exportJson.asStateFlow()

    private val _deletionState =
        MutableStateFlow<AccountDeletionState>(AccountDeletionState.Idle)
    val deletionState: StateFlow<AccountDeletionState> = _deletionState.asStateFlow()

    /** Recovery-address state for the Account recovery row, read from the same
     *  `/me` call that loads the display name. Starts Unknown and only ever
     *  moves when the server actually answers — see [loadProfile]. */
    private val _recoveryEmailStatus =
        MutableStateFlow<com.pingolin.note.util.AccountRecovery.Status>(
            com.pingolin.note.util.AccountRecovery.Status.Unknown
        )
    val recoveryEmailStatus: StateFlow<com.pingolin.note.util.AccountRecovery.Status> =
        _recoveryEmailStatus.asStateFlow()

    init {
        loadProfile()
        observeSubscriptionTier()
    }

    // ---- App Lock ----

    fun isAppLockEnabled(): Boolean = sessionManager.isAppLockEnabled()

    fun setAppLockEnabled(enabled: Boolean) {
        sessionManager.setAppLockEnabled(enabled)
        if (!enabled) {
            sessionManager.setForceAuthOnNextLaunch(false)
            sessionManager.clearBackgroundTime()
        }
    }

    fun getAutoLockTimeout(): Long = sessionManager.getAutoLockTimeout()

    fun setAutoLockTimeout(timeout: Long) {
        sessionManager.setAutoLockTimeout(timeout)
    }

    fun isInterpretMarkdownEnabled(): Boolean = sessionManager.isInterpretMarkdownEnabled()

    fun setInterpretMarkdown(enabled: Boolean) {
        sessionManager.setInterpretMarkdown(enabled)
    }

    fun isBlockScreenshotsEnabled(): Boolean = sessionManager.isBlockScreenshotsEnabled()

    fun setBlockScreenshots(blocked: Boolean) {
        sessionManager.setBlockScreenshots(blocked)
    }

    // ---- Editor Mode ----

    fun getEditorMode(): String = sessionManager.getEditorMode()

    fun setEditorMode(mode: String) {
        sessionManager.setEditorMode(mode)
    }

    /** How many notes are pinned AWAY from the current default. A pin that
     *  happens to equal the default changes nothing the user can see, so
     *  offering to reset it would be offering to fix nothing. */
    fun pinnedNoteCount(): Int {
        val global = sessionManager.getEditorMode()
        return userMetadata.pins().count { it.value != global }
    }

    /** Forget every per-note mode pin. A pin is only visible with its note
     *  open, so this is the one way back for a user who pinned a few and
     *  cannot remember which. */
    fun resetEditorModePins(onDone: () -> Unit) {
        viewModelScope.launch {
            userMetadata.clearPins()
            onDone()
        }
    }


    fun getEmail(): String = sessionManager.getEmail() ?: ""

    fun getAppVersion(): String {
        return try {
            val pInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun setTheme(theme: String) {
        sessionManager.setTheme(theme)
        _theme.value = theme
    }

    fun syncNow() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        viewModelScope.launch {
            try {
                syncRepository.sync()
            } catch (_: Exception) {
                // Handled by repository
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun logout() {
        // The orchestration lives in LogoutOrchestrator so the web shell's
        // sign-out scheme runs the identical flow; this ViewModel only
        // delegates.
        logoutOrchestrator.logout()
    }

    // ---- Account deletion ----

    /**
     * Permanently delete the account (GDPR Article 17).
     *
     * The account is the AUTH-service account, not a per-app one: the auth server
     * deletes the user row and then cascades into every app hanging off it —
     * Track AND Note — so this destroys the user's Track data as well as their
     * notes. The confirmation dialog says so before this is reached.
     *
     * Identity is proven by a zero-knowledge SRP step-up ([reauthenticate]); the
     * raw password never leaves the device. On success the local database, keys
     * and session are wiped, because the session it was signed in with is gone
     * server-side.
     *
     * SECURITY: [password] arrives as a CharArray and is cleared before return.
     *
     * @param forfeitCreditBalance the user's answer to the server's refusal when
     *   the account still holds credit it paid for. Never true on a first
     *   attempt: destroying somebody's money should take a deliberate act.
     */
    fun deleteAccount(password: CharArray, forfeitCreditBalance: Boolean = false) {
        if (_deletionState.value is AccountDeletionState.InProgress) return
        _deletionState.value = AccountDeletionState.InProgress
        // viewModelScope is safe here only because nothing navigates away until
        // Deleted is published, and the wipe is handed off to the wiper's own
        // application-scoped coroutine before that happens — see below.
        viewModelScope.launch {
            try {
                val reauthToken = reauthenticate(password)
                val response = authApi.deleteAccount(
                    DeleteAccountRequest(
                        reauthToken = reauthToken,
                        forfeitCreditBalance = if (forfeitCreditBalance) true else null,
                    )
                )
                val body = response.body()
                // A refusal's code is in its error body: body() is null on
                // auth's 409, so the credit question never came.
                val refusal = if (response.isSuccessful) body?.errorCode else ApiErrors.codeOf(response)
                if (refusal == "unspent_credit_balance") {
                    // A question, not a failure — nothing has been deleted. The
                    // balance is money the account paid for and it goes with the
                    // account, so the user answers before we retry. In this
                    // app's words: the server's sentence is never shown.
                    _deletionState.value = AccountDeletionState.UnspentCredit(UNSPENT_CREDIT_TEXT)
                    return@launch
                }
                if (!response.isSuccessful || body?.data?.deleted != true) {
                    _deletionState.value = AccountDeletionState.Failed(
                        ApiErrors.plainLine(response.code(), refusal, COULD_NOT_DELETE),
                    )
                    return@launch
                }

                val result = body.data
                Log.i(
                    "SettingsVM",
                    "Account deleted (subscriptions cancelled=${result.subscriptionsCanceled}, " +
                        "App Store remaining=${result.appStoreSubscriptionsRemaining}, " +
                        "failed=${result.subscriptionCancellationsFailed})",
                )

                // Hand the local teardown to the wiper's application-scoped
                // coroutine FIRST. wipe() is fire-and-forget so this costs
                // nothing, and publishing Deleted below lets the Fragment
                // navigate to AuthActivity — which cancels viewModelScope, and
                // would abort the hand-off if it were still pending.
                wipeAfterDeletion()
                // The billing notice rides on the state because once the account
                // is gone there is no screen left to carry it, and an App Store
                // subscription keeps charging until the customer cancels it.
                _deletionState.value = AccountDeletionState.Deleted(billingNotice(result))
            } catch (e: Exception) {
                Log.e("SettingsVM", "Account deletion failed", e)
                _deletionState.value = AccountDeletionState.Failed(e.plainText(COULD_NOT_DELETE))
            } finally {
                // Idempotent — reauthenticate() already clears it on both its
                // paths; this also covers a throw before it is reached.
                SrpClient.clearCharArray(password)
            }
        }
    }

    /**
     * Zero-knowledge SRP step-up against `/auth/reauth/srp/step1|step2`.
     *
     * Mirrors the login handshake, but the server identifies the account from
     * the session instead of an email, so step 1 is bodyless. A wrong password
     * fails proof verification server-side and comes back 401, which surfaces
     * here as "Invalid password".
     */
    private suspend fun reauthenticate(password: CharArray): String {
        try {
            // Each refusal in this app's words (a plain Exception says what it
            // says; the server's text is never shown).
            val email = sessionManager.getEmail()
                ?: throw Exception(AuthViewModel.REAUTH_SERVER_PROOF_REJECTED)

            val step1Response = authApi.reauthStep1()
            val step1 = step1Response.body()?.data
            if (!step1Response.isSuccessful || step1 == null) {
                throw Exception(ApiErrors.plainLine(step1Response, AuthViewModel.REAUTH_SERVER_PROOF_REJECTED))
            }

            // bcrypt preprocessing + the SRP modpow are CPU-bound; keep them off
            // the main thread exactly as the login handshake does. SrpClientSession
            // takes a String — same constraint AuthViewModel's login path lives
            // with — so the copy is made as late as possible and the caller's
            // CharArray is still cleared in the `finally` below.
            val (session, proof) = withContext(Dispatchers.Default) {
                val session = SrpClient.createSession()
                session.step1(email, String(password), step1.bcryptSalt)
                session to session.step2(step1.salt, step1.serverPublic)
            }

            val step2Response = authApi.reauthStep2(
                SrpStep2Request(
                    sessionId = step1.sessionId,
                    clientPublic = proof.clientPublic,
                    clientProof = proof.clientProof,
                )
            )
            val step2 = step2Response.body()?.data
            if (!step2Response.isSuccessful || step2 == null) {
                val refusal = ApiErrors.codeOf(step2Response)
                // A wrong password fails the proof server-side; its other 401, an
                // expired handshake, is not one.
                if (step2Response.code() == 401 && refusal == "invalid_credentials") throw Exception(WRONG_PASSWORD)
                throw Exception(ApiErrors.plainLine(step2Response.code(), refusal, AuthViewModel.REAUTH_SERVER_PROOF_REJECTED))
            }
            // The reauth token is used only if the server proved it
            // holds this account's verifier — never "Invalid
            // password" here.
            if (!com.pingolin.note.ui.auth.AuthViewModel.serverProofMatches(
                    proof.clientPublic, proof.clientProof, session.sessionKeyBase64(), step2.serverProof,
                )
            ) {
                throw Exception(AuthViewModel.REAUTH_SERVER_PROOF_REJECTED)
            }
            return step2.reauthToken
        } finally {
            SrpClient.clearCharArray(password)
        }
    }

    /**
     * The post-deletion local teardown.
     *
     * Deliberately NOT [logout]'s pre-wipe: there is no account left to flush
     * unsynced edits to, no session to revoke (the backend revoked every one
     * before deleting the user), and no push row to unregister — the note
     * server drops the account's push registrations along with its data. What
     * is left is the local side: drop this device's provider token so the OS
     * stops holding one for an account that is gone, then wipe.
     */
    private fun wipeAfterDeletion() {
        // The erased account's id, read before the wipe. A sign-out keeps
        // its namespaced trust (pins, floors, item owners, retained wraps)
        // for its next sign-in; an erasure removes every blob of that id,
        // in every store, with its e-mail record.
        val erased = sessionManager.getUserId()
        localDataWiper.wipe(
            preWipe = {
                runCatching { pushNotificationManager.unregister() }
                    .onFailure { Log.w("SettingsVM", "post-deletion push unregister failed", it) }
                erased?.let { accountTrustTidy.erase(it) }
                // The legacy signing-key counter this install kept goes too.
                runCatching {
                    application.getSharedPreferences(
                        com.pingolin.note.ui.auth.AuthViewModel.SIGNING_KEY_PREFS,
                        android.content.Context.MODE_PRIVATE,
                    ).edit().clear().commit()
                }.onFailure { Log.w("SettingsVM", "post-deletion signing-key counter clear failed", it) }
            },
            onComplete = { sessionManager.clear() },
        )
    }

    /**
     * What the user still has to do about money, or null when nothing is left.
     *
     * Keys off `billingNeedsAttention` rather than re-deriving it from the
     * counts, so this client cannot disagree with the others about when to
     * speak up.
     *
     * What to tell somebody about their money after their account is gone.
     *
     * The two counters are INDEPENDENT and can both be non-zero: subscriptions
     * are unique per (user, product), so one person can hold a Note
     * subscription bought through the App Store and a Track one billed by
     * Paddle. If the Paddle cancellation then fails, the server reports
     * `appStoreSubscriptionsRemaining = 1` AND
     * `subscriptionCancellationsFailed = 1`.
     *
     * Both parts are reported. With an either/or, somebody in that position
     * could cancel with Apple, believe they were done, and go on being billed
     * by the provider nobody mentioned — with no account left to find out
     * from.
     */
    private fun billingNotice(
        result: com.pingolin.note.data.remote.dto.DeleteAccountResponse,
    ): String? {
        // Wider than billingNeedsAttention on purpose: an erasure that queued
        // while the billing was clean would produce no notice at all, so the
        // one thing the user actually asked for would be the one thing nobody
        // mentioned.
        if (!result.billingNeedsAttention && erasureNoticeText(result.dataErasure) == null) return null
        return deletionNoticeText(
            appStoreRemaining = result.appStoreSubscriptionsRemaining,
            cancellationsFailed = result.subscriptionCancellationsFailed,
            dataErasure = result.dataErasure,
        )
    }

    private fun observeSubscriptionTier() {
        // Source the badge from the SAME note entitlement the Plan card
        // uses (GET /api/v1/quota → tier == "paid"), NOT "any active
        // auth subscription": a check over getSubscriptions() ignores
        // product_slug, so a Paddle sub for a different product (or
        // Track) would light this badge "Premium" while the
        // note-accurate Plan card shows Free. quota is null until the
        // first /quota lands → default "Free" (matches renderPlanCard's
        // conservative pre-fetch default).
        quotaManager.refresh()
        viewModelScope.launch {
            quotaManager.quota.collect { q ->
                _subscriptionTier.value =
                    if (q?.tier == QuotaManager.TIER_PAID) "Premium" else "Free"
            }
        }
    }

    /**
     * Re-read the profile. Safe to call on every resume, and worth it: the
     * recovery address can ONLY be changed in the web portal, so the common
     * path is coming straight back from having added one.
     */
    fun refreshProfile() = loadProfile()

    private fun loadProfile() {
        // First load cached display name from session
        val cached = sessionManager.getDisplayName()
        if (!cached.isNullOrEmpty()) {
            _displayName.value = cached
        }
        // Then refresh from auth service
        viewModelScope.launch {
            try {
                val response = authApi.getCurrentUser()
                if (response.isSuccessful) {
                    val user = response.body()?.data
                    val name = user?.displayName ?: ""
                    _displayName.value = name
                    if (name.isNotEmpty()) {
                        sessionManager.saveDisplayName(name)
                    }
                    // Same response, no extra request. Only written when the
                    // body actually parsed: a null user here means the answer
                    // didn't arrive, and "we couldn't ask" must never be
                    // rendered as "you have no recovery address".
                    if (user != null) {
                        _guardianManaged.value = user.guardianManaged
                        _recoveryEmailStatus.value =
                            com.pingolin.note.util.AccountRecovery.statusOf(
                                user.recoveryEmail, user.recoveryEmailVerified,
                            )
                    }
                }
            } catch (e: Exception) {
                Log.e("SettingsVM", "Failed to load profile", e)
            }
        }
    }

    fun saveDisplayName(name: String) {
        viewModelScope.launch {
            try {
                val response = authApi.updateDisplayName(UpdateProfileRequest(displayName = name))
                if (response.isSuccessful) {
                    _displayName.value = name
                    sessionManager.saveDisplayName(name)
                }
            } catch (e: Exception) {
                Log.e("SettingsVM", "Failed to save display name", e)
            }
        }
    }

    /**
     * Write a backup this app — and the web, and any other client — can read
     * back. See [BackupFormat].
     *
     * This writes the shared envelope: the whole note, plus the notebooks,
     * label colours and editor pins that live in the metadata blob rather than
     * in any note.
     */
    fun exportNotes(exportPassphrase: String? = null) {
        viewModelScope.launch {
            try {
                // The ENTIRE build runs off Main.
                // viewModelScope is Main.immediate, and the
                // build is heavy: a JSONObject parse of every
                // note plus a pretty-print of the whole
                // library in BackupFormat.build, a per-note
                // unwrap+decrypt loop in attachmentManifest,
                // and sealBackupFile, which is Argon2id over
                // 64MiB at 3 iterations (435–458ms measured).
                // On the UI thread, with a real library, the
                // sum is an ANR, not a stutter. Only the
                // final StateFlow assignment returns to Main.
                _exportJson.value = withContext(Dispatchers.Default) {
                    val notes = noteRepository.getAllNotes()
                    // Null when the blob can't be read (no key yet, or never
                    // written). The builder records that as an explicit reason
                    // the backup is incomplete rather than quietly omitting
                    // notebooks.
                    val metadata = runCatching {
                        userMetadata.read()?.let { JSONObject(it) }
                    }.getOrNull()
                    val attachmentNames = attachmentManifest(notes)
                    val body = BackupFormat.build(notes, metadata, attachmentNames).toString(2)
                    // Sealing is OPT-IN: a plaintext export is what makes
                    // portability work, and a passphrase is what turns it into
                    // a backup worth keeping. The SDK owns the format so every
                    // client writes the same container.
                    if (exportPassphrase.isNullOrEmpty()) {
                        body
                    } else {
                        uniffi.pingolin_mobile.sealBackupFile(
                            body,
                            exportPassphrase,
                            "pingolin-note",
                            java.time.Instant.now().toString(),
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("SettingsVM", "Export failed", e)
                _exportJson.value = null
            }
        }
    }

    fun clearExport() {
        _exportJson.value = null
    }

    /**
     * Name the files a backup cannot carry, per note.
     *
     * The bytes stay out (object storage, 100MB a user on the free tier alone),
     * but "3 notes lost their attachments" is not enough to act on — the point
     * of the manifest is to say WHAT to go and find. Filenames live in each
     * attachment's `encrypted_meta`, sealed under the note's content key, so
     * this costs a request plus a decrypt per note that has any.
     *
     * Best-effort throughout: a note whose list won't load maps to null ("could
     * not read"), which the format keeps distinct from an empty list. A backup
     * missing a filename is a far smaller loss than no backup.
     */
    private suspend fun attachmentManifest(
        notes: List<com.pingolin.note.data.local.entity.ItemEntity>,
    ): Map<String, List<String>?> {
        val vault = sessionManager.getMasterKey()?.let {
            runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()
        }?.takeIf { it.size == 32 } ?: return emptyMap()

        val out = mutableMapOf<String, List<String>?>()
        for (item in notes) {
            val content = item.decryptedContent
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: continue
            if (!content.optBoolean("has_attachments", false)) continue

            out[item.id] = runCatching {
                val resp = noteApi.listAttachments(item.id)
                val list = resp.body()?.data
                if (!resp.isSuccessful || list == null) return@runCatching null

                // Same key order the editor uses: the note's own content key,
                // then retained pre-rotation keys (a blob sealed before a re-key
                // still has its metadata under the old one, and the owner can
                // always open their own), then the vault key for legacy notes
                // that predate per-note content keys.
                val keys = buildList {
                    val w = item.wrappedContentKey
                    val n = item.wrappedContentKeyNonce
                    if (w != null && n != null) {
                        noteCrypto.unwrapWithVault(w, n, vault)?.let { add(it) }
                    }
                    for (rk in retainedContentKeyStore.retained(item.id)) {
                        noteCrypto.unwrapWithVault(rk.wrap, rk.nonce, vault)?.let { add(it) }
                    }
                    add(vault)
                }

                list.mapNotNull { att ->
                    val metaJson = keys.firstNotNullOfOrNull { k ->
                        runCatching {
                            noteCrypto.decryptNoteWithContentKeyV2(att.metaNonce, att.encryptedMeta, k, item.id)
                        }.getOrNull()
                    } ?: return@mapNotNull null
                    runCatching { JSONObject(metaJson).optString("filename").takeIf { it.isNotEmpty() } }
                        .getOrNull()
                }
            }.getOrNull()
        }
        return out
    }

    companion object {
        /** The unspent-credit question, in this app's words (the same as the
         * Track Android app's and the iPhone's): the server's sentence is
         * never shown. */
        internal const val UNSPENT_CREDIT_TEXT = "Deleting your account destroys the credit on it, and it can't be " +
            "returned afterwards because the record goes with the account. Write to help@support.pingolin.com " +
            "to have it refunded first, or delete anyway."

        /** A deletion refused for a reason the person can't act on (the Track Android
         * app's words).
         */
        internal const val COULD_NOT_DELETE = "Couldn't delete your account just now."

        /** The password typed to confirm the deletion was refused. */
        internal const val WRONG_PASSWORD = "Invalid password"
    }
}

/** Progress of an account deletion, observed by SettingsFragment. */
sealed class AccountDeletionState {
    data object Idle : AccountDeletionState()
    data object InProgress : AccountDeletionState()

    /**
     * The server refused because the account still holds bought credit. Nothing
     * has been deleted; the user answers and the caller retries.
     */
    data class UnspentCredit(val message: String) : AccountDeletionState()

    /**
     * The account is gone. [billingNotice] is what the user still has to do
     * about money themselves (an App Store subscription we cannot stop, or a
     * cancellation that failed), or null when nothing is outstanding.
     */
    data class Deleted(val billingNotice: String?) : AccountDeletionState()

    data class Failed(val message: String) : AccountDeletionState()
}

/**
 * Body of the post-deletion billing notice. Top-level, and taking the two
 * counts rather than the DTO, so it can be exercised without standing up a
 * ViewModel or a live deletion.
 */
internal fun deletionNoticeText(
    appStoreRemaining: Int,
    cancellationsFailed: Int,
    dataErasure: String = "completed",
): String {
    val appStore = appStoreRemaining
    val failed = cancellationsFailed
    val parts = mutableListOf<String>()

    if (appStore > 0) {
        parts += if (appStore == 1) {
            "Your App Store subscription is still running. Apple will keep charging you " +
                "until you cancel it yourself: open Settings, tap your name, then " +
                "Subscriptions."
        } else {
            "Your $appStore App Store subscriptions are still running. Apple will keep " +
                "charging you until you cancel them yourself: open Settings, tap your " +
                "name, then Subscriptions."
        }
    }

    if (failed > 0) {
        parts += if (appStore > 0) {
            // Say plainly that this is a DIFFERENT subscription, or it reads
            // as a restatement of the Apple one and gets ignored.
            "Separately, we could not cancel a subscription that is not billed through " +
                "the App Store, so you may still be charged for that one too. Write to " +
                "help@support.pingolin.com and we will stop it."
        } else {
            "We could not cancel your subscription, so you may still be charged. Write " +
                "to help@support.pingolin.com and we will stop it."
        }
    }

    erasureNoticeText(dataErasure)?.let { parts += it }

    // Unreachable while needsAttention is (appStore > 0 || failed > 0), but
    // if the server ever widens that flag, say something rather than
    // showing a bare "Your account is deleted, but".
    if (parts.isEmpty()) {
        return "Your account is deleted. Your subscription may need attention — write to " +
            "help@support.pingolin.com and we will check it."
    }
    return "Your account is deleted.\n\n" + parts.joinToString("\n\n")
}

/**
 * What to say about the erasure of the note and location data, or null when
 * there is nothing to say.
 *
 * A queued erasure is not a failure — it is retried until it goes through — but
 * somebody who asked to be erased is entitled to know it has not finished yet.
 */
internal fun erasureNoticeText(dataErasure: String): String? = when (dataErasure) {
    "queued" ->
        "Your notes and locations could not be erased at that moment — one of the services " +
            "was unreachable. The erasure is recorded and will be retried until it goes " +
            "through. If you want to know that it has, write to privacy@mail.pingolin.com."
    "failed" ->
        "We could not record the erasure of your notes and locations, so it may not have " +
            "happened. Write to privacy@mail.pingolin.com and we will complete it."
    else -> null
}
