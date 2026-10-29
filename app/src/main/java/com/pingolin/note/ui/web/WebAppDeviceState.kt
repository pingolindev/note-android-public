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
import org.json.JSONObject

/**
 * The device-local settings snapshot handed to the web Settings panel.
 *
 * ## Why the page needs this at all
 *
 * App lock, screenshot blocking, push transport and notification detail
 * cannot live inside the page — they need Keychain/BiometricPrompt,
 * FLAG_SECURE and the push registrar. So they live on the native settings
 * screen.
 *
 * This snapshot lets the panel list them as real rows showing their real
 * values, rather than one button that says nothing about what is behind
 * it, and deep-link into the native screen only when the person wants to
 * CHANGE one.
 *
 * ## What this must never become
 *
 * A **push, never a pull**, exactly like [WebAppKeyHandoff]: no
 * `@JavascriptInterface` is exposed, so the page has nothing to call. The
 * shell writes the object and returns, against the same single-origin
 * allowlist — the account portal and every other origin the WebView visits
 * never receive it.
 *
 * It is also **read-only state, never a control surface**. Every write
 * still happens on the native screen behind the PIN/biometric prompt it
 * already uses. That is deliberate: `shouldOverrideUrlLoading` fires for
 * ANY origin loaded in this WebView, so a scheme that could switch app
 * lock off would be a capability handed to every page the WebView ever
 * shows. The deep link can only ever OPEN a screen.
 *
 * Values are display-ready strings built here rather than in the page: the
 * page has no business knowing that `AUTO_LOCK_1_MIN` is 60000, and
 * duplicating the mapping in TypeScript is how the two drift apart.
 */
object WebAppDeviceState {

    /** Section ids the page may deep-link to; mirrored by SettingsFragment. */
    const val SECTION_SECURITY = "security"
    const val SECTION_NOTIFICATIONS = "notifications"
    const val SECTION_DIAGNOSTICS = "diagnostics"
    const val SECTION_ABOUT = "about"
    const val SECTION_ACCOUNT = "account"

    /**
     * Build the JS that assigns `window.__pingolinDevice`.
     *
     * @param onLoad true for the document-start registration (assign only),
     *   false for the in-place resume refresh, which also fires
     *   `pingolin-device-changed` so an open Settings panel re-reads a value
     *   the person just changed natively.
     */
    fun buildScript(device: Device, origin: String, onLoad: Boolean): String {
        val json = snapshot(device).toString()
        val notify = if (onLoad) {
            ""
        } else {
            "window.dispatchEvent(new CustomEvent('pingolin-device-changed'));"
        }
        // The origin guard is the FIRST statement, as every injected script
        // here must be: addDocumentStartJavaScript takes an allowlist, but
        // the resume path goes through evaluateJavascript, which does not.
        return "(function(){if(location.origin!==${quote(origin)})return;" +
            "window.__pingolinDevice=$json;$notify})();"
    }

    /**
     * The device values, lifted off [SessionManager] so the snapshot itself is
     * pure — the same shape [WebAppKeyHandoff.Keys] uses, and for the same
     * reason: a builder that takes a live manager can only be tested with a
     * mock, which tests the mock.
     */
    data class Device(
        val pushType: String?,
        val pushEndpoint: String?,
        /**
         * Display-ready `version (build)`, shown as the About row's value so the
         * number a problem report needs is readable without opening anything.
         *
         * The SHAPE is a cross-platform agreement, not a local choice: the iOS
         * app sends `"\(appVersion) (\(buildNumber))"` for the same row, and the
         * two rows render in the same panel with the same label, so a bare
         * version name on one side would look like a mistake on the other.
         */
        val appVersion: String?,
    )

    fun from(session: SessionManager) = Device(
        pushType = session.getPushType(),
        pushEndpoint = session.getPushEndpoint(),
        appVersion = "${com.pingolin.note.BuildConfig.VERSION_NAME} " +
            "(${com.pingolin.note.BuildConfig.VERSION_CODE})",
    )

    /** The snapshot itself — pure, so the unit test can read it directly. */
    fun snapshot(device: Device): JSONObject {
        val push = when (device.pushType) {
            "unifiedpush" -> if (device.pushEndpoint.isNullOrBlank()) {
                "UnifiedPush · waiting for distributor"
            } else {
                "UnifiedPush"
            }
            "fcm" -> "Google FCM"
            else -> "Not registered"
        }
        // rows: [{id, label, value, section}] — the page renders them in this
        // order and does not interpret any of it beyond the section deep link.
        //
        // Notification detail is deliberately NOT a row: it sits inside the
        // sheet Push delivery opens, so listing it here would give the panel
        // two rows leading to the same place, one of them a duplicate of what
        // the other already shows.
        val rows = listOf(
            // Security carries NO value: "PIN · Screenshots blocked" summarised
            // a group rather than describing one setting, which reads as noise
            // next to a row like "Push delivery · Google FCM" that names one
            // thing. The sheet states each protection exactly.
            row("security", "Security", "", SECTION_SECURITY),
            row("push", "Push delivery", push, SECTION_NOTIFICATIONS),
            // These three have to be rows.
            //
            // The panel renders `rows` and nothing else — it never
            // reads `sections` below — and its no-rows FALLBACK
            // button never renders once a snapshot is injected.
            // Without these rows everything the focused sheet hides
            // would be unreachable: the open-source licences, the
            // version string, the crash-log export, and account
            // deletion, for which there is no web equivalent
            // either.
            //
            // About carries the version as its value so the number
            // a support request needs is legible without a tap.
            row("about", "About", device.appVersion.orEmpty(), SECTION_ABOUT),
            row("diagnostics", "Diagnostics", "", SECTION_DIAGNOSTICS),
            row("account", "Account", "", SECTION_ACCOUNT),
        )

        return JSONObject().apply {
            put("platform", "android")
            put("rows", org.json.JSONArray(rows))
            put(
                "sections",
                JSONObject().apply {
                    put("security", SECTION_SECURITY)
                    put("notifications", SECTION_NOTIFICATIONS)
                    put("diagnostics", SECTION_DIAGNOSTICS)
                    put("about", SECTION_ABOUT)
                    put("account", SECTION_ACCOUNT)
                },
            )
        }
    }

    private fun row(id: String, label: String, value: String, section: String) =
        JSONObject().apply {
            put("id", id)
            put("label", label)
            put("value", value)
            put("section", section)
        }

    /** JSON string literal — the values are ours, but quoting them by hand
     *  is how an apostrophe in a distributor name breaks the whole script. */
    private fun quote(s: String): String = JSONObject.quote(s)
}
