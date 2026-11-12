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

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * What the shell's WebView answers a request with before it can reach the network: ONE code path
 * for the activity's client and the parked WebView's idle client (RetainedWebView).
 *
 * WHY THIS EXISTS
 *   The web app's own code comes from the APK, online or not, and only API traffic reaches the
 *   network: the page is handed this device's E2EE keys at document start, so it must be the code
 *   this binary shipped with, not whatever the server returns at the time (WebAppAssets has the
 *   policy and its cost); the deployed build fetched online would get the keys from code no store
 *   review had seen. Only the activity's client served it, though. Parked, the page keeps running,
 *   and a document it loaded then (its own wipe-and-reload once a refused session's account check
 *   gets the server's 401, or any other reload) came from the network, and the document-start
 *   script still handed it the keys.
 */
internal object ShellRequests {

    /**
     * The shell's answer to [req], or null to let it go on (the activity then tries its /auth/me
     * prefetch): the web app's own code from the APK ([server]); then the page's API on a good
     * session ([session], [PageSession.beforeRequest]), its WARN line logged, and a refused
     * session's request answered here ([PageSession.sessionEndedResponse]). [parked]: the parked
     * WebView's, where nothing signs in until the next open.
     */
    fun answer(
        req: WebResourceRequest,
        server: WebAppShellServer,
        session: PageSession?,
        parked: Boolean,
    ): WebResourceResponse? {
        server.intercept(req)?.let { return it }
        val check = session?.beforeRequest(req.url.toString()) ?: return null
        check.describe(parked)?.let { android.util.Log.w(WebAppActivity.PAGE_SESSION_TAG, it) }
        return if (check.refused) PageSession.sessionEndedResponse() else null
    }
}
