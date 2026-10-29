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

package com.pingolin.note.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.note.R
import com.pingolin.note.databinding.ActivityMainBinding
import com.pingolin.note.ui.auth.AuthActivity
import com.pingolin.note.util.KeystoreEncryption
import com.pingolin.note.util.PinHasher
import com.pingolin.note.util.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's ENTRY ROUTER — not its home. The web app's list (WebAppActivity
 * at the origin root) is the home screen; this activity exists for exactly
 * four jobs:
 *
 *  1. the session gate (bounce to [AuthActivity] when logged out),
 *  2. the app-lock prompt (PIN / biometric, drawn natively),
 *  3. hosting the NATIVE settings screen (app lock, screenshots, theme,
 *     push mode — the controls that cannot live inside the page), reached
 *     via the pingolin-settings scheme, and
 *  4. the intent fan-out the OS aims here: share sheet, widget taps,
 *     notification deep links — each stashed and converted into one
 *     WebAppActivity launch by [routeToWeb].
 *
 * A plain launch forwards before paying for ANY UI setup.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    @Inject
    lateinit var sessionManager: SessionManager

    // LAZY, because the common launch uses NEITHER and both cost Keystore work
    // to construct. KeystoreEncryption's constructor does
    // `KeyStore.getInstance("AndroidKeyStore").load(null)` — a Binder round trip
    // — and it is only ever touched by the biometric prompt
    // (getCipherForBiometricAuth). PushRegistrar is only reached through
    // ensureFcmRegistered(), which is below the routeToWeb() early return. Eager
    // @Inject would make every launch pay for both, including the launch that
    // does nothing but forward to the web home and finish.
    @Inject
    lateinit var keystoreEncryption: dagger.Lazy<KeystoreEncryption>

    @Inject
    lateinit var pushRegistrar: dagger.Lazy<com.pingolin.note.util.PushRegistrar>

    @Inject
    lateinit var pendingShareStore: com.pingolin.note.util.PendingShareStore

    private var isLocked = false
    private var openNativeSettings = false
    private var settingsSection: String? = null
    private var pendingShareDraft: Pair<String, String>? = null // title to body
    private var pendingNewNoteType: String? = null // "text" | "checklist" — from the widget when unlocked
    private var pendingOpenNoteId: String? = null // deep-link target note id (notification/widget/pinned)

    companion object {
        /** Intent extra: open a fresh note of this type ("text"|"checklist")
         *  in the full editor. Set by LockScreenCaptureActivity when the
         *  device is unlocked, so the widget routes to the rich app editor. */
        const val EXTRA_NEW_NOTE_TYPE = "new_note_type"

        /** Intent extra: deep-link straight to an existing note by id. Set by
         *  the FCM single-reminder notification, the pinned-note notification,
         *  and the single-note / note-list widgets. The id is an opaque item
         *  id (no E2EE content), so it's safe to carry in a plain extra. */
        const val EXTRA_OPEN_NOTE_ID = "open_note_id"

        /** Intent extra: land on the "Shared with me" inbox instead of the
         *  notes list. Set by whichever push transport delivered the share
         *  tickle — FCM (gms only) or UnifiedPush. Declared HERE rather than
         *  on the FCM service because that service is compiled into the gms
         *  flavour only, while both senders and this consumer are in
         *  src/main. */
        const val EXTRA_OPEN_INBOX = "open_inbox"

        /** Intent extra: host the NATIVE settings screen instead of
         *  forwarding to the web home — the one surface (app lock, block
         *  screenshots, theme, push mode) that cannot live in the page.
         *  Set by the shell-settings scheme the web app navigates to. */
        const val EXTRA_OPEN_SETTINGS = "open_native_settings"

        /** Intent extra: which settings section to reveal on arrival, from
         *  the `?section=` hint on the shell-settings scheme. The panel's
         *  Device rows each name their own section so a tap lands on the
         *  control instead of the top of the screen. Null/unknown just opens
         *  the screen normally. */
        const val EXTRA_SETTINGS_SECTION = "open_native_settings_section"

        /** App-lock PIN brute-force throttle. */
        private const val MAX_PIN_ATTEMPTS = 5
        private const val PIN_LOCKOUT_DURATION_MS = 30_000L
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _granted ->
        // Either way, try to register the FCM token — the service posts
        // notifications only if POST_NOTIFICATIONS is granted.
        ensureFcmRegistered()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // READ THE PAYLOAD BEFORE THE AUTH GATE.
        //
        // A share that arrives on a lapsed session must not be
        // dropped. Read below the `isLoggedIn` return, it would be:
        // the user would see a login screen that said nothing about
        // it, sign in, and land on an empty list with their text
        // gone. Extraction has no session dependency, so it belongs
        // above the gate; only the DESTINATION depends on being
        // signed in.
        val incomingDraft = if (savedInstanceState == null) extractShareDraft(intent) else null

        // A saved account id that isn't a UUID is no account
        // (AccountIds): the session ends here like an expiry — the
        // tokens go, the sign-in screen shows, and the next sign-in
        // counts that id as another account's.
        if (sessionManager.savedAccountIdInvalid()) sessionManager.clearAuthOnly()

        if (!sessionManager.isLoggedIn()) {
            // Hold it across the sign-in rather than losing it. Persistent,
            // because this Activity is about to finish and the user may take
            // minutes — or a process death — to get through SRP.
            if (incomingDraft != null) {
                pendingShareStore.save(incomingDraft.first, incomingDraft.second)
            }
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) {
            // A draft on THIS intent wins over a stashed one; otherwise drain
            // the stash, which is how a share that arrived logged-out gets
            // replayed on the first launch after sign-in.
            pendingShareDraft = incomingDraft
                ?: pendingShareStore.take()?.let { it.title to it.body }
            pendingNewNoteType = intent?.getStringExtra(EXTRA_NEW_NOTE_TYPE)
            pendingOpenNoteId = intent?.getStringExtra(EXTRA_OPEN_NOTE_ID)
        }
        openNativeSettings = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true
        settingsSection = intent?.getStringExtra(EXTRA_SETTINGS_SECTION)

        // The common case: no lock pending, no settings request — forward to
        // the web home without inflating anything. Push registration is
        // app-scoped (PushRegistrar) and the launch-time sync triggers live
        // in WebAppActivity, so nothing here needs to outlive this frame.
        if (!openNativeSettings && !sessionManager.requiresAppLockAuth()) {
            routeToWeb()
            return
        }

        // Screenshot protection — secure by default
        if (sessionManager.isBlockScreenshotsEnabled()) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        maybeRequestNotificationPermission()
        observeSession()

        if (sessionManager.requiresAppLockAuth()) {
            isLocked = true
            binding.navHostFragment.visibility = android.view.View.INVISIBLE
            showAppLockPrompt()
        }
    }

    /**
     * Bounce back to AuthActivity if the session is cleared mid-run
     * — e.g., `TokenAuthenticator.clearAuthOnly()` after a refresh
     * fails. Without this, the user is left on a screen reading from
     * an empty SessionManager (empty email/display name, missing
     * tokens for sync). Collecting at STARTED so it runs while the
     * activity is visible; the initial value reflects "logged in"
     * after the onCreate gate above, so the first emit is a no-op.
     */
    private fun observeSession() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionManager.isLoggedInFlow.collect { loggedIn ->
                    if (!loggedIn && !isFinishing) {
                        startActivity(
                            Intent(this@MainActivity, AuthActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        finish()
                    }
                }
            }
        }
    }

    /**
     * Ask for POST_NOTIFICATIONS on Android 13+ if we haven't already. On older
     * Android versions the permission is implicit — go straight to registering
     * the push token. Re-registration on every launch is cheap (the server
     * upserts by device_id) and catches any token rotation that happened while
     * the push service was unreachable.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                ensureFcmRegistered()
            } else {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            ensureFcmRegistered()
        }
    }

    private fun ensureFcmRegistered() {
        // Push registration lives in PushRegistrar (app-scoped); this
        // just delegates, so the settings/lock paths share one call
        // site.
        pushRegistrar.get().ensure()
    }

    // Background-time stamping lives in NoteApplication's started-activity
    // counter, not in a per-activity onStop: onStop also fires during the
    // unlocked MainActivity -> WebAppActivity handoff, and in the timeout
    // modes that stale stamp would re-lock the app after a brief real
    // background even though it had been foregrounded the whole time.

    override fun onStart() {
        super.onStart()
        if (!isLocked && sessionManager.requiresAppLockAuth()) {
            isLocked = true
            binding.navHostFragment.visibility = android.view.View.INVISIBLE
            showAppLockPrompt()
        } else if (!isLocked) {
            sessionManager.clearBackgroundTime()
        }
    }

    /**
     * App-lock gate entry point: dispatch to the configured unlock method. PIN
     * and biometric are mutually exclusive as the primary method; the biometric
     * path keeps the PIN as a fallback.
     */
    private fun showAppLockPrompt() {
        when (sessionManager.getAppLockMethod()) {
            SessionManager.APP_LOCK_PIN -> showPinPrompt()
            SessionManager.APP_LOCK_BIOMETRICS -> showBiometricPrompt()
            else -> unlockApp()
        }
    }

    private fun showBiometricPrompt() {
        // With a backup PIN set: BIOMETRIC_STRONG bound to a CryptoObject
        // (anti-bypass), with a "Use PIN" fallback. Accounts that enabled the lock
        // before a PIN was required have none to fall back to, so they get the
        // BIOMETRIC_STRONG-or-DEVICE_CREDENTIAL prompt instead — demanding a PIN
        // there would lock them out of the app entirely.
        if (sessionManager.getAppLockPinHash() == null) {
            showLegacyBiometricPrompt()
            return
        }

        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            // Strong biometric not usable right now — fall back to the PIN.
            showPinPrompt()
            return
        }

        // CryptoObject binding: the cipher can only be produced after genuine
        // biometric auth, so hooking the callback alone won't unlock.
        val cipher = try {
            keystoreEncryption.get().getCipherForBiometricAuth()
        } catch (e: Exception) {
            // Key invalidated (e.g., a new biometric was enrolled) — fall back.
            showPinPrompt()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val cryptoObject = BiometricPrompt.CryptoObject(cipher)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (result.cryptoObject?.cipher != null) {
                        unlockApp()
                    } else {
                        // CryptoObject missing — possible bypass attempt.
                        sessionManager.setForceAuthOnNextLaunch(true)
                        finishAffinity()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> showPinPrompt() // "Use PIN"
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> {
                            sessionManager.setForceAuthOnNextLaunch(true)
                            finishAffinity()
                        }
                        else -> showPinPrompt() // any other biometric error → PIN
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Allow retry.
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Pingolin Note")
            .setSubtitle("Verify your identity to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use PIN")
            .build()

        prompt.authenticate(promptInfo, cryptoObject)
    }

    /**
     * Biometric/device-credential prompt for users with no app PIN, so there is
     * no PIN fallback to offer. Falls through to unlock when no authenticator is
     * configured at all — enabling the lock without a usable credential must not
     * hard-brick the app.
     */
    private fun showLegacyBiometricPrompt() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            unlockApp()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                unlockApp()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                ) {
                    finishAffinity()
                } else {
                    Toast.makeText(this@MainActivity, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                    finishAffinity()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
            }
        }

        val prompt = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Pingolin Note")
            .setSubtitle("Authenticate to access your notes")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
    }

    /**
     * App-PIN entry with a 5-attempt / 30-second lockout. Verifies against the
     * PBKDF2 hash in [SessionManager]; cancelling forces re-auth on the next
     * launch and closes the app.
     */
    private fun showPinPrompt() {
        val failedAttempts = sessionManager.getPinFailedAttempts()
        val lockoutUntil = sessionManager.getPinLockoutUntil()

        // Currently locked out — re-show once the window expires.
        if (failedAttempts >= MAX_PIN_ATTEMPTS && System.currentTimeMillis() < lockoutUntil) {
            val remainingSeconds = ((lockoutUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
            Toast.makeText(
                this,
                "Too many failed attempts. Try again in $remainingSeconds seconds.",
                Toast.LENGTH_LONG,
            ).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                showPinPrompt()
            }, (remainingSeconds * 1000).coerceAtMost(PIN_LOCKOUT_DURATION_MS))
            return
        }

        if (failedAttempts >= MAX_PIN_ATTEMPTS && System.currentTimeMillis() >= lockoutUntil) {
            sessionManager.resetPinLockout()
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_pin_input, null)
        val pinInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPinInput)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Enter PIN")
            .setView(dialogView)
            .setCancelable(false)
            // Click handled in the OnShowListener so the dialog doesn't auto-dismiss
            // while the async PBKDF2 verify runs.
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel") { _, _ ->
                sessionManager.setForceAuthOnNextLaunch(true)
                finishAffinity()
            }
            .create()

        dialog.setOnShowListener {
            val unlockButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            unlockButton.setOnClickListener {
                val editable = pinInput.text
                val enteredPin = CharArray(editable?.length ?: 0)
                if (editable != null && editable.isNotEmpty()) {
                    editable.getChars(0, editable.length, enteredPin, 0)
                    // Wipe the EditText buffer so the plaintext PIN doesn't linger.
                    editable.clear()
                }
                val storedHash = sessionManager.getAppLockPinHash()

                // PBKDF2 verify (210k HMAC-SHA256) is ~100-500ms; run it off the main
                // thread so it doesn't jank the UI on every attempt, and disable the
                // button while it runs so a double-tap can't launch a second verify.
                unlockButton.isEnabled = false
                lifecycleScope.launch {
                val verified = try {
                    withContext(Dispatchers.Default) {
                        storedHash != null && PinHasher.verifyPin(enteredPin, storedHash)
                    }
                } finally {
                    enteredPin.fill('\u0000')
                }
                if (verified) {
                    sessionManager.resetPinLockout()
                    dialog.dismiss()
                    unlockApp()
                } else {
                    dialog.dismiss()
                    val newFailedCount = sessionManager.getPinFailedAttempts() + 1
                    sessionManager.setPinFailedAttempts(newFailedCount)

                    if (newFailedCount >= MAX_PIN_ATTEMPTS) {
                        sessionManager.setPinLockoutUntil(System.currentTimeMillis() + PIN_LOCKOUT_DURATION_MS)
                        Toast.makeText(
                            this@MainActivity,
                            "Too many failed attempts. Locked for 30 seconds.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        val remaining = MAX_PIN_ATTEMPTS - newFailedCount
                        Toast.makeText(
                            this@MainActivity,
                            "Incorrect PIN. $remaining attempt${if (remaining != 1) "s" else ""} remaining.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    showPinPrompt()
                }
                }
            }
        }
        dialog.show()
    }

    private fun unlockApp() {
        isLocked = false
        sessionManager.markAppLockPassed()
        sessionManager.setForceAuthOnNextLaunch(false)
        sessionManager.clearBackgroundTime()
        if (!openNativeSettings) {
            routeToWeb()
            return
        }
        binding.navHostFragment.visibility = android.view.View.VISIBLE
        setupNavigation()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        if (openNativeSettings) {
            openNativeSettings = false
            binding.root.post {
                // The start destination is an empty host; popping it keeps
                // back-from-settings = leave.
                navController.navigate(
                    R.id.settingsFragment,
                    settingsSection?.let {
                        android.os.Bundle().apply {
                            putString(com.pingolin.note.ui.settings.SettingsFragment.ARG_SECTION, it)
                        }
                    },
                    androidx.navigation.navOptions {
                        popUpTo(R.id.routerHostFragment) { inclusive = true }
                    },
                )
            }
        }
    }

    /** Consume the stashed routing intents into ONE web-app launch and
     *  finish. The task root becomes WebAppActivity, whose home-mode back
     *  walks the page to the grid instead of leaving the app. */
    private fun routeToWeb() {
        val draft = pendingShareDraft
        val newType = pendingNewNoteType
        val noteId = pendingOpenNoteId
        pendingShareDraft = null
        pendingNewNoteType = null
        pendingOpenNoteId = null
        // The inbox (EXTRA_OPEN_INBOX) has no in-page deep link; the home
        // grid is one sidebar tap from it, so the plain launch covers it.
        val target = when {
            draft != null -> com.pingolin.note.ui.web.WebAppActivity.newNoteIntent(
                this, noteType = null, title = draft.first, body = draft.second,
            )
            newType != null -> com.pingolin.note.ui.web.WebAppActivity.newNoteIntent(this, newType)
            noteId != null -> com.pingolin.note.ui.web.WebAppActivity.intent(this, noteId)
            else -> com.pingolin.note.ui.web.WebAppActivity.intent(this)
        }
        // Reuse the existing web home instead of stacking a second one:
        // singleTop alone only matches when the target is already the top
        // activity, and during this trampoline MainActivity is the top — a
        // relaunch would pile [Web, Web] into the task, leaving a blank
        // duplicate under the live one (the retained WebView can only be
        // attached to one of them) and confusing back.
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(target)
        finish()
    }

    /** Pull (title, body) out of an ACTION_SEND text intent, or null when
     *  the intent isn't a usable text share. */
    private fun extractShareDraft(intent: Intent?): Pair<String, String>? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        val body = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        if (body.isEmpty() && title.isEmpty()) return null
        return title to body
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-delivery while this activity happens to be alive (a lock prompt
        // or a settings visit is up). Stash exactly like onCreate; if no lock
        // is pending, convert to a web launch now — otherwise the unlock
        // consumes the stash via routeToWeb.
        pendingShareDraft = extractShareDraft(intent) ?: pendingShareDraft
        intent.getStringExtra(EXTRA_NEW_NOTE_TYPE)?.let { pendingNewNoteType = it }
        intent.getStringExtra(EXTRA_OPEN_NOTE_ID)?.let { pendingOpenNoteId = it }
        if (!sessionManager.requiresAppLockAuth() && !isLocked) {
            routeToWeb()
        }
    }
}
