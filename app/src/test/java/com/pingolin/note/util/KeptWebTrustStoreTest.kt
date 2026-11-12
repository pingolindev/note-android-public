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

package com.pingolin.note.util

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The web page's kept trust, held by the app: per canonical account, as the page's strings,
 * committed before a wipe, and never "nothing kept" when it can't be read.
 *
 * WHY THESE EXIST
 *   Every sign-out wipe in this shell deletes the page's storage, its pins, floors and owners with
 *   the rest, so without this store each sign-in would meet every sender as a first contact. This
 *   store is what survives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class KeptWebTrustStoreTest {

    private val me = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val other = "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private lateinit var prefs: SharedPreferences
    /** How each write ended: "commit" or "apply". */
    private val writes = mutableListOf<String>()
    private lateinit var store: KeptWebTrustStore

    /** [prefs], recording how each edit is written. */
    private fun recording(p: SharedPreferences): SharedPreferences = object : SharedPreferences by p {
        override fun edit(): SharedPreferences.Editor {
            val e = p.edit()
            return object : SharedPreferences.Editor by e {
                override fun commit(): Boolean { writes += "commit"; return e.commit() }
                override fun apply() { writes += "apply"; e.apply() }
            }
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("kept_web_trust_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        writes.clear()
        store = KeptWebTrustStore { recording(prefs) }
    }

    @Test
    fun `keeps the page's three blobs per account, and gives back exactly them`() {
        assertEquals(KeptWebTrustStore.Kept.None, store.get(me))

        assertTrue(store.keep(me.uppercase(), "{\"p\":1}", "{\"f\":2}", null))

        assertEquals(KeptWebTrustStore.Kept.Copy("{\"p\":1}", "{\"f\":2}", null), store.get(me))
        assertEquals("another account sees none of it", KeptWebTrustStore.Kept.None, store.get(other))
        assertEquals(setOf("$me|pins", "$me|floors"), prefs.all.keys)
    }

    @Test
    fun `commits every keep, so the last one before a wipe is on disk`() {
        store.keep(me, "{}", "{}", "{}")
        assertEquals(listOf("commit"), writes)
    }

    @Test
    fun `a null blob takes its entry away`() {
        store.keep(me, "{}", "{}", "{}")
        store.keep(me, "{}", null, "{}")
        assertEquals(KeptWebTrustStore.Kept.Copy("{}", null, "{}"), store.get(me))
    }

    @Test
    fun `keeps nothing over 1 MB`() {
        store.keep(me, "{}", null, null)
        val big = "x".repeat(KeptWebTrustStore.MAX_BLOB_BYTES + 1)
        assertFalse(store.keep(me, "{\"new\":1}", big, null))
        assertEquals("nothing of that keep was written", KeptWebTrustStore.Kept.Copy("{}", null, null), store.get(me))
        assertTrue(store.keep(me, "x".repeat(KeptWebTrustStore.MAX_BLOB_BYTES), null, null))
    }

    @Test
    fun `a store that can't be read is Unreadable, never None, keeps nothing, and its forget throws`() {
        val broken = KeptWebTrustStore { throw IllegalStateException("keystore said no") }
        assertEquals(KeptWebTrustStore.Kept.Unreadable, broken.get(me))
        assertFalse(broken.keep(me, "{}", "{}", "{}"))
        assertThrows(StoreUnreadable::class.java) { broken.forget(me) }
    }

    @Test
    fun `forgets one account under any spelling, and nobody else`() {
        store.keep(me, "{}", "{}", "{}")
        store.keep(other, "{}", "{}", "{}")
        prefs.edit().putString("{$me}|pins", "{}").commit()

        store.forget(me.uppercase())

        assertEquals(KeptWebTrustStore.Kept.None, store.get(me))
        assertTrue(prefs.all.keys.none { AccountIds.same(it.substringBefore('|'), me) })
        assertEquals(KeptWebTrustStore.Kept.Copy("{}", "{}", "{}"), store.get(other))
    }

    // --- The pins, read by the invitation finisher ---

    private val zoe = "2b2b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val ann = "3c3c2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val s = "cc".repeat(32)
    private val g = "dd".repeat(32)

    private fun pin(sharing: String, signing: String, conflict: Boolean = false) =
        org.json.JSONObject().put("sharingPubkeyHex", sharing).put("signingPubkeyHex", signing)
            .put("firstPinnedAt", 1).put("lastVerifiedAt", 2).apply { if (conflict) put("conflict", true) }

    @Test
    fun `the pins read as the page's foldPins reads them, and reading writes nothing`() {
        assertEquals(KeptWebTrustStore.Pins.Absent, store.pins(me))
        val blob = org.json.JSONObject()
            .put(zoe.uppercase(), pin(s.uppercase(), g))
            .put(ann, pin("0".repeat(64), "0".repeat(64), conflict = true))
            .put("not-an-id", pin(s, g))
            .put("4d4d2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", org.json.JSONObject().put("sharingPubkeyHex", s))
            .toString()
        store.keep(me, blob, null, null)
        writes.clear()

        assertEquals(
            KeptWebTrustStore.Pins.Held(
                mapOf(
                    zoe to KeptWebTrustStore.Pin(s, g, conflict = false),
                    ann to KeptWebTrustStore.Pin("0".repeat(64), "0".repeat(64), conflict = true),
                ),
            ),
            store.pins(me),
        )
        assertEquals("nothing written", emptyList<String>(), writes)
        assertEquals(KeptWebTrustStore.Kept.Copy(blob, null, null), store.get(me))
        assertEquals("another account's are its own", KeptWebTrustStore.Pins.Absent, store.pins(other))
    }

    @Test
    fun `two records of one contact with different keys are a conflict, with the same keys one pin`() {
        val zoeBare = zoe.replace("-", "")
        store.keep(me, org.json.JSONObject().put(zoe, pin(s, g)).put(zoeBare, pin(s, "ee".repeat(32))).toString(), null, null)
        assertEquals(true, (store.pins(me) as KeptWebTrustStore.Pins.Held).byContact.getValue(zoe).conflict)
        store.keep(me, org.json.JSONObject().put(zoe, pin(s, g)).put(zoeBare, pin(s.uppercase(), g)).toString(), null, null)
        assertEquals(KeptWebTrustStore.Pin(s, g, conflict = false), (store.pins(me) as KeptWebTrustStore.Pins.Held).byContact.getValue(zoe))
    }

    @Test
    fun `pins that don't parse as an object, or a store that can't be read, are unreadable, never absent`() {
        for (blob in listOf("not json", "[]", "null", "\"x\"")) {
            store.keep(me, blob, null, null)
            assertEquals(blob, KeptWebTrustStore.Pins.Unreadable, store.pins(me))
        }
        assertEquals(KeptWebTrustStore.Pins.Unreadable, KeptWebTrustStore { throw IllegalStateException("keystore said no") }.pins(me))
        store.keep(me, "{}", null, null)
        assertEquals("an empty blob is a blob", KeptWebTrustStore.Pins.Held(emptyMap()), store.pins(me))
    }

    // --- Seals ---

    private fun seals(account: String = me) = (store.seals(account) as KeptWebTrustStore.Seals.Held).seals

    @Test
    fun `a seal is recorded once per person, committed, lower-cased, and never replaced`() {
        assertEquals(KeptWebTrustStore.SealRecord.RECORDED, store.recordSeal(me, zoe.uppercase(), s.uppercase(), g, 500L))
        assertEquals(listOf("commit"), writes)
        assertEquals(listOf(KeptWebTrustStore.Seal(zoe, s, g, 500L)), seals())

        assertEquals(KeptWebTrustStore.SealRecord.HELD, store.recordSeal(me, zoe, s, "ee".repeat(32), 600L))
        assertEquals(KeptWebTrustStore.SealRecord.DIFFERENT, store.recordSeal(me, zoe, "ee".repeat(32), g, 600L))
        assertEquals("nothing more written", listOf("commit"), writes)
        assertEquals(listOf(KeptWebTrustStore.Seal(zoe, s, g, 500L)), seals())
        assertEquals("another account holds none", emptyList<KeptWebTrustStore.Seal>(), seals(other))
    }

    @Test
    fun `a first contact needs two 64-hex keys and an account id`() {
        assertEquals(KeptWebTrustStore.SealRecord.REFUSED, store.recordSeal(me, zoe, s, "dd".repeat(31), 1L))
        assertEquals(KeptWebTrustStore.SealRecord.REFUSED, store.recordSeal(me, zoe, "xy".repeat(32), g, 1L))
        assertEquals(KeptWebTrustStore.SealRecord.REFUSED, store.recordSeal(me, zoe, "0".repeat(64), g, 1L))
        assertEquals(KeptWebTrustStore.SealRecord.REFUSED, store.recordSeal(me, "zoe", s, g, 1L))
        assertEquals(KeptWebTrustStore.SealRecord.REFUSED, store.recordSeal("nobody", zoe, s, g, 1L))
        assertEquals(emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    @Test
    fun `a seal recorded after others is later than all of them, so a clear up to what was taken never takes it`() {
        store.recordSeal(me, zoe, s, g, 1_000L)
        // Taken by the page: up to 1000. Then the finisher seals again with a clock that is behind.
        store.recordSeal(me, ann, s, g, 900L)
        assertEquals(listOf(1_000L, 1_001L), seals().map { it.sealedAt })
        // The page folded both into its pins and kept them.
        store.keep(me, org.json.JSONObject().put(zoe, pin(s, g)).put(ann, pin(s, g)).toString(), null, null)
        assertTrue(store.clearSeals(me, 1_000.0))
        assertEquals(listOf(KeptWebTrustStore.Seal(ann, s, g, 1_001L)), seals())
    }

    /** Review of bb1d05b: a page that clears before its keep of the fold has landed (or that
     *  didn't fold at all) must not take the only record that the app sealed to someone. */
    @Test
    fun `a clear takes only the seals of people the kept pins now hold, a pin or a conflict`() {
        store.recordSeal(me, zoe, s, g, 1_000L)
        store.recordSeal(me, ann, s, g, 1_000L)
        assertTrue("no pins kept: nothing is taken", store.clearSeals(me, 5_000.0))
        assertEquals(2, seals().size)

        store.keep(me, org.json.JSONObject().put(zoe, pin("0".repeat(64), "0".repeat(64), conflict = true)).toString(), null, null)
        assertTrue(store.clearSeals(me, 5_000.0))
        assertEquals("zoe's conflict holds her; ann isn't held yet", listOf(ann), seals().map { it.userId })

        store.keep(me, "garbled", null, null)
        assertTrue(store.clearSeals(me, 5_000.0))
        assertEquals("pins that don't read hold nobody", listOf(ann), seals().map { it.userId })
    }

    /** Re-review of 42fd9a6: a page that couldn't fold (its pins weren't loaded) and still kept and
     *  cleared. Zoe pinned at another key S' and no conflict means this seal was never folded:
     *  dropping it would lose the conflict the fold must make. */
    @Test
    fun `a clear keeps a seal whose person the kept pins hold at another key without a conflict`() {
        store.recordSeal(me, zoe, s, g, 1_000L)
        store.keep(me, org.json.JSONObject().put(zoe, pin("ee".repeat(32), g)).toString(), null, null)
        assertTrue(store.clearSeals(me, 5_000.0))
        assertEquals("unfolded: kept", listOf(zoe), seals().map { it.userId })

        store.keep(me, org.json.JSONObject().put(zoe, pin(s.uppercase(), g.uppercase())).toString(), null, null)
        assertTrue(store.clearSeals(me, 5_000.0))
        assertEquals("pinned at both of the seal's own keys, in any case: folded, taken", emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    /** The page's merge (pinMerge.ts `combined`) makes a conflict when only the SIGNING key
     *  differs too: a seal at (K, G2) that the kept pins hold at (K, G1) with no conflict was
     *  never folded, and stays. */
    @Test
    fun `a clear keeps a seal the kept pins hold at its sharing key but another signing key, without a conflict`() {
        store.recordSeal(me, zoe, s, "ee".repeat(32), 1_000L)
        store.keep(me, org.json.JSONObject().put(zoe, pin(s, g)).toString(), null, null)
        assertTrue(store.clearSeals(me, 5_000.0))
        assertEquals("unfolded: kept", listOf(KeptWebTrustStore.Seal(zoe, s, "ee".repeat(32), 1_000L)), seals())

        store.keep(me, org.json.JSONObject().put(zoe, pin(s, "EE".repeat(32))).toString(), null, null)
        assertTrue(store.clearSeals(me, 5_000.0))
        assertEquals("pinned at both its keys: folded, taken", emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    // --- The finisher's decision, against the pins kept now ---

    @Test
    fun `the caller's own refusal counts only for someone the kept pins hold no record of`() {
        store.keep(me, org.json.JSONObject().put(zoe, pin(s, g)).toString(), null, null)
        assertEquals("a kept pin decides", KeptWebTrustStore.Trust.PINNED, store.trustForSeal(me, zoe, s, g, 1L, refuseUnpinned = true))
        assertEquals(KeptWebTrustStore.Trust.REFUSED, store.trustForSeal(me, ann, s, g, 1L, refuseUnpinned = true))
        assertEquals("refused before anything is recorded", emptyList<KeptWebTrustStore.Seal>(), seals())
        assertEquals(KeptWebTrustStore.Trust.RECORDED, store.trustForSeal(me, ann, s, g, 2L))
        assertEquals("a seal held: still refused when the caller refuses", KeptWebTrustStore.Trust.REFUSED, store.trustForSeal(me, ann, s, g, 3L, refuseUnpinned = true))
    }

    private fun trust(sharing: String = s, signing: String = g, contact: String = zoe, at: Long = 50L) =
        store.trustForSeal(me, contact, sharing, signing, at)

    @Test
    fun `a decision reads the pins kept at that moment, a kept pin first`() {
        assertEquals("no pins kept: refused", KeptWebTrustStore.Trust.REFUSED, trust())
        store.keep(me, org.json.JSONObject().put(zoe, pin(s.uppercase(), g)).toString(), null, null)
        assertEquals(KeptWebTrustStore.Trust.PINNED, trust())
        assertEquals(KeptWebTrustStore.Trust.REFUSED, trust(sharing = "ee".repeat(32)))
        store.keep(me, org.json.JSONObject().put(zoe, pin("0".repeat(64), "0".repeat(64), conflict = true)).toString(), null, null)
        assertEquals("a conflict, kept since", KeptWebTrustStore.Trust.REFUSED, trust())
        store.keep(me, "[]", null, null)
        assertEquals("pins that don't parse", KeptWebTrustStore.Trust.REFUSED, trust())
        assertEquals("never a seal over pins", emptyList<KeptWebTrustStore.Seal>(), seals())
    }

    @Test
    fun `with no kept pin, a seal decides, and a first contact is recorded`() {
        store.keep(me, "{}", null, null)
        assertEquals(KeptWebTrustStore.Trust.RECORDED, trust(at = 70L))
        assertEquals(listOf(KeptWebTrustStore.Seal(zoe, s, g, 70L)), seals())
        assertEquals(KeptWebTrustStore.Trust.SEALED, trust(at = 80L))
        assertEquals(KeptWebTrustStore.Trust.REFUSED, trust(sharing = "ee".repeat(32)))
        assertEquals("a first contact needs two 64-hex keys", KeptWebTrustStore.Trust.REFUSED, trust(contact = ann, signing = "dd"))
        assertEquals(KeptWebTrustStore.Trust.REFUSED, trust(contact = "ann"))
        assertEquals(listOf(KeptWebTrustStore.Seal(zoe, s, g, 70L)), seals())
    }

    @Test
    fun `seals that can't be read are unreadable, and record and clear nothing`() {
        prefs.edit().putString("$me|seals", "{not an array").commit()
        assertEquals(KeptWebTrustStore.Seals.Unreadable, store.seals(me))
        assertEquals(KeptWebTrustStore.SealRecord.REFUSED, store.recordSeal(me, zoe, s, g, 1L))
        assertEquals(false, store.clearSeals(me, 1e15))
        assertEquals("{not an array", prefs.getString("$me|seals", null))
        val broken = KeptWebTrustStore { throw IllegalStateException("keystore said no") }
        assertEquals(KeptWebTrustStore.Seals.Unreadable, broken.seals(me))
        assertEquals(KeptWebTrustStore.SealRecord.REFUSED, broken.recordSeal(me, zoe, s, g, 1L))
    }

    @Test
    fun `seals and tries are not the page's blobs, and go with the account's forget`() {
        store.keep(me, "{}", null, null)
        store.recordSeal(me, zoe, s, g, 1L)
        store.keepTriedAt(me, mapOf("share-1" to 5L))
        store.recordSeal(other, zoe, s, g, 1L)
        assertEquals("the page's copy is still its three blobs", KeptWebTrustStore.Kept.Copy("{}", null, null), store.get(me))

        store.forget(me)
        assertEquals(emptyList<KeptWebTrustStore.Seal>(), seals())
        assertEquals(emptyMap<String, Long>(), store.triedAt(me))
        assertEquals("nobody else's", 1, seals(other).size)
    }

    // --- Tries ---

    @Test
    fun `the tries are kept per account, committed, and an empty set removes the entry`() {
        assertEquals(emptyMap<String, Long>(), store.triedAt(me))
        assertTrue(store.keepTriedAt(me, mapOf("a" to 1L, "b" to 2L)))
        assertEquals(listOf("commit"), writes)
        assertEquals(mapOf("a" to 1L, "b" to 2L), store.triedAt(me))
        assertEquals(emptyMap<String, Long>(), store.triedAt(other))
        assertTrue(store.keepTriedAt(me, emptyMap()))
        assertTrue(prefs.all.keys.none { it.endsWith("|finish_tried") })
        prefs.edit().putString("$me|finish_tried", "garbled").commit()
        assertEquals(null, store.triedAt(me))
    }
}
