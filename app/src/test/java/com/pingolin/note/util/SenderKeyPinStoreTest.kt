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
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real pin store over plain preferences (EncryptedSharedPreferences needs the Keystore, which
 * Robolectric lacks): canonical keys, the pin-merge fold, and a store that can't be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SenderKeyPinStoreTest {

    private val me = "00000000-0000-4000-8000-00000000000e"
    private val alice = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val aliceHex = alice.replace("-", "")
    private val aliceSpellings = listOf(alice.uppercase(), aliceHex, aliceHex.uppercase(), "{$alice}", "urn:uuid:$alice")
    private val signA = "a".repeat(64)
    private val shareA = "b".repeat(64)
    private val signB = "c".repeat(64)
    private val shareB = "d".repeat(64)

    private lateinit var prefs: SharedPreferences
    private val session = mockk<SessionManager>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("sender_pins_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        every { session.getUserId() } returns me
    }

    private fun store(open: () -> SharedPreferences = { prefs }) = SenderKeyPinStore(open, session)

    private fun blob(vararg entries: Pair<String, Triple<String, String?, Long>>): String {
        val root = JSONObject()
        for ((k, v) in entries) {
            val o = JSONObject().put("signing", v.first).put("pinnedAt", v.third)
            v.second?.let { o.put("sharing", it) }
            root.put(k, o)
        }
        return root.toString()
    }

    @Test
    fun `a pin taken under the canonical id is found under every spelling`() {
        val s = store()
        assertEquals(SenderKeyPinStore.PinState.NewPinning, s.pin(alice, signA, shareA))
        for (spelling in aliceSpellings) {
            assertEquals(spelling, signA, s.getPinned(spelling)?.signingPubHex)
            assertEquals(spelling, SenderKeyPinStore.PinState.Matched, s.pin(spelling, signA, shareA))
        }
        // And under another spelling nothing new was pinned: one entry, keyed canonically.
        assertEquals(setOf(alice), JSONObject(prefs.getString("sender_pins:$me", null)!!).keys().asSequence().toSet())
    }

    @Test
    fun `a pin taken under another spelling is kept canonically`() {
        val s = store()
        assertEquals(SenderKeyPinStore.PinState.NewPinning, s.pin(aliceHex.uppercase(), signA, shareA))
        assertEquals(signA, s.getPinned(alice)?.signingPubHex)
        assertEquals(setOf(alice), JSONObject(prefs.getString("sender_pins:$me", null)!!).keys().asSequence().toSet())
    }

    @Test
    fun `an id that is no UUID is refused, never read as not pinned`() {
        val s = store()
        s.pin(alice, signA, shareA)
        for (bad in listOf("", "me", " $alice", "{$aliceHex}", "URN:UUID:$alice")) {
            assertThrows(bad, AccountIds.NotAnAccountId::class.java) { s.getPinned(bad) }
            assertThrows(bad, AccountIds.NotAnAccountId::class.java) { s.pin(bad, signB, shareB) }
        }
        assertEquals(setOf(alice), JSONObject(prefs.getString("sender_pins:$me", null)!!).keys().asSequence().toSet())
    }

    @Test
    fun `the send gate refuses an id that is no UUID and pins nothing`() {
        val s = store()
        assertFalse(s.allowSealToRecipient("me", signA, shareA))
        assertFalse(s.allowSealToRecipient(" $alice", signA, shareA))
        assertNull(prefs.getString("sender_pins:$me", null))
    }

    @Test
    fun `the send gate refuses a re-spelled pinned recipient with a substituted key`() {
        val s = store()
        assertTrue(s.allowSealToRecipient(alice, signA, shareA))
        for (spelling in aliceSpellings) {
            assertFalse(spelling, s.allowSealToRecipient(spelling, signB, shareB))
            assertTrue(spelling, s.allowSealToRecipient(spelling, signA, shareA))
        }
    }

    @Test
    fun `spellings with the same keys fold into one record with the earliest first pin`() {
        prefs.edit().putString(
            "sender_pins:$me",
            blob(alice to Triple(signA, shareA, 200L), aliceHex to Triple(signA, null, 100L)),
        ).commit()
        val pin = store().getPinned(alice)
        assertEquals(SenderKeyPinStore.PinRecord(signA, shareA, 100L), pin)
        // Written back canonical: one entry.
        assertEquals(setOf(alice), JSONObject(prefs.getString("sender_pins:$me", null)!!).keys().asSequence().toSet())
    }

    @Test
    fun `spellings with different keys fold into a conflict that is never matched nor a first contact`() {
        prefs.edit().putString(
            "sender_pins:$me",
            blob(alice to Triple(signA, shareA, 200L), "{$alice}" to Triple(signB, shareB, 100L)),
        ).commit()
        val s = store()
        val pin = s.getPinned(alice)
        assertNotNull(pin)
        assertTrue(pin!!.isConflict)
        assertEquals(SenderKeyPinStore.CONFLICT_KEY_HEX, pin.sharingPubHex)
        assertEquals(100L, pin.pinnedAt)
        assertEquals(SenderKeyPinStore.PinState.ChangedBoth, s.pin(alice, signA, shareA))
        assertEquals(SenderKeyPinStore.PinState.ChangedBoth, s.pin(alice, signB, shareB))
        assertFalse(s.allowSealToRecipient(alice, signA, shareA))
    }

    @Test
    fun `an entry keyed by no UUID is dropped, and the blob is written back canonical`() {
        prefs.edit().putString(
            "sender_pins:$me",
            blob(alice.uppercase() to Triple(signA, shareA, 100L), "not-an-id" to Triple(signB, shareB, 100L)),
        ).commit()
        assertEquals(signA, store().getPinned(alice)?.signingPubHex)
        assertEquals(setOf(alice), JSONObject(prefs.getString("sender_pins:$me", null)!!).keys().asSequence().toSet())
    }

    @Test
    fun `a blob that does not parse is unreadable, never empty, and is not written over`() {
        prefs.edit().putString("sender_pins:$me", "{not json").commit()
        val s = store()
        assertThrows(StoreUnreadable::class.java) { s.getPinned(alice) }
        assertThrows(StoreUnreadable::class.java) { s.pin(alice, signA, shareA) }
        assertThrows(StoreUnreadable::class.java) { s.ensureReadable() }
        assertFalse("the gate refuses what it can't check", s.allowSealToRecipient(alice, signA, shareA))
        assertEquals("{not json", prefs.getString("sender_pins:$me", null))
    }

    @Test
    fun `a store the Keystore refuses is unreadable, and read again at its next use`() {
        var refuse = true
        val s = store { if (refuse) throw SecurityException("keystore") else prefs }
        assertThrows(StoreUnreadable::class.java) { s.getPinned(alice) }
        s.switchUser(me) // a warm-up swallows it
        refuse = false
        assertNull(s.getPinned(alice))
        s.pin(alice, signA, shareA)
        assertEquals(signA, s.getPinned(alice)?.signingPubHex)
    }

    // --- The namespace is this account's canonical id; other spellings are merged in ---

    private val bob = "1b1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val other = "3c1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private fun keysOf(key: String): Set<String> =
        prefs.getString(key, null)?.let { JSONObject(it).keys().asSequence().toSet() } ?: emptySet()

    @Test
    fun `pins kept under another spelling of this account's id are merged in, then that spelling goes`() {
        val mine = listOf(me.replace("-", ""), me.uppercase(), "{$me}", "urn:uuid:$me")
        prefs.edit()
            .putString("sender_pins:${mine[0]}", blob(alice to Triple(signA, shareA, 100L)))
            .putString("sender_pins:${mine[2]}", blob(alice.uppercase() to Triple(signA, null, 50L)))
            .putString("sender_pins:$me", blob(bob to Triple(signB, shareB, 200L)))
            .commit()
        val s = store()
        assertEquals(SenderKeyPinStore.PinRecord(signA, shareA, 50L), s.getPinned(alice))
        assertEquals(signB, s.getPinned(bob)?.signingPubHex)
        assertEquals(setOf(alice, bob), keysOf("sender_pins:$me"))
        for (spelling in mine) assertNull(spelling, prefs.getString("sender_pins:$spelling", null))
    }

    @Test
    fun `the same sender under two spellings of this account with different keys is a conflict`() {
        prefs.edit()
            .putString("sender_pins:${me.replace("-", "")}", blob(alice to Triple(signB, shareB, 100L)))
            .putString("sender_pins:$me", blob(alice to Triple(signA, shareA, 200L)))
            .commit()
        val pin = store().getPinned(alice)
        assertTrue(pin!!.isConflict)
    }

    @Test
    fun `another account's pins are not touched`() {
        prefs.edit()
            .putString("sender_pins:$other", blob(alice to Triple(signB, shareB, 100L)))
            .putString("sender_pins:$me", blob(alice to Triple(signA, shareA, 200L)))
            .commit()
        assertEquals(signA, store().getPinned(alice)?.signingPubHex)
        assertEquals(setOf(alice), keysOf("sender_pins:$other"))
    }

    @Test
    fun `another spelling stays when the merged blob can't be written`() {
        val spelling = "sender_pins:${me.replace("-", "")}"
        prefs.edit().putString(spelling, blob(alice to Triple(signA, shareA, 100L))).commit()
        // Writes fail; removals would go through. Only the order of the two keeps the source.
        val writesFail = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val real = prefs.edit()
                var wrote = false
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        wrote = true
                        return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor {
                        real.remove(key)
                        return this
                    }
                    override fun commit(): Boolean = if (wrote) false else real.commit()
                    override fun apply() {
                        if (!wrote) real.apply()
                    }
                }
            }
        }
        val s = store { writesFail }
        assertEquals(signA, s.getPinned(alice)?.signingPubHex)
        assertNotNull("nothing it came from is removed", prefs.getString(spelling, null))
        assertNull(prefs.getString("sender_pins:$me", null))
    }

    @Test
    fun `a read that throws is unreadable, never empty`() {
        prefs.edit().putString("sender_pins:$me", blob(alice to Triple(signA, shareA, 100L))).commit()
        val throwing = object : SharedPreferences by prefs {
            override fun getString(key: String?, defValue: String?): String? = throw SecurityException("decrypt")
            override fun getAll(): MutableMap<String, *> = throw SecurityException("decrypt")
        }
        val s = store { throwing }
        assertThrows(StoreUnreadable::class.java) { s.getPinned(alice) }
        assertThrows(StoreUnreadable::class.java) { s.ensureReadable() }
    }

    // --- A caller that names the account it works for
    // -----------------------------------------

    @Test
    fun `a call for an account that is no longer the one signed in is refused, and pins nothing`() {
        // The second account knows alice under OTHER keys.
        prefs.edit().putString("sender_pins:$other", blob(alice to Triple(signB, shareB, 100L))).commit()
        val s = store()
        s.ensureReadable(me)
        assertEquals(SenderKeyPinStore.PinState.NewPinning, s.pin(alice, signA, shareA, me))
        val mine = prefs.getString("sender_pins:$me", null)
        val theirs = prefs.getString("sender_pins:$other", null)

        // The refresh was started for `me`; `other` signs in while it runs.
        every { session.getUserId() } returns other
        assertThrows(StoreUnreadable::class.java) { s.ensureReadable(me) }
        // Not the second account's pin for her (a key change the first account never had), and
        // not "unpinned" either (a first contact pinned under the second account's name).
        assertThrows(StoreUnreadable::class.java) { s.getPinned(alice, me) }
        assertThrows(StoreUnreadable::class.java) { s.getPinned(bob, me) }
        assertThrows(StoreUnreadable::class.java) { s.pin(bob, signA, shareA, me) }
        assertThrows(StoreUnreadable::class.java) { s.pin(alice, signA, shareA, me) }
        assertEquals("the first account's pins are as they were", mine, prefs.getString("sender_pins:$me", null))
        assertEquals("and so are the second's", theirs, prefs.getString("sender_pins:$other", null))
        assertNull("bob is pinned for nobody", s.getPinned(bob))
        assertEquals(signB, s.getPinned(alice)?.signingPubHex)
    }

    @Test
    fun `the account is named in any spelling, and nobody signed in is not that account`() {
        val s = store()
        s.ensureReadable("{$me}")
        assertEquals(SenderKeyPinStore.PinState.NewPinning, s.pin(alice, signA, shareA, me.uppercase()))
        assertEquals(signA, s.getPinned(alice, me.replace("-", ""))?.signingPubHex)
        every { session.getUserId() } returns null
        assertThrows(StoreUnreadable::class.java) { s.getPinned(alice, me) }
        assertThrows(StoreUnreadable::class.java) { s.pin(bob, signA, shareA, me) }
    }

    @Test
    fun `a caller that names no account is served for whoever is signed in`() {
        val s = store()
        s.pin(alice, signA, shareA)
        every { session.getUserId() } returns other
        assertNull(s.getPinned(alice))
        assertEquals(SenderKeyPinStore.PinState.NewPinning, s.pin(alice, signB, shareB))
        s.ensureReadable()
    }

    // --- peekPinned: the invitation finisher's read, which never writes ---

    @Test
    fun `peekPinned reads what a load would read, and writes nothing, where a load writes back`() {
        val otherSpelling = "sender_pins:${me.uppercase()}"
        prefs.edit()
            .putString("sender_pins:$me", blob(alice to Triple(signA, shareA, 200L)))
            .putString(otherSpelling, blob(aliceHex to Triple(signA, null, 100L)))
            .commit()
        val before = prefs.all.toMap()

        val s = store()
        assertEquals(SenderKeyPinStore.PinRecord(signA, shareA, 100L), s.peekPinned(alice.uppercase(), me))
        assertNull(s.peekPinned("1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", me))
        assertEquals("nothing written, nothing folded away", before, prefs.all.toMap())

        // A load (any other read) folds and writes back; peekPinned then reads the loaded pins.
        assertEquals(SenderKeyPinStore.PinRecord(signA, shareA, 100L), s.getPinned(alice))
        assertFalse(prefs.contains(otherSpelling))
        assertEquals(SenderKeyPinStore.PinRecord(signA, shareA, 100L), s.peekPinned(alice, me))
    }

    @Test
    fun `peekPinned refuses an account that isn't signed in, and pins that can't be read, never reading them as none`() {
        prefs.edit().putString("sender_pins:$me", blob(alice to Triple(signA, shareA, 1L))).commit()
        assertThrows(StoreUnreadable::class.java) { store().peekPinned(alice, "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d") }
        prefs.edit().putString("sender_pins:$me", "{not json").commit()
        assertThrows(StoreUnreadable::class.java) { store().peekPinned(alice, me) }
        assertEquals("{not json", prefs.getString("sender_pins:$me", null))
    }
}
