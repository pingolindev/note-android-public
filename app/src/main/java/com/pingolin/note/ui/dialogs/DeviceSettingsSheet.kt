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

package com.pingolin.note.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.pingolin.note.R
import com.pingolin.note.databinding.BottomSheetDeviceSettingsBinding
import com.pingolin.note.ui.settings.SettingsFragment
import com.pingolin.note.ui.web.WebAppDeviceState
import dagger.hilt.android.AndroidEntryPoint

/**
 * One group of device settings, as a sheet over the page.
 *
 * ## Why a sheet and not the settings screen
 *
 * The web panel lists app lock, screenshots, push delivery and notification
 * detail as rows with their current values. Starting a whole other Activity
 * from one of them — a full-screen page, its own toolbar, its own title that
 * also says "Settings" — would make a tap inside Settings appear to leave the
 * app for a rival menu.
 *
 * This is the same layer idiom the panel itself uses: the page stays visible
 * behind, the sheet carries only the group that was tapped, and it is
 * dismissed rather than navigated away from. The iOS app presents its native
 * settings as a sheet too.
 *
 * ## Why it hosts SettingsFragment rather than reimplementing the controls
 *
 * App lock is PIN dialogs, a biometric prompt and an auto-lock picker; push
 * delivery is a provider chooser, a distributor picker and a live status line.
 * Rebuilding that for the sheet would put the same logic in two places. The
 * fragment renders one group in focused mode ([SettingsFragment.focusedArgs]),
 * so the controls exist once.
 */
@AndroidEntryPoint
class DeviceSettingsSheet : ExpandedBottomSheetDialogFragment() {

    private var _binding: BottomSheetDeviceSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetDeviceSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val section = arguments?.getString(ARG_SECTION) ?: WebAppDeviceState.SECTION_SECURITY

        binding.tvDeviceSheetTitle.setText(
            when (section) {
                WebAppDeviceState.SECTION_NOTIFICATIONS -> R.string.device_sheet_title_notifications
                WebAppDeviceState.SECTION_DIAGNOSTICS -> R.string.device_sheet_title_diagnostics
                WebAppDeviceState.SECTION_ABOUT -> R.string.device_sheet_title_about
                WebAppDeviceState.SECTION_ACCOUNT -> R.string.device_sheet_title_account
                else -> R.string.device_sheet_title_security
            },
        )
        // The subtitle is per-section: a single "Set on this device
        // only." is true of app lock and push transport, and flatly
        // wrong above a button that deletes the account on every
        // device. A reassurance that is false in one section is worse
        // than no reassurance at all.
        binding.tvDeviceSheetSubtitle.setText(
            when (section) {
                WebAppDeviceState.SECTION_ABOUT -> R.string.device_sheet_subtitle_about
                WebAppDeviceState.SECTION_ACCOUNT -> R.string.device_sheet_subtitle_account
                else -> R.string.device_sheet_subtitle
            },
        )

        // Only on first creation: on a rotation the child fragment is restored
        // by the FragmentManager, and adding a second one stacks two copies of
        // the controls.
        if (savedInstanceState == null) {
            childFragmentManager.commit {
                setReorderingAllowed(true)
                replace(
                    binding.deviceSheetContainer.id,
                    SettingsFragment::class.java,
                    SettingsFragment.focusedArgs(section),
                )
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // Tell the page what changed. A sheet dismiss is not an Activity
        // resume, so nothing else would.
        (activity as? com.pingolin.note.ui.web.WebAppActivity)?.refreshDeviceSettingsSnapshot()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SECTION = "device_sheet_section"
        private const val TAG = "device-settings-sheet"

        /** Sections the sheet can show. Anything else belongs on the full
         *  screen, which the section-less deep link still opens. */
        fun handles(section: String?): Boolean = section in setOf(
            WebAppDeviceState.SECTION_SECURITY,
            WebAppDeviceState.SECTION_NOTIFICATIONS,
            WebAppDeviceState.SECTION_DIAGNOSTICS,
            WebAppDeviceState.SECTION_ABOUT,
            WebAppDeviceState.SECTION_ACCOUNT,
        )

        fun show(fm: FragmentManager, section: String) {
            // Replace rather than stack: tapping a second Device row while one
            // sheet is up should swap the group, not bury it.
            (fm.findFragmentByTag(TAG) as? DeviceSettingsSheet)?.dismissAllowingStateLoss()
            DeviceSettingsSheet().apply {
                arguments = Bundle().apply { putString(ARG_SECTION, section) }
            }.show(fm, TAG)
        }
    }
}
