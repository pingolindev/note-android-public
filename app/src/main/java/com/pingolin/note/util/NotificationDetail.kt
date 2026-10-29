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

/**
 * Render-time redaction for the LOCAL surfaces, applying
 * SessionManager's notification-detail level (Signal-style
 * full/titles/generic). Push-driven notifications are already
 * content-free and don't come through here; the surfaces that put
 * decrypted note content onto the Android tray / lock screen / home
 * screen are the reminder alarm (note title), the pinned-note ongoing
 * notification (title + body preview) and the home-screen widgets (title
 * + body preview). Widgets have no VISIBILITY_PRIVATE boundary, so their
 * callers additionally force generic while App Lock is enabled.
 *
 * Level semantics for note:
 * - full:    titles and previews, current behavior
 * - titles:  note titles survive, bodies/previews don't
 * - generic: fixed copy, no decrypted content at all
 *
 * Notification TITLES of the event-type kind ("Note Reminder") always
 * survive — they carry no user content.
 */
object NotificationDetail {

    /** Body line of the reminder notification. The only content a
     *  reminder carries is the note's title, so full and titles render
     *  the same; generic drops it. */
    fun reminderBody(level: String, noteTitle: String?): String =
        when (level) {
            SessionManager.NOTIF_DETAIL_GENERIC -> "Tap to view your note"
            else -> noteTitle?.takeIf { it.isNotBlank() } ?: "Tap to view your note"
        }

    fun pinnedTitle(level: String, noteTitle: String?): String =
        when (level) {
            SessionManager.NOTIF_DETAIL_GENERIC -> "Pinned note"
            else -> noteTitle?.takeIf { it.isNotBlank() } ?: "Note"
        }

    /** Body/preview of the pinned-note notification, or null when the
     *  level says the decrypted body must not reach the tray. Unknown
     *  levels behave like full — same normalization SessionManager's
     *  getter applies; this is a display preference, not a security
     *  boundary. */
    fun pinnedPreview(level: String, preview: String?): String? =
        when (level) {
            SessionManager.NOTIF_DETAIL_TITLES,
            SessionManager.NOTIF_DETAIL_GENERIC,
            -> null
            else -> preview
        }

    /** Title line for a home-screen widget note row. Widgets are another
     *  LOCAL surface that renders decrypted content, but unlike
     *  notifications they have no lock-screen VISIBILITY_PRIVATE boundary,
     *  so the caller passes an effective level already forced to generic
     *  when App Lock is on. generic drops the decrypted title. */
    fun widgetTitle(level: String, noteTitle: String?): String =
        when (level) {
            SessionManager.NOTIF_DETAIL_GENERIC -> "Locked note"
            else -> noteTitle?.takeIf { it.isNotBlank() } ?: "Untitled"
        }

    /** Body/preview for a home-screen widget note row, or null when the
     *  level says the decrypted body must not reach the widget (titles or
     *  generic — same semantics as pinnedPreview). */
    fun widgetPreview(level: String, preview: String?): String? =
        when (level) {
            SessionManager.NOTIF_DETAIL_TITLES,
            SessionManager.NOTIF_DETAIL_GENERIC,
            -> null
            else -> preview
        }
}
