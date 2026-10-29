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

import com.pingolin.note.ui.web.WebAppAssets.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the web app's requests are answered from.
 *
 * The rule under test: on our origin, ONLY `/api/` reaches the network. The
 * page is handed this device's keys at document start, so every byte of its
 * code has to come from the APK; a request the bundle cannot answer is a 404
 * from the shell, never a fetch the server could fill with code.
 *
 * Every failure here is silent at runtime: a wrong path or MIME does not throw,
 * it produces a page that simply does not start — and a rule that sends a shell
 * path to the network does not break anything visible at all, which is why it
 * has to be pinned here.
 */
class WebAppAssetsTest {

    private fun get(path: String) = WebAppAssets.route("GET", path)

    @Test
    fun `the app root serves the bundled shell`() {
        assertEquals(Route.Asset("webapp/index.html"), get("/"))
        assertEquals(Route.Asset("webapp/index.html"), get(""))
    }

    @Test
    fun `hashed build files come from the bundle, online or not`() {
        assertEquals(Route.Asset("webapp/assets/index-C5f9pC4X.js"), get("/assets/index-C5f9pC4X.js"))
        assertEquals(Route.Asset("webapp/assets/attachmentCryptoWorker-C7N3lhtU.js"), get("/assets/attachmentCryptoWorker-C7N3lhtU.js"))
        assertEquals(Route.Asset("webapp/favicon.png"), get("/favicon.png"))
    }

    /** `/note/<uuid>` is a client-side route, not a file — same as the server's SPA fallback. */
    @Test
    fun `an extensionless path serves the shell so the router can resolve it`() {
        assertEquals(Route.Asset("webapp/index.html"), get("/note/7537c421-408b-4c08-b343-075423d5ab9b"))
        assertEquals(Route.Asset("webapp/index.html"), get("/import"))
    }

    /** The API is the one thing on our origin that goes to the network. */
    @Test
    fun `only the API goes to the network, whatever the method`() {
        assertEquals(Route.Network, get("/api/v1/auth/me"))
        assertEquals(Route.Network, get("/api/v1/sync/pull"))
        assertEquals(Route.Network, WebAppAssets.route("POST", "/api/v1/items"))
        assertEquals(Route.Network, WebAppAssets.route("PUT", "/api/v1/items/abc"))
        assertEquals(Route.Network, get("/api"))
        // A look-alike is not the API.
        assertEquals(Route.Asset("webapp/index.html"), get("/apix"))
    }

    @Test
    fun `a traversal attempt is refused, and never becomes a network fetch`() {
        assertEquals(Route.NotFound, get("/assets/../../../etc/passwd"))
        assertEquals(Route.NotFound, get("/a/../b.js"))
        // Even one aimed at the API prefix: refused, not forwarded.
        assertEquals(Route.NotFound, get("/api/../index.html"))
    }

    @Test
    fun `a write to a non-API path is refused rather than forwarded`() {
        assertEquals(Route.NotFound, WebAppAssets.route("POST", "/"))
        assertEquals(Route.NotFound, WebAppAssets.route("PUT", "/assets/index.js"))
        assertEquals(Route.Asset("webapp/index.html"), WebAppAssets.route("HEAD", "/"))
    }

    /**
     * The page never registers a service worker in the shell, so any worker on
     * the origin is left over from network-served code; its update check must
     * get the kill switch, which unregisters it and drops its caches.
     */
    @Test
    fun `service worker scripts are answered with the kill switch`() {
        assertEquals(Route.Asset("webapp/sw-kill.js"), get("/sw"))
        assertEquals(Route.Asset("webapp/sw-kill.js"), get("/sw.js"))
    }

    @Test
    fun `only the configured origin is ours`() {
        val base = "https://note.pingolindev.com/"
        assertTrue(WebAppAssets.isAppOrigin("https", "note.pingolindev.com", -1, base))
        assertTrue(WebAppAssets.isAppOrigin("https", "NOTE.pingolindev.com", 443, base))
        assertFalse(WebAppAssets.isAppOrigin("http", "note.pingolindev.com", -1, base))
        assertFalse(WebAppAssets.isAppOrigin("https", "note.pingolindev.com", 8443, base))
        assertFalse(WebAppAssets.isAppOrigin("https", "account.pingolindev.com", -1, base))
        assertFalse(WebAppAssets.isAppOrigin("https", "abc.r2.cloudflarestorage.com", -1, base))
        assertTrue(WebAppAssets.isAppOrigin("http", "localhost", 3003, "http://localhost:3003/"))
    }

    /** The bundled page runs under the deployed page's CSP, with the hosts derived the way the
     * web server derives them. Pinned whole: a directive widened, dropped or added fails here. */
    @Test
    fun `the shell carries the deployed security headers`() {
        fun csp(domain: String) = listOf(
            "default-src 'self'",
            "script-src 'self' 'wasm-unsafe-eval'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "media-src 'self' blob:",
            "connect-src 'self' wss://note.$domain https://*.r2.cloudflarestorage.com",
            "worker-src 'self' blob:",
            "font-src 'self'",
            "frame-src 'self' https://account.$domain",
            "frame-ancestors 'none'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
        ).joinToString("; ")
        assertEquals(
            mapOf(
                "Content-Security-Policy" to csp("pingolindev.com"),
                "Cross-Origin-Opener-Policy" to "same-origin",
                "Cross-Origin-Embedder-Policy" to "credentialless",
                "X-Content-Type-Options" to "nosniff",
                "Referrer-Policy" to "strict-origin-when-cross-origin",
            ),
            WebAppAssets.shellHeaders("https://note.pingolindev.com/"),
        )
        assertEquals(csp("pingolin.com"), WebAppAssets.shellHeaders("https://note.pingolin.com/").getValue("Content-Security-Policy"))
    }

    /** A module script served as octet-stream is refused by the browser outright. */
    @Test
    fun `javascript gets a real script mime`() {
        assertEquals("text/javascript", WebAppAssets.mimeFor("webapp/assets/index-abc.js"))
        assertEquals("text/css", WebAppAssets.mimeFor("webapp/assets/index-abc.css"))
        assertEquals("text/html", WebAppAssets.mimeFor("webapp/index.html"))
        assertEquals("font/woff2", WebAppAssets.mimeFor("webapp/assets/x.woff2"))
        assertEquals("application/wasm", WebAppAssets.mimeFor("webapp/x.wasm"))
    }
}
