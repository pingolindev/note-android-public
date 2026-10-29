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

/**
 * The words of the "shared with you" notification, in one place for the three
 * push paths (NoteFirebaseMessagingService, NoteUnifiedPushReceiver,
 * NoteTickleHandler). The dispatcher sends a note share as kind "share" and a
 * notebook share as kind "notebook_share". Both sit behind the device-local
 * "Shared notes" switch, as the server's own `shared_notes_push` preference
 * covers both.
 */
internal object ShareNotice {
    const val NOTEBOOK_KIND = "notebook_share"

    fun title(kind: String?): String =
        if (kind == NOTEBOOK_KIND) "New notebook shared with you" else "New note shared with you"

    fun text(kind: String?): String =
        if (kind == NOTEBOOK_KIND) "Tap to accept or decline it" else "Tap to open Shared with me"
}
