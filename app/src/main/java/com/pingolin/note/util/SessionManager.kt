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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages auth tokens and user session state.
 * All data stored in EncryptedSharedPreferences (AES-256-GCM + AES-256-SIV).
 */
class SessionManager internal constructor(
    /** The store's file. Tests pass plain preferences: EncryptedSharedPreferences needs the
     *  Android Keystore, which Robolectric doesn't have. */
    private val prefs: SharedPreferences,
    /** Runs when the session dies ([clearAuthOnly]). Tests pass their own. */
    private val onSessionEnded: () -> Unit = {},
) {

    /** The app's: the session's end refreshes the home-screen widgets, so none keeps showing a note. */
    constructor(context: Context) : this(SecurePrefs.open(context, "note_session"), widgetsRefresh(context.applicationContext))

    /** Single source of truth for "is the user authenticated right now."
     *  MainActivity observes this so a mid-session session wipe (e.g.,
     *  token refresh failure in [TokenAuthenticator]) bounces the user
     *  to AuthActivity instead of leaving them on a half-broken
     *  screen. Initial value reflects whatever was on disk at construct
     *  time — SessionManager is a @Singleton so all callers share this
     *  flow. */
    private val _isLoggedInFlow = MutableStateFlow(prefs.getString(KEY_ACCESS_TOKEN, null) != null)
    val isLoggedInFlow: StateFlow<Boolean> = _isLoggedInFlow.asStateFlow()

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)
    /**
     * This account's id, canonical ([AccountIds]): one saved in another spelling is written back
     * canonical at its first read. A saved id that isn't a UUID is no account (null): the launch
     * ends the session ([savedAccountIdInvalid]) and the next sign-in treats it as another
     * account's.
     */
    fun getUserId(): String? {
        val saved = prefs.getString(KEY_USER_ID, null) ?: return null
        val canonical = AccountIds.canonical(saved) ?: return null
        if (canonical != saved) runCatching { prefs.edit().putString(KEY_USER_ID, canonical).apply() }
        return canonical
    }

    /** A saved account id that isn't a UUID: MainActivity's launch ends the session, and the next
     *  sign-in counts it as another account. */
    fun savedAccountIdInvalid(): Boolean =
        prefs.getString(KEY_USER_ID, null)?.let { AccountIds.canonical(it) == null } ?: false

    fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
        _isLoggedInFlow.value = true
    }

    /** Saved canonical; an id that isn't a UUID throws [AccountIds.NotAnAccountId] and is never
     *  saved (the sign-in refuses it before it gets here). */
    fun saveUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, AccountIds.parse(userId)).apply()
    }

    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun saveEmail(email: String) {
        prefs.edit().putString(KEY_EMAIL, email).apply()
    }

    fun getDisplayName(): String? = prefs.getString(KEY_DISPLAY_NAME, null)

    fun saveDisplayName(name: String) {
        prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    /** Web-format notebook metadata (JSON array of {id, name} objects) */
    fun getWebNotebooks(): String? = prefs.getString(KEY_WEB_NOTEBOOKS, null)

    fun saveWebNotebooks(json: String) {
        prefs.edit().putString(KEY_WEB_NOTEBOOKS, json).apply()
    }

    /**
     * In-memory sign-out latch. The logout orchestration deliberately clears
     * the prefs only AFTER the wipe (the pre-wipe flush still needs the
     * token), so for several seconds a signed-out user still LOOKS logged in
     * — and AuthActivity's logged-in auto-forward would bounce them through
     * the router straight back into a half-wiped web home. Set synchronously
     * at the top of LogoutOrchestrator.logout(); reset by [clear] (and by
     * process death, when the tokens' absence answers instead).
     */
    @Volatile
    private var signingOut = false

    fun beginSignOut() {
        signingOut = true
    }

    fun isLoggedIn(): Boolean = !signingOut && getAccessToken() != null

    fun getTheme(): String {
        return prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    fun setTheme(theme: String) {
        prefs.edit().putString(KEY_THEME, theme).apply()
    }

    fun saveMasterKey(key: String) {
        prefs.edit().putString(KEY_MASTER_KEY, key).apply()
    }

    fun getMasterKey(): String? = prefs.getString(KEY_MASTER_KEY, null)

    /** Base64-encoded 32-byte X25519 private scalar. Used by the sharing
     *  flow to seal content keys for recipients and unwrap seals
     *  addressed to us. Written at login alongside the vault key. */
    fun saveSharingPrivateKey(keyB64: String) {
        prefs.edit().putString(KEY_SHARING_PRIVATE_KEY, keyB64).apply()
    }

    fun getSharingPrivateKey(): String? = prefs.getString(KEY_SHARING_PRIVATE_KEY, null)

    /** Base64-encoded 32-byte Ed25519 signing seed (the RFC-8032 seed, NOT
     *  the X25519 sharing scalar). This is the identity that wrap
     *  signatures are made with and whose verifying key the directory
     *  publishes as `signing_public_key`. Decrypted from
     *  `encrypted_signing_private_key` at login (or freshly generated by
     *  the regenerate path). Persisted so the share/rotate flow can sign
     *  without re-deriving. Null until a signing key is provisioned. */
    fun saveSigningPrivateKey(seedB64: String) {
        prefs.edit().putString(KEY_SIGNING_PRIVATE_KEY, seedB64).apply()
    }

    fun getSigningPrivateKey(): String? = prefs.getString(KEY_SIGNING_PRIVATE_KEY, null)

    /** JSON map {notebookId: [{email, permission, publicKey}...]} from the
     *  web-shared encrypted metadata item. Used by the Android auto-share
     *  fan-out when a new note lands in a shared notebook. */
    fun saveNotebookShares(json: String) {
        prefs.edit().putString(KEY_NOTEBOOK_SHARES, json).apply()
    }

    fun getNotebookShares(): String? = prefs.getString(KEY_NOTEBOOK_SHARES, null)

    /** noteId -> pinned editor mode, mirrored out of the user-metadata blob so
     *  the editor can resolve a note's mode without decrypting it on open. */
    fun saveEditorModes(json: String) {
        prefs.edit().putString(KEY_EDITOR_MODES, json).apply()
    }

    fun getEditorModes(): String? = prefs.getString(KEY_EDITOR_MODES, null)

    /** Full session wipe — manual sign-out path. Removes everything,
     *  including identity (email, display name) and user preferences
     *  (theme, app-lock settings, editor mode). */
    fun clear() {
        prefs.edit().clear().apply()
        signingOut = false
        _isLoggedInFlow.value = false
    }

    /** Auth-credentials-only wipe — used when a token refresh fails so
     *  the user has to re-authenticate, but their identity, preferences,
     *  and cached crypto material survive. Without this split, a
     *  transient refresh failure would erase the email and display name
     *  the Settings screen reads from prefs and leave the UI looking
     *  half-broken. */
    fun clearAuthOnly() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_PUSH_TOKEN)
            .remove(KEY_PUSH_TOKEN_SYNCED)
            .apply()
        _isLoggedInFlow.value = false
        // The session just died (refresh failed, or revoked from another
        // device), and the retained WebView is still holding a BOOTED,
        // authenticated document: the last note's decrypted body in the DOM
        // and the vault key in its session storage. "Sign out all devices"
        // must mean this device's resident plaintext too, so throw the
        // document away here — this is the one choke point every
        // dead-session path goes through. Full sign-out and account switch
        // go through LocalDataWiper, which destroys it separately.
        // discard() touches the WebView and must run on the main thread;
        // this is called from OkHttp's authenticator thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            com.pingolin.note.ui.web.RetainedWebView.discard()
        }
        // And the home-screen widgets drop what they show: every dead-session path is
        // here, the 401 included. A full sign-out refreshes them through LocalDataWiper.
        onSessionEnded()
    }

    /** Account-switch wipe — removes every value scoped to the signed-out person so
     * the NEXT account on a shared device inherits none of it. Called from
     * [com.pingolin.note.domain.repository.LocalDataWiper]'s wipeBlocking on the
     * account-switch path (a DIFFERENT user logging in), which runs BEFORE the
     * incoming session's tokens/identity/keys are persisted — so nothing of the new
     * account is clobbered.
     *
     * Deliberately narrower than [clear]: it keeps device-level preferences (theme,
     * app-lock, editor mode, notification detail, the per-install [KEY_DEVICE_ID])
     * that aren't tied to a person, and it does NOT touch the access/refresh/push
     * tokens ([clearAuthOnly] / [clearPushSettings] own those) nor [KEY_EMAIL]
     * (which bootstrapSession overwrites unconditionally) nor [KEY_USER_ID] (which
     * bootstrapSession overwrites with the incoming id, or CLEARS on the
     * fail-closed no-user path) on the very next lines.
     *
     * What it DOES remove is everything that would otherwise render or leak the
     * prior account on the next launch:
     *   - notebook names ([KEY_WEB_NOTEBOOKS]), the share-recipient emails
     *     ([KEY_NOTEBOOK_SHARES]) and the per-note editor-mode pins
     *     ([KEY_EDITOR_MODES]) — note ids are not scoped to an account, so a
     *     leftover pin would silently apply to whatever the incoming account's note
     *     with that id happens to be;
     *   - share-inbox membership ([KEY_INBOX_SHARE_IDS]), the shared notebooks seen
     *     accepted ([KEY_SEEN_ACCEPTED_NOTEBOOKS]), the ones last found on offer
     *     ([KEY_OFFERED_NOTEBOOKS]) and the notes held for an invitation
     *     ([KEY_HELD_FOR_AN_INVITATION]);
     *   - the display name ([KEY_DISPLAY_NAME]) — only *conditionally* re-written
     *     at login, so an incoming account with no display name would otherwise
     *     keep showing the previous person's;
     *   - the account crypto material ([KEY_MASTER_KEY] vault key,
     *     [KEY_SHARING_PRIVATE_KEY], [KEY_SIGNING_PRIVATE_KEY]) — re-derived and
     *     re-persisted for the incoming account during key bootstrap. */
    fun clearAccountScopedData() {
        prefs.edit()
            .remove(KEY_WEB_NOTEBOOKS)
            .remove(KEY_NOTEBOOK_SHARES)
            .remove(KEY_EDITOR_MODES)
            .remove(KEY_INBOX_SHARE_IDS)
            .remove(KEY_SEEN_ACCEPTED_NOTEBOOKS)
            .remove(KEY_OFFERED_NOTEBOOKS)
            .remove(KEY_OFFERS_KNOWN_AS_OF)
            .remove(KEY_HELD_FOR_AN_INVITATION)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_MASTER_KEY)
            .remove(KEY_SHARING_PRIVATE_KEY)
            .remove(KEY_SIGNING_PRIVATE_KEY)
            // The notification gates are the account's, filled
            // from the server (NotificationPrefsSync): the next
            // account reads its own.
            .remove(KEY_NOTIF_REMINDERS_PUSH)
            .remove(KEY_NOTIF_SHARES_PUSH)
            .remove(KEY_NOTIF_EMAIL_ENABLED)
            .remove(KEY_NOTIF_REMINDERS_EMAIL)
            .remove(KEY_NOTIF_SHARES_EMAIL)
            .apply()
    }

    /** Item ids present in the shares inbox at the last refresh. Persisted
     *  (not just in-memory) so an un-share/owner-delete that happens while
     *  the app is closed is still detected on the next launch: the
     *  inbox-membership diff drives removal of stale materialized shared
     *  rows from Room. */
    fun setInboxShareIds(ids: Set<String>) {
        prefs.edit().putString(KEY_INBOX_SHARE_IDS, ids.joinToString(",")).apply()
    }

    fun getInboxShareIds(): Set<String> {
        val raw = prefs.getString(KEY_INBOX_SHARE_IDS, null) ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    /** The shared notebooks this device has seen ACCEPTED (SharesInboxRepository.acceptedNotebooks). */
    fun setSeenAcceptedNotebooks(ids: Set<String>) {
        prefs.edit().putString(KEY_SEEN_ACCEPTED_NOTEBOOKS, ids.joinToString(",")).apply()
    }

    fun getSeenAcceptedNotebooks(): Set<String> {
        val raw = prefs.getString(KEY_SEEN_ACCEPTED_NOTEBOOKS, null) ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    /**
     * The shared notebooks on offer, as of the last inbox read that KNEW: the server said, or
     * the read reached the inbox's end. Kept across restarts because a read that stops early
     * against a server that does not say does not reach every notebook's own row, and the notes
     * of an invitation nobody answered must stay off the widgets all the same
     * (SharesInboxRepository, NotebooksOnOffer).
     */
    fun setOfferedNotebooks(ids: Set<String>) {
        prefs.edit().putString(KEY_OFFERED_NOTEBOOKS, ids.joinToString(",")).apply()
    }

    /** Null when no read on this device has known what is on offer yet: NOT the same as "none on offer". */
    fun getOfferedNotebooks(): Set<String>? {
        val raw = prefs.getString(KEY_OFFERED_NOTEBOOKS, null) ?: return null
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    /**
     * As of when [getOfferedNotebooks] is known: the `created_at` of the newest share the inbox
     * held at that refresh, as the server wrote it. A share newer than this arrived after
     * anything knew what was on offer (SharesInboxRepository,
     * NotebooksOnOffer.heldForArrivingUnseen). The account's, like the set itself.
     */
    fun setOffersKnownAsOf(sharedAt: String) {
        prefs.edit().putString(KEY_OFFERS_KNOWN_AS_OF, sharedAt).apply()
    }

    /** Null when no refresh that knew what was on offer has seen a share on this device. */
    fun getOffersKnownAsOf(): String? = prefs.getString(KEY_OFFERS_KNOWN_AS_OF, null)

    /**
     * The shared notes held back because of an INVITATION, as the last inbox refresh left them
     * (SharesInboxRepository, NotebooksOnOffer.heldForAnInvitation).
     *
     * A note held for an invitation stays held when the invitation is declined: the rule for a
     * declined notebook keeps away only what was shared AFTER the decline, and the notes that
     * came with the invitation are older than it. Remembered in memory only, the hold would
     * not survive a restart, and the app's next start would write those notes to the database
     * the widgets read.
     */
    fun setHeldForAnInvitation(items: Set<String>) {
        prefs.edit().putString(KEY_HELD_FOR_AN_INVITATION, items.joinToString(",")).apply()
    }

    fun getHeldForAnInvitation(): Set<String> =
        prefs.getString(KEY_HELD_FOR_AN_INVITATION, null)?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun setBlockScreenshots(blocked: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, blocked).apply()
    }

    fun isBlockScreenshotsEnabled(): Boolean {
        return prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, true) // Secure by default
    }

    /**
     * Whether markdown you TYPE turns into formatting.
     *
     * Off makes Preview a plain rich-text editor: the toolbar still formats,
     * existing markdown still renders, but typing "#" at the start of a line
     * leaves a "#". INPUT ONLY — nothing about display changes, so two people
     * with different settings never see the same note differently, which
     * matters because this preference is per-device and the note body is
     * shared.
     *
     * Defaults ON, because flipping the default would silently change what
     * existing users' keystrokes mean.
     */
    fun setInterpretMarkdown(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INTERPRET_MARKDOWN, enabled).apply()
    }

    fun isInterpretMarkdownEnabled(): Boolean {
        return prefs.getBoolean(KEY_INTERPRET_MARKDOWN, false)
    }

    fun setNotificationDetailLevel(level: String) {
        prefs.edit().putString(KEY_NOTIFICATION_DETAIL, level).apply()
    }

    /** Defaults to full detail; unknown stored values also fall back to full. */
    fun getNotificationDetailLevel(): String =
        when (val v = prefs.getString(KEY_NOTIFICATION_DETAIL, NOTIF_DETAIL_FULL)) {
            NOTIF_DETAIL_TITLES, NOTIF_DETAIL_GENERIC -> v
            else -> NOTIF_DETAIL_FULL
        }

    // ---- Notification categories (per-type on/off) ----
    //
    // DEVICE-LOCAL gates, mirrored in from the server's notification row by
    // NotificationPrefsSync. Every gate is applied client-side: a fired alarm has
    // no network and a push has already arrived by the time it is read. The two
    // PUSH toggles cover the app's real user-facing push kinds — reminders and
    // shared notes (NoteFirebaseMessagingService / NoteUnifiedPushReceiver); the
    // silent "sync" tickle isn't a category. ReminderReceiver and the two push
    // receivers honor them and skip posting when a category is off. The EMAIL
    // toggles are STORED ONLY: Note sends no email, so nothing gates on them.
    // Defaults: push categories ON, email OFF.

    fun areReminderNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_NOTIF_REMINDERS_PUSH, true)

    fun setReminderNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_REMINDERS_PUSH, enabled).apply()
    }

    fun areShareNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_NOTIF_SHARES_PUSH, true)

    fun setShareNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_SHARES_PUSH, enabled).apply()
    }

    /** Global "Email notifications" default. Mirrored from the server
     * row and stored device-local; Note sends no email, so nothing
     * gates on it. */
    fun isEmailNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_NOTIF_EMAIL_ENABLED, false)

    fun setEmailNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_EMAIL_ENABLED, enabled).apply()
    }

    fun isReminderEmailEnabled(): Boolean =
        prefs.getBoolean(KEY_NOTIF_REMINDERS_EMAIL, false)

    fun setReminderEmailEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_REMINDERS_EMAIL, enabled).apply()
    }

    fun isShareEmailEnabled(): Boolean =
        prefs.getBoolean(KEY_NOTIF_SHARES_EMAIL, false)

    fun setShareEmailEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_SHARES_EMAIL, enabled).apply()
    }

    // ---- App Lock (PIN + biometric + backup PIN) ----
    //
    // An app-specific 4-digit PIN, an optional biometric toggle (which keeps the
    // PIN as a backup so a biometric failure can't lock the user out), and a
    // shared auto-lock timeout. Installs that only ever stored the older
    // boolean-only lock ([KEY_APP_LOCK_ENABLED]) are migrated on read to
    // [APP_LOCK_BIOMETRICS], so their lock survives with no PIN required — the
    // unlock flow falls back to the device-credential prompt whenever no PIN
    // hash is stored.

    /**
     * Set the app lock method: [APP_LOCK_NONE], [APP_LOCK_PIN], or
     * [APP_LOCK_BIOMETRICS]. Kept in sync with the legacy boolean so widget /
     * notification redaction (which reads [isAppLockEnabled]) stays correct.
     */
    fun setAppLockMethod(method: String) {
        prefs.edit()
            .putString(KEY_APP_LOCK_METHOD, method)
            .putBoolean(KEY_APP_LOCK_ENABLED, method != APP_LOCK_NONE)
            .apply()
    }

    /** Current app lock method, migrating the legacy boolean on first read. */
    fun getAppLockMethod(): String {
        prefs.getString(KEY_APP_LOCK_METHOD, null)?.let { return it }
        // No method recorded: honor the older boolean-only lock.
        return if (prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)) {
            APP_LOCK_BIOMETRICS
        } else {
            APP_LOCK_NONE
        }
    }

    fun isAppLockEnabled(): Boolean = getAppLockMethod() != APP_LOCK_NONE

    /** True when biometric is the selected primary unlock method. */
    fun isBiometricEnabled(): Boolean = getAppLockMethod() == APP_LOCK_BIOMETRICS

    /**
     * On/off entry point for callers that only know "locked or not". Maps to
     * the biometric method, or to none.
     */
    fun setAppLockEnabled(enabled: Boolean) {
        setAppLockMethod(if (enabled) APP_LOCK_BIOMETRICS else APP_LOCK_NONE)
    }

    /** Store the PBKDF2 hash of the app-lock PIN (see [PinHasher]). */
    fun setAppLockPinHash(pinHash: String) {
        prefs.edit().putString(KEY_APP_LOCK_PIN_HASH, pinHash).apply()
    }

    fun getAppLockPinHash(): String? = prefs.getString(KEY_APP_LOCK_PIN_HASH, null)

    /** Clear the stored PIN hash (call when disabling PIN lock). */
    fun clearAppLockPinHash() {
        prefs.edit().remove(KEY_APP_LOCK_PIN_HASH).apply()
    }

    // ---- PIN lockout (throttles brute-force on the unlock screen) ----

    fun getPinFailedAttempts(): Int = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0)

    fun setPinFailedAttempts(count: Int) {
        prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, count).apply()
    }

    fun getPinLockoutUntil(): Long = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)

    fun setPinLockoutUntil(until: Long) {
        prefs.edit().putLong(KEY_PIN_LOCKOUT_UNTIL, until).apply()
    }

    fun resetPinLockout() {
        prefs.edit()
            .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun getAutoLockTimeout(): Long = prefs.getLong(KEY_AUTO_LOCK_TIMEOUT, AUTO_LOCK_IMMEDIATELY)

    fun setAutoLockTimeout(timeout: Long) {
        prefs.edit().putLong(KEY_AUTO_LOCK_TIMEOUT, timeout).apply()
    }

    /**
     * In-memory only: the lock was passed and the whole app has stayed
     * foregrounded since. Needed because AUTO_LOCK_IMMEDIATELY would
     * otherwise make [requiresAppLockAuth] true for the entire foreground
     * session, and the unlocked Main -> WebAppActivity handoff has no
     * shared activity state: without this flag the web home's onStart guard
     * would re-lock (discard + finish) the instant the user unlocked.
     * Cleared by [clearAppLockPassed] when the app's started-activity count
     * reaches zero (NoteApplication's lifecycle counter); process death
     * clears it by construction.
     */
    @Volatile
    private var appLockPassed = false

    fun markAppLockPassed() {
        appLockPassed = true
    }

    fun clearAppLockPassed() {
        appLockPassed = false
    }

    fun requiresAppLockAuth(): Boolean {
        if (!isAppLockEnabled()) return false
        if (!isLoggedIn()) return false
        if (appLockPassed) return false
        if (prefs.getBoolean(KEY_FORCE_AUTH_NEXT_LAUNCH, false)) return true
        val timeout = prefs.getLong(KEY_AUTO_LOCK_TIMEOUT, AUTO_LOCK_IMMEDIATELY)
        if (timeout == AUTO_LOCK_IMMEDIATELY) return true
        val lastBackground = prefs.getLong(KEY_LAST_BACKGROUND_TIME, 0)
        if (lastBackground == 0L) return true
        return System.currentTimeMillis() - lastBackground > timeout
    }

    fun setForceAuthOnNextLaunch(force: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_AUTH_NEXT_LAUNCH, force).apply()
    }

    fun recordBackgroundTime() {
        prefs.edit().putLong(KEY_LAST_BACKGROUND_TIME, System.currentTimeMillis()).apply()
    }

    fun clearBackgroundTime() {
        prefs.edit().remove(KEY_LAST_BACKGROUND_TIME).apply()
    }

    // ---- Editor Mode ----

    fun getEditorMode(): String {
        // Default is Preview — matches the web client. "regular" is an older
        // name for the mode now labelled "hybrid": rewrite it in place so a
        // stored preference keeps its behavior instead of falling through to
        // the default.
        val raw = prefs.getString(KEY_EDITOR_MODE, EDITOR_MODE_PREVIEW) ?: EDITOR_MODE_PREVIEW
        return if (raw == "regular") {
            prefs.edit().putString(KEY_EDITOR_MODE, EDITOR_MODE_HYBRID).apply()
            EDITOR_MODE_HYBRID
        } else raw
    }

    fun setEditorMode(mode: String) {
        prefs.edit().putString(KEY_EDITOR_MODE, mode).apply()
    }


    // ---- FCM push token ----

    /**
     * Stable client-generated UUID for this install. Used as the key under
     * which the backend stores our FCM token, so rotations replace the row
     * in place rather than accumulating dead tokens.
     */
    fun getOrCreateDeviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    fun getPushToken(): String? = prefs.getString(KEY_PUSH_TOKEN, null)

    fun savePushToken(token: String) {
        prefs.edit().putString(KEY_PUSH_TOKEN, token).apply()
    }

    /** Records whether the current push token / endpoint has been (re)registered
     * with the backend. [savePushSettings] clears it, and only a successful
     * server register sets it — so it is the difference between "this device
     * holds an endpoint" and "the server can actually reach us".
     *
     * Something has to read it: otherwise a failed UnifiedPush register is
     * silently permanent — the endpoint lands locally, Settings reads the local
     * endpoint and says "Registered", and nothing ever retries.
     * [com.pingolin.note.worker.PushTokenSyncWorker] is the heal path that
     * gates on it — keep it that way. */
    fun setPushTokenSynced(synced: Boolean) {
        prefs.edit().putBoolean(KEY_PUSH_TOKEN_SYNCED, synced).apply()
        // Settings shows "completing registration…" until this flips, and the
        // flip happens on a WorkManager thread well after savePushSettings has
        // already fired. Without this emit the status line would sit on the
        // pending text until Settings was reopened.
        _pushSettingsChanged.tryEmit(Unit)
    }

    /** True once the current push registration is live SERVER-side. False means
     *  the backend cannot reach this device yet, whatever the local endpoint says. */
    fun isPushTokenSynced(): Boolean = prefs.getBoolean(KEY_PUSH_TOKEN_SYNCED, false)

    /** "fcm" or "unifiedpush" — null when push isn't configured yet. */
    fun getPushType(): String? = prefs.getString(KEY_PUSH_TYPE, null)

    /** UnifiedPush distributor endpoint URL. Set by the receiver when
     *  the distributor delivers a fresh endpoint via onNewEndpoint. */
    fun getPushEndpoint(): String? = prefs.getString(KEY_PUSH_ENDPOINT, null)

    /**
     * Emitted whenever [savePushSettings] persists a new push registration.
     * SettingsFragment observes this to refresh the push-provider status line
     * when the UnifiedPush endpoint arrives asynchronously in
     * [com.pingolin.note.service.NoteUnifiedPushReceiver.onNewEndpoint] after
     * the user picks a distributor — the moment the transient "registration
     * pending" state becomes a live UnifiedPush registration. Without this the
     * status sits on the old provider ("Status: Google FCM") until Settings is
     * reopened.
     */
    private val _pushSettingsChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val pushSettingsChanged: kotlinx.coroutines.flow.SharedFlow<Unit> = _pushSettingsChanged

    /** Bulk update for the push registration triple. type/endpoint may
     *  be null when clearing or for the FCM path. */
    fun savePushSettings(token: String?, type: String, endpoint: String?) {
        prefs.edit().apply {
            if (token != null) putString(KEY_PUSH_TOKEN, token) else remove(KEY_PUSH_TOKEN)
            putString(KEY_PUSH_TYPE, type)
            if (endpoint != null) putString(KEY_PUSH_ENDPOINT, endpoint) else remove(KEY_PUSH_ENDPOINT)
            putBoolean(KEY_PUSH_TOKEN_SYNCED, false) // mark unsynced until the next successful backend (re)register
        }.apply()
        _pushSettingsChanged.tryEmit(Unit)
    }

    fun clearPushSettings() {
        prefs.edit()
            .remove(KEY_PUSH_TOKEN)
            .remove(KEY_PUSH_TYPE)
            .remove(KEY_PUSH_ENDPOINT)
            .remove(KEY_PUSH_TOKEN_SYNCED)
            .apply()
    }

    companion object {
        /** The session's end, for the app: every widget re-reads, and reads nothing while signed out. */
        private fun widgetsRefresh(appContext: Context): () -> Unit = {
            runCatching { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }
                .onFailure { android.util.Log.w("SessionManager", "widget refresh failed", it) }
        }

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_WEB_NOTEBOOKS = "web_notebooks"
        private const val KEY_INBOX_SHARE_IDS = "inbox_share_ids"
        private const val KEY_SEEN_ACCEPTED_NOTEBOOKS = "seen_accepted_notebooks"
        private const val KEY_OFFERED_NOTEBOOKS = "offered_notebooks"
        private const val KEY_OFFERS_KNOWN_AS_OF = "offers_known_as_of"
        private const val KEY_HELD_FOR_AN_INVITATION = "held_for_an_invitation"
        private const val KEY_THEME = "theme"
        private const val KEY_MASTER_KEY = "master_key"
        private const val KEY_SHARING_PRIVATE_KEY = "sharing_private_key"
        private const val KEY_SIGNING_PRIVATE_KEY = "signing_private_key"
        private const val KEY_NOTEBOOK_SHARES = "notebook_shares"
        private const val KEY_EDITOR_MODES = "editor_modes"
        private const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
        private const val KEY_INTERPRET_MARKDOWN = "interpret_markdown"
        private const val KEY_NOTIFICATION_DETAIL = "notification_detail_level"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_APP_LOCK_METHOD = "app_lock_method"
        private const val KEY_APP_LOCK_PIN_HASH = "app_lock_pin_hash"
        private const val KEY_PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"
        private const val KEY_FORCE_AUTH_NEXT_LAUNCH = "force_auth_next_launch"
        private const val KEY_LAST_BACKGROUND_TIME = "last_background_time"
        private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout"
        private const val KEY_EDITOR_MODE = "editor_mode"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUSH_TOKEN = "push_token"
        private const val KEY_PUSH_TOKEN_SYNCED = "push_token_synced"
        private const val KEY_PUSH_TYPE = "push_type"
        private const val KEY_PUSH_ENDPOINT = "push_endpoint"

        // Notification categories (device-local; see the getters above).
        private const val KEY_NOTIF_REMINDERS_PUSH = "notif_reminders_push"
        private const val KEY_NOTIF_SHARES_PUSH = "notif_shares_push"
        private const val KEY_NOTIF_EMAIL_ENABLED = "notif_email_enabled"
        private const val KEY_NOTIF_REMINDERS_EMAIL = "notif_reminders_email"
        private const val KEY_NOTIF_SHARES_EMAIL = "notif_shares_email"

        const val PUSH_TYPE_FCM = "fcm"
        const val PUSH_TYPE_UNIFIEDPUSH = "unifiedpush"

        // Notification display detail levels (Signal-style). Controls how much
        // decrypted note content the LOCAL notification surfaces render — a
        // pure display-layer choice; pushes are content-free tickles either
        // way, and the in-app UI keeps full detail behind the app lock. The
        // middle level keeps note TITLES: titles survive, bodies/previews don't.
        const val NOTIF_DETAIL_FULL = "full"
        const val NOTIF_DETAIL_TITLES = "titles"
        const val NOTIF_DETAIL_GENERIC = "generic"

        // App lock methods
        const val APP_LOCK_NONE = "none"
        const val APP_LOCK_PIN = "pin"
        const val APP_LOCK_BIOMETRICS = "biometrics"

        const val AUTO_LOCK_IMMEDIATELY = 0L
        const val AUTO_LOCK_1_MIN = 60_000L
        const val AUTO_LOCK_5_MIN = 300_000L

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        // Live-preview cursor-line reveal — markdown rendered everywhere
        // except the active line, which shows raw syntax for editing.
        const val EDITOR_MODE_HYBRID = "hybrid"
        const val EDITOR_MODE_MARKDOWN = "markdown"
        const val EDITOR_MODE_PREVIEW = "preview"
    }
}
