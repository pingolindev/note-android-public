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
}
