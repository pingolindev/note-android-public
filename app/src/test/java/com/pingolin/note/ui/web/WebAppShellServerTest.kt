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
import android.net.Uri
import android.webkit.WebResourceRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/**
 * The interception itself: what the WebView is actually handed.
 *
 * null from [WebAppShellServer.intercept] means "go to the network". The whole
 * point of the class is that this happens for API traffic and other origins
 * ONLY — so each case below pins whether a request can reach the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WebAppShellServerTest {

    private val base = "https://note.pingolindev.com/"
    private val assets = mockk<AssetManager>()
    private val server = WebAppShellServer(assets, base)

    private fun req(url: String, method: String = "GET"): WebResourceRequest = mockk {
        every { this@mockk.url } returns Uri.parse(url)
        every { this@mockk.method } returns method
        every { isForMainFrame } returns false
    }

    @Test
    fun `the document and its scripts come from the APK, with the deployed CSP`() {
        every { assets.open("webapp/index.html") } returns ByteArrayInputStream("<html>".toByteArray())
        every { assets.open("webapp/assets/index-abc.js") } returns ByteArrayInputStream("js".toByteArray())

        val doc = server.intercept(req("https://note.pingolindev.com/"))
        assertNotNull(doc)
        assertEquals(200, doc!!.statusCode)
        assertEquals("text/html", doc.mimeType)
        assertTrue(doc.responseHeaders["Content-Security-Policy"]!!.contains("script-src 'self' 'wasm-unsafe-eval'"))

        val js = server.intercept(req("https://note.pingolindev.com/assets/index-abc.js"))
        assertEquals("text/javascript", js!!.mimeType)
        verify { assets.open("webapp/assets/index-abc.js") }
    }

    @Test
    fun `a file the bundle lacks is a 404 from the shell, not a network fetch`() {
        every { assets.open(any()) } throws FileNotFoundException()

        val missing = server.intercept(req("https://note.pingolindev.com/assets/injected-by-server.js"))

        assertNotNull("must not fall through to the network", missing)
        assertEquals(404, missing!!.statusCode)
    }

    @Test
    fun `traversal and non-GET on shell paths are refused, never forwarded`() {
        assertEquals(404, server.intercept(req("https://note.pingolindev.com/assets/../../x"))!!.statusCode)
        assertEquals(404, server.intercept(req("https://note.pingolindev.com/", "POST"))!!.statusCode)
    }

    @Test
    fun `the API and other origins go to the network`() {
        assertNull(server.intercept(req("https://note.pingolindev.com/api/v1/auth/me")))
        assertNull(server.intercept(req("https://note.pingolindev.com/api/v1/items", "POST")))
        assertNull(server.intercept(req("https://account.pingolindev.com/login")))
        assertNull(server.intercept(req("https://abc.r2.cloudflarestorage.com/obj?sig=1")))
        verify(exactly = 0) { assets.open(any()) }
    }
}
