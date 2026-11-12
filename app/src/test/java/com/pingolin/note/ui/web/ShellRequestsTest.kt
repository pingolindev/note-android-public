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

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.pingolin.note.util.TokenAuthenticator.AdoptOutcome
import com.pingolin.note.util.TokenAuthenticator.RefreshOutcome
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * The shell's answers before the network, ONE path for the activity's client and the parked
 * WebView's ([ShellRequests]).
 *
 * WHY THESE EXIST
 *   Parked, only the activity's client served the web app's code from the APK: a document the
 *   parked page loaded (its wipe-and-reload after a refused session, any reload) came from the
 *   network, and the document-start script handed the deployed build the keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShellRequestsTest {

    private val origin = "https://note.pingolindev.com"
    private val now = 1_800_000_000L
    private val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
    private val expired = b64.encodeToString("""{"alg":"EdDSA"}""".toByteArray()) + "." +
        b64.encodeToString("""{"sub":"u","sid":"s","iat":${now - 960},"exp":${now - 60}}""".toByteArray()) + ".c2ln"

    private var storedReads = 0
    private var signIns = 0
    private var outcome = RefreshOutcome.REFUSED

    private fun session() = PageSession(
        noteOrigin = origin,
        storedToken = {
            storedReads++
            expired
        },
        jarToken = { expired },
        jarPair = { null },
        refresh = { _, _ -> outcome },
        adopt = { _, _, _ -> AdoptOutcome.NOT_NEWER },
        reseed = { _, _ -> true },
        signIn = { signIns++ },
        nowSeconds = { now },
    )

    private fun request(url: String): WebResourceRequest = mockk {
        every { this@mockk.url } returns Uri.parse(url)
        every { isForMainFrame } returns false
        every { method } returns "GET"
    }

    private fun server(answer: WebResourceResponse?): WebAppShellServer = mockk {
        every { intercept(any()) } returns answer
    }

    @Before
    fun clearLogs() {
        ShadowLog.clear()
    }

    @Test
    fun `the web app's own code comes from the APK, on screen and parked, before the session is looked at`() {
        val shell = WebResourceResponse("text/html", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
        for (parked in listOf(false, true)) {
            assertSame(shell, ShellRequests.answer(request("$origin/notes/1"), server(shell), session(), parked))
        }
        assertEquals("the session never saw those requests", 0, storedReads)
    }

    @Test
    fun `a refused session's request is answered with a real 503, logged, parked or not`() {
        for (parked in listOf(false, true)) {
            ShadowLog.clear()
            val answer = ShellRequests.answer(request("$origin/api/v1/auth/me"), server(null), session(), parked)!!
            assertEquals(503, answer.statusCode)
            assertEquals("Service Unavailable", answer.reasonPhrase)
            assertEquals("application/json", answer.mimeType)
            assertEquals("no-store", answer.responseHeaders["Cache-Control"])
            assertEquals(PageSession.SESSION_ENDED_BODY, answer.data.readBytes().toString(Charsets.UTF_8))
            val lines = ShadowLog.getLogsForTag(WebAppActivity.PAGE_SESSION_TAG).map { it.msg }
            assertEquals(1, lines.size)
            assertTrue(lines.single(), lines.single().startsWith("stale session before a page request: refresh REFUSED, "))
            assertEquals(parked, lines.single().contains("(parked, "))
        }
    }

    @Test
    fun `a request on a good session, another origin's, or with no session handed over goes on`() {
        outcome = RefreshOutcome.UNREACHABLE
        assertNull(ShellRequests.answer(request("$origin/api/v1/items"), server(null), session(), parked = true))
        assertNull(ShellRequests.answer(request("https://account.pingolindev.com/api/v1/auth/me"), server(null), session(), parked = false))
        assertNull(ShellRequests.answer(request("$origin/api/v1/items"), server(null), null, parked = true))
        assertEquals(0, signIns)
    }

    /** Both clients, read from the source: the same call, the activity's before its prefetch. */
    @Test
    fun `both clients answer through ShellRequests (read from the source)`() {
        val activity = File("src/main/java/com/pingolin/note/ui/web/WebAppActivity.kt").readText()
        val shared = activity.indexOf("ShellRequests.answer(req, shellServer, pageSession, parked = false)?.let { return it }")
        val prefetch = activity.indexOf("AuthMePrefetch.take(")
        assertTrue(shared in 0 until prefetch)
        assertEquals("nothing intercepts around it", -1, activity.indexOf("shellServer.intercept(req)"))
        val retained = File("src/main/java/com/pingolin/note/ui/web/RetainedWebView.kt").readText()
        assertTrue(retained.contains("ShellRequests.answer(req, WebAppActivity.shellServerFor(v.context), parkedSession, parked = true)"))
        val shellRequests = File("src/main/java/com/pingolin/note/ui/web/ShellRequests.kt").readText()
        val served = shellRequests.indexOf("server.intercept(req)?.let { return it }")
        val checked = shellRequests.indexOf("session?.beforeRequest(req.url.toString())")
        assertTrue("the APK's code first", served in 0 until checked)
    }
}
