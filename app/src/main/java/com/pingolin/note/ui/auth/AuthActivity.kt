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

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.method.LinkMovementMethod
import androidx.activity.viewModels
import androidx.core.text.HtmlCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.note.R
import com.pingolin.note.databinding.ActivityAuthBinding
import com.pingolin.note.ui.MainActivity
import com.pingolin.note.util.SrpClient
import com.pingolin.note.worker.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Sign-in host. Mirrors the Track Android app:
 *  - tvSwitchMode opens the account portal's /register page: the app has no
 *    sign-up form. Registration, the assisted lane for people who cannot clear
 *    the security check, guardian-managed signups and the recovery key all live
 *    on the portal.
 *  - An account still awaiting email verification lands on a "verify your
 *    email" screen that polls /email/status every 5s; tapping the link in the
 *    email deep-links back into this activity and immediately verifies.
 */
@AndroidEntryPoint
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val viewModel: AuthViewModel by viewModels()

    @Inject
    lateinit var pendingShareStore: com.pingolin.note.util.PendingShareStore


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SECURITY: keep FLAG_SECURE on. Recovery keys are surfaced
        // here; they must never land in screenshots or the recents
        // task switcher.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupEmailVerificationUI()
        observeViewModel()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Sent here by the web shell because this device has a session
        // but not its keys: say why a sign-in is being asked for.
        if (intent?.getStringExtra(EXTRA_REAUTH_REASON) == REAUTH_KEYS) {
            showFormError(getString(R.string.auth_keys_unavailable))
        }
        val data = intent?.data
        // Verify-email deep link from the welcome email. Handles both
        // the https path (configured in AssetLinks) and the
        // note://verify-email scheme used as a fallback.
        if (data != null && (data.path?.contains("verify-email") == true || data.host == "verify-email")) {
            val token = data.getQueryParameter("token")
            if (!token.isNullOrBlank() && isValidTokenFormat(token)) {
                viewModel.verifyEmail(token)
                return
            } else if (!token.isNullOrBlank()) {
                Log.w(TAG, "Rejecting invalid token format in deep link")
                Toast.makeText(this, R.string.auth_invalid_verification_link, Toast.LENGTH_SHORT).show()
            }
        }

        if (viewModel.isLoggedIn()) navigateToMain()
    }

    /**
     * SECURITY: validate token format before sending to /verify-email
     * — opaque safe-character whitelist + length cap so a hostile
     * deep link can't push arbitrary content into the request body.
     */
    private fun isValidTokenFormat(token: String): Boolean {
        if (token.length > 256) return false
        if (token.length < 20) return false
        return Regex("^[A-Za-z0-9\\-_+=/.]+$").matches(token)
    }

    private fun setupUI() {
        updateUIMode()

        binding.btnSubmit.setOnClickListener { submit() }

        // Sign-up leaves the app, exactly as password reset does and for the
        // same reason: the portal owns registration.
        binding.tvSwitchMode.setOnClickListener {
            val url = com.pingolin.note.BuildConfig.ACCOUNT_BASE_URL.trimEnd('/') + "/register"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.auth_no_browser, Toast.LENGTH_SHORT).show()
            }
        }

        // Password reset lives entirely in the web portal — this app must not
        // grow its own. The reset flow binds SRP credentials to an identity and
        // hashes an address into a token; a second implementation that disagrees
        // with the first does not fail visibly, it bricks the account. So this
        // is a link and nothing else, and it is the only thing on the phone that
        // tells a phone-first user the way back in exists at all.
        binding.tvForgotPassword.setOnClickListener {
            val url = com.pingolin.note.util.AccountRecovery.forgotPasswordUrl(
                com.pingolin.note.BuildConfig.ACCOUNT_BASE_URL
            )
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.recovery_no_browser, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Sign-in only: registration lives on the account portal, so this form
     * has no sign-up half.
     */
    private fun updateUIMode() {
        binding.tvTitle.setText(R.string.auth_title)
        // Name the held share when there is one. Text shared into the
        // app on a lapsed session is kept (see PendingShareStore), and
        // the user looking at an unexpected login screen should be
        // told that before deciding whether to sign in.
        binding.tvSubtitle.setText(
            if (pendingShareStore.hasPending()) {
                R.string.auth_subtitle_pending_share
            } else {
                R.string.auth_subtitle
            },
        )
        binding.btnSubmit.setText(R.string.auth_sign_in)
        binding.tvSwitchMode.setText(R.string.auth_switch_to_sign_up)
        binding.tvForgotPassword.visibility = View.VISIBLE
        binding.etEmail.text?.clear()
        binding.etPassword.text?.clear()
    }

    /**
     * Submit the form. SECURITY: extract passwords as CharArray so we
     * can zero them after use rather than leaving them in interned
     * Strings until GC eventually runs.
     */
    private fun submit() {
        val email = binding.etEmail.text?.toString()?.trim() ?: ""

        val pwEditable = binding.etPassword.text
        val password = CharArray(pwEditable?.length ?: 0)
        pwEditable?.getChars(0, pwEditable.length, password, 0)

        try {
            // login() takes a String; best-effort by wrapping the
            // array. (AuthViewModel.login does not use the
            // CharArray pattern; changing it here would touch the
            // SRP flow.)
            viewModel.login(email, String(password))
        } finally {
            SrpClient.clearCharArray(password)
        }
    }

    private fun setupEmailVerificationUI() {
        binding.btnOpenEmail.setOnClickListener { openEmailApp() }
        binding.btnResendVerification.setOnClickListener { viewModel.resendVerificationEmail() }
    }

    private fun showEmailVerification(email: String, canResend: Boolean) {
        binding.authFormContainer.visibility = View.GONE
        binding.emailVerificationContainer.visibility = View.VISIBLE
        binding.tvVerifyEmail.text = email
        binding.btnResendVerification.isEnabled = canResend
        binding.waitingContainer.visibility = View.VISIBLE
    }

    private fun openEmailApp() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_EMAIL)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.auth_no_email_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Put a sign-in failure where the user is looking.
     *
     * The password field carries it: an SRP failure cannot distinguish "wrong
     * password" from "no such account" without leaking whether the address is
     * registered, so the field the user can actually act on is the right one.
     * `announceForAccessibility` because a TextInputLayout error is announced on
     * focus, and the focus is usually still on the button that was just pressed.
     */
    private fun showFormError(message: String) {
        binding.tilPassword.error = message
        binding.tilPassword.isErrorEnabled = true
        binding.root.announceForAccessibility(message)
    }

    private fun clearFormError() {
        binding.tilPassword.error = null
        binding.tilPassword.isErrorEnabled = false
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is AuthUiState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSubmit.isEnabled = true
                    }
                    is AuthUiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.btnSubmit.isEnabled = false
                        clearFormError()
                    }
                    is AuthUiState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        navigateToMain()
                    }
                    is AuthUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSubmit.isEnabled = true
                        // Inline on the field, not a Toast. A sign-in failure is
                        // the one moment the message has to stay on screen and
                        // point at what is wrong; a Toast floats away on a timer,
                        // leaves both fields looking valid, and is not reliably
                        // announced.
                        showFormError(state.message)
                        viewModel.resetState()
                    }
                    is AuthUiState.EmailVerificationRequired -> {
                        binding.progressBar.visibility = View.GONE
                        showEmailVerification(state.email, state.canResend)
                    }
                    is AuthUiState.EmailVerified -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@AuthActivity, R.string.auth_email_verified, Toast.LENGTH_SHORT).show()
                        navigateToMain()
                    }
                    is AuthUiState.TwoFactorRequired -> {
                        binding.progressBar.visibility = View.GONE
                        showTwoFactorDialog(state.retryMessage)
                    }
                    is AuthUiState.TwoFactorPasskeyOnly -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSubmit.isEnabled = true
                        // A dialog, not the inline field error: this is not a
                        // typo to fix in the form, and the two instructions in
                        // it need to stay readable until dismissed.
                        MaterialAlertDialogBuilder(this@AuthActivity)
                            .setTitle(R.string.auth_2fa_title)
                            .setMessage(R.string.auth_2fa_passkey_only)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        viewModel.resetState()
                    }
                    is AuthUiState.KeysUnavailable -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSubmit.isEnabled = true
                        showFormError(getString(R.string.auth_keys_unavailable))
                        viewModel.resetState()
                    }
                }
            }
        }
    }

    /**
     * Code entry for a sign-in that stopped at the two-factor challenge.
     *
     * Without it, an account with two-factor on could not sign in to Note on
     * Android at all: the challenge would decode to nothing, the access token
     * come back null, and the login die as "Login succeeded but no token
     * received".
     *
     * [retryMessage]: the last code was not taken and the attempt is still
     * open, so the box comes back saying why.
     */
    private fun showTwoFactorDialog(retryMessage: String?) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.auth_2fa_hint)
            // Letters as well as digits: a backup code is twelve of them, and it
            // is the way in for somebody whose authenticator was on the phone they
            // lost (as in the Track Android app). No length cap: it is sent whole.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            gravity = android.view.Gravity.CENTER
            contentDescription = getString(R.string.auth_2fa_content_description)
            // Let a password manager or the SMS/authenticator autofill provider
            // offer the code, and stop the keyboard learning it: this is the one
            // field in the app that holds a one-time secret.
            setAutofillHints(android.view.View.AUTOFILL_HINT_PASSWORD)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // dp, not raw pixels: device pixels (as in setPadding(64, 32, 64,
            // 0)) are 24dp on a 420dpi phone and 10dp on a 640dpi one, so the
            // dialog's insets would change with the screen.
            val h = dp(24)
            setPadding(h, dp(12), h, 0)
            addView(input)
        }

        // Title, message and view all set on the BUILDER. Setting any of
        // them on the created dialog renders an empty body.
        //
        // MaterialAlertDialogBuilder, not the appcompat one, so this
        // dialog — the one screen a security-conscious user is
        // guaranteed to meet — has the same corners and button
        // typography as every other dialog in the app.
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.auth_2fa_title)
            .setMessage(R.string.auth_2fa_message)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.auth_2fa_verify, null)
            .setNegativeButton(R.string.cancel) { d, _ ->
                viewModel.cancelTwoFactorChallenge()
                d.dismiss()
            }
            .create()

        // Bound after show() so a code that can't be right leaves the dialog
        // open without spending one of the attempt's tries.
        dialog.setOnShowListener {
            retryMessage?.let {
                input.error = it
                input.announceForAccessibility(it)
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener {
                    val code = input.text.toString()
                    if (AuthViewModel.tooShortToTry(code)) {
                        input.error = getString(R.string.auth_2fa_short_code)
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    viewModel.verifyTwoFactor(code)
                }
        }
        dialog.show()
    }

    private fun navigateToMain() {
        SyncWorker.enqueueImmediateSync(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val TAG = "AuthActivity"

        /** Why an already-signed-in user is being asked to sign in again. */
        const val EXTRA_REAUTH_REASON = "reauth_reason"

        /** [EXTRA_REAUTH_REASON]: the session had no vault / sharing key. */
        const val REAUTH_KEYS = "keys"
    }
}
