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

import android.app.Dialog
import android.os.Bundle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * A bottom sheet that opens at its full content height, in every orientation.
 *
 * ## Why this exists
 *
 * Material's default is a COLLAPSED sheet whose peek height is a fraction of the
 * window. In portrait that fraction is generous enough that a short sheet looks
 * fine by accident. In landscape it is not: in a phone-sized landscape window
 * (780.2 x 411.4 dp on an A50) the device-settings sheet comes up as a **64 dp
 * stub** — title, a clipped subtitle, and the App Lock PIN, biometrics and Block
 * screenshots switches absent from the view tree entirely, with no drag or scroll
 * that reaches them. Turn the phone sideways and the app's only device-local
 * security controls would be unreachable.
 *
 * ## Why a base class rather than three copies
 *
 * Same reason [DeviceSettingsSheet] hosts the real settings fragment instead of
 * reimplementing its controls: behaviour that has to be remembered three times
 * will be present in two of them. Any new sheet should extend this.
 *
 * `isFitToContents` keeps a SHORT sheet short — expanding is about removing the
 * arbitrary peek cap, not about forcing every sheet to full height.
 * `skipCollapsed` means a downward drag dismisses instead of snapping back to a
 * stub the user cannot use.
 */
abstract class ExpandedBottomSheetDialogFragment : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { dialog ->
            // setOnShowListener rather than onStart(): the behavior has to be
            // set after the sheet's content view is attached and measured,
            // otherwise STATE_EXPANDED is applied to a sheet whose height is
            // still zero and the peek cap wins anyway.
            dialog.setOnShowListener {
                val behavior = (dialog as? BottomSheetDialog)?.behavior ?: return@setOnShowListener
                behavior.isFitToContents = true
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
}
