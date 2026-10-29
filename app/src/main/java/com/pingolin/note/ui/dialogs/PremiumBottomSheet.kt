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
import com.pingolin.note.databinding.BottomSheetPremiumBinding

/**
 * Read-only explainer for what Pingolin Note Premium unlocks. Shown
 * when the page's Upgrade call-to-action is tapped (the
 * `pingolin-upgrade` scheme handled by WebAppActivity). Names no
 * purchase channel and exposes no upgrade route — Google Play
 * prohibits directing users to an external purchase method for
 * digital goods when not using Play Billing, and this app is not on
 * Play Billing.
 */
class PremiumBottomSheet : ExpandedBottomSheetDialogFragment() {

    private var _binding: BottomSheetPremiumBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetPremiumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnPremiumDismiss.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "premium_sheet"

        /** Show the explainer. Show at most one instance —
         *  repeated calls during a single drag/tap are no-ops. */
        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) != null) return
            PremiumBottomSheet().show(fm, TAG)
        }
    }
}
