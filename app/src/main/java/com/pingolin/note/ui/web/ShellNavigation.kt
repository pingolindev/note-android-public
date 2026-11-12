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

import com.pingolin.note.BuildConfig

/**
 * Which pages the shell's WebView shows itself, and which open outside the app.
 *
 * WHY THIS EXISTS
 *   The WebView loaded every http(s) URL in place: shouldOverrideUrlLoading answered false for all
 *   but the app's own schemes, and with no multiple windows a `target="_blank"` link (every link
 *   in a note: the editor's markdown renders them so) navigates the shell's own main frame. A
 *   tapped link in a SHARED note put the sharer's page inside the app, with the app's resume
 *   handoff then writing the keys into it (fixed in the handoff itself), and its cookies, its
 *   storage and its hooks next to the user's notes. Only three origins are the app's: the note
 *   origin; the account portal's, which loads in place on purpose (the bounce, Settings'
 *   "Manage", the client-key handoff); and Track web's, through which the account deletion's
 *   erasure chain runs from the portal (account erasureChain.ts: portal, Track web's
 *   /forget-account, Note web, back to the portal), so a deletion started in the app ends in it.
 *   The iOS shell already sends the rest to Safari.
 *
 * So a navigation of the MAIN frame to any other http(s) origin opens outside the app, in the
 * browser ([Where.OUTSIDE]); parked, with no screen to open anything on, it is only cancelled.
 * Subframes are not this rule's (Turnstile, a payment frame), nor are other schemes (the app's
 * own, about:, data:, blob:, mailto:).
 */
internal object ShellNavigation {

    enum class Where {
        /** Not this rule's: a subframe, or not http(s). */
        UNTOUCHED,
        /** The note origin, the account portal's or Track web's: shown in the app. */
        IN_APP,
        /** Any other http(s) origin in the main frame: opened outside the app (parked: cancelled). */
        OUTSIDE,
    }

    /** The note origin, the account portal's and Track web's, for this build's environment. */
    val inAppOrigins: Set<String> by lazy {
        setOfNotNull(
            originOfUrl(BuildConfig.NOTE_API_BASE_URL),
            originOfUrl(BuildConfig.ACCOUNT_BASE_URL),
            originOfUrl(BuildConfig.TRACK_WEB_BASE_URL),
        )
    }

    /**
     * Where a navigation goes: [scheme], [host] and [port] as the WebView's request gives them
     * (android.net.Uri of the URL Chromium has already canonicalised; port -1 when none).
     */
    fun decide(scheme: String?, host: String?, port: Int, isMainFrame: Boolean, inApp: Set<String> = inAppOrigins): Where {
        if (!isMainFrame) return Where.UNTOUCHED
        val web = scheme?.lowercase()
        if (web != "http" && web != "https") return Where.UNTOUCHED
        val origin = origin(web, host, port) ?: return Where.OUTSIDE
        return if (origin in inApp) Where.IN_APP else Where.OUTSIDE
    }

    /**
     * An http(s) origin, `scheme://host[:port]`: the scheme and host in lower case, the port only
     * when it isn't the scheme's default. Null without a host.
     */
    fun origin(scheme: String, host: String?, port: Int): String? {
        if (host.isNullOrEmpty()) return null
        val s = scheme.lowercase()
        val defaultPort = if (s == "https") 443 else if (s == "http") 80 else -1
        val p = if (port == -1 || port == defaultPort) "" else ":$port"
        return "$s://${host.lowercase()}$p"
    }

    /**
     * The script that routes the live document to [path] through its own router
     * (`window.__pingolinNavigate`), answering 'ok', or 'no-hook' when it can't (the caller then
     * loads the page instead). It does nothing in a document of any origin but [origin] (the note
     * origin), checked first: evaluateJavascript has no allowlist and runs in whatever the main
     * frame shows, and a page there could have a hook of that name. [path] and [origin] are JSON
     * strings in it, so neither can break out of its literal.
     */
    fun routeScript(origin: String, path: String): String =
        "(function(){if(location.origin!==${org.json.JSONObject.quote(origin)})return 'no-hook';" +
            "var n=window.__pingolinNavigate;if(!n){return 'no-hook';}n(${org.json.JSONObject.quote(path)});return 'ok';})()"

    /** [origin] of a configured base URL (BuildConfig's), or null when it isn't http(s). */
    fun originOfUrl(url: String): String? {
        val uri = try {
            java.net.URI(url)
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        return origin(scheme, uri.host, uri.port)
    }
}
