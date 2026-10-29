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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Render-time redaction levels for the local notification surfaces.
 * The contract under test: at "generic" no decrypted note content
 * reaches the tray; at "titles" note titles survive but body previews
 * don't; "full" is the unredacted default. Blank/absent titles fall
 * back to neutral copy at every level.
 */
class NotificationDetailTest {

    // ---- reminder body ----

    @Test
    fun `reminder full shows note title`() {
        assertEquals(
            "Groceries",
            NotificationDetail.reminderBody(SessionManager.NOTIF_DETAIL_FULL, "Groceries"),
        )
    }

    @Test
    fun `reminder titles level shows note title`() {
        // A reminder's only content IS the title, so full and titles
        // render the same; the distinction matters for pinned previews.
        assertEquals(
            "Groceries",
            NotificationDetail.reminderBody(SessionManager.NOTIF_DETAIL_TITLES, "Groceries"),
        )
    }

    @Test
    fun `reminder generic drops the title`() {
        assertEquals(
            "Tap to view your note",
            NotificationDetail.reminderBody(SessionManager.NOTIF_DETAIL_GENERIC, "Groceries"),
        )
    }

    @Test
    fun `reminder blank title falls back to neutral copy`() {
        assertEquals(
            "Tap to view your note",
            NotificationDetail.reminderBody(SessionManager.NOTIF_DETAIL_FULL, "  "),
        )
        assertEquals(
            "Tap to view your note",
            NotificationDetail.reminderBody(SessionManager.NOTIF_DETAIL_FULL, null),
        )
    }

    // ---- pinned title ----

    @Test
    fun `pinned title survives full and titles levels`() {
        assertEquals(
            "Groceries",
            NotificationDetail.pinnedTitle(SessionManager.NOTIF_DETAIL_FULL, "Groceries"),
        )
        assertEquals(
            "Groceries",
            NotificationDetail.pinnedTitle(SessionManager.NOTIF_DETAIL_TITLES, "Groceries"),
        )
    }

    @Test
    fun `pinned title generic is fixed copy`() {
        assertEquals(
            "Pinned note",
            NotificationDetail.pinnedTitle(SessionManager.NOTIF_DETAIL_GENERIC, "Groceries"),
        )
    }

    @Test
    fun `pinned untitled note falls back to Note`() {
        assertEquals(
            "Note",
            NotificationDetail.pinnedTitle(SessionManager.NOTIF_DETAIL_FULL, null),
        )
    }

    // ---- pinned preview ----

    @Test
    fun `pinned preview only at full detail`() {
        assertEquals(
            "milk, eggs",
            NotificationDetail.pinnedPreview(SessionManager.NOTIF_DETAIL_FULL, "milk, eggs"),
        )
        assertNull(NotificationDetail.pinnedPreview(SessionManager.NOTIF_DETAIL_TITLES, "milk, eggs"))
        assertNull(NotificationDetail.pinnedPreview(SessionManager.NOTIF_DETAIL_GENERIC, "milk, eggs"))
    }

    // ---- widget title ----

    @Test
    fun `widget title survives full and titles levels`() {
        assertEquals(
            "Groceries",
            NotificationDetail.widgetTitle(SessionManager.NOTIF_DETAIL_FULL, "Groceries"),
        )
        assertEquals(
            "Groceries",
            NotificationDetail.widgetTitle(SessionManager.NOTIF_DETAIL_TITLES, "Groceries"),
        )
    }

    @Test
    fun `widget title generic drops the decrypted title`() {
        // App Lock forces generic; the row must not leak the note title.
        assertEquals(
            "Locked note",
            NotificationDetail.widgetTitle(SessionManager.NOTIF_DETAIL_GENERIC, "Groceries"),
        )
    }

    @Test
    fun `widget untitled note falls back to Untitled`() {
        assertEquals(
            "Untitled",
            NotificationDetail.widgetTitle(SessionManager.NOTIF_DETAIL_FULL, null),
        )
        assertEquals(
            "Untitled",
            NotificationDetail.widgetTitle(SessionManager.NOTIF_DETAIL_FULL, "  "),
        )
    }

    // ---- widget preview ----

    @Test
    fun `widget preview only at full detail`() {
        assertEquals(
            "milk, eggs",
            NotificationDetail.widgetPreview(SessionManager.NOTIF_DETAIL_FULL, "milk, eggs"),
        )
        assertNull(NotificationDetail.widgetPreview(SessionManager.NOTIF_DETAIL_TITLES, "milk, eggs"))
        assertNull(NotificationDetail.widgetPreview(SessionManager.NOTIF_DETAIL_GENERIC, "milk, eggs"))
    }

    @Test
    fun `unknown level treated as full`() {
        // SessionManager normalizes unknown stored values to full, but
        // the helper itself must also fail open to full (display-layer
        // preference, not a security boundary — never bury a reminder).
        assertEquals(
            "Groceries",
            NotificationDetail.reminderBody("definitely-not-a-level", "Groceries"),
        )
        assertEquals(
            "milk, eggs",
            NotificationDetail.pinnedPreview("definitely-not-a-level", "milk, eggs"),
        )
    }
}
