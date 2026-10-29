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

package com.pingolin.note.ui.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import android.util.Base64
import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiErrors
import com.pingolin.note.data.remote.dto.KdfParamsRequest
import com.pingolin.note.data.remote.dto.SrpRegisterRequest
import com.pingolin.note.data.remote.dto.SrpStep1Request
import com.pingolin.note.data.remote.dto.SrpStep2Request
import com.pingolin.note.data.remote.dto.UnregisterFcmRequest
import com.pingolin.note.domain.repository.LocalDataWiper
import com.pingolin.note.domain.repository.SyncRepository
import com.pingolin.note.util.AccountIds
import com.pingolin.note.util.AttestationManager
import com.pingolin.note.util.AuthRateLimiter
import com.pingolin.note.util.NoteGenFloorStore
import com.pingolin.note.util.PendingPushUnregisterStore
import com.pingolin.note.util.PushNotificationManager
import com.pingolin.note.util.RateLimitResult
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import com.pingolin.note.util.SrpClient
import com.pingolin.note.util.SrpClientSession
import com.pingolin.note.util.UNREACHABLE_TEXT
import com.pingolin.note.util.plainText
import com.pingolin.note.worker.PushUnregisterWorker
import com.pingolin.note.worker.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.KdfParams
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val authApi: AuthApi,
    private val noteApi: NoteApi,
    private val sessionManager: SessionManager,
    private val pushNotificationManager: PushNotificationManager,
    private val cryptoManager: CryptoManager,
    private val rateLimiter: AuthRateLimiter,
    private val attestationManager: AttestationManager,
    private val localDataWiper: LocalDataWiper,
    private val syncRepository: SyncRepository,
    private val senderKeyPinStore: SenderKeyPinStore,
    private val noteGenFloorStore: NoteGenFloorStore,
    private val pendingPushUnregisterStore: PendingPushUnregisterStore,
    private val accountTrustTidy: com.pingolin.note.util.AccountTrustTidy,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // Polling job for the email-verification screen.
    private var verificationPollingJob: Job? = null

    fun isLoggedIn(): Boolean = sessionManager.isLoggedIn()

    /**
     * SRP login flow:
     * 1. Call srp/step1 with email to get salt + server public value
     * 2. Compute client proof using SRP-6a (password never leaves the device)
     * 3. Call srp/step2 with client proof to get tokens + encrypted keys
     * 4. Decrypt master key with password via CryptoManager, bootstrap vault
     */
    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = AuthUiState.Error("Please enter email and password")
            return
        }

        // Client-side brute-force gate. Server enforces its own limits but
        // checking first gives instant UI feedback and avoids spinning the
        // login button on a guaranteed-failure round trip.
        when (val rl = rateLimiter.checkAttempt(email)) {
            is RateLimitResult.RateLimited -> {
                val mins = rl.waitSeconds / 60
                val secs = rl.waitSeconds % 60
                val pretty = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                _uiState.value = AuthUiState.Error("Too many attempts. Try again in $pretty.")
                return
            }
            RateLimitResult.Allowed -> { /* fall through */ }
        }

        _uiState.value = AuthUiState.Loading

        viewModelScope.launch {
            try {
                // Cold-start retry: the auth backend may serve a stale 401
                // during the first request after a long idle period (load
                // balancer recycling SRP session state). One short retry
                // catches that without papering over real bad credentials —
                // a genuinely wrong password also fails on the retry.
                var step2BodyOrError = runSrpHandshake(email, password)
                if (step2BodyOrError is SrpOutcome.AuthFailed) {
                    delay(500)
                    step2BodyOrError = runSrpHandshake(email, password)
                }

                when (val outcome = step2BodyOrError) {
                    is SrpOutcome.AuthFailed -> {
                        rateLimiter.recordFailedAttempt(email)
                        val remaining = rateLimiter.getRemainingAttempts(email)
                        val msg = if (remaining in 1..2) {
                            "${outcome.message} ($remaining attempts left)"
                        } else outcome.message
                        _uiState.value = AuthUiState.Error(msg)
                        return@launch
                    }
                    is SrpOutcome.NetworkError -> {
                        _uiState.value = AuthUiState.Error(outcome.message)
                        return@launch
                    }
                    SrpOutcome.EmailNotVerified -> {
                        // The password was right: not a failed try, and never "wrong password".
                        _uiState.value = AuthUiState.Error(EMAIL_NOT_VERIFIED)
                        return@launch
                    }
                    SrpOutcome.ServerProofRejected -> {
                        // Not "wrong password": the
                        // password may be right. The same
                        // words as every other client.
                        rateLimiter.recordFailedAttempt(email)
                        _uiState.value = AuthUiState.Error(SERVER_PROOF_REJECTED)
                        return@launch
                    }
                    SrpOutcome.TwoFactorPasskeyOnly -> {
                        // The password was right (SRP
                        // proved it), but the only second
                        // step the account offers is a
                        // passkey, and this app can only
                        // take a 6-digit code. A code box
                        // here could never be answered; say
                        // what to do instead. Nothing is
                        // held: there is no challenge this
                        // screen can complete.
                        rateLimiter.recordSuccess(email)
                        _uiState.value = AuthUiState.TwoFactorPasskeyOnly
                    }
                    is SrpOutcome.TwoFactorRequired -> {
                        // The password is correct — SRP already proved it — so
                        // this counts as a success against the rate limiter.
                        rateLimiter.recordSuccess(email)
                        // Held in memory only. Needed after the code is accepted
                        // because Note's vault key is derived from it; a 2FA
                        // login that dropped it would sign in and leave every
                        // note unreadable.
                        pendingTwoFactorToken = outcome.twoFactorToken
                        pendingTwoFactorEmail = email
                        pendingTwoFactorPassword = password
                        _uiState.value = AuthUiState.TwoFactorRequired()
                    }
                    is SrpOutcome.Ok -> {
                        rateLimiter.recordSuccess(email)
                        bootstrapSession(email, password, outcome.step2Body)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                _uiState.value = AuthUiState.Error(e.plainText(COULD_NOT_SIGN_IN))
            }
        }
    }

    private suspend fun runSrpHandshake(email: String, password: String): SrpOutcome {
        val step1Response = authApi.srpStep1(SrpStep1Request(email))
        if (step1Response.code() == 401) return SrpOutcome.AuthFailed(INVALID_CREDENTIALS)
        if (!step1Response.isSuccessful || step1Response.body()?.data == null) {
            return SrpOutcome.NetworkError(ApiErrors.plainLine(step1Response, COULD_NOT_SIGN_IN))
        }
        val step1 = step1Response.body()!!.data!!

        val (session, clientResult) = withContext(Dispatchers.Default) {
            val session = SrpClient.createSession()
            session.step1(email, password, step1.bcryptSalt)
            session to session.step2(step1.salt, step1.serverPublic)
        }

        val step2Response = authApi.srpStep2(
            SrpStep2Request(
                sessionId = step1.sessionId,
                clientPublic = clientResult.clientPublic,
                clientProof = clientResult.clientProof,
            )
        )
        if (step2Response.code() == 401) return SrpOutcome.AuthFailed(INVALID_CREDENTIALS)
        if (!step2Response.isSuccessful || step2Response.body()?.data == null) {
            val code = ApiErrors.codeOf(step2Response)
            // The password was right, but the address was never verified (auth's 403
            // `email_not_verified`): say so.
            if (code == "email_not_verified") return SrpOutcome.EmailNotVerified
            // In this app's words, never the server's (as in the Track Android app): any
            // other 403 is the credentials; too many tries or a server in trouble say so.
            val line = if (step2Response.code() == 403) INVALID_CREDENTIALS
            else ApiErrors.plainLine(step2Response.code(), code, INVALID_CREDENTIALS)
            return SrpOutcome.NetworkError(line)
        }
        val step2 = step2Response.body()!!.data!!
        // Nothing in the reply is used — no tokens, no keys, no 2FA
        // token — unless the server proved it holds this account's
        // verifier.
        if (!serverProofMatches(clientResult.clientPublic, clientResult.clientProof,
                session.sessionKeyBase64(), step2.serverProof)) {
            return SrpOutcome.ServerProofRejected
        }
        if (step2.requires2fa == true) {
            val token = step2.twoFactorToken
                ?: return SrpOutcome.NetworkError(COULD_NOT_SIGN_IN)
            if (!canAnswerTwoFactor(step2.availableMethods)) return SrpOutcome.TwoFactorPasskeyOnly
            return SrpOutcome.TwoFactorRequired(token)
        }
        return SrpOutcome.Ok(step2)
    }

    /** Held between SRP step 2 and the code entry, in memory only and never in
     *  SecurePrefs. The password is here because Note's vault key derives from
     *  it; cleared on success, on cancellation and on any failure that ends the
     *  attempt. */
    private var pendingTwoFactorToken: String? = null
    private var pendingTwoFactorEmail: String? = null
    private var pendingTwoFactorPassword: String? = null

    /** Whether any part of a two-step attempt (its token, email or stashed
     *  password) is still held: false after every outcome that ends it. */
    @androidx.annotation.VisibleForTesting
    internal val holdsTwoFactorAttempt: Boolean
        get() = pendingTwoFactorToken != null || pendingTwoFactorEmail != null || pendingTwoFactorPassword != null

    /**
     * Exchange a typed code — an authenticator code or a backup code — for the
     * real session.
     *
     * Routes back through [bootstrapSession] rather than duplicating it, so an
     * account with two-factor on gets the identical vault-key derivation,
     * signing-key migration and session setup as one without it.
     *
     * A wrong code, one just used, or no answer at all leaves the attempt open,
     * and the code box comes back on it (the auth server keeps the attempt
     * until its 5th wrong code); anything else ends it, back to the password.
     */
    fun verifyTwoFactor(code: String) {
        val token = pendingTwoFactorToken
        val email = pendingTwoFactorEmail
        val password = pendingTwoFactorPassword
        if (token == null || email == null || password == null) {
            _uiState.value = AuthUiState.Error("That sign-in attempt timed out. Enter your password again.")
            clearTwoFactorChallenge()
            return
        }

        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val response = try {
                authApi.verify2fa(
                    com.pingolin.note.data.remote.dto.TwoFactorVerifyRequest(
                        twoFactorToken = token,
                        code = codeToSend(code),
                        method = "totp",
                    )
                )
            } catch (e: java.io.IOException) {
                // Not reached is not refused: the attempt is still open for a code.
                Log.w(TAG, "Code step not answered: ${e.javaClass.simpleName}")
                stayOnTheCode(token, UNREACHABLE_TEXT)
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "Code step failed: ${e.javaClass.simpleName}")
                clearTwoFactorChallenge()
                _uiState.value = AuthUiState.Error(e.plainText(TWO_STEP_ENDED))
                return@launch
            }
            val body = response.body()?.data
            if (!response.isSuccessful || body == null) {
                val errorCode = ApiErrors.codeOf(response)
                Log.w(TAG, "Code step refused: HTTP ${response.code()} ${errorCode.orEmpty()}")
                val refusal = twoStepRefusal(response.code(), errorCode)
                if (refusal.attemptStillOpen) {
                    stayOnTheCode(token, refusal.message)
                } else {
                    clearTwoFactorChallenge()
                    _uiState.value = AuthUiState.Error(refusal.message)
                }
                return@launch
            }

            // Accepted: the server has spent the attempt, whatever happens next.
            clearTwoFactorChallenge()
            try {
                // Re-shape into the step-2 body bootstrapSession already knows
                // how to consume. Both carry the same key material; server_proof
                // belongs to the handshake that has already completed and is
                // unused from here on.
                bootstrapSession(
                    email,
                    password,
                    com.pingolin.note.data.remote.dto.SrpStep2Response(
                        serverProof = "",
                        user = body.user,
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken,
                        kdfParams = body.kdfParams,
                        encryptedKeys = body.encryptedKeys,
                        signingPublicKey = body.signingPublicKey,
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Sign-in after the code step failed", e)
                _uiState.value = AuthUiState.Error(e.plainText(COULD_NOT_SIGN_IN))
            }
        }
    }

    /** The code box again, on the same attempt, saying [message]; unless the
     *  person backed out of the attempt meanwhile. */
    private fun stayOnTheCode(token: String, message: String) {
        _uiState.value = if (pendingTwoFactorToken == token) {
            AuthUiState.TwoFactorRequired(retryMessage = message)
        } else {
            AuthUiState.Idle
        }
    }

    /** Abandon a two-factor challenge — the user backed out of the code screen.
     *  Drops the exchange token and the stashed password. */
    fun cancelTwoFactorChallenge() {
        clearTwoFactorChallenge()
        _uiState.value = AuthUiState.Idle
    }

    private fun clearTwoFactorChallenge() {
        pendingTwoFactorToken = null
        pendingTwoFactorEmail = null
        pendingTwoFactorPassword = null
    }

    @androidx.annotation.VisibleForTesting
    internal suspend fun bootstrapSession(
        email: String,
        password: String,
        step2: com.pingolin.note.data.remote.dto.SrpStep2Response,
    ) {
        // Store tokens. Auth service returns access_token in the body;
        // refresh_token may be empty when refresh is cookie-based.
        val accessToken = step2.accessToken
        if (accessToken == null) {
            _uiState.value = AuthUiState.Error(COULD_NOT_SIGN_IN)
            return
        }

        // The account id is the server's word, taken only as a UUID
        // and kept canonical (AccountIds): a reply without one, or
        // with one that isn't a UUID, signs nobody in — before
        // anything is flushed, wiped or saved. (The auth server always
        // sends one, as a UUID.)
        val incomingUserId = AccountIds.canonical(step2.user?.id) ?: run {
            Log.w(TAG, "Sign-in: the reply names no account id that is a UUID; not signing in")
            _uiState.value = AuthUiState.Error(COULD_NOT_SIGN_IN)
            return
        }

        // The order is PROVE, then WIPE, then SAVE. This account's keys
        // are opened in memory first. Nothing of the previous account's
        // (rows, keys, pins, page storage, push row) is touched, and
        // nothing of this one's is saved, until they have opened and the
        // gate below has passed. Wiping the previous account's data first
        // would mean that, if the incoming keys then failed to open, the
        // session would be rolled back with both accounts' data gone.
        val opened = withContext(Dispatchers.Default) { openKeys(password, step2) }
        try {
            // A switch is any sign-in that isn't plainly the account this device
            // holds, compared canonically: a saved id that isn't a UUID, another
            // account's id, or no saved id while rows are held (whose rows
            // those are, nothing says). Only the same account (a transient 401 /
            // offline re-login) keeps local data: TokenAuthenticator's
            // clearAuthOnly keeps KEY_USER_ID and the vault key so offline-first
            // notes stay readable.
            val priorUserId = sessionManager.getUserId()
            val priorNotAnId = sessionManager.savedAccountIdInvalid()
            val sameAccount = !priorNotAnId && priorUserId == incomingUserId
            val accountSwitch = !sameAccount &&
                (priorNotAnId || priorUserId != null || syncRepository.hasAnyLocalRows())

            // The gate. A switch, or a first sign-in, needs this
            // account's keys opened HERE. Only the same account may
            // go on with the keys it already holds when a re-derive
            // fails (no keys, no session).
            if (opened == null && !(sameAccount && holdsKeys())) {
                Log.w(TAG, "Sign-in opened no vault or sharing key; nothing changed on this device")
                _uiState.value = AuthUiState.KeysUnavailable
                return
            }

            if (accountSwitch) {
                // Offline data-loss guard: the wipe below clears the departing account's
                // rows, INCLUDING any unsynced (is_dirty) offline edits. When we're
                // offline flushOutgoingBeforeAccountSwitch() can't push them first (its
                // isOnline() gate returns early), so they'd be destroyed silently — no
                // dialog warns on a switch the way logout does. Block the switch and keep
                // the prior account's data intact until the user reconnects so the flush
                // can run. (SRP itself needs the network, so this only trips on a drop
                // between the handshake and the wipe.)
                if (!isOnline() && syncRepository.hasUnsyncedChanges()) {
                    _uiState.value = AuthUiState.Error(
                        "You have unsynced notes on this device from another account. " +
                            "Reconnect to the internet so they can sync before signing in.",
                    )
                    return
                }
                // Tear down account A's server-side device row and local
                // push registration (the FCM/UnifiedPush lanes) BEFORE B's
                // tokens land, while A's token is still valid: else A's
                // shares/reminders keep dispatching to this device and
                // surface in B's session (the server keys device rows by
                // (user_id, device_id) and never rehomes them). Run it
                // CONCURRENTLY with the flush: they're independent
                // best-effort teardowns, each ~5s bounded, so racing them
                // keeps the login on AuthUiState.Loading for ~5s instead of
                // ~10s. coroutineScope awaits BOTH before we proceed, so
                // the flush still completes before the wipe and the
                // unregister still runs while A's token is valid.
                coroutineScope {
                    launch { runCatching { unregisterPushBeforeAccountSwitch() } }
                    launch { runCatching { flushOutgoingBeforeAccountSwitch() } }
                }
                // Post-flush residual-dirt guard: if unsynced edits remain AFTER the
                // best-effort flush (it timed out, hit an HTTP 402 quota error, or only
                // partially pushed), ABORT the switch rather than let wipeBlocking()
                // silently destroy them — gated on RESIDUAL DIRT, not connectivity (the
                // offline case is already caught above). A clean switch (nothing left
                // dirty) falls through to the wipe.
                if (syncRepository.hasUnsyncedChanges()) {
                    // The push teardown above may have armed a deferred delete of the
                    // departing account's server-side push row. We're aborting, so that
                    // account stays signed in: left armed, the record makes the worker
                    // delete the row that same account re-registers at startup, which
                    // silently kills its notifications. getUserId() is still the
                    // departing account here (the incoming session was never persisted),
                    // so disarming by current user targets the right record.
                    disarmDeferredUnregisterForCurrentUser()
                    _uiState.value = AuthUiState.Error(
                        "Couldn't sync all your notes from the previous account before " +
                            "switching. Reconnect and try again so nothing is lost.",
                    )
                    return
                }
                localDataWiper.wipeBlocking()
            }

            sessionManager.saveTokens(accessToken, step2.refreshToken ?: "")
            sessionManager.saveEmail(email)

            // The incoming account's id, canonical.
            sessionManager.saveUserId(incomingUserId)
            step2.user?.displayName?.let { sessionManager.saveDisplayName(it) }

            // Establish this account's namespace for the TOFU pin + rollback-floor
            // stores so their reads/writes hit the right per-account blob; a
            // same-account re-login re-adopts its retained floors/pins, whatever
            // spelling an older build kept them under.
            senderKeyPinStore.switchUser(incomingUserId)
            noteGenFloorStore.switchUser(incomingUserId)

            // Only now are this account's keys imported and saved. Off the Main
            // thread: the imports and the Keystore-backed writes are slow on
            // low-end devices.
            withContext(Dispatchers.Default) {
                if (opened != null) {
                    // A save that throws part-way is caught by
                    // the no-keys check below.
                    runCatching { persistOpenedKeys(opened, step2.signingPublicKey) }
                        .onFailure { Log.w(TAG, "Saving the opened keys failed: ${it.message}", it) }
                } else {
                    // The same account, whose re-derive failed: it goes on with
                    // the keys it already holds.
                    runCatching { cryptoManager.loadPersistedKeys() }
                }
            }

            // No keys, no session. The gate above already refused
            // a sign-in with nothing to go on; this catches a
            // save that didn't take. Drop the tokens just saved,
            // so a relaunch cannot resume a keyless session
            // (MainActivity routes on the token alone).
            if (!holdsKeys()) {
                Log.w(TAG, "Sign-in has no vault or sharing key; rolling the session back")
                sessionManager.clearAuthOnly()
                _uiState.value = AuthUiState.KeysUnavailable
                return
            }

            // A logout arms a deferred push-unregister. Logging back into the SAME
            // account on this device must disarm it, or the worker unregisters the
            // push row this session is about to re-register. A different-account
            // switch is a no-op here: that record targets the departed account and
            // the worker still has to run it.
            disarmDeferredUnregisterForCurrentUser()

            // The sign-in went through: other accounts' kept trust goes only when
            // their saved e-mail differs from the one typed here, and this account's
            // typed e-mail is saved. Both sign-in paths bring the typed address
            // (login, and the two-step code's stashed one).
            withContext(Dispatchers.Default) { accountTrustTidy.afterSignIn(incomingUserId, email) }

            _uiState.value = AuthUiState.Success
        } finally {
            // Whatever path this took, the opened keys' byte copies are zeroed:
            // the saved copies are the Keystore-backed ones.
            opened?.wipe()
        }
    }

    /** This device holds a vault key and a sharing key. */
    private fun holdsKeys(): Boolean =
        !sessionManager.getMasterKey().isNullOrEmpty() && !sessionManager.getSharingPrivateKey().isNullOrEmpty()

    /**
     * What this sign-in's password opened, in memory only: nothing is imported or saved until
     * the gate has passed and, on an account switch, the previous account's data is gone. The
     * byte arrays are zeroed by [wipe] on every path; the SDK hands the base64 forms back as
     * Strings, which the JVM can't zero, so those are only dropped.
     */
    @androidx.annotation.VisibleForTesting
    internal class OpenedKeys(
        val sharingPrivateKey: String,
        val sharingPrivateKeyBytes: ByteArray,
        /** The Ed25519 seed (base64), when the signing blob opened. */
        val signingSeed: String?,
        val signingSeedBytes: ByteArray?,
        val signingBlobPresent: Boolean,
        val vaultKey: String,
    ) {
        fun wipe() {
            sharingPrivateKeyBytes.fill(0)
            signingSeedBytes?.fill(0)
        }
    }

    /**
     * Open this account's keys with its password, in memory: the master key (Argon2id), the X25519
     * sharing key, the Ed25519 signing seed, and the note vault key derived from the sharing key.
     * Imports and saves nothing. Null when the reply carries no key blobs, or the master or the
     * sharing key doesn't open to a 32-byte key: a session can't read a note without them.
     */
    @androidx.annotation.VisibleForTesting
    internal fun openKeys(password: String, step2: com.pingolin.note.data.remote.dto.SrpStep2Response): OpenedKeys? {
        val kdfParams = step2.kdfParams ?: return null
        val encryptedKeys = step2.encryptedKeys ?: return null
        val encSharing = encryptedKeys.sharingPrivateKey ?: return null
        var sharingBytes: ByteArray? = null
        var seedBytes: ByteArray? = null
        try {
            val masterKey = cryptoManager.decryptWithPassword(
                EncryptedData(nonce = encryptedKeys.masterKey.nonce, ciphertext = encryptedKeys.masterKey.ciphertext),
                password,
                KdfParams(
                    salt = kdfParams.salt,
                    memoryKib = kdfParams.memoryKib.toUInt(),
                    iterations = kdfParams.iterations.toUInt(),
                    parallelism = kdfParams.parallelism.toUInt(),
                ),
            )
            val sharingPrivateKey = cryptoManager.decryptWithKey(
                EncryptedData(nonce = encSharing.nonce, ciphertext = encSharing.ciphertext),
                masterKey,
            )
            sharingBytes = java.util.Base64.getDecoder().decode(sharingPrivateKey)
            if (sharingBytes.size != 32) {
                Log.w(TAG, "The sharing key didn't open to a 32-byte key")
                sharingBytes.fill(0)
                return null
            }

            // Load the Ed25519 signing seed as well as the X25519 sharing key:
            // share/rotate wraps must be signed with the identity the directory
            // publishes as signing_public_key, and a signature made with the
            // sharing scalar never verifies against it. Login is the one place
            // we hold the master key, so this is where the seed is opened.
            var signingSeed: String? = null
            encryptedKeys.signingPrivateKey?.let { encSigning ->
                try {
                    val rawBytes = java.util.Base64.getDecoder().decode(
                        cryptoManager.decryptWithKey(
                            EncryptedData(nonce = encSigning.nonce, ciphertext = encSigning.ciphertext),
                            masterKey,
                        ),
                    )
                    // The blob is nominally the 32-byte seed, but some accounts'
                    // server rows store the FULL 64-byte secret (seed||pub).
                    // Everything downstream — the SDK import, the WebView key
                    // handoff — assumes a seed, and the handoff appends pub to
                    // what it is given, so a 64-byte secret would become a 96-byte
                    // key the web could not sign with. Normalize to the seed here.
                    seedBytes = if (rawBytes.size >= 64) rawBytes.copyOfRange(0, 32) else rawBytes.copyOf()
                    rawBytes.fill(0)
                    signingSeed = java.util.Base64.getEncoder().encodeToString(seedBytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't open the signing seed: ${e.message}")
                }
            }

            // Derive note vault key via BLAKE2b. Matches web:
            // sodium.crypto_generichash(32, privateKey, "pingolin-note-vault")
            val vaultKeyBytes = com.pingolin.note.util.Blake2b.hash(
                message = sharingBytes,
                key = "pingolin-note-vault".toByteArray(Charsets.UTF_8),
                outLen = 32,
            )
            val vaultKey = java.util.Base64.getEncoder().encodeToString(vaultKeyBytes)
            vaultKeyBytes.fill(0)
            return OpenedKeys(
                sharingPrivateKey = sharingPrivateKey,
                sharingPrivateKeyBytes = sharingBytes,
                signingSeed = signingSeed,
                signingSeedBytes = seedBytes,
                signingBlobPresent = encryptedKeys.signingPrivateKey != null,
                vaultKey = vaultKey,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Key bootstrap failed: ${e.message}", e)
            sharingBytes?.fill(0)
            seedBytes?.fill(0)
            return null
        }
    }

    /** Import and save what [openKeys] opened: only after the gate, and after an account switch's
     *  wipe, so none of it can be wiped or left behind by a sign-in that doesn't go through. */
    private fun persistOpenedKeys(keys: OpenedKeys, serverSigningPublicKey: String?) {
        cryptoManager.importPrivateKey(keys.sharingPrivateKey)
        sessionManager.saveSharingPrivateKey(keys.sharingPrivateKey)
        Log.d(TAG, "Private key imported + persisted")
        val importedSeed = keys.signingSeed?.let { seed ->
            runCatching {
                cryptoManager.importSigningPrivateKey(seed)
                sessionManager.saveSigningPrivateKey(seed)
            }.onFailure { Log.w(TAG, "Couldn't load signing seed: ${it.message}") }.isSuccess
        } == true
        // Signing-key check for legacy registrations that stored the
        // X25519 sharing key as the signing seed — decided from the keys
        // just opened, never from the server's signing_public_key.
        checkSigningKey(
            serverSigningPublicKey = serverSigningPublicKey,
            openedSeed = if (importedSeed) keys.signingSeedBytes else null,
            blobPresent = keys.signingBlobPresent,
            sharingPrivateKey = keys.sharingPrivateKeyBytes,
        )
        sessionManager.saveMasterKey(keys.vaultKey)
        Log.d(TAG, "Note vault key derived via BLAKE2b")
        cryptoManager.persistKeys()
        Log.d(TAG, "Keys persisted")
    }

    private sealed class SrpOutcome {
        data class Ok(val step2Body: com.pingolin.note.data.remote.dto.SrpStep2Response) : SrpOutcome()
        /** The account has two-factor enrolled: no session yet, only an
         *  exchange token for `auth/login/2fa`. */
        data class TwoFactorRequired(val twoFactorToken: String) : SrpOutcome()
        /** Two-factor is on, but no method this app can answer is offered. */
        data object TwoFactorPasskeyOnly : SrpOutcome()
        data class AuthFailed(val message: String) : SrpOutcome()
        data class NetworkError(val message: String) : SrpOutcome()
        /** The password was right, but the account's address was never
         *  verified (auth's 403 `email_not_verified`). */
        data object EmailNotVerified : SrpOutcome()
        /** The server's proof M2 did not check out. */
        data object ServerProofRejected : SrpOutcome()
    }

    /** Product of the off-Main registration key bootstrap: the assembled
     *  upload request plus the freshly-minted private keys the success path
     *  imports/persists after the server accepts the registration. */
    private data class RegistrationUpload(
        val request: SrpRegisterRequest,
        val sharingPrivateKeyB64: String,
        val signingSeedB64: String,
        val recoveryKey: String,
    )

    fun resetState() {
        _uiState.value = AuthUiState.Idle
        stopVerificationPolling()
    }

    // ===== Email verification =====

    /** Tap-the-link verification fired by AuthActivity's deep-link
     *  handler (note://verify-email?token=…). */
    fun verifyEmail(token: String) {
        if (token.isBlank()) {
            _uiState.value = AuthUiState.Error("Invalid verification token")
            return
        }
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            try {
                val resp = authApi.verifyEmail(com.pingolin.note.data.remote.dto.VerifyEmailRequest(token))
                if (resp.isSuccessful) {
                    stopVerificationPolling()
                    onEmailVerified()
                } else {
                    _uiState.value = AuthUiState.Error(ApiErrors.plainLine(resp, COULD_NOT_VERIFY_EMAIL))
                }
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.plainText(COULD_NOT_VERIFY_EMAIL))
            }
        }
    }

    /** "Resend verification email" button on the
     *  EmailVerificationRequired screen. Disables itself for 60s after
     *  a successful send to keep the user from spamming. */
    fun resendVerificationEmail() {
        viewModelScope.launch {
            val current = _uiState.value
            val email = (current as? AuthUiState.EmailVerificationRequired)?.email
                ?: sessionManager.getEmail() ?: return@launch
            _uiState.value = AuthUiState.Loading
            try {
                val resp = authApi.resendVerification()
                if (resp.isSuccessful) {
                    _uiState.value = AuthUiState.EmailVerificationRequired(email = email, canResend = false)
                    viewModelScope.launch {
                        delay(60_000)
                        val now = _uiState.value
                        if (now is AuthUiState.EmailVerificationRequired && !now.canResend) {
                            _uiState.value = now.copy(canResend = true)
                        }
                    }
                } else {
                    _uiState.value = AuthUiState.Error(ApiErrors.plainLine(resp, COULD_NOT_RESEND))
                }
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.plainText(COULD_NOT_RESEND))
            }
        }
    }

    private fun startVerificationPolling(email: String) {
        stopVerificationPolling()
        verificationPollingJob = viewModelScope.launch {
            while (true) {
                delay(5_000)
                try {
                    val resp = authApi.getVerificationStatus()
                    val verified = resp.body()?.data?.emailVerified == true
                    if (verified) {
                        stopVerificationPolling()
                        onEmailVerified()
                        break
                    }
                } catch (_: Exception) {
                    // Keep polling — transient network errors shouldn't kill the screen.
                }
            }
        }
    }

    private fun stopVerificationPolling() {
        verificationPollingJob?.cancel()
        verificationPollingJob = null
    }

    /**
     * Registration and the recovery key belong to the account portal, so
     * email verification simply completes.
     */
    private fun onEmailVerified() {
        _uiState.value = AuthUiState.EmailVerified
    }

    override fun onCleared() {
        super.onCleared()
        stopVerificationPolling()
    }

    /**
     * Check the account's signing key at sign-in. Nothing here replaces a key:
     * a legacy shape (the seed IS the X25519 sharing key) is only logged and
     * counted. Everything is decided from the keys this sign-in OPENED, never
     * from the server's unauthenticated `signing_public_key`:
     *   - a legacy shape → log and count, keep it;
     *   - a genuine opened seed the server's record disagrees with → log, keep;
     *   - no seed opened → nothing is minted, ever: every registration path
     *     creates a signing key (only the portal registers, and it always does;
     *     the server requires one at /srp/register).
     * Never throws: a failed check must not block login.
     */
    @androidx.annotation.VisibleForTesting
    internal fun checkSigningKey(
        serverSigningPublicKey: String?,
        openedSeed: ByteArray?,
        blobPresent: Boolean,
        sharingPrivateKey: ByteArray,
    ) {
        when (signingKeyAction(openedSeed, sharingPrivateKey)) {
            SigningKeyAction.LOG_LEGACY -> {
                val seen = countLegacySigningKey()
                Log.w(TAG, "Signing key has the legacy shape (seed is the sharing key); not replaced (seen ${seen}x)")
            }
            SigningKeyAction.KEEP -> when {
                openedSeed == null -> Log.w(
                    TAG,
                    "No signing key opened (blob ${if (blobPresent) "did not open" else "absent"}); none is minted",
                )
                !openedKeyMatchesServer(serverSigningPublicKey) -> Log.w(
                    TAG,
                    "Server's signing_public_key differs from the key this sign-in opened; keeping the opened key",
                )
                else -> Log.d(TAG, "Signing key in sync with server")
            }
        }
    }

    /** How many sign-ins on this install found the legacy shape (counted;
     * nothing replaces it). Returns the new count. */
    private fun countLegacySigningKey(): Int {
        val prefs = appContext.getSharedPreferences(SIGNING_KEY_PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getInt(LEGACY_SIGNING_KEY_SEEN, 0) + 1
        prefs.edit().putInt(LEGACY_SIGNING_KEY_SEEN, seen).apply()
        return seen
    }

    /** For the log only: does the server's record name the key just imported?
     *  Accepts the base64 or 64-hex forms the server has used. */
    private fun openedKeyMatchesServer(serverSigningPublicKey: String?): Boolean {
        val local = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull() ?: return false
        val raw = serverSigningPublicKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val serverHex = if (raw.length == 64 && raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            raw
        } else {
            runCatching { java.util.Base64.getDecoder().decode(raw).joinToString("") { "%02x".format(it) } }
                .getOrNull() ?: return false
        }
        return local.equals(serverHex, ignoreCase = true)
    }

    /**
     * Best-effort flush of the OUTGOING account's unsynced (is_dirty) edits before an
     * account-switch wipe destroys them. At the call sites the departing account's token,
     * user id and vault key are ALL still in SessionManager (the incoming session is
     * persisted only after the wipe), so this pushes the leaving account's own notes to
     * its own server. Online-gated + short-timeout-bounded so it can't hang the incoming
     * login; offline / failure falls through to the wipe. Unlike explicit logout there is
     * no dialog to warn the departing user, so this is their only safety net.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun flushOutgoingBeforeAccountSwitch() {
        if (!isOnline()) return
        runCatching { SyncWorker.cancelAllSync(appContext) }   // make the flush the sole pusher
        runCatching {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(ACCOUNT_SWITCH_FLUSH_TIMEOUT_MS) {
                    syncRepository.pushPendingChanges()
                }
            }
        }.onFailure { Log.w(TAG, "account-switch flush failed: ${it.message}") }
    }

    /**
     * Best-effort teardown of the OUTGOING account's FCM/UnifiedPush registration
     * before an account-switch wipe. The server keys device rows by (user_id,
     * device_id) and never rehomes them, so without this the departing account's row
     * keeps dispatching its shares/reminders to this device and they surface once the
     * incoming account is logged in (its isLoggedIn() gate passes). Runs while the
     * departing account's token + device id are still in SessionManager (the incoming
     * session is persisted only after the wipe). Online-gated network call +
     * timeout-bounded so it can't hang the incoming login; the LOCAL clear always runs
     * so the incoming account re-registers its own token instead of inheriting the
     * departing provider. Never throws into the caller.
     */
    private suspend fun unregisterPushBeforeAccountSwitch() {
        // Snapshot the departing account's push identity while its session is still
        // active (this runs BEFORE saveTokens persists the incoming account). Needed
        // by the deferred-delete fallback below, which authenticates as this account.
        val deviceId = sessionManager.getOrCreateDeviceId()
        val departingToken = sessionManager.getAccessToken()
        val departingUserId = sessionManager.getUserId()
        val pushLane = sessionManager.getPushType()
        val hadPushRegistration = pushLane != null || sessionManager.getPushToken() != null
        // Nothing registered → no server row to delete → treat as done.
        var serverUnregisterDone = !hadPushRegistration
        if (isOnline()) {
            runCatching {
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(ACCOUNT_SWITCH_FLUSH_TIMEOUT_MS) {
                        val resp = runCatching { noteApi.unregisterFcmDevice(UnregisterFcmRequest(deviceId)) }
                            .onFailure { Log.w(TAG, "server push unregister failed: ${it.message}") }
                            .getOrNull()
                        if (resp != null && (resp.isSuccessful || resp.code() == 404)) serverUnregisterDone = true
                        runCatching { pushNotificationManager.unregister() }
                            .onFailure { Log.w(TAG, "provider push unregister failed: ${it.message}") }
                    }
                }
            }.onFailure { Log.w(TAG, "account-switch push unregister failed: ${it.message}") }
        }
        // If the server-row delete didn't complete (offline / failed / timed out),
        // defer it: persist the DEPARTING account's {deviceId, token, lane} and replay
        // via PushUnregisterWorker on next connectivity — otherwise the incoming
        // account inherits A's live server-side push row (keyed by (user_id,
        // device_id), never rehomed) and receives A's tickles. The worker also forces
        // a token rotation once safe. Idempotent + self-clearing.
        if (!serverUnregisterDone && departingToken != null) {
            runCatching {
                pendingPushUnregisterStore.save(
                    deviceId, departingToken, pushLane ?: SessionManager.PUSH_TYPE_FCM, departingUserId,
                )
                PushUnregisterWorker.enqueue(appContext)
            }.onFailure { Log.w(TAG, "deferring account-switch push unregister failed: ${it.message}") }
        }
        // Always drop the LOCAL push registration (no network) so MainActivity's
        // autoDetectAndRegister re-registers a fresh token for the incoming account
        // instead of no-op'ing on the departing provider (currentProvider != NONE) —
        // covers the offline path and any timeout mid-teardown above.
        runCatching { sessionManager.clearPushSettings() }
            .onFailure { Log.w(TAG, "clearPushSettings failed: ${it.message}") }
        // Drop any in-flight register retry for the DEPARTING account. Its
        // backoff chain would otherwise wake up holding the incoming account's
        // session and re-register the wrong device row.
        runCatching { com.pingolin.note.worker.PushTokenSyncWorker.cancel(appContext) }
            .onFailure { Log.w(TAG, "cancelling push token sync failed: ${it.message}") }
    }

    /**
     * DISARM the deferred push-unregister when the account it targets is the one
     * now signed in on THIS device. Two callers, one invariant — at each, the id
     * in [SessionManager.getUserId] is exactly the account that will REMAIN
     * active here:
     *   - a successful login/bootstrap of the SAME account that armed the record
     *     (it logged out and came back — the account-switch wipe never fires for
     *     a matching id, so the record was never superseded); and
     *   - an ABORTED account switch (residual-dirt guard), where the departing
     *     account stays because we returned before persisting the incoming one.
     * In both cases the armed record would make [PushUnregisterWorker] delete the
     * returned/kept account's OWN fresh push row (re-registered at MainActivity
     * startup) with its still-valid token — and nothing heals it. So clear the
     * record AND cancel the queued unique work before it can run. A no-op when no
     * record is armed or it targets a different (genuinely departed) account.
     */
    private fun disarmDeferredUnregisterForCurrentUser() {
        val currentUserId = sessionManager.getUserId() ?: return
        val pending = pendingPushUnregisterStore.get() ?: return
        if (AccountIds.same(pending.userId, currentUserId)) {
            pendingPushUnregisterStore.clear()
            runCatching {
                WorkManager.getInstance(appContext).cancelUniqueWork(PushUnregisterWorker.TAG)
            }.onFailure { Log.w(TAG, "cancel deferred push-unregister failed: ${it.message}") }
        }
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private const val TAG = "AuthViewModel"

        /**
         * Whether a two-factor challenge offering [availableMethods] can be
         * answered here. This app has only a 6-digit code box, i.e. TOTP; a
         * challenge that offers only a passkey cannot be completed, so the code
         * box must not be shown for it. Null — an older server that did not
         * list the methods — falls back to asking for a code.
         */
        internal fun canAnswerTwoFactor(availableMethods: List<String>?): Boolean =
            availableMethods == null || availableMethods.any { it.equals("totp", ignoreCase = true) }

        /**
         * What sign-in does with the account's signing key — see
         * [checkSigningKey]. Pure, from the keys this sign-in opened; never a
         * replacement. [openedSeed] is the seed as opened (first 32 bytes of a
         * 64-byte secret).
         */
        internal fun signingKeyAction(
            openedSeed: ByteArray?,
            sharingPrivateKey: ByteArray,
        ): SigningKeyAction = when {
            openedSeed != null && constantTimeEquals(openedSeed, sharingPrivateKey) -> SigningKeyAction.LOG_LEGACY
            else -> SigningKeyAction.KEEP
        }

        /** Same length and bytes, in time that does not depend on where they differ. */
        internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }

        /** A sign-in that failed for a reason the person can't act on. */
        internal const val COULD_NOT_SIGN_IN = "Couldn't sign in. Please try again."

        /** The email link could not be checked (the Track Android
         * app's words).
         */
        internal const val COULD_NOT_VERIFY_EMAIL = "Couldn't verify your email just now."

        /** This app's existing line for a verification email that did not go out. */
        internal const val COULD_NOT_RESEND = "Couldn't resend verification email"

        /** Shown when the server's proof fails at sign-in: the words every
         * client uses, never "wrong password". */
        internal const val SERVER_PROOF_REJECTED = COULD_NOT_SIGN_IN

        /** A wrong password (or no such account: SRP can't tell them apart). */
        internal const val INVALID_CREDENTIALS = "Invalid email or password"

        /** A sign-in refused because the address was never verified
         * (auth's 403 `email_not_verified`): the same line as the Track
         * apps. */
        internal const val EMAIL_NOT_VERIFIED =
            "Please verify your email address first. Open the link we sent you, then sign in again."

        // The code step's refusals, in this app's words: the same lines as the Track
        // Android app.
        internal const val TWO_STEP_WRONG_CODE = "That code isn't right. Please try again."
        internal const val TWO_STEP_CODE_JUST_USED = "That code was just used. Wait for the next one and try again."
        internal const val TWO_STEP_TOO_MANY = "Too many wrong codes. Please wait a while, then sign in again."
        internal const val TWO_STEP_ENDED = "That sign-in has ended. Please sign in again."

        /**
         * What the code step's refusal says, and whether its attempt is still
         * open for another code (the auth server's replies on /login/2fa, as in
         * the Track Android app's twoStepRefusal). The server's own words are
         * for logs.
         */
        internal fun twoStepRefusal(status: Int, errorCode: String?): TwoStepRefusal = when {
            // "Invalid 2FA code": the attempt survives until its 5th.
            status == 401 -> TwoStepRefusal(TWO_STEP_WRONG_CODE, attemptStillOpen = true)
            // "Code already used. Please wait for a new code."
            errorCode == "totp_replay" -> TwoStepRefusal(TWO_STEP_CODE_JUST_USED, attemptStillOpen = true)
            // The attempt's 5th wrong code, or the account's limit.
            status == 429 || errorCode == "rate_limited" -> TwoStepRefusal(TWO_STEP_TOO_MANY, attemptStillOpen = false)
            // The code was right; the address was never verified.
            errorCode == "email_not_verified" -> TwoStepRefusal(EMAIL_NOT_VERIFIED, attemptStillOpen = false)
            // Any other 403 comes after the code was taken: the attempt is spent (as on iPhone).
            status == 403 -> TwoStepRefusal(TWO_STEP_ENDED, attemptStillOpen = false)
            // "Invalid or expired 2FA token": it expired, or was already used.
            status == 400 && errorCode == null -> TwoStepRefusal(TWO_STEP_ENDED, attemptStillOpen = false)
            else -> TwoStepRefusal(ApiErrors.plainLine(status, errorCode, TWO_STEP_ENDED), attemptStillOpen = false)
        }

        /**
         * What a typed code is sent as: the whole of it, with only the spaces
         * and dashes it was typed or pasted with taken out, as the account
         * portal sends it. An authenticator code is 6 digits and a backup code
         * 12 characters; nothing is cut short, and the case is left alone (auth
         * compares a backup code in upper case).
         */
        internal fun codeToSend(typed: String): String = typed.filterNot { it.isWhitespace() || it == '-' }

        /** A digits-only entry shorter than an authenticator code can't be
         *  right, so the box says so without spending one of the tries. */
        internal fun tooShortToTry(typed: String): Boolean =
            codeToSend(typed).let { sent -> sent.length < 6 && sent.all { it.isDigit() } }

        /** The same refusal at reauth (account deletion), where "sign in" does
         *  not fit. */
        internal const val REAUTH_SERVER_PROOF_REJECTED = "Couldn't check your password just now."

        /**
         * Did the server prove it holds this account's verifier? Checks the
         * SRP step-2 reply's `server_proof` (M2) for this attempt's A, M1 and
         * K, in the encoding this client's M1 used — padded, like [SrpClient]
         * — through the SDK, compared in constant time. A missing proof, or
         * an SDK refusal of our own values (a programming error), is a
         * failure, logged.
         */
        internal fun serverProofMatches(
            clientPublic: String,
            clientProof: String,
            sessionKey: String,
            serverProof: String?,
        ): Boolean {
            if (serverProof.isNullOrEmpty()) return false
            return try {
                uniffi.pingolin_mobile.srpVerifyServerProof(
                    clientPublic, clientProof, sessionKey, serverProof,
                    uniffi.pingolin_mobile.SrpProofEncoding.PADDED,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Server proof could not be checked: ${e.message}")
                false
            }
        }

        internal const val SIGNING_KEY_PREFS = "signing_key_check"
        internal const val LEGACY_SIGNING_KEY_SEEN = "legacy_shape_seen"

        /** Upper bound on the account-switch flush — tighter than logout's since it runs
         *  synchronously inside the incoming login and must not hang it. */
        private const val ACCOUNT_SWITCH_FLUSH_TIMEOUT_MS = 5_000L
    }
}

/** See [AuthViewModel.twoStepRefusal]. */
internal data class TwoStepRefusal(val message: String, val attemptStillOpen: Boolean)

/** See [AuthViewModel.signingKeyAction]. There is no action that replaces a
 * key. */
internal enum class SigningKeyAction {
    /** The opened seed is the sharing private key: log and count it, keep it. */
    LOG_LEGACY,
    /** Everything else, including a server record that disagrees with the opened key. */
    KEEP,
}

sealed class AuthUiState {
    data object Idle : AuthUiState()
    data object Loading : AuthUiState()
    data object Success : AuthUiState()
    data class Error(val message: String) : AuthUiState()

    /** SRP succeeded but the account has two-factor enrolled — AuthActivity
     * shows the code entry. [retryMessage]: why the last code was not taken,
     * when the attempt is still open for another. */
    data class TwoFactorRequired(val retryMessage: String? = null) : AuthUiState()

    /** Two-factor is on and its only method is a passkey, which this app cannot
     * use — AuthActivity explains instead of asking for a code. */
    data object TwoFactorPasskeyOnly : AuthUiState()

    /** The password was right but the account's keys could not be set up on
     *  this device; the session was rolled back. */
    data object KeysUnavailable : AuthUiState()

    /** Registration succeeded but the email isn't verified yet —
     *  AuthActivity shows the "Check your email" screen. */
    data class EmailVerificationRequired(
        val email: String,
        val canResend: Boolean = true,
    ) : AuthUiState()

    /** Email got verified (e.g. user verified an existing account
     * via a deep link on a new install). */
    data object EmailVerified : AuthUiState()


}
