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

package com.pingolin.note.ui.web

import com.pingolin.note.util.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These guard a contract that spans two repos: the row shape the Note web
 * editor's Settings panel renders, and the section ids it hands back on the
 * `pingolin-settings://open?section=` deep link. A wrong section id does not
 * fail loudly — the native screen just opens at the top, which reads as "the
 * deep link isn't implemented".
 */
class WebAppDeviceStateTest {

    private val base = WebAppDeviceState.Device(
        pushType = "fcm",
        pushEndpoint = null,
        appVersion = "9.9.9-test",
    )

    private fun rows(d: WebAppDeviceState.Device): Map<String, String> {
        val arr = WebAppDeviceState.snapshot(d).getJSONArray("rows")
        return (0 until arr.length()).associate {
            val o = arr.getJSONObject(it)
            o.getString("id") to o.getString("value")
        }
    }

    private fun sections(d: WebAppDeviceState.Device): Map<String, String> {
        val arr = WebAppDeviceState.snapshot(d).getJSONArray("rows")
        return (0 until arr.length()).associate {
            val o = arr.getJSONObject(it)
            o.getString("id") to o.getString("section")
        }
    }

    @Test
    fun `every row carries a label, a value field and a known section`() {
        val arr = WebAppDeviceState.snapshot(base).getJSONArray("rows")
        assertEquals(5, arr.length())
        val known = setOf(
            WebAppDeviceState.SECTION_SECURITY,
            WebAppDeviceState.SECTION_NOTIFICATIONS,
            WebAppDeviceState.SECTION_DIAGNOSTICS,
            WebAppDeviceState.SECTION_ABOUT,
            WebAppDeviceState.SECTION_ACCOUNT,
        )
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertTrue(o.getString("id").isNotBlank())
            assertTrue(o.getString("label").isNotBlank())
            // The value may be EMPTY (a row that only navigates), but the key
            // must exist — the page reads it without checking.
            assertTrue(o.has("value"))
            assertTrue("unknown section on ${o.getString("id")}", o.getString("section") in known)
        }
    }

    /**
     * The page renders `rows` and never reads `sections`, so any group without
     * a row has no route at all. Without the About, Diagnostics and Account
     * rows, the licences, the version, the crash-log export and account
     * deletion would be unreachable.
     */
    @Test
    fun `about, diagnostics and account are reachable rows, not just sections`() {
        val s = sections(base)
        assertEquals(WebAppDeviceState.SECTION_ABOUT, s["about"])
        assertEquals(WebAppDeviceState.SECTION_DIAGNOSTICS, s["diagnostics"])
        assertEquals(WebAppDeviceState.SECTION_ACCOUNT, s["account"])
    }

    /** The version is the row's VALUE, so it reads without a tap. */
    @Test
    fun `about carries the app version as its value`() {
        assertEquals("9.9.9-test", rows(base)["about"])
    }

    @Test
    fun `a missing version degrades to an empty value, never to the string null`() {
        assertEquals("", rows(base.copy(appVersion = null))["about"])
    }

    @Test
    fun `security is a label only — no summary of its contents`() {
        // "PIN · Screenshots blocked" described a GROUP, which reads as noise
        // beside a row like Push delivery that names one thing. The sheet
        // states each protection.
        assertEquals("", rows(base)["security"])
    }

    @Test
    fun `app lock and screenshots are one row, not two`() {
        // Both open the same sheet; two rows to one place read as two places.
        val r = rows(base)
        assertFalse(r.containsKey("appLock"))
        assertFalse(r.containsKey("screenshots"))
    }

    @Test
    fun `notification detail is not its own row`() {
        // It lives inside the sheet the push row opens, so a row here would be
        // a second way to the same place showing what that place already says.
        assertFalse(rows(base).containsKey("notificationDetail"))
    }

    @Test
    fun `UnifiedPush without an endpoint says it is still waiting`() {
        assertEquals("Google FCM", rows(base)["push"])
        assertEquals("Not registered", rows(base.copy(pushType = null))["push"])
        assertEquals(
            "UnifiedPush · waiting for distributor",
            rows(base.copy(pushType = "unifiedpush", pushEndpoint = null))["push"],
        )
        assertEquals(
            "UnifiedPush",
            rows(base.copy(pushType = "unifiedpush", pushEndpoint = "https://ntfy.example/x"))["push"],
        )
    }

    @Test
    fun `security rows point at security, notification rows at notifications`() {
        val s = sections(base)
        assertEquals(WebAppDeviceState.SECTION_SECURITY, s["security"])
        assertEquals(WebAppDeviceState.SECTION_NOTIFICATIONS, s["push"])
    }

    @Test
    fun `the script guards its origin before touching window`() {
        val js = WebAppDeviceState.buildScript(base, "https://note.pingolindev.com", onLoad = true)
        assertTrue(js.startsWith("(function(){if(location.origin!==\"https://note.pingolindev.com\")return;"))
        assertTrue(js.contains("window.__pingolinDevice="))
        // The document-start copy must NOT fire the change event: it runs
        // before the app mounts, so nothing is listening, and dispatching
        // there only invites a listener that assumes a user-visible change.
        assertFalse(js.contains("pingolin-device-changed"))
    }

    @Test
    fun `the resume refresh notifies an open panel`() {
        val js = WebAppDeviceState.buildScript(base, "https://note.pingolindev.com", onLoad = false)
        assertTrue(js.contains("pingolin-device-changed"))
    }
}
