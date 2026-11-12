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
import com.pingolin.note.ui.web.ShellNavigation.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which pages the shell shows itself ([ShellNavigation]).
 *
 * WHY THESE EXIST
 *   Every http(s) URL loaded in place, and a note's links are target=_blank: a tapped link in a
 *   shared note put the sharer's page in the app's own frame, where a resume then handed it the
 *   keys. Only the note origin and the account portal's are the app's.
 */
class ShellNavigationTest {

    private val inApp = setOf("https://note.pingolindev.com", "https://account.pingolindev.com", "https://track.pingolindev.com")

    private fun where(url: String, mainFrame: Boolean = true): Where {
        val uri = java.net.URI(url)
        return ShellNavigation.decide(uri.scheme, uri.host, uri.port, mainFrame, inApp)
    }

    @Test
    fun `the note origin and the account portal's are shown in the app`() {
        for (url in listOf(
            "https://note.pingolindev.com/",
            "https://note.pingolindev.com/notes/7f3a?x=1#ck=abc",
            "https://NOTE.pingolindev.com/notes",
            "https://note.pingolindev.com:443/",
            "https://account.pingolindev.com/login?return=https%3A%2F%2Fnote.pingolindev.com",
            "https://account.pingolindev.com/settings/recovery-email",
            // The account deletion's erasure chain: Track web's route, carrying the receipt.
            "https://track.pingolindev.com/forget-account#r=eyJ2IjoxfQ.c2ln",
        )) {
            assertEquals(url, Where.IN_APP, where(url))
        }
    }

    @Test
    fun `any other http(s) page in the main frame opens outside the app`() {
        for (url in listOf(
            "https://sharer.example/landing",
            "http://note.pingolindev.com/",
            "https://note.pingolindev.com:8443/",
            "https://note.pingolindev.com.evil.example/",
            "https://evil.example/?next=https://note.pingolindev.com",
            "https://pingolindev.com/",
            "https://www.pingolindev.com/help",
            "https://api-auth.pingolindev.com/api/v1/auth/me",
            "https://track.pingolindev.com.evil.example/forget-account",
            "http://track.pingolindev.com/forget-account",
            "https://note.pingolin.com/",
            "HTTPS://evil.example/",
        )) {
            assertEquals(url, Where.OUTSIDE, where(url))
        }
        // userinfo is not the host: Chromium's URL, as the request carries it, names evil.example.
        assertEquals(Where.OUTSIDE, where("https://note.pingolindev.com@evil.example/"))
        assertEquals("no host", Where.OUTSIDE, ShellNavigation.decide("https", null, -1, true, inApp))
    }

    @Test
    fun `subframes and other schemes are not this rule's`() {
        assertEquals(Where.UNTOUCHED, where("https://challenges.cloudflare.com/turnstile", mainFrame = false))
        assertEquals(Where.UNTOUCHED, where("https://sharer.example/", mainFrame = false))
        for (other in listOf("pingolin-close", "pingolin-settings", "about", "data", "blob", "mailto", "intent", "javascript", null)) {
            assertEquals("$other", Where.UNTOUCHED, ShellNavigation.decide(other, "evil.example", -1, true, inApp))
        }
    }

    @Test
    fun `this build's three origins are the configured ones, normalised`() {
        assertEquals(
            setOf(
                ShellNavigation.originOfUrl(BuildConfig.NOTE_API_BASE_URL),
                ShellNavigation.originOfUrl(BuildConfig.ACCOUNT_BASE_URL),
                ShellNavigation.originOfUrl(BuildConfig.TRACK_WEB_BASE_URL),
            ),
            ShellNavigation.inAppOrigins,
        )
        assertEquals(3, ShellNavigation.inAppOrigins.size)
        // The erasure chain's hosts (account erasureChain.ts CHAIN_HOSTS): this build is dev.
        assertEquals("https://track.pingolindev.com", ShellNavigation.originOfUrl(BuildConfig.TRACK_WEB_BASE_URL))
        assertTrue(ShellNavigation.inAppOrigins.all { it.startsWith("https://") && !it.endsWith("/") })
        assertEquals("https://note.pingolindev.com", ShellNavigation.originOfUrl("https://note.pingolindev.com/"))
        assertEquals("https://account.pingolin.com", ShellNavigation.originOfUrl("https://ACCOUNT.pingolin.com:443/x"))
        assertEquals("http://10.0.2.2:8080", ShellNavigation.originOfUrl("http://10.0.2.2:8080/"))
        assertEquals(null, ShellNavigation.originOfUrl("pingolin-close:"))
    }

    /** Both clients, read from the source: on screen a page that isn't the app's opens in the
     *  browser, after the app's own schemes and in place of the plain "load it"; parked, it is
     *  only cancelled. */
    @Test
    fun `the activity opens other pages outside, and the parked WebView cancels them (read from the source)`() {
        val activity = File("src/main/java/com/pingolin/note/ui/web/WebAppActivity.kt").readText()
        val override = activity.indexOf("override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {")
        val settings = activity.indexOf("if (req.url.scheme == SETTINGS_SCHEME) {", override)
        val decide = activity.indexOf(
            "if (ShellNavigation.decide(req.url.scheme, req.url.host, req.url.port, req.isForMainFrame) ==\n" +
                "                    ShellNavigation.Where.OUTSIDE\n" +
                "                ) {\n" +
                "                    openOutside(req.url)\n" +
                "                    return true\n" +
                "                }\n" +
                "                return false\n" +
                "            }",
            override,
        )
        assertTrue(override in 0 until settings)
        assertTrue(settings < decide)
        assertTrue(activity.contains("startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))"))
        val retained = File("src/main/java/com/pingolin/note/ui/web/RetainedWebView.kt").readText()
        assertTrue(
            retained.contains(
                "override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean =\n" +
                    "            ShellNavigation.decide(req.url.scheme, req.url.host, req.url.port, req.isForMainFrame) ==\n" +
                    "                ShellNavigation.Where.OUTSIDE",
            ),
        )
        assertTrue("nothing opened while parked", !retained.contains("startActivity("))
    }

    /** The navigate hook runs through evaluateJavascript, which has no allowlist: it does nothing
     *  in another origin's document, checked first, and answers 'no-hook' so the page is loaded. */
    @Test
    fun `the route script does nothing in a document of another origin`() {
        val script = ShellNavigation.routeScript("https://note.pingolindev.com", "/note/7f3a")
        assertTrue(script, script.startsWith("(function(){if(location.origin!=="))
        val guard = script.indexOf(")return 'no-hook';")
        val hook = script.indexOf("var n=window.__pingolinNavigate;")
        assertTrue(script, guard in 0 until hook)
        assertTrue(script, script.contains("n(\"\\/note\\/7f3a\");") || script.contains("n(\"/note/7f3a\");"))
        assertTrue(script.endsWith("return 'ok';})()"))
        // A path that tries to break out stays a string.
        val hostile = ShellNavigation.routeScript("https://note.pingolindev.com", "/x\");alert(1);//")
        assertTrue(hostile, hostile.contains("\\\");alert(1);"))
        val activity = File("src/main/java/com/pingolin/note/ui/web/WebAppActivity.kt").readText()
        assertTrue(activity.contains("val js = ShellNavigation.routeScript(origin, path)\n        web.evaluateJavascript(js) { result ->"))
        assertEquals("the hook is built in one place", -1, activity.indexOf("window.__pingolinNavigate"))
    }
}

