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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.note.ui.auth.AuthActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Account deletion, from the first warning to the sign-in screen, as one flow
 * any screen can start.
 *
 * The page's Settings panel ends its Account section with "Delete account",
 * the shell's `account` row ([com.pingolin.note.ui.web.WebAppDeviceState.SECTION_ACCOUNT]).
 * That row used to open a sheet holding one button, which opened this warning:
 * a menu of one item, a tap and a screen between the row and what it does.
 * [com.pingolin.note.ui.web.WebAppActivity] now starts this flow straight from
 * the row, and the settings screen's own button starts the same flow, so the
 * dialogs and the handling of the result exist once.
 *
 * No view: the dialogs are the UI. It holds the [SettingsViewModel] whose
 * [SettingsViewModel.deleteAccount] does the work, watches its state, and
 * removes itself whenever the flow ends without a deletion (every Cancel, a
 * dismissed dialog, "Keep my credit", a failure). A deletion ends on the
 * sign-in screen.
 */
@AndroidEntryPoint
class DeleteAccountFlow : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()

    /** Up while the request runs: the dialogs are gone by then, and without
     *  it nothing on screen would say anything is happening. */
    private var progress: AlertDialog? = null

    /** The state last acted on. The state survives a trip to the background
     *  and is delivered again on return; one refusal is one dialog. */
    private var handled: AccountDeletionState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null &&
            viewModel.deletionState.value is AccountDeletionState.Idle
        ) {
            // Recreated (a rotation): the plain dialogs it had up are gone and
            // nothing was sent. Leave; the row starts it again.
            end()
            return
        }
        if (savedInstanceState == null) showDeleteAccountConfirmation()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.deletionState.collectLatest { state -> onState(state) }
            }
        }
    }

    private fun onState(state: AccountDeletionState) {
        showProgress(state is AccountDeletionState.InProgress)
        if (state === handled) return
        handled = state
        when (state) {
            is AccountDeletionState.Idle, is AccountDeletionState.InProgress -> Unit
            is AccountDeletionState.Deleted -> onAccountDeleted(state.billingNotice)
            is AccountDeletionState.UnspentCredit -> {
                // Nothing deleted. Message on the BUILDER: AlertDialog inflates
                // its message TextView only when setMessage precedes create().
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("You still have credit")
                    .setMessage(state.message)
                    .setPositiveButton("Delete anyway") { _, _ ->
                        showPasswordDialogForDeletion(forfeitCredit = true)
                    }
                    .setNegativeButton("Keep my credit") { _, _ -> end() }
                    .setOnCancelListener { end() }
                    .show()
            }
            is AccountDeletionState.Failed -> {
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                end()
            }
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
                    "\n\nThis is your Pingolin account for every Pingolin app. Deleting it deletes " +
                    "your data in both Pingolin Track and Pingolin Note." +
                    // Stated before the button, because afterwards there is no account
                    // left to read it in. We cancel what we can; an App Store
                    // subscription only Apple can stop, at the customer's request.
                    "\n\nDeleting your account cancels any subscription you pay for through us. An App Store " +
                    "subscription is different: only you can cancel it, in your Apple account settings."
            )
            .setPositiveButton("Delete") { _, _ ->
                showPasswordDialogForDeletion()
            }
            .setNegativeButton("Cancel") { _, _ -> end() }
            .setOnCancelListener { end() }
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
            .setMessage("Enter your password to confirm.")
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
                    // The button closed the dialog; ask again rather than leave
                    // the person with nothing on screen and no way on.
                    Toast.makeText(requireContext(), "Password is required", Toast.LENGTH_SHORT).show()
                    showPasswordDialogForDeletion(forfeitCredit)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> end() }
            .setOnCancelListener { end() }
            .show()
    }

    private fun showProgress(show: Boolean) {
        if (!show) {
            progress?.dismiss()
            progress = null
            return
        }
        if (progress != null) return
        progress = MaterialAlertDialogBuilder(requireContext())
            .setMessage("Deleting account…")
            .setCancelable(false)
            .show()
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
            // On the BUILDER, not the created dialog: set on the dialog it
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

    /** Done without a deletion: take the flow off the screen it was started on. */
    private fun end() {
        progress?.dismiss()
        progress = null
        if (isAdded && !isRemoving) {
            parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
        }
    }

    override fun onDestroy() {
        progress?.dismiss()
        progress = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "delete-account-flow"

        /**
         * Start the flow over [fm]'s screen. A second tap while one is already
         * running does nothing: two warnings stacked would be two flows racing
         * one deletion.
         */
        fun start(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) != null) return
            fm.beginTransaction().add(DeleteAccountFlow(), TAG).commitAllowingStateLoss()
        }
    }
}
