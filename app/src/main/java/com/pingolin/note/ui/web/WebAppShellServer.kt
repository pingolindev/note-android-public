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

import android.content.res.AssetManager
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers the note web app's requests from the APK — see [WebAppAssets] for the
 * policy and why. One instance per process; safe to call from the WebView's IO
 * threads (AssetManager.open is thread-safe).
 */
class WebAppShellServer(
    private val assets: AssetManager,
    private val baseUrl: String,
) {
    private val headers = WebAppAssets.shellHeaders(baseUrl)

    /**
     * The response for [req], or null to let the WebView fetch it from the
     * network — which happens ONLY for API traffic on our origin and for
     * requests to other origins (R2, the account portal).
     */
    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val uri = req.url
        if (!WebAppAssets.isAppOrigin(uri.scheme, uri.host, uri.port, baseUrl)) return null
        return when (val route = WebAppAssets.route(req.method, uri.path ?: "/")) {
            WebAppAssets.Route.Network -> null
            WebAppAssets.Route.NotFound -> notFound()
            is WebAppAssets.Route.Asset -> serve(route.path)
        }
    }

    private fun serve(assetPath: String): WebResourceResponse = try {
        WebResourceResponse(
            WebAppAssets.mimeFor(assetPath),
            "utf-8",
            200,
            "OK",
            headers,
            assets.open(assetPath),
        )
    } catch (e: IOException) {
        // Not in the bundle. A 404 FROM HERE, never the network: an asset the
        // page asks for that this APK did not ship is exactly what a network
        // fallback would let the server supply. WARN (R8 strips INFO): a
        // missing index.html means the build shipped without its web app.
        Log.w(TAG, "not in the bundled web app: $assetPath")
        notFound()
    }

    private fun notFound(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        headers,
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private const val TAG = "WebAppShellServer"
        private val serviceWorkerHooked = AtomicBoolean(false)

        /**
         * Route service-worker fetches through the same policy.
         *
         * A worker's own requests — its script and update checks, and every
         * fetch its handler makes — never reach the WebViewClient; they go
         * through the process-wide ServiceWorkerController. The page does not
         * register a worker in this shell, but one left on the origin by a
         * network-served page of an older app version would otherwise fetch
         * the app shell from the server. Hooked once per process.
         */
        fun hookServiceWorkers(server: WebAppShellServer) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
            ) {
                return
            }
            if (!serviceWorkerHooked.compareAndSet(false, true)) return
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                        server.intercept(request)
                },
            )
        }
    }
}
