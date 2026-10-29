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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These guard a contract that spans two repos: the exact `sessionStorage` names
 * the Note web editor's `restoreFromSession()` reads. Getting one wrong does
 * not fail loudly — the web app simply finds no keys and sends the user to the
 * account portal for a password, which reads as "the handoff isn't
 * implemented".
 */
class WebAppKeyHandoffTest {

    private val keys = WebAppKeyHandoff.Keys(
        vaultKeyB64 = "dmF1bHRLZXlCeXRlcw==",
        sharingPrivB64 = "c2hhcmluZ1ByaXY=",
        sharingPubB64 = "c2hhcmluZ1B1Yg==",
        signSecretB64 = "c2lnblNlY3JldA==",
        signPublicB64 = "c2lnblB1YmxpYw==",
    )

    @Test
    fun `writes every key the web app reads back`() {
        val script = WebAppKeyHandoff.buildScript(keys, trustIsOurs = true)!!
        for (name in listOf(
            "pingolin_note_session_key",
            "pingolin_note_sharing_priv",
            "pingolin_note_sharing_pub",
            "pingolin_note_sign_secret",
            "pingolin_note_sign_public",
        )) {
            assertTrue("missing $name", script.contains("s.setItem('$name',"))
        }
        assertTrue(script.contains("'dmF1bHRLZXlCeXRlcw=='"))
    }

    @Test
    fun `uses sessionStorage, never localStorage`() {
        val script = WebAppKeyHandoff.buildScript(keys, trustIsOurs = true)!!
        assertTrue(script.contains("window.sessionStorage"))
        assertFalse("key material must not outlive the session", script.contains("localStorage"))
    }

    /**
     * Without both halves the web app would sign with an identity it cannot
     * publish, and recipients drop every share — a silent, remote failure.
     */
    @Test
    fun `omits signing entirely when only one half is present`() {
        val script = WebAppKeyHandoff.buildScript(keys.copy(signPublicB64 = null), trustIsOurs = true)!!
        assertFalse(script.contains("pingolin_note_sign_secret"))
        assertFalse(script.contains("pingolin_note_sign_public"))
        // the sharing keys still go over — notes must still decrypt
        assertTrue(script.contains("pingolin_note_sharing_priv"))
    }

    @Test
    fun `still hands over sharing keys when the account has no signing seed`() {
        val script = WebAppKeyHandoff.buildScript(
            keys.copy(signSecretB64 = null, signPublicB64 = null),
            trustIsOurs = true,
        )!!
        assertTrue(script.contains("pingolin_note_session_key"))
        assertFalse(script.contains("pingolin_note_sign_secret"))
    }

    /**
     * The page reaches our hosts through the WebView's own network stack: while that stack's trust isn't
     * this app's (CompromisedKeyTrustProvider not the default), no key goes over, whole or partial.
     */
    @Test
    fun `no key goes to the page while the WebView's trust isn't ours`() {
        assertNull(WebAppKeyHandoff.buildScript(keys, trustIsOurs = false))
        assertNull(WebAppKeyHandoff.buildScript(keys.copy(signSecretB64 = null, signPublicB64 = null), trustIsOurs = false))
        assertNull(WebAppKeyHandoff.buildScript(null, trustIsOurs = false))
    }

    @Test
    fun `both handoffs ask whether the default trust is still ours`() {
        val activity = java.io.File("src/main/java/com/pingolin/note/ui/web/WebAppActivity.kt").readText()
        val calls = Regex("""WebAppKeyHandoff\.buildScript\(([^\n]*)\)""").findAll(activity).map { it.groupValues[1] }.toList()
        assertEquals(2, calls.size)
        assertTrue(calls.all { it == "collectKeys(), CompromisedKeyTrustProvider.isDefault()" })
    }

    @Test
    fun `no keys means no script, so the web app falls back to the password`() {
        assertNull(WebAppKeyHandoff.buildScript(null, trustIsOurs = true))
    }

    /**
     * Base64 cannot contain a quote or a backslash. Anything that does is not
     * key material, and must never be built into a script.
     */
    @Test
    fun `refuses values that could break out of the string literal`() {
        assertNull(WebAppKeyHandoff.buildScript(keys.copy(vaultKeyB64 = "abc');alert(1);//"), trustIsOurs = true))
        assertNull(WebAppKeyHandoff.buildScript(keys.copy(sharingPrivB64 = "abc\\"), trustIsOurs = true))
        assertNull(WebAppKeyHandoff.buildScript(keys.copy(sharingPubB64 = "abc'def"), trustIsOurs = true))
    }

    @Test
    fun `a partial key set is refused rather than half-applied`() {
        assertNull(WebAppKeyHandoff.buildScript(keys.copy(vaultKeyB64 = ""), trustIsOurs = true))
        assertNull(WebAppKeyHandoff.buildScript(keys.copy(sharingPubB64 = ""), trustIsOurs = true))
    }

    /**
     * The allowlist takes origins. A trailing path never matches, and the
     * symptom is an app that boots keyless with nothing logged.
     */
    @Test
    fun `origin strips the path from the base url`() {
        assertEquals(
            "https://note.pingolindev.com",
            WebAppActivity.originOf("https://note.pingolindev.com/"),
        )
        assertEquals(
            "https://note.pingolin.com",
            WebAppActivity.originOf("https://note.pingolin.com/api/v1"),
        )
    }
}

/**
 * The deep-link URL. A push tap is the one path where the user never sees the
 * notes grid, so a wrong URL here is not a cosmetic slip — it is a
 * notification that opens the wrong thing.
 */
class WebAppStartUrlTest {
    private val base = BuildConfig.NOTE_API_BASE_URL.trimEnd('/')

    @Test
    fun `a note id becomes the per-note route`() {
        assertEquals(
            "$base/note/abc-123",
            WebAppActivity.startUrl("abc-123"),
        )
    }

    @Test
    fun `no note id opens the grid`() {
        assertEquals("$base/", WebAppActivity.startUrl(null))
        assertEquals("$base/", WebAppActivity.startUrl(""))
        assertEquals("$base/", WebAppActivity.startUrl("   "))
    }

    /**
     * BuildConfig carries a trailing slash. Concatenating naively yields
     * `//note/<id>`, which is a protocol-relative path, not the route.
     */
    @Test
    fun `does not double the slash from the configured base url`() {
        assertFalse(WebAppActivity.startUrl("x").contains(".com//"))
    }
}

class WebAppSessionCookieTest {
    @Test
    fun `both seeded bearer cookies are HttpOnly`() {
        val auth = WebAppActivity.authCookieHeader("access", ".pingolindev.com")
        val refresh = WebAppActivity.refreshCookieHeader("refresh", ".pingolindev.com")

        assertTrue(auth.contains("; HttpOnly;"))
        assertTrue(refresh.contains("; HttpOnly;"))
        assertTrue(auth.contains("; Secure;"))
        assertTrue(refresh.contains("; Secure;"))
        assertTrue(refresh.contains("Path=/api/v1/auth/refresh"))
    }
}

/**
 * The `/new` deep link.
 *
 * The security property is the one to hold onto: share-into-app passes the
 * user's own text, so it must ride in the FRAGMENT. A query string is sent to
 * the server in the GET request line, which would put note plaintext in the
 * server's logs and break E2EE outright.
 */
class WebAppNewNoteUrlTest {
    private val base = BuildConfig.NOTE_API_BASE_URL.trimEnd('/')

    @Test
    fun `a plain new note opens the composer`() {
        assertEquals("$base/new", WebAppActivity.newNoteUrl(null))
        assertEquals("$base/new", WebAppActivity.newNoteUrl("text"))
    }

    @Test
    fun `a checklist carries its type`() {
        assertEquals(
            "$base/new#type=checklist",
            WebAppActivity.newNoteUrl("checklist"),
        )
    }

    @Test
    fun `shared text NEVER lands in the query string`() {
        val url = WebAppActivity.newNoteUrl(null, "Trip", "Milk and eggs")
        assertFalse("note content must not precede the fragment", url.substringBefore('#').contains("Trip"))
        assertFalse(url.substringBefore('#').contains("Milk"))
        assertTrue(url.startsWith("$base/new#"))
    }

    @Test
    fun `title and body are encoded so newlines and separators survive`() {
        val url = WebAppActivity.newNoteUrl(null, "a&b", "line1\nline2")
        assertTrue("raw & would split the params", url.contains("title=a%26b"))
        assertTrue("raw newline would break the URL", url.contains("body=line1%0D%0Aline2") || url.contains("body=line1%0Aline2"))
    }

    @Test
    fun `empty prefill is omitted rather than sent as blank`() {
        assertEquals("$base/new", WebAppActivity.newNoteUrl(null, "", ""))
    }
}
