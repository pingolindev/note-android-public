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

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.util.Log
import android.view.View
import androidx.core.view.doOnPreDraw
import android.view.ViewGroup
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.note.R
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.RegisterFcmRequest
import com.pingolin.note.databinding.FragmentSettingsBinding
import com.pingolin.note.ui.auth.AuthActivity
import com.pingolin.note.util.BiometricAuthManager
import com.pingolin.note.util.BiometricStatus
import com.pingolin.note.util.CrashReporter
import com.pingolin.note.util.PinHasher
import com.pingolin.note.util.PushNotificationManager
import com.pingolin.note.util.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    @Inject
    lateinit var crashReporter: CrashReporter

    @Inject
    lateinit var quotaManager: com.pingolin.note.domain.repository.QuotaManager

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var pushNotificationManager: PushNotificationManager

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var noteApi: NoteApi

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.settingsToolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Device-only controls: plan, account, theme, editor and
        // export/import live in the web panel, which every platform renders.
        // What is here needs an OS capability the page does not have.
        setupNotificationDetailSection()
        setupPushNotifications()
        setupSecuritySection()
        setupActionsSection()
        setupCrashLogsSection()
        setupVersionLine()
        observeViewModel()
        applySectionFocus()
    }

    /**
     * Strip HORIZONTAL padding from [view] and everything under it, leaving
     * vertical padding — and therefore row heights and touch targets — alone.
     *
     * Depth matters and is why this recurses rather than peeling one layer: the
     * cards do not agree about where their inset lives. `cardSecurity` puts
     * 16dp on its immediate child; `cardNotifications` and `cardAbout` put none
     * there and 16dp on each ROW instead. Peeling one level would fix the first
     * and leave the other two 16dp adrift of the sheet title.
     */
    private fun clearHorizontalPadding(view: View) {
        view.setPadding(0, view.paddingTop, 0, view.paddingBottom)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) clearHorizontalPadding(view.getChildAt(i))
        }
    }

    /**
     * Put the version on the version line: it is the one place in the app to
     * read the version a problem report asks for.
     */
    private fun setupVersionLine() {
        binding.tvVersion.text = getString(
            R.string.settings_version,
            com.pingolin.note.BuildConfig.VERSION_NAME,
            com.pingolin.note.BuildConfig.VERSION_CODE,
        )
    }

    /**
     * Show ONE group and nothing else, for the focused sheet.
     *
     * The web panel lists each device setting as its own row, so a tap has to
     * land on THAT setting — arriving at a whole screen scrolled somewhere is
     * the "you left the app" feeling this is meant to remove. In focused mode
     * the sheet supplies its own title, so the toolbar goes too.
     *
     * Unfocused (the full screen, still reachable by a section-less deep link)
     * everything stays visible, which is why this only ever HIDES.
     */
    private fun applySectionFocus() {
        val section = arguments?.getString(ARG_SECTION)
        if (section == null || arguments?.getBoolean(ARG_FOCUSED) != true) return

        val groups: Map<String, List<View>> = mapOf(
            SECTION_SECURITY to listOf(binding.headerSecurity, binding.cardSecurity),
            SECTION_NOTIFICATIONS to listOf(
                binding.headerNotifications, binding.cardNotifications,
                binding.headerPush, binding.cardPushNotifications,
            ),
            SECTION_DIAGNOSTICS to listOf(binding.headerDiagnostics, binding.cardDiagnostics),
            // About and Account must be reachable from a focused sheet: the
            // FULL screen has no route in the shipping app (see
            // WebAppDeviceState.snapshot), so without these groups the
            // licences, the version and account deletion would be unreachable.
            SECTION_ABOUT to listOf(binding.headerAbout, binding.cardAbout, binding.tvVersion),
            SECTION_ACCOUNT to listOf(binding.btnDeleteAccount),
        )
        val keep = groups[section] ?: return

        binding.settingsToolbar.visibility = View.GONE
        // Logout stays off every sheet: the page's own header owns sign-out and
        // runs the same orchestration, so a second one here would be two buttons
        // for one action.
        (groups.values.flatten() + listOf<View>(binding.btnLogout))
            .forEach { it.visibility = View.GONE }
        keep.forEach { it.visibility = View.VISIBLE }

        // The sheet's own title names the group, so the group's FIRST heading
        // would just say it twice. Later headings inside a group stay — for
        // notifications that is "Push delivery", which is a real distinction.
        //
        // Matched against the KNOWN header ids, not against a type. "Is it a
        // TextView?" looks equivalent and is not: MaterialButton extends
        // TextView, so the Account group — whose single member is the delete
        // button — would have had its only control hidden and shown an empty
        // sheet.
        val headerIds = setOf(
            binding.headerSecurity.id, binding.headerNotifications.id,
            binding.headerPush.id, binding.headerDiagnostics.id, binding.headerAbout.id,
        )
        keep.firstOrNull()?.takeIf { it.id in headerIds }?.visibility = View.GONE

        // Flatten the cards: inside the sheet the SHEET is the surface, and a
        // card drawn on top of it reads as a panel within a panel. Losing the
        // elevation, stroke and fill (and their outer margins) makes the rows
        // sit directly on the sheet the way they sit on the web panel.
        keep.filterIsInstance<com.google.android.material.card.MaterialCardView>().forEach { card ->
            card.cardElevation = 0f
            card.strokeWidth = 0
            card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.leftMargin = 0
                lp.rightMargin = 0
                lp.bottomMargin = 0
                card.layoutParams = lp
            }
        }
        // ONE gutter, 24dp, matching the sheet's own title.
        //
        // Padding the ScrollView alone would stack on top of the
        // screen's 16dp page padding and the card's own 16dp: rows at
        // x=56dp under a title at x=24dp, a 32dp misalignment between a
        // heading and the group it heads, and 299dp of usable width
        // inside a 411dp sheet. The content container and the card's
        // own padding are zeroed so the 24dp below is the only
        // horizontal inset left.
        val gutter = (SHEET_GUTTER_DP * resources.displayMetrics.density).toInt()
        clearHorizontalPadding(binding.settingsContent)
        keep.forEach { clearHorizontalPadding(it) }
        binding.settingsScroll.setPadding(gutter, 0, gutter, 0)
    }

    /**
     * Signal-style notification display verbosity. Device-local pref
     * applied at render time by the local notification surfaces
     * (reminder alarms, pinned notes); pushes are content-free
     * tickles regardless, and the
     * in-app UI keeps full detail behind the app lock. This only
     * controls how much decrypted note content lands in the Android
     * tray / lock screen.
     */
    private fun setupNotificationDetailSection() {
        refreshNotificationDetailLabel()
        binding.btnNotificationDetail.setOnClickListener {
            val labels = arrayOf(
                "Titles and content — “Groceries: milk, eggs…”",
                "Titles only — “Groceries”",
                "Generic — “Tap to view”",
            )
            val values = arrayOf(
                SessionManager.NOTIF_DETAIL_FULL,
                SessionManager.NOTIF_DETAIL_TITLES,
                SessionManager.NOTIF_DETAIL_GENERIC,
            )
            val current = values.indexOf(sessionManager.getNotificationDetailLevel())
                .coerceAtLeast(0)

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Notification detail")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    sessionManager.setNotificationDetailLevel(values[which])
                    refreshNotificationDetailLabel()
                    // Push the new detail level to already-rendered widgets and
                    // the pinned notification so stale plaintext is redacted now.
                    refreshHomeScreenSurfaces()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshNotificationDetailLabel() {
        binding.tvNotificationDetailValue.text =
            when (sessionManager.getNotificationDetailLevel()) {
                SessionManager.NOTIF_DETAIL_TITLES -> "Titles only"
                SessionManager.NOTIF_DETAIL_GENERIC -> "Generic"
                else -> "Titles and content"
            }
    }

    /** Re-render the home-screen widgets and any pinned-note notifications so
     *  they reflect the current App Lock / notification-detail redaction level
     *  immediately, instead of showing stale plaintext until the next edit. */
    private fun refreshHomeScreenSurfaces() {
        com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(requireContext())
        com.pingolin.note.service.PinnedNoteService.refreshAllPinned(requireContext())
    }


    /**
     * Push-provider chooser. Lets the user pick FCM or UnifiedPush and, for
     * UnifiedPush, which distributor app delivers the endpoint. A live status
     * line reflects the configured provider and refreshes when the UnifiedPush
     * endpoint lands asynchronously (via [SessionManager.pushSettingsChanged])
     * so it never sticks on "Google FCM" after a switch. This covers the
     * PRIMARY provider only.
     */
    private fun setupPushNotifications() {
        val available = pushNotificationManager.detectAvailableProviders()
        val current = pushNotificationManager.currentProvider()

        // FCM chip is only tappable when Play Services is present; UnifiedPush
        // stays tappable so we can surface an install hint when no distributor
        // is installed.
        binding.chipPushFcm.isEnabled =
            available.contains(PushNotificationManager.Provider.FCM)
        binding.chipPushUnifiedPush.isEnabled = true

        when (current) {
            PushNotificationManager.Provider.FCM -> binding.chipPushFcm.isChecked = true
            PushNotificationManager.Provider.UNIFIEDPUSH -> {
                binding.chipPushUnifiedPush.isChecked = true
                binding.layoutUnifiedPushDistributor.visibility = View.VISIBLE
            }
            else -> binding.chipGroupPushProvider.clearCheck()
        }

        updatePushStatus()
        updatePrimaryDeliverySubtitle()
        updateUnifiedPushDistributorStatus()

        // Refresh when push settings change out-of-band — notably when the
        // UnifiedPush endpoint arrives asynchronously
        // (NoteUnifiedPushReceiver.onNewEndpoint) after the user picks a
        // distributor. Without this the status sits on the old provider until
        // Settings is reopened, even though UnifiedPush is already registered
        // and synced to the backend. Plain launch (not
        // repeatOnLifecycle) so a replay=0 emission arriving while the fragment
        // is briefly stopped — e.g. the distributor app comes to the foreground
        // — is still caught for the lifetime of the view.
        viewLifecycleOwner.lifecycleScope.launch {
            sessionManager.pushSettingsChanged.collectLatest {
                updatePushStatus()
                updateUnifiedPushDistributorStatus()
            }
        }

        binding.chipGroupPushProvider.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.chipPushFcm) -> {
                    binding.layoutUnifiedPushDistributor.visibility = View.GONE
                    updatePrimaryDeliverySubtitle()
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = pushNotificationManager.registerWith(
                            PushNotificationManager.Provider.FCM,
                        )
                        // The FCM token is in hand synchronously — POST it to the
                        // backend (mirrors MainActivity.ensureFcmRegistered) so the
                        // switch actually takes effect server-side.
                        if (result is PushNotificationManager.RegistrationResult.Success &&
                            result.token != null
                        ) {
                            registerFcmWithBackend(result.token)
                        }
                        handlePushRegistrationResult(result)
                    }
                }
                checkedIds.contains(R.id.chipPushUnifiedPush) -> {
                    binding.layoutUnifiedPushDistributor.visibility = View.VISIBLE
                    updatePrimaryDeliverySubtitle()
                    updateUnifiedPushDistributorStatus()
                }
            }
        }

        binding.btnSelectDistributor.setOnClickListener {
            showDistributorSelectionDialog()
        }

    }

    private fun updatePrimaryDeliverySubtitle() {
        binding.tvPrimaryDeliverySubtitle.text = if (binding.chipPushUnifiedPush.isChecked) {
            "Google-free. Requires a distributor app like ntfy."
        } else {
            "Battery efficient. Requires Google Play Services."
        }
    }

    /**
     * Live push status line. During an in-flight provider switch the persisted
     * pushType still reflects the OLD provider until the async endpoint/token
     * lands; when [pendingProvider] is supplied (from a Pending registration
     * result) we reflect the target so the status reads "UnifiedPush —
     * registration pending" instead of the provider we're leaving.
     */
    private fun updatePushStatus(
        pendingProvider: PushNotificationManager.Provider? = null,
    ) {
        val pushType = when (pendingProvider) {
            PushNotificationManager.Provider.UNIFIEDPUSH -> SessionManager.PUSH_TYPE_UNIFIEDPUSH
            PushNotificationManager.Provider.FCM -> SessionManager.PUSH_TYPE_FCM
            else -> sessionManager.getPushType()
        }
        // Holding a token/endpoint locally is NOT the same as the server being
        // able to reach us, and the difference is invisible to the user: the
        // endpoint is persisted before the backend register is even attempted.
        // Require both, so a register that never landed reads as "pending"
        // rather than a green check on push that silently does not work.
        val primaryOk = sessionManager.isPushTokenSynced() && when (pushType) {
            SessionManager.PUSH_TYPE_FCM -> sessionManager.getPushToken() != null
            SessionManager.PUSH_TYPE_UNIFIEDPUSH -> sessionManager.getPushEndpoint() != null
            else -> false
        }
        val label = when (pushType) {
            SessionManager.PUSH_TYPE_FCM -> "Google FCM"
            SessionManager.PUSH_TYPE_UNIFIEDPUSH -> "UnifiedPush"
            else -> null
        }
        binding.tvPushStatus.text = when {
            label == null -> "Status: Not configured"
            !primaryOk -> "Status: $label — registration pending"
            else -> "Status: $label"
        }
        // Green check only once the provider is actually live (token/endpoint
        // on file); no icon while pending or unconfigured.
        val icon = if (label != null && primaryOk) {
            androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_check)
                ?.apply {
                    setTint(requireContext().getColor(com.google.android.material.R.color.design_default_color_primary))
                }
        } else {
            null
        }
        binding.tvPushStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
    }

    private fun updateUnifiedPushDistributorStatus() {
        val distributors = pushNotificationManager.getUnifiedPushDistributors()
        if (distributors.isEmpty()) {
            binding.tvDistributorStatus.text =
                "No UnifiedPush distributor apps installed. Install ntfy or another distributor."
            binding.btnSelectDistributor.isEnabled = false
        } else {
            binding.btnSelectDistributor.isEnabled = true
            val hasEndpoint = sessionManager.getPushEndpoint() != null
            // "Registered" needs more than the local endpoint, which
            // is written before the backend is ever told about it: a
            // failed register would show as success while push never
            // arrives. Report the three states separately.
            binding.tvDistributorStatus.text = when {
                hasEndpoint && sessionManager.isPushTokenSynced() -> "Registered"
                hasEndpoint -> "Endpoint received — completing registration…"
                else -> "${distributors.size} distributor(s) available"
            }
        }
    }

    private fun showDistributorSelectionDialog() {
        val distributors = pushNotificationManager.getUnifiedPushDistributors()
        if (distributors.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No UnifiedPush distributors installed",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val displayNames = distributors.map { pkg ->
            when {
                pkg.contains("ntfy") -> "ntfy"
                pkg.contains("nextpush") -> "NextPush"
                pkg.contains("molly") -> "Molly"
                else -> pkg.substringAfterLast(".")
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Distributor")
            .setItems(displayNames) { _, which ->
                val selected = distributors[which]
                val result = pushNotificationManager.registerWithUnifiedPushDistributor(selected)
                handlePushRegistrationResult(result)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handlePushRegistrationResult(
        result: PushNotificationManager.RegistrationResult,
    ) {
        when (result) {
            is PushNotificationManager.RegistrationResult.Success -> {
                val name = when (result.provider) {
                    PushNotificationManager.Provider.FCM -> "Google FCM"
                    PushNotificationManager.Provider.UNIFIEDPUSH -> "UnifiedPush"
                    PushNotificationManager.Provider.NONE -> "None"
                }
                Toast.makeText(requireContext(), "Registered with $name", Toast.LENGTH_SHORT).show()
                updatePushStatus()
                updateUnifiedPushDistributorStatus()
            }
            is PushNotificationManager.RegistrationResult.Pending -> {
                // Pending is the NORMAL path for UnifiedPush —
                // the distributor hands back an endpoint
                // asynchronously, a beat after this returns —
                // so the line says the request is in flight
                // rather than surfacing an internal message
                // that reads like a failure.
                val waiting = when (result.provider) {
                    PushNotificationManager.Provider.UNIFIEDPUSH ->
                        "Waiting for your distributor to provide an endpoint…"
                    else -> "Finishing registration…"
                }
                Toast.makeText(requireContext(), waiting, Toast.LENGTH_SHORT).show()
                updatePushStatus(result.provider)
                updateUnifiedPushDistributorStatus()
            }
            is PushNotificationManager.RegistrationResult.Failure -> {
                Toast.makeText(
                    requireContext(),
                    "Registration failed: ${result.error}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** POST the freshly obtained FCM token to the note server. Mirrors
     * MainActivity.ensureFcmRegistered so switching to FCM in Settings
     * actually re-registers server-side (the UnifiedPush path self-registers
     * from its receiver). Best-effort; marks synced so SyncWorker won't
     * redundantly re-push. */
    private suspend fun registerFcmWithBackend(token: String) {
        try {
            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                noteApi.registerFcmDevice(
                    RegisterFcmRequest(
                        deviceId = sessionManager.getOrCreateDeviceId(),
                        fcmToken = token,
                        platform = "android",
                        appVersion = runCatching {
                            requireContext().packageManager
                                .getPackageInfo(requireContext().packageName, 0).versionName
                        }.getOrNull(),
                        pushType = SessionManager.PUSH_TYPE_FCM,
                        pushEndpoint = null,
                    ),
                )
            }
            if (response.isSuccessful) {
                sessionManager.setPushTokenSynced(true)
            }
        } catch (_: Exception) {
            // Non-fatal; MainActivity.ensureFcmRegistered retries next launch.
        }
    }

    private fun setupSecuritySection() {
        // App Lock: an app-specific 4-digit PIN plus an optional biometric
        // toggle, which keeps the PIN as a mandatory backup. Accounts whose
        // lock predates the PIN read back as method=BIOMETRICS with no PIN
        // hash; the unlock flow keeps their device-credential prompt until
        // they opt into a PIN, so nobody is locked out.
        val biometricStatus = biometricAuthManager.canAuthenticate()
        val isBiometricAvailable = biometricStatus == BiometricStatus.Available

        val currentMethod = sessionManager.getAppLockMethod()
        binding.switchPinLock.isChecked = currentMethod == SessionManager.APP_LOCK_PIN
        binding.switchBiometric.isChecked = currentMethod == SessionManager.APP_LOCK_BIOMETRICS
        binding.switchBiometric.isEnabled = isBiometricAvailable
        // Dim the WHOLE row, not just the switch. Disabling the switch
        // alone would leave "Use biometrics" at full contrast above a
        // status line reading "No biometrics enrolled on device" — a
        // label that looks live over a control that is not, which reads
        // as an error rather than a state. The row is also un-tappable
        // while disabled, so it cannot swallow a press that would do
        // nothing.
        binding.rowBiometric.isEnabled = isBiometricAvailable
        binding.rowBiometric.alpha = if (isBiometricAvailable) 1f else DISABLED_ALPHA

        // Tapping the LABEL toggles the switch. The switch sits at x=303-355dp
        // on a 411dp phone — the far corner, the worst place for a one-handed
        // right thumb and out of reach left-handed — and every other Android
        // settings screen lets you press the row. performClick() rather than
        // flipping isChecked so the listeners below still run.
        binding.rowPinLock.setOnClickListener { binding.switchPinLock.performClick() }
        binding.rowBiometric.setOnClickListener {
            if (isBiometricAvailable) binding.switchBiometric.performClick()
        }
        binding.rowBlockScreenshots.setOnClickListener {
            binding.switchBlockScreenshots.performClick()
        }

        if (!isBiometricAvailable) {
            binding.tvBiometricStatus.text = when (biometricStatus) {
                BiometricStatus.NoHardware -> getString(R.string.app_lock_no_hardware)
                BiometricStatus.NoneEnrolled -> getString(R.string.app_lock_none_enrolled)
                BiometricStatus.HardwareUnavailable -> getString(R.string.app_lock_hardware_unavailable)
                else -> getString(R.string.app_lock_biometrics_unavailable)
            }
        }

        updateAutoLockVisibility()

        // PIN lock toggle
        binding.switchPinLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // PIN and biometric are mutually exclusive as the primary method.
                binding.switchBiometric.isChecked = false
                showSetPinDialog()
            } else {
                sessionManager.setAppLockMethod(SessionManager.APP_LOCK_NONE)
                sessionManager.clearAppLockPinHash()
                Toast.makeText(requireContext(), getString(R.string.app_lock_pin_removed), Toast.LENGTH_SHORT).show()
                updateAutoLockVisibility()
                // Widgets + the pinned notification redact based on App Lock
                // state, so refresh both now.
                refreshHomeScreenSurfaces()
            }
        }

        // Biometric lock toggle — requires a PIN backup before enabling, so a
        // biometric failure (new fingerprint enrolled, sensor failure) can't
        // lock the user out.
        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (sessionManager.getAppLockPinHash() == null) {
                    binding.switchBiometric.isChecked = false
                    showSetPinForBiometricDialog()
                    return@setOnCheckedChangeListener
                }
                // PIN exists — verify biometric, then enable.
                binding.switchPinLock.isChecked = false
                biometricAuthManager.authenticate(
                    activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                    title = "Enable biometric lock",
                    subtitle = "Verify your identity to enable biometric unlock",
                    onSuccess = {
                        sessionManager.setAppLockMethod(SessionManager.APP_LOCK_BIOMETRICS)
                        // Keep the PIN hash — it's the fallback if biometrics fail.
                        Toast.makeText(requireContext(), getString(R.string.app_lock_biometrics_enabled), Toast.LENGTH_SHORT).show()
                        updateAutoLockVisibility()
                        refreshHomeScreenSurfaces()
                    },
                    onError = { errorCode, _ ->
                        binding.switchBiometric.isChecked = false
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            Toast.makeText(requireContext(), "Authentication failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            } else {
                sessionManager.setAppLockMethod(SessionManager.APP_LOCK_NONE)
                sessionManager.clearAppLockPinHash()
                Toast.makeText(requireContext(), getString(R.string.app_lock_biometrics_disabled), Toast.LENGTH_SHORT).show()
                updateAutoLockVisibility()
                refreshHomeScreenSurfaces()
            }
        }

        // Block screenshots (FLAG_SECURE). Secure by default; this lets the
        // user opt out. Applied live to the host window so it takes effect
        // immediately, and persisted so MainActivity.onCreate honors it next
        // launch. AuthActivity + the lock-screen capture stay always-secure
        // regardless (they show recovery keys / run over the keyguard).
        binding.switchBlockScreenshots.isChecked = viewModel.isBlockScreenshotsEnabled()
        binding.switchBlockScreenshots.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBlockScreenshots(isChecked)
            val window = requireActivity().window
            if (isChecked) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        // Auto-lock timeout
        updateAutoLockLabel(viewModel.getAutoLockTimeout())
        binding.btnAutoLockTimeout.setOnClickListener {
            val options = arrayOf("Immediately", "After 1 minute", "After 5 minutes")
            val values = longArrayOf(
                SessionManager.AUTO_LOCK_IMMEDIATELY,
                SessionManager.AUTO_LOCK_1_MIN,
                SessionManager.AUTO_LOCK_5_MIN,
            )
            val currentIndex = values.indexOf(viewModel.getAutoLockTimeout()).coerceAtLeast(0)

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Auto-lock timeout")
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    viewModel.setAutoLockTimeout(values[which])
                    updateAutoLockLabel(values[which])
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Auto-lock timeout is only meaningful once a lock method is on. */
    private fun updateAutoLockVisibility() {
        binding.layoutAutoLock.visibility =
            if (sessionManager.isAppLockEnabled()) View.VISIBLE else View.GONE
    }

    private fun updateAutoLockLabel(timeout: Long) {
        val label = when (timeout) {
            SessionManager.AUTO_LOCK_1_MIN -> "After 1 minute"
            SessionManager.AUTO_LOCK_5_MIN -> "After 5 minutes"
            else -> "Immediately"
        }
        binding.tvAutoLockValue.text = label
    }

    // ----- PIN set / confirm dialogs -----

    /** Wrap a PIN entry field in a padded container for the dialog body. */
    private fun pinDialogContainer(editText: View): android.widget.FrameLayout {
        return android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(editText)
        }
    }

    private fun newPinEditText(hintRes: Int): com.google.android.material.textfield.TextInputEditText =
        com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            hint = getString(hintRes)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            setSingleLine(true)
        }

    private fun showSetPinDialog() {
        val pinEditText = newPinEditText(R.string.app_lock_enter_pin)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_lock_enter_pin))
            .setView(pinDialogContainer(pinEditText))
            .setPositiveButton("Next") { _, _ ->
                val pin = readAndClearPin(pinEditText)
                if (pin.size == 4) {
                    showConfirmPinDialog(pin)
                } else {
                    pin.fill('\u0000')
                    binding.switchPinLock.isChecked = false
                    Toast.makeText(requireContext(), "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                readAndClearPin(pinEditText).fill('\u0000')
                binding.switchPinLock.isChecked = false
            }
            .setOnCancelListener {
                readAndClearPin(pinEditText).fill('\u0000')
                binding.switchPinLock.isChecked = false
            }
            .show()
    }

    private fun showConfirmPinDialog(originalPin: CharArray) {
        val confirmPinEditText = newPinEditText(R.string.app_lock_confirm_pin)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_lock_confirm_pin))
            .setView(pinDialogContainer(confirmPinEditText))
            // Confirm handled in the OnShowListener so the dialog doesn't auto-dismiss
            // while the async PBKDF2 hash runs.
            .setPositiveButton("Confirm", null)
            .setNegativeButton("Cancel") { _, _ ->
                readAndClearPin(confirmPinEditText).fill('\u0000')
                originalPin.fill('\u0000')
                binding.switchPinLock.isChecked = false
            }
            .setOnCancelListener {
                readAndClearPin(confirmPinEditText).fill('\u0000')
                originalPin.fill('\u0000')
                binding.switchPinLock.isChecked = false
            }
            .create()

        dialog.setOnShowListener {
            val confirmButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            confirmButton.setOnClickListener {
                val confirmPin = readAndClearPin(confirmPinEditText)
                if (!charArraysEqual(confirmPin, originalPin)) {
                    confirmPin.fill('\u0000')
                    originalPin.fill('\u0000')
                    binding.switchPinLock.isChecked = false
                    Toast.makeText(requireContext(), getString(R.string.app_lock_pin_mismatch), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setOnClickListener
                }
                // PBKDF2 hash (210k HMAC-SHA256) off the main thread (~100-500ms);
                // disable Confirm while it runs so a double-tap can't double-hash.
                confirmButton.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val pinHash = withContext(Dispatchers.Default) { hashPin(originalPin) }
                        sessionManager.setAppLockPinHash(pinHash)
                        sessionManager.setAppLockMethod(SessionManager.APP_LOCK_PIN)
                        Toast.makeText(requireContext(), getString(R.string.app_lock_pin_set), Toast.LENGTH_SHORT).show()
                        updateAutoLockVisibility()
                        refreshHomeScreenSurfaces()
                    } finally {
                        // Always wipe the PINs AND dismiss the dialog, even if the
                        // view is destroyed mid-hash (coroutine cancelled). A
                        // dismiss() sitting inside the cancellable body instead
                        // would run only when the hash completes, so cancellation
                        // leaks the dialog window. dismiss() is guarded because
                        // the window may already be gone on the cancel path.
                        confirmPin.fill('\u0000')
                        originalPin.fill('\u0000')
                        runCatching { dialog.dismiss() }
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * PIN setup as a prerequisite for enabling biometric lock. After the PIN is
     * confirmed it's stored as the biometric fallback and the biometric toggle
     * is re-triggered (which now finds a PIN and proceeds to verify).
     */
    private fun showSetPinForBiometricDialog() {
        val pinEditText = newPinEditText(R.string.app_lock_enter_pin)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set backup PIN")
            .setMessage("A 4-digit PIN is required as a backup in case biometrics aren't available (e.g., a new fingerprint enrolled or sensor failure).")
            .setView(pinDialogContainer(pinEditText))
            .setPositiveButton("Next") { _, _ ->
                val pin = readAndClearPin(pinEditText)
                if (pin.size == 4) {
                    showConfirmPinForBiometricDialog(pin)
                } else {
                    pin.fill('\u0000')
                    Toast.makeText(requireContext(), "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                readAndClearPin(pinEditText).fill('\u0000')
            }
            .setOnCancelListener {
                readAndClearPin(pinEditText).fill('\u0000')
            }
            .show()
    }

    private fun showConfirmPinForBiometricDialog(originalPin: CharArray) {
        val confirmPinEditText = newPinEditText(R.string.app_lock_confirm_pin)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_lock_confirm_pin))
            .setView(pinDialogContainer(confirmPinEditText))
            // Confirm handled in the OnShowListener so the dialog doesn't auto-dismiss
            // while the async PBKDF2 hash runs.
            .setPositiveButton("Confirm", null)
            .setNegativeButton("Cancel") { _, _ ->
                readAndClearPin(confirmPinEditText).fill('\u0000')
                originalPin.fill('\u0000')
            }
            .setOnCancelListener {
                readAndClearPin(confirmPinEditText).fill('\u0000')
                originalPin.fill('\u0000')
            }
            .create()

        dialog.setOnShowListener {
            val confirmButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            confirmButton.setOnClickListener {
                val confirmPin = readAndClearPin(confirmPinEditText)
                if (!charArraysEqual(confirmPin, originalPin)) {
                    confirmPin.fill('\u0000')
                    originalPin.fill('\u0000')
                    Toast.makeText(requireContext(), getString(R.string.app_lock_pin_mismatch), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setOnClickListener
                }
                // Save PIN hash off the main thread (PBKDF2 210k), then re-trigger the
                // biometric toggle (which now finds a PIN and proceeds to verify).
                // Disable Confirm while hashing so a double-tap can't double-hash.
                confirmButton.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val pinHash = withContext(Dispatchers.Default) { hashPin(originalPin) }
                        sessionManager.setAppLockPinHash(pinHash)
                        Toast.makeText(requireContext(), "Backup PIN set", Toast.LENGTH_SHORT).show()
                        binding.switchBiometric.isChecked = true
                    } finally {
                        // Always wipe the PINs AND dismiss the dialog, even if the
                        // view is destroyed mid-hash (coroutine cancelled) - otherwise
                        // the dialog window leaks. dismiss() guarded for the cancel path.
                        confirmPin.fill('\u0000')
                        originalPin.fill('\u0000')
                        runCatching { dialog.dismiss() }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun hashPin(pin: CharArray): String = PinHasher.hashPin(pin)

    /**
     * Copy the EditText's contents into a fresh CharArray and wipe the
     * underlying Editable so the plaintext PIN doesn't linger on the heap.
     * Callers MUST wipe the returned CharArray after use.
     */
    private fun readAndClearPin(editText: com.google.android.material.textfield.TextInputEditText): CharArray {
        val editable = editText.text ?: return CharArray(0)
        val length = editable.length
        if (length == 0) return CharArray(0)
        val out = CharArray(length)
        editable.getChars(0, length, out, 0)
        editable.clear()
        return out
    }

    private fun charArraysEqual(a: CharArray, b: CharArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }




    /** Licences, sign-out and account deletion. Sync, export and import
     * are in the page, whose export routes through the shell's native
     * save channel. */
    private fun setupActionsSection() {
        binding.btnOpenSourceLicenses.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_licenses)
        }

        binding.btnLogout.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Logout")
                .setMessage("Log out? Any unsynced changes will be uploaded first. If you're offline, unsynced notes may be lost.")
                .setPositiveButton("Logout") { _, _ ->
                    viewModel.logout()
                    navigateToAuth()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnDeleteAccount.setOnClickListener {
            showDeleteAccountConfirmation()
        }
    }

    private fun showDeleteAccountConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Account")
            .setMessage(
                "Are you sure you want to permanently delete your account? This action cannot be undone." +
                    "\n\nAll your notes, notebooks, attachments, shares and reminders will be permanently deleted." +
                    // The same account owns Track circles, and those end with it for
                    // everyone in them. Every delete screen says so, this one
                    // included.
                    "\n\nCircles you own in Pingolin Track are deleted for everyone in them, along with all the " +
                    "location history they hold. To keep a circle going, transfer its ownership in Track first." +
                    // The account is the auth-service account, not a per-app one, and
                    // deleting it cascades into every app that hangs off it (note data
                    // AND track data). Someone deleting from Note has no way to know
                    // their location history goes with it unless this dialog says so,
                    // before the button.
                    "\n\nThis is your Pingolin account, which every Pingolin app shares. Deleting it deletes " +
                    "your data in all of them - Pingolin Note and Pingolin Track - not just this app." +
                    // Stated before the button, because afterwards there is no account
                    // left to read it in. We cancel what we can; an App Store
                    // subscription only Apple can stop, at the customer's request.
                    "\n\nThis also cancels your subscription. If you bought it through the App Store on an " +
                    "iPhone or iPad, only you can cancel that - in your Apple account settings."
            )
            .setPositiveButton("Delete") { _, _ ->
                showPasswordDialogForDeletion()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasswordDialogForDeletion(forfeitCredit: Boolean = false) {
        val passwordEditText = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val textInputLayout = com.google.android.material.textfield.TextInputLayout(
            requireContext(),
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "Enter your password"
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(passwordEditText)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(textInputLayout)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Deletion")
            // Set on the BUILDER, not on the created dialog: a dialog that also
            // carries a custom view drops a message applied afterwards.
            .setMessage("Please enter your password to confirm account deletion.")
            .setView(container)
            .setPositiveButton("Delete Account") { _, _ ->
                val editable = passwordEditText.text
                if (editable != null && editable.isNotEmpty()) {
                    val password = CharArray(editable.length)
                    editable.getChars(0, editable.length, password, 0)
                    // Wipe the EditText buffer so the plaintext password doesn't linger.
                    editable.clear()
                    viewModel.deleteAccount(password, forfeitCredit)
                } else {
                    Toast.makeText(requireContext(), "Password is required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupCrashLogsSection() {
        val logs = crashReporter.getCrashLogs()
        binding.tvCrashLogCount.text = if (logs.isEmpty()) {
            "No crash logs"
        } else {
            "${logs.size} crash log${if (logs.size > 1) "s" else ""}"
        }

        binding.btnCrashLogs.setOnClickListener {
            val crashLogs = crashReporter.getCrashLogs()
            if (crashLogs.isEmpty()) {
                Toast.makeText(requireContext(), "No crash logs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val names = crashLogs.map { "${it.fileName} (${it.getFormattedTimestamp()})" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Crash Logs (${crashLogs.size})")
                .setItems(names) { _, which ->
                    val log = crashLogs[which]
                    val content = crashReporter.readCrashLog(log.fileName)
                    if (content != null) {
                        showCrashLogDialog(log.fileName, content)
                    }
                }
                .setNeutralButton("Export All") { _, _ ->
                    exportAllCrashLogs()
                }
                .setNegativeButton("Delete All") { _, _ ->
                    crashReporter.deleteAllCrashLogs()
                    binding.tvCrashLogCount.text = "No crash logs"
                    Toast.makeText(requireContext(), "All crash logs deleted", Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun showCrashLogDialog(fileName: String, content: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(fileName)
            .setMessage(content)
            .setPositiveButton("Share") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Crash Log: $fileName")
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                startActivity(Intent.createChooser(shareIntent, "Share crash log"))
            }
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Crash Log", content))
                Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun exportAllCrashLogs() {
        val logs = crashReporter.getCrashLogs()
        val allContent = buildString {
            for (log in logs) {
                val content = crashReporter.readCrashLog(log.fileName) ?: continue
                appendLine(content)
                appendLine()
            }
        }

        if (allContent.isBlank()) {
            Toast.makeText(requireContext(), "No crash logs to export", Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Pingolin Note - Crash Logs")
            putExtra(Intent.EXTRA_TEXT, allContent)
        }
        startActivity(Intent.createChooser(shareIntent, "Export crash logs"))
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Account deletion — the only flow this screen
                // owns that has server state to watch. Sync,
                // display name, tier and export live in the
                // page with their controls.
                launch {
                    viewModel.deletionState.collectLatest { state ->
                        when (state) {
                            is AccountDeletionState.Idle -> Unit
                            is AccountDeletionState.InProgress ->
                                binding.btnDeleteAccount.isEnabled = false
                            is AccountDeletionState.Deleted -> onAccountDeleted(state.billingNotice)
                            is AccountDeletionState.UnspentCredit -> {
                                // Nothing deleted. Message on the BUILDER —
                                // AlertDialog inflates its message TextView only
                                // when setMessage precedes create().
                                binding.btnDeleteAccount.isEnabled = true
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("You still have credit")
                                    .setMessage(state.message)
                                    .setPositiveButton("Delete anyway") { _, _ ->
                                        showPasswordDialogForDeletion(forfeitCredit = true)
                                    }
                                    .setNegativeButton("Keep my credit", null)
                                    .show()
                            }
                            is AccountDeletionState.Failed -> {
                                binding.btnDeleteAccount.isEnabled = true
                                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The account is gone. [billingNotice] is what the user still has to do
     * about money themselves, or null when there is nothing outstanding.
     */
    private fun onAccountDeleted(billingNotice: String?) {
        if (billingNotice == null) {
            Toast.makeText(requireContext(), "Account deleted", Toast.LENGTH_LONG).show()
            navigateToAuth()
            return
        }
        // A toast would be wrong twice over: it is dismissible by accident and
        // navigateToAuth() would race it off the screen. The account is already
        // gone, so this is the last chance to say it.
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Account deleted")
            // On the BUILDER, not the created dialog — set on the dialog it
            // renders empty.
            .setMessage(billingNotice)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> navigateToAuth() }
            .show()
    }




    private fun navigateToAuth() {
        val intent = Intent(requireContext(), AuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }

    override fun onResume() {
        super.onResume()
        // Picks up a recovery address (or display name) changed on web while
        // this screen was backgrounded — which for the recovery address is the
        // ONLY way it can change, so it is the expected path, not an edge case.
        viewModel.refreshProfile()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Fragment arg: which section to reveal. Values are the ones
         *  [com.pingolin.note.ui.web.WebAppDeviceState] hands the page, so the
         *  two ends cannot drift. */
        const val ARG_SECTION = "settings_section"

        /** Fragment arg: show only [ARG_SECTION]'s group, no toolbar — the
         *  shape the device-settings sheet wants. */
        const val ARG_FOCUSED = "settings_focused"

        /** The web panel's 24px gutter, so the sheet's rows line up with the
         *  panel's rather than the screen's. Density-converted at use — a raw
         *  pixel value here would be ~9dp on a 2.6x phone. */
        private const val SHEET_GUTTER_DP = 24f

        /** Material's disabled-content opacity. */
        private const val DISABLED_ALPHA = 0.38f

        /** Build the args for a focused, single-group instance. */
        fun focusedArgs(section: String) = android.os.Bundle().apply {
            putString(ARG_SECTION, section)
            putBoolean(ARG_FOCUSED, true)
        }


        private const val SECTION_SECURITY = com.pingolin.note.ui.web.WebAppDeviceState.SECTION_SECURITY
        private const val SECTION_NOTIFICATIONS = com.pingolin.note.ui.web.WebAppDeviceState.SECTION_NOTIFICATIONS
        private const val SECTION_DIAGNOSTICS = com.pingolin.note.ui.web.WebAppDeviceState.SECTION_DIAGNOSTICS
        private const val SECTION_ABOUT = com.pingolin.note.ui.web.WebAppDeviceState.SECTION_ABOUT
        private const val SECTION_ACCOUNT = com.pingolin.note.ui.web.WebAppDeviceState.SECTION_ACCOUNT
    }
}
