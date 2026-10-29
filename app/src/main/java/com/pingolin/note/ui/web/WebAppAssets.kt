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

/**
 * Where each request for the note web app is answered from.
 *
 * ## The app shell comes from the APK, always
 *
 * The web app's own code — the document, its scripts, styles, WASM, workers,
 * fonts — is served ONLY from the copy bundled into the APK at build time,
 * online or not. That code receives this device's E2EE keys at document start
 * (WebAppKeyHandoff), so it has to be code that shipped through the
 * store-reviewed binary. Loading it live from the note origin whenever there is
 * a network would hand the vault, sharing and signing keys to whatever the
 * server, the CDN, or anything in front of them returned on a given launch,
 * with no app update and nothing visible.
 *
 * The cost is deliberate: a web change reaches Android through an app update
 * (each APK build bundles the current web editor), and the bundled page talks
 * to whatever API the server runs, so the server keeps backward compatibility
 * with bundles still in the field.
 *
 * ## Only the API leaves the device
 *
 * `/api/…` goes to the network — the note backend and, through the same origin,
 * auth. So do WebSockets (never intercepted), R2 object URLs and the account
 * portal (other hosts). A request for anything else on our origin that the
 * bundle does not have is answered 404 from here: falling through to the
 * network would let the server add code to the page after all.
 *
 * The requests keep their real origin. A `WebResourceResponse` returned from
 * `shouldInterceptRequest` is treated as having come from the requested URL, so
 * `localStorage`, the same-origin `/api/v1` base and the account-portal key
 * handoff are all unaffected. Serving the same files from `file://` or an
 * asset-loader origin would break all three at once.
 */
object WebAppAssets {

    /** Where the build places the built web app inside the APK. */
    const val ROOT = "webapp"

    /** Written next to the bundle so a device can report which web build it carries. */
    const val BUILD_ID = "$ROOT/BUILD_ID"

    /** How one same-origin request is answered. */
    sealed interface Route {
        /** API traffic — the only thing on our origin that goes to the network. */
        data object Network : Route

        /** A file from the bundle. */
        data class Asset(val path: String) : Route

        /** Answered 404 by the shell. Never the network. */
        data object NotFound : Route
    }

    /**
     * Route a request on the app's own origin.
     *
     * The traversal check runs FIRST, before any other rule: a request is
     * attacker-influenced input the moment the WebView loads anything we did
     * not author, and checking later lets a traversal path fall through to one
     * of the rules below instead of being refused. It answers NotFound, not
     * Network — a refused path must never become a fetch.
     */
    fun route(method: String, requestPath: String): Route {
        if (requestPath.contains("..")) return Route.NotFound
        if (requestPath == "/api" || requestPath.startsWith("/api/")) return Route.Network
        // Nothing in the app shell is ever written to: any other verb on a
        // non-API path is not ours to forward.
        if (!method.equals("GET", ignoreCase = true) && !method.equals("HEAD", ignoreCase = true)) {
            return Route.NotFound
        }
        return Route.Asset(assetPathFor(requestPath))
    }

    /**
     * The bundled file for a (non-API) request path.
     *
     * `/sw` and `/sw.js` get the web app's KILL SWITCH worker (its
     * public/sw-kill.js), not the offline worker. The page never registers a
     * service worker in this shell (the web editor's offlineShell.ts skips it by
     * the UA token), so any registration on this origin is left over from a
     * network-served page of an older app version. A registered worker is
     * re-fetched by its update check; answering with the kill switch makes it
     * delete its caches and unregister itself — it could otherwise keep answering
     * navigations from a cache this class never sees.
     */
    fun assetPathFor(requestPath: String): String {
        val path = requestPath.ifEmpty { "/" }
        if (path == "/") return "$ROOT/index.html"
        if (path == "/sw" || path == "/sw.js") return "$ROOT/sw-kill.js"

        // A path whose last segment has no extension is a client-side route
        // (`/note/<uuid>`), not a file — serve the shell and let the router
        // resolve it, which is what the server's SPA fallback does online.
        val last = path.substringAfterLast('/')
        if (!last.contains('.')) return "$ROOT/index.html"

        return ROOT + path
    }

    /**
     * Whether a request URL is on the app's origin: same scheme, host and
     * effective port as [baseUrl] (`BuildConfig.NOTE_API_BASE_URL`). A port of
     * -1 means the scheme default.
     */
    fun isAppOrigin(scheme: String?, host: String?, port: Int, baseUrl: String): Boolean {
        val baseScheme = baseUrl.substringBefore("://")
        val hostPort = baseUrl.substringAfter("://").substringBefore('/')
        val baseHost = hostPort.substringBefore(':')
        val basePort = hostPort.substringAfter(':', "").toIntOrNull() ?: defaultPort(baseScheme)
        val reqPort = if (port == -1) defaultPort(scheme ?: "") else port
        return scheme.equals(baseScheme, ignoreCase = true) &&
            host.equals(baseHost, ignoreCase = true) &&
            reqPort == basePort
    }

    private fun defaultPort(scheme: String): Int = when (scheme.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }

    /**
     * The security headers the web editor's own server sends (its nginx config),
     * so the bundled page runs under the same policy as the deployed one — the
     * CSP above all, since the bundle is the only copy of the page this app ever
     * runs. The hosts are derived from [baseUrl] exactly as that nginx derives
     * them from the request host: the websocket on the note host itself, the
     * account portal on the sibling `account.` host.
     */
    fun shellHeaders(baseUrl: String): Map<String, String> {
        val host = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        val registrable = host.substringAfter('.')
        val csp = listOf(
            "default-src 'self'",
            "script-src 'self' 'wasm-unsafe-eval'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            // The page plays a decrypted video attachment from a blob: URL it
            // minted itself; without this, default-src refuses it (as nginx's).
            "media-src 'self' blob:",
            "connect-src 'self' wss://$host https://*.r2.cloudflarestorage.com",
            "worker-src 'self' blob:",
            "font-src 'self'",
            "frame-src 'self' https://account.$registrable",
            "frame-ancestors 'none'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
        ).joinToString("; ")
        return mapOf(
            "Content-Security-Policy" to csp,
            "Cross-Origin-Opener-Policy" to "same-origin",
            "Cross-Origin-Embedder-Policy" to "credentialless",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "strict-origin-when-cross-origin",
        )
    }

    /**
     * A module script served with the wrong MIME is refused by the browser
     * outright, and the page then fails with nothing obviously wrong in the
     * asset itself.
     */
    fun mimeFor(assetPath: String): String = when {
        assetPath.endsWith(".html") -> "text/html"
        assetPath.endsWith(".js") -> "text/javascript"
        assetPath.endsWith(".mjs") -> "text/javascript"
        assetPath.endsWith(".css") -> "text/css"
        assetPath.endsWith(".json") -> "application/json"
        assetPath.endsWith(".webmanifest") -> "application/manifest+json"
        assetPath.endsWith(".svg") -> "image/svg+xml"
        assetPath.endsWith(".png") -> "image/png"
        assetPath.endsWith(".webp") -> "image/webp"
        assetPath.endsWith(".ico") -> "image/x-icon"
        assetPath.endsWith(".woff2") -> "font/woff2"
        assetPath.endsWith(".woff") -> "font/woff"
        assetPath.endsWith(".wasm") -> "application/wasm"
        else -> "application/octet-stream"
    }
}
