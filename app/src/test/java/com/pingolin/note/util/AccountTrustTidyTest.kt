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
import org.junit.Assert.assertEquals
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
 * The kept-trust tidy against the REAL stores over plain preferences: another account's kept trust
 * (pins, floors, item owners, retained wraps) goes only when this sign-in's typed e-mail and that
 * account's saved one both exist and differ; an erasure removes every blob of that id, in every
 * store, under every spelling, with its e-mail record, and nobody else's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AccountTrustTidyTest {

    private val me = "00000000-0000-4000-8000-00000000000e"
    private val other = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val third = "2a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val item = "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val signer = "3a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private lateinit var pinPrefs: SharedPreferences
    private lateinit var floorPrefs: SharedPreferences
    private lateinit var retiredPrefs: SharedPreferences
    private lateinit var emailPrefs: SharedPreferences
    private lateinit var webPrefs: SharedPreferences
    private val session = mockk<SessionManager>()
    private var signedIn: String? = null

    private lateinit var pins: SenderKeyPinStore
    private lateinit var floors: NoteGenFloorStore
    private lateinit var retained: RetainedContentKeyStore
    private lateinit var emails: AccountEmailStore
    private lateinit var web: KeptWebTrustStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun fresh(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        pinPrefs = fresh("tidy_pins")
        floorPrefs = fresh("tidy_floors")
        retiredPrefs = fresh("tidy_retired")
        emailPrefs = fresh("tidy_emails")
        webPrefs = fresh("tidy_web")
        every { session.getUserId() } answers { signedIn }
        pins = SenderKeyPinStore({ pinPrefs }, session)
        floors = NoteGenFloorStore({ floorPrefs }, session)
        retained = RetainedContentKeyStore({ retiredPrefs }, session)
        emails = AccountEmailStore { emailPrefs }
        web = KeptWebTrustStore { webPrefs }
    }

    private fun tidy(e: AccountEmailStore = emails) = AccountTrustTidy(pins, floors, retained, e, web)

    /** [account] signed in once: a pin, a floor, a binding, a retired wrap and its typed e-mail. */
    private fun keptTrustOf(account: String, email: String?) {
        signedIn = account
        pins.pin(signer, "a".repeat(64), "b".repeat(64))
        floors.accept(item, signer, 3L)
        retained.retain(item, "w-$account", "n-$account")
        if (email != null) emails.save(account, email)
        signedIn = null
    }

    private fun holds(account: String): List<Boolean> = listOf(
        pinPrefs.all.keys.any { it.endsWith(":$account") },
        floorPrefs.all.keys.any { it == "gen_floors:$account" },
        floorPrefs.all.keys.any { it == "item_owners:$account" },
        retiredPrefs.all.keys.any { it.endsWith(":$account") },
    )

    @Test
    fun `another account whose saved e-mail differs is forgotten in every store, and this one's e-mail saved`() {
        keptTrustOf(other, "other@x.y")
        keptTrustOf(me, null)
        assertEquals(listOf(true, true, true, true), holds(other))

        tidy().afterSignIn(me, "  Me@X.y ")

        assertEquals(listOf(false, false, false, false), holds(other))
        assertNull(emails.emailOf(other))
        assertEquals("me@x.y", emails.emailOf(me))
        assertEquals(listOf(true, true, true, true), holds(me))
    }

    @Test
    fun `an account held by one store only is found and forgotten there`() {
        val onlyPins = "4a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val onlyFloors = "5a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val onlyRetained = "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val onlyEmail = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        signedIn = onlyPins; pins.pin(signer, "a".repeat(64), "b".repeat(64))
        signedIn = onlyFloors; floors.accept(item, signer, 2L)
        signedIn = onlyRetained; retained.retain(item, "w", "n")
        signedIn = null
        for (account in listOf(onlyPins, onlyFloors, onlyRetained, onlyEmail)) emails.save(account, "$account@x.y")

        tidy().afterSignIn(me, "me@x.y")

        assertTrue(pinPrefs.all.isEmpty())
        assertTrue(floorPrefs.all.isEmpty())
        assertTrue(retiredPrefs.all.isEmpty())
        assertEquals(setOf(me), emails.accounts())
    }

    @Test
    fun `nothing is forgotten without a typed e-mail, without the other's saved one, or when they're the same address`() {
        keptTrustOf(other, "Other@X.y")
        keptTrustOf(third, null)
        tidy().afterSignIn(me, "   ")
        tidy().afterSignIn(me, "OTHER@x.y ")
        assertEquals(listOf(true, true, true, true), holds(other))
        assertEquals("the other account's e-mail is nothing to compare with", listOf(true, true, true, true), holds(third))
        assertEquals("other@x.y", emails.emailOf(other))

        // A record that holds no address is no address either: nothing to compare with.
        emailPrefs.edit().putString("email:$third", "   ").commit()
        tidy().afterSignIn(me, "me@x.y")
        assertEquals("a blank record keeps its account", listOf(true, true, true, true), holds(third))
    }

    @Test
    fun `an e-mail record that can't be read forgets nothing`() {
        keptTrustOf(other, "other@x.y")
        val broken = AccountEmailStore { throw IllegalStateException("keystore said no") }
        tidy(broken).afterSignIn(me, "me@x.y")
        assertEquals(listOf(true, true, true, true), holds(other))
        assertNotNull(emails.emailOf(other))
    }

    @Test
    fun `an erasure removes that account's blobs under every spelling, and nobody else's`() {
        keptTrustOf(other, "other@x.y")
        keptTrustOf(me, "me@x.y")
        // Another spelling of the same account, as an
        // older version stored it.
        pinPrefs.edit().putString("sender_pins:${me.uppercase()}", pinPrefs.getString("sender_pins:$me", null)).commit()

        tidy().erase(me.uppercase())

        assertEquals(listOf(false, false, false, false), holds(me))
        assertTrue(pinPrefs.all.keys.none { AccountIds.same(it.substringAfter(':'), me) })
        assertNull(emails.emailOf(me))
        assertEquals(listOf(true, true, true, true), holds(other))
        assertEquals("other@x.y", emails.emailOf(other))
    }

    @Test
    fun `a forget removes every spelling and drops the signed-in account's cache`() {
        keptTrustOf(other, "other@x.y")
        floorPrefs.edit().putString("gen_floors:${other.replace("-", "")}", "{}").commit()
        assertEquals(setOf(other), emails.accounts())

        signedIn = other
        assertEquals(3L, floors.floor(item))
        floors.forget(other)
        assertEquals("forgotten, not held in memory", 0L, floors.floor(item))
        assertNull(floors.owner(item))
        assertTrue(floorPrefs.all.keys.none { AccountIds.same(it.substringAfter(':'), other) })
    }

    @Test
    fun `a store that can't be read throws, and forgets nothing`() {
        val broken = SenderKeyPinStore({ throw IllegalStateException("keystore said no") }, session)
        assertThrows(StoreUnreadable::class.java) { broken.forget(other) }
        assertThrows(AccountIds.NotAnAccountId::class.java) { emails.save("not-an-id", "a@b.c") }
    }

    @Test
    fun `an e-mail is saved under the account's one form, in one form`() {
        emails.save(me.uppercase(), " Me@X.Y ")
        emailPrefs.edit().putString("email:{$me}", "old@x.y").commit()
        emails.save(me, "ME@x.y")
        assertEquals(mapOf("email:$me" to "me@x.y"), emailPrefs.all)
        assertEquals("me@x.y", emails.emailOf("{$me}"))
        assertEquals(setOf(me), emails.accounts())
    }

    @Test
    fun `the web page's kept trust goes with the rest, at a differing sign-in and at an erasure, and stays for the same address`() {
        val same = "4a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        for (account in listOf(me, other, third, same)) web.keep(account, "{}", "{}", "{}")
        emails.save(other, "other@x.y")
        emails.save(same, "me@x.y")

        tidy().afterSignIn(me, "me@x.y")
        assertEquals("another address: forgotten", KeptWebTrustStore.Kept.None, web.get(other))
        assertEquals("the same address: kept", KeptWebTrustStore.Kept.Copy("{}", "{}", "{}"), web.get(same))
        assertEquals("no saved address: kept", KeptWebTrustStore.Kept.Copy("{}", "{}", "{}"), web.get(third))
        assertEquals(KeptWebTrustStore.Kept.Copy("{}", "{}", "{}"), web.get(me))

        tidy().erase(me.uppercase())
        assertEquals("erased", KeptWebTrustStore.Kept.None, web.get(me))
        assertEquals(KeptWebTrustStore.Kept.Copy("{}", "{}", "{}"), web.get(third))
    }
}
