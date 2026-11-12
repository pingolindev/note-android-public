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

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.pingolin.note.util.KeptWebTrustStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `pingolinTrust`, the shell's half: the note page's main frame only, the signed-in account
 * only, the page's v1 messages answered with their id, and a store that can't be read never
 * answered as "nothing kept". And the wipe's last keep is bounded.
 *
 * WHY THESE EXIST
 *   The channel hands an account's kept pins, floors and owners to whatever page asks. Served to
 *   another frame or for another account, it would hand that page one account's trust; answered
 *   "none" for an unreadable store, the page would overwrite the copy with less.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class KeptWebTrustChannelTest {

    private val note = "https://note.pingolindev.com"
    private val me = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val other = "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private lateinit var prefs: SharedPreferences
    private lateinit var store: KeptWebTrustStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("kept_web_trust_channel", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        store = KeptWebTrustStore { prefs }
    }

    @After
    fun tearDown() = RetainedWebView.discard()

    private fun ask(
        message: String?,
        origin: String = note,
        mainFrame: Boolean = true,
        signedIn: String? = me,
        s: KeptWebTrustStore = store,
    ): JSONObject? = KeptWebTrustChannel.decide(message, origin, mainFrame, note, signedIn, s)?.let(::JSONObject)

    private fun get(account: String = me, id: Any = 7) =
        JSONObject().put("op", "keptTrust").put("v", 1).put("account", account).put("id", id).toString()

    private fun keep(account: String = me, pins: Any? = "{\"p\":1}", floors: Any? = "{\"f\":2}", owners: Any? = null) =
        JSONObject().put("op", "keepTrust").put("v", 1).put("account", account)
            .put("pins", pins ?: JSONObject.NULL).put("floors", floors ?: JSONObject.NULL)
            .put("owners", owners ?: JSONObject.NULL).put("id", 8).toString()

    @Test
    fun `answers only the note page's main frame`() {
        store.keep(me, "{}", null, null)
        assertNull(ask(get(), origin = "https://account.pingolindev.com"))
        assertNull(ask(get(), mainFrame = false))
        assertEquals("copy", ask(get())!!.getString("state"))
    }

    @Test
    fun `answers nothing that isn't this contract`() {
        assertNull(ask("not json"))
        assertNull(ask(JSONObject(get()).apply { remove("id") }.toString()))
        assertNull(ask(JSONObject(get()).put("id", "7").toString()))
        assertNull(ask(JSONObject(get()).put("v", 2).toString()))
        assertNull(ask(null))
    }

    @Test
    fun `refuses with none signed in, or for another account, and gives nothing of theirs`() {
        store.keep(other, "{\"theirs\":1}", null, null)
        val signedOut = ask(get(), signedIn = null)!!
        assertEquals("refused", signedOut.getString("state"))
        assertEquals(7, signedOut.getInt("id"))
        val theirs = ask(get(account = other))!!
        assertEquals("refused", theirs.getString("state"))
        assertTrue(!theirs.has("pins"))
        assertEquals("refused", ask(keep(account = other))!!.getString("state"))
        assertEquals("their copy is untouched", KeptWebTrustStore.Kept.Copy("{\"theirs\":1}", null, null), store.get(other))
    }

    @Test
    fun `gives back the signed-in account's copy, with the id echoed`() {
        store.keep(me, "{\"p\":1}", "{\"f\":2}", null)
        val r = ask(get(account = me.uppercase(), id = 41))!!
        assertEquals(1, r.getInt("v"))
        assertEquals("copy", r.getString("state"))
        assertEquals(me, r.getString("account"))
        assertEquals("{\"p\":1}", r.getString("pins"))
        assertEquals("{\"f\":2}", r.getString("floors"))
        assertTrue(r.isNull("owners"))
        assertEquals(41, r.getInt("id"))
    }

    @Test
    fun `says none when nothing is kept, and unreadable (never none) when the store can't be read`() {
        val none = ask(get())!!
        assertEquals("none", none.getString("state"))
        assertEquals(me, none.getString("account"))
        val broken = KeptWebTrustStore { throw IllegalStateException("keystore said no") }
        val unreadable = ask(get(), s = broken)!!
        assertEquals("unreadable", unreadable.getString("state"))
        assertEquals(7, unreadable.getInt("id"))
    }

    @Test
    fun `keeps what it's sent, and refuses a blob that isn't a string or is over 1 MB`() {
        val kept = ask(keep())!!
        assertEquals("kept", kept.getString("state"))
        assertEquals(8, kept.getInt("id"))
        assertEquals(KeptWebTrustStore.Kept.Copy("{\"p\":1}", "{\"f\":2}", null), store.get(me))

        assertEquals("refused", ask(keep(pins = JSONObject().put("p", 1)))!!.getString("state"))
        assertEquals("refused", ask(keep(floors = "x".repeat(KeptWebTrustStore.MAX_BLOB_BYTES + 1)))!!.getString("state"))
        assertEquals("nothing refused was written", KeptWebTrustStore.Kept.Copy("{\"p\":1}", "{\"f\":2}", null), store.get(me))
    }

    @Test
    fun `refuses an op it doesn't know`() {
        val r = ask(JSONObject(get()).put("op", "dumpAll").toString())!!
        assertEquals("refused", r.getString("state"))
    }

    // --- The seals the app made finishing invitations (InvitationFinisher) ---

    private val zoe = "2b2b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val ann = "3c3c2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private fun take(account: String = me, id: Any = 9) =
        JSONObject().put("op", "takeSeals").put("v", 1).put("account", account).put("id", id).toString()

    private fun clear(upTo: Any?, account: String = me) =
        JSONObject().put("op", "clearSeals").put("v", 1).put("account", account)
            .apply { if (upTo != null) put("upTo", upTo) }.put("id", 10).toString()

    @Test
    fun `takeSeals gives the signed-in account's seals with the id echoed, and deletes nothing`() {
        store.recordSeal(me, zoe, "cc".repeat(32), "dd".repeat(32), 1_700_000_000_123L)
        store.recordSeal(other, ann, "ee".repeat(32), "ff".repeat(32), 5L)

        val r = ask(take(account = me.uppercase(), id = 42))!!
        assertEquals(1, r.getInt("v"))
        assertEquals("seals", r.getString("state"))
        assertEquals(me, r.getString("account"))
        assertEquals(42, r.getInt("id"))
        val seals = r.getJSONArray("seals")
        assertEquals(1, seals.length())
        val seal = seals.getJSONObject(0)
        assertEquals(zoe, seal.getString("user_id"))
        assertEquals("cc".repeat(32), seal.getString("sharing_public_key"))
        assertEquals("dd".repeat(32), seal.getString("signing_public_key"))
        assertEquals(1_700_000_000_123L, seal.getLong("sealed_at"))

        assertEquals("taking deletes nothing", "seals", ask(take())!!.getString("state"))
    }

    @Test
    fun `takeSeals says none when there are none, and unreadable (never none) when they can't be read`() {
        val none = ask(take())!!
        assertEquals("none", none.getString("state"))
        assertEquals(me, none.getString("account"))
        assertTrue(!none.has("seals"))

        store.keep(me, "{}", null, null)
        assertEquals("the page's blobs are no seals", "none", ask(take())!!.getString("state"))

        val broken = KeptWebTrustStore { throw IllegalStateException("keystore said no") }
        val unreadable = ask(take(), s = broken)!!
        assertEquals("unreadable", unreadable.getString("state"))
        assertEquals(9, unreadable.getInt("id"))
        prefs.edit().putString("$me|seals", "garbled").commit()
        assertEquals("unreadable", ask(take())!!.getString("state"))
    }

    /** The page's pins after it folded these seals (the person, and the seal's sharing and signing
     *  keys) and kept them. */
    private fun keptPinsHolding(vararg people: Triple<String, String, String>) {
        val pins = JSONObject()
        for ((p, sharing, signing) in people) pins.put(p, JSONObject().put("sharingPubkeyHex", sharing).put("signingPubkeyHex", signing))
        store.keep(me, pins.toString(), null, null)
    }

    private val zoeSealed get() = Triple(zoe, "cc".repeat(32), "dd".repeat(32))
    private val annSealed get() = Triple(ann, "ee".repeat(32), "ff".repeat(32))

    @Test
    fun `clearSeals removes the seals up to what was taken, and keeps a later one`() {
        store.recordSeal(me, zoe, "cc".repeat(32), "dd".repeat(32), 1_000L)
        store.recordSeal(me, ann, "ee".repeat(32), "ff".repeat(32), 2_000L)
        keptPinsHolding(zoeSealed, annSealed)

        val r = ask(clear(1_000))!!
        assertEquals("cleared", r.getString("state"))
        assertEquals(me, r.getString("account"))
        assertEquals(10, r.getInt("id"))
        assertEquals(
            listOf(KeptWebTrustStore.Seal(ann, "ee".repeat(32), "ff".repeat(32), 2_000L)),
            (store.seals(me) as KeptWebTrustStore.Seals.Held).seals,
        )
        assertEquals("cleared", ask(clear(2_000.5))!!.getString("state"))
        assertEquals("none", ask(take())!!.getString("state"))
    }

    /** Review of bb1d05b: a page that clears before its keep of the fold has landed. */
    @Test
    fun `clearSeals keeps a seal whose person the kept pins don't hold yet, whatever its time`() {
        store.recordSeal(me, zoe, "cc".repeat(32), "dd".repeat(32), 1_000L)
        store.recordSeal(me, ann, "ee".repeat(32), "ff".repeat(32), 1_500L)
        keptPinsHolding(zoeSealed)

        assertEquals("cleared", ask(clear(2_000))!!.getString("state"))
        assertEquals(listOf(ann), (store.seals(me) as KeptWebTrustStore.Seals.Held).seals.map { it.userId })
        assertEquals("seals", ask(take())!!.getString("state"))

        keptPinsHolding(zoeSealed, annSealed)
        assertEquals("cleared", ask(clear(2_000))!!.getString("state"))
        assertEquals("none", ask(take())!!.getString("state"))
    }

    @Test
    fun `clearSeals refuses another account, a missing or non-numeric upTo, and clears nothing then`() {
        store.recordSeal(me, zoe, "cc".repeat(32), "dd".repeat(32), 1_000L)
        store.recordSeal(other, ann, "ee".repeat(32), "ff".repeat(32), 1_000L)
        assertEquals("refused", ask(clear(5_000, account = other))!!.getString("state"))
        assertEquals("refused", ask(clear(null))!!.getString("state"))
        assertEquals("refused", ask(clear("5000"))!!.getString("state"))
        assertEquals("refused", ask(take(account = other))!!.getString("state"))
        assertEquals("refused", ask(take(), signedIn = null)!!.getString("state"))
        assertEquals(1, (store.seals(me) as KeptWebTrustStore.Seals.Held).seals.size)
        assertEquals(1, (store.seals(other) as KeptWebTrustStore.Seals.Held).seals.size)
        assertNull("not the note page's main frame", ask(take(), mainFrame = false))
    }

    @Test(timeout = 10_000)
    fun `the wipe's last keep is bounded, even when the page never answers`() = runBlocking {
        assertNull("no document, no keep", RetainedWebView.keepWebTrustNow(500))
        val context = ApplicationProvider.getApplicationContext<Context>()
        RetainedWebView.obtain(context) {}
        RetainedWebView.markLoaded(note)
        val started = System.currentTimeMillis()
        assertNull(RetainedWebView.keepWebTrustNow(300))
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun `every wipe gives the page its last keep after the flush and before the WebView goes, and the shell installs the channel (read from the source)`() {
        val src = File("src/main/java/com/pingolin/note")
        val wiper = File(src, "domain/repository/LocalDataWiper.kt").readText()
        val flush = wiper.indexOf("RetainedWebView.flushWebPending(")
        val last = wiper.indexOf("RetainedWebView.keepWebTrustNow(1_000)")
        val destroy = wiper.indexOf("RetainedWebView.destroy()")
        val storage = wiper.indexOf("WebStorage.getInstance().deleteAllData()")
        assertTrue(flush in 0 until last)
        assertTrue(last < destroy && destroy < storage)

        val activity = File(src, "ui/web/WebAppActivity.kt").readText()
        assertTrue(activity.contains(
            "RetainedWebView.onceTrustChannel {\n" +
                "            KeptWebTrustChannel.install(web, originOf(BuildConfig.NOTE_API_BASE_URL), keptWebTrustStore) {\n" +
                "                sessionManager.getUserId()",
        ))
    }
}
