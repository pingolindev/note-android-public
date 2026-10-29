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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real SessionManager over plain preferences (EncryptedSharedPreferences needs the Keystore,
 * which Robolectric lacks): this account's id is kept and read in its canonical form, and a saved
 * one that isn't a UUID is no account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SessionManagerIdTest {

    private val me = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private lateinit var prefs: SharedPreferences
    private lateinit var session: SessionManager

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("session_id_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        session = SessionManager(prefs)
    }

    @Test
    fun `an account switch's wipe takes the account's notification gates, which the next account fills from the server`() {
        session.setReminderNotificationsEnabled(false)
        session.setShareNotificationsEnabled(false)
        session.setEmailNotificationsEnabled(true)
        session.setReminderEmailEnabled(true)
        session.setShareEmailEnabled(true)
        session.clearAccountScopedData()
        assertTrue(prefs.all.keys.none { it.startsWith("notif_") })
    }

    /**
     * What the last inbox read TO ITS END found on offer. "Never stored" is not "nothing on
     * offer": the first makes a read that stopped early hold every note of a notebook that was
     * not accepted, the second holds none of them.
     */
    @Test
    fun `the notebooks last found on offer - never stored is not the same as none`() {
        assertNull(session.getOfferedNotebooks())
        session.setOfferedNotebooks(emptySet())
        assertEquals(emptySet<String>(), session.getOfferedNotebooks())
        session.setOfferedNotebooks(setOf(me, "1b1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"))
        assertEquals(setOf(me, "1b1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"), session.getOfferedNotebooks())
    }

    @Test
    fun `an account switch's wipe takes what the outgoing account was offered and had accepted`() {
        session.setOfferedNotebooks(setOf(me))
        session.setSeenAcceptedNotebooks(setOf(me))
        session.setInboxShareIds(setOf(me))
        session.setHeldForAnInvitation(setOf(me))
        session.clearAccountScopedData()
        assertNull("the next account has had no read to the end yet", session.getOfferedNotebooks())
        assertEquals(emptySet<String>(), session.getSeenAcceptedNotebooks())
        assertEquals(emptySet<String>(), session.getInboxShareIds())
        assertEquals("nor does it hold what the outgoing account held", emptySet<String>(), session.getHeldForAnInvitation())
    }

    /** The notes held for an invitation are kept, so that the hold outlives the process. */
    @Test
    fun `the notes held for an invitation are kept, and none is the same as never stored`() {
        assertEquals(emptySet<String>(), session.getHeldForAnInvitation())
        val held = setOf(me, "1b1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")
        session.setHeldForAnInvitation(held)
        assertEquals(held, session.getHeldForAnInvitation())
        session.setHeldForAnInvitation(emptySet())
        assertEquals(emptySet<String>(), session.getHeldForAnInvitation())
        // And it is not what is on offer: the two are stored apart.
        session.setOfferedNotebooks(setOf(me))
        assertEquals(emptySet<String>(), session.getHeldForAnInvitation())
    }

    @Test
    fun `the id is saved in its canonical form`() {
        session.saveUserId(me.replace("-", "").uppercase())
        assertEquals(me, prefs.getString("user_id", null))
        assertEquals(me, session.getUserId())
    }

    @Test
    fun `an id that isn't a UUID is never saved`() {
        for (bad in listOf("u1", "", " $me", "URN:UUID:$me")) {
            assertThrows(bad, AccountIds.NotAnAccountId::class.java) { session.saveUserId(bad) }
        }
        assertNull(prefs.getString("user_id", null))
    }

    @Test
    fun `a saved id in another spelling is written back canonical at its first read`() {
        for (spelling in listOf(me.uppercase(), me.replace("-", ""), "{$me}", "urn:uuid:$me")) {
            prefs.edit().putString("user_id", spelling).commit()
            assertEquals(spelling, me, session.getUserId())
            assertEquals(spelling, me, prefs.getString("user_id", null))
            assertFalse(spelling, session.savedAccountIdInvalid())
        }
    }

    @Test
    fun `a saved id that isn't a UUID is no account, says so, and stays for the next sign-in`() {
        for (bad in listOf("u1", " $me", "")) {
            prefs.edit().putString("user_id", bad).commit()
            assertNull(bad, session.getUserId())
            assertTrue(bad, session.savedAccountIdInvalid())
            assertEquals(bad, bad, prefs.getString("user_id", null))
        }
    }

    @Test
    fun `no saved id is none, not an invalid one`() {
        assertNull(session.getUserId())
        assertFalse(session.savedAccountIdInvalid())
    }

    /** As of when the offers are known is the account's, like the offers themselves. */
    @Test
    fun `as of when the offers are known is kept, and not carried to the next account`() {
        assertNull(session.getOffersKnownAsOf())
        session.setOffersKnownAsOf("2026-10-01T12:00:00.250000Z")
        assertEquals("2026-10-01T12:00:00.250000Z", session.getOffersKnownAsOf())
        session.clearAccountScopedData()
        assertNull(session.getOffersKnownAsOf())
    }
}
