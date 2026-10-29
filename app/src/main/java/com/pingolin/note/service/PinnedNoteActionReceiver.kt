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

package com.pingolin.note.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles unpin action from pinned note notifications.
 */
class PinnedNoteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PinnedNoteService.ACTION_UNPIN) return
        val noteId = intent.getStringExtra(PinnedNoteService.EXTRA_NOTE_ID) ?: return
        PinnedNoteService.unpinNote(context, noteId)
    }
}
