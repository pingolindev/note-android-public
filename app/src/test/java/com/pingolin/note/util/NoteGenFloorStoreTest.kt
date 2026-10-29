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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real floor store over plain preferences: floors keyed by the canonical item id, the max
 * fold, and a store that can't be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NoteGenFloorStoreTest {

    private val me = "00000000-0000-4000-8000-00000000000e"
    private val item = "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val itemHex = item.replace("-", "")
    private val itemSpellings = listOf(item.uppercase(), itemHex, itemHex.uppercase(), "{$item}", "urn:uuid:$item")

    private lateinit var prefs: SharedPreferences
    private val session = mockk<SessionManager>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("gen_floors_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        every { session.getUserId() } returns me
    }

    private fun store(open: () -> SharedPreferences = { prefs }) = NoteGenFloorStore(open, session)

    @Test
    fun `a floor bumped under one spelling holds for every spelling of the item`() {
        val s = store()
        s.bump(item, 5L)
        for (spelling in itemSpellings) assertEquals(spelling, 5L, s.floor(spelling))
        s.bump(itemHex.uppercase(), 7L)
        assertEquals(7L, s.floor(item))
        assertEquals(setOf(item), JSONObject(prefs.getString("gen_floors:$me", null)!!).keys().asSequence().toSet())
    }

    @Test
    fun `an item id that is no UUID is refused, never floor 0`() {
        val s = store()
        for (bad in listOf("", "item-1", " $item", "{$itemHex}", "URN:UUID:$item")) {
            assertThrows(bad, AccountIds.NotAnAccountId::class.java) { s.floor(bad) }
            assertThrows(bad, AccountIds.NotAnAccountId::class.java) { s.bump(bad, 3L) }
        }
    }

    @Test
    fun `spellings of one item fold to the highest generation, and non-UUID keys are dropped`() {
        prefs.edit().putString(
            "gen_floors:$me",
            JSONObject().put(item, 3L).put(itemHex.uppercase(), 9L).put("{$item}", 4L).put("junk", 50L).toString(),
        ).commit()
        assertEquals(9L, store().floor(item))
        val stored = JSONObject(prefs.getString("gen_floors:$me", null)!!)
        assertEquals(setOf(item), stored.keys().asSequence().toSet())
        assertEquals(9L, stored.getLong(item))
    }

    @Test
    fun `a map that does not parse is unreadable, never floor 0, and is not written over`() {
        prefs.edit().putString("gen_floors:$me", "[1,2").commit()
        val s = store()
        assertThrows(StoreUnreadable::class.java) { s.floor(item) }
        assertThrows(StoreUnreadable::class.java) { s.bump(item, 2L) }
        assertThrows(StoreUnreadable::class.java) { s.ensureReadable() }
        assertEquals("[1,2", prefs.getString("gen_floors:$me", null))
    }

    @Test
    fun `floors kept under another spelling of this account's id are merged in by the highest generation`() {
        val other = "3c1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        prefs.edit()
            .putString("gen_floors:${me.replace("-", "")}", JSONObject().put(item, 9L).toString())
            .putString("gen_floors:urn:uuid:$me", JSONObject().put(itemHex, 4L).toString())
            .putString("gen_floors:$me", JSONObject().put(item, 5L).toString())
            .putString("gen_floors:$other", JSONObject().put(item, 1L).toString())
            .commit()
        assertEquals(9L, store().floor(item))
        assertEquals(9L, JSONObject(prefs.getString("gen_floors:$me", null)!!).getLong(item))
        assertEquals(null, prefs.getString("gen_floors:${me.replace("-", "")}", null))
        assertEquals(null, prefs.getString("gen_floors:urn:uuid:$me", null))
        assertEquals("another account's is untouched", 1L, JSONObject(prefs.getString("gen_floors:$other", null)!!).getLong(item))
    }

    @Test
    fun `another spelling stays when the merged map can't be written`() {
        val spelling = "gen_floors:${me.replace("-", "")}"
        prefs.edit().putString(spelling, JSONObject().put(item, 6L).toString()).commit()
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
        assertEquals(6L, store { writesFail }.floor(item))
        assertEquals("nothing it came from is removed", 6L, JSONObject(prefs.getString(spelling, null)!!).getLong(item))
    }

    @Test
    fun `a read that throws is unreadable, and read again at the next use`() {
        prefs.edit().putString("gen_floors:$me", JSONObject().put(item, 4L).toString()).commit()
        var refuse = true
        val flaky = object : SharedPreferences by prefs {
            override fun getString(key: String?, defValue: String?): String? =
                if (refuse) throw SecurityException("decrypt") else prefs.getString(key, defValue)
            override fun getAll(): MutableMap<String, *> =
                if (refuse) throw SecurityException("decrypt") else prefs.all
        }
        val s = store { flaky }
        assertThrows(StoreUnreadable::class.java) { s.floor(item) }
        refuse = false
        assertEquals(4L, s.floor(item))
    }

    // --- each item's owner, beside its floor ------------------------------------------------

    private val alice = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val bob = "2a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    /** Plain preferences that count the commits their editors make. */
    private class CountingPrefs(val base: SharedPreferences) : SharedPreferences by base {
        var commits = 0
        override fun edit(): SharedPreferences.Editor {
            val e = base.edit()
            val outer = this
            return object : SharedPreferences.Editor by e {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { e.putString(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { e.remove(key); return this }
                override fun commit(): Boolean { outer.commits++; return e.commit() }
            }
        }
    }

    @Test
    fun `an accepted share binds its item under every spelling, and a binding is never replaced`() {
        val s = store()
        assertNull(s.owner(item))
        s.accept(itemHex.uppercase(), alice.uppercase())
        for (spelling in itemSpellings) assertEquals(spelling, alice, s.owner(spelling))
        s.accept(item, bob, 4L)
        assertEquals(alice, s.owner(item))
        // Read again from disk: the canonical binding, and the floor the second share raised.
        val again = store()
        assertEquals(alice, again.owner(item))
        assertEquals(4L, again.floor(item))
        assertEquals(alice, JSONObject(prefs.getString("item_owners:$me", null)!!).getString(item))
    }

    @Test
    fun `a verified share's floor and binding are saved in one commit`() {
        val counting = CountingPrefs(prefs)
        val s = store { counting }
        s.accept(item, alice, 3L)
        assertEquals(1, counting.commits)
        assertEquals(3L, JSONObject(prefs.getString("gen_floors:$me", null)!!).getLong(item))
        assertEquals(alice, JSONObject(prefs.getString("item_owners:$me", null)!!).getString(item))
        // Nothing new (bound, not higher): no write at all.
        s.accept(item, alice, 2L)
        assertEquals(1, counting.commits)
    }

    @Test
    fun `stored owners read canonically, entries that are no UUID are dropped, and two owners of one item are a conflict`() {
        prefs.edit().putString(
            "item_owners:$me",
            JSONObject().put(itemHex.uppercase(), alice.uppercase()).put("junk", alice).put(item.replace('1', 'e'), "not-an-owner").toString(),
        ).commit()
        assertEquals(alice, store().owner(item))
        prefs.edit().putString("item_owners:$me", JSONObject().put(item, alice).put(itemHex, bob).toString()).commit()
        assertEquals(NoteGenFloorStore.OWNER_CONFLICT, store().owner(item))
    }

    @Test
    fun `an owners blob that does not parse is unreadable, never unbound, and is not written over`() {
        prefs.edit().putString("item_owners:$me", "{not json").commit()
        val s = store()
        assertThrows(StoreUnreadable::class.java) { s.owner(item) }
        assertThrows(StoreUnreadable::class.java) { s.ensureReadable() }
        assertThrows(StoreUnreadable::class.java) { s.accept(item, alice, 2L) }
        assertEquals("{not json", prefs.getString("item_owners:$me", null))
    }

    @Test
    fun `with no account every item is nobody's and nothing is bound`() {
        every { session.getUserId() } returns null
        val s = store()
        assertEquals(NoteGenFloorStore.OWNER_CONFLICT, s.owner(item))
        s.accept(item, alice, 3L)
        assertNull(prefs.getString("item_owners:null", null))
        assertEquals(0, prefs.all.size)
    }

    @Test
    fun `an item id or owner that is no UUID is refused`() {
        val s = store()
        assertThrows(AccountIds.NotAnAccountId::class.java) { s.owner("item-1") }
        assertThrows(AccountIds.NotAnAccountId::class.java) { s.accept(item, "{$alice}x") }
        assertThrows(AccountIds.NotAnAccountId::class.java) { s.accept("item-1", alice) }
        assertThrows(AccountIds.NotAnAccountId::class.java) { s.acceptUnsaved(item, "{$alice}x") }
        assertThrows(AccountIds.NotAnAccountId::class.java) { s.acceptUnsaved("item-1", alice) }
    }

    // --- One save per refresh
    // ------------------------------------------------------------------
    //
    // accept() writes the whole of both maps at each call, so an inbox refresh that meets N
    // items for the first time would write them N times, each larger than the last: for 2,000
    // shares about 1,500 writes and 223 MB, for 10,000 about 5.9 GB. The refresh records with
    // acceptUnsaved() and saves once, before it publishes any key.

    private val item2 = "2a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val item3 = "3a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val other = "0f0f0f0f-0000-4000-8000-00000000000f"

    /** Plain preferences that count commits, and can be made to refuse them. */
    private class FlakyPrefs(val base: SharedPreferences) : SharedPreferences by base {
        var commits = 0
        var refuse = false
        override fun edit(): SharedPreferences.Editor {
            val e = base.edit()
            val outer = this
            return object : SharedPreferences.Editor by e {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { e.putString(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { e.remove(key); return this }
                override fun commit(): Boolean {
                    outer.commits++
                    return if (outer.refuse) false else e.commit()
                }
            }
        }
    }

    private fun storedFloors(account: String = me) = prefs.getString("gen_floors:$account", null)?.let { JSONObject(it) }
    private fun storedOwners(account: String = me) = prefs.getString("item_owners:$account", null)?.let { JSONObject(it) }

    @Test
    fun `shares recorded unsaved are in force at once, and reach the file with ONE save`() {
        val counting = FlakyPrefs(prefs)
        val s = store { counting }
        s.acceptUnsaved(item, alice, 3L)
        s.acceptUnsaved(item2, bob)
        s.acceptUnsaved(item3, alice, 7L)

        // In force for the very next share of the same refresh: bound, and floored.
        assertEquals(alice, s.owner(item))
        assertEquals(3L, s.floor(item))
        assertEquals(bob, s.owner(item2))
        assertEquals(0L, s.floor(item2))
        assertEquals(7L, s.floor(item3))
        // A binding is never replaced, unsaved or not.
        s.acceptUnsaved(item, bob, 9L)
        assertEquals(alice, s.owner(item))
        assertEquals(9L, s.floor(item))
        // Nothing written yet.
        assertEquals(0, counting.commits)
        assertNull(storedFloors())
        assertNull(storedOwners())

        assertEquals(true, s.saveAccepted())
        assertEquals(1, counting.commits)
        assertEquals(9L, storedFloors()!!.getLong(item))
        assertEquals(7L, storedFloors()!!.getLong(item3))
        assertEquals("a share that verified nothing sets no floor", false, storedFloors()!!.has(item2))
        assertEquals(alice, storedOwners()!!.getString(item))
        assertEquals(bob, storedOwners()!!.getString(item2))
        assertEquals(alice, storedOwners()!!.getString(item3))
        // And another process reads the same.
        val again = store()
        assertEquals(alice, again.owner(item))
        assertEquals(9L, again.floor(item))
        assertEquals(bob, again.owner(item2))
    }

    @Test
    fun `five hundred shares met for the first time are one write, not five hundred`() {
        val counting = FlakyPrefs(prefs)
        val s = store { counting }
        val items = (0 until 500).map { "%08x-0000-4000-8000-000000000000".format(it + 1) }
        for (id in items) s.acceptUnsaved(id, alice, 2L)
        assertEquals(0, counting.commits)
        assertEquals(true, s.saveAccepted())
        assertEquals(1, counting.commits)
        assertEquals(500, storedFloors()!!.length())
        assertEquals(500, storedOwners()!!.length())
    }

    @Test
    fun `a save with nothing to save writes nothing`() {
        val counting = FlakyPrefs(prefs)
        val s = store { counting }
        assertEquals(true, s.saveAccepted())
        assertEquals(0, counting.commits)
        s.acceptUnsaved(item, alice, 3L)
        assertEquals(true, s.saveAccepted())
        assertEquals(1, counting.commits)
        // Saved: a second save has nothing left.
        assertEquals(true, s.saveAccepted())
        assertEquals(1, counting.commits)
        // A share that changes nothing (bound already, not higher) leaves nothing to save either.
        s.acceptUnsaved(item, alice, 3L)
        s.acceptUnsaved(item, bob, 2L)
        assertEquals(true, s.saveAccepted())
        assertEquals(1, counting.commits)
    }

    @Test
    fun `a save that fails says so and keeps everything, and the next save writes it with nothing new accepted`() {
        val flaky = FlakyPrefs(prefs)
        val s = store { flaky }
        s.acceptUnsaved(item, alice, 3L)
        flaky.refuse = true
        assertEquals(false, s.saveAccepted())
        assertNull(storedFloors())
        // Held: the binding and the floor still decide.
        assertEquals(alice, s.owner(item))
        assertEquals(3L, s.floor(item))

        flaky.refuse = false
        assertEquals(true, s.saveAccepted())
        assertEquals(3L, storedFloors()!!.getLong(item))
        assertEquals(alice, storedOwners()!!.getString(item))
    }

    /**
     * The inbox refresh withholds what it opened for exactly the items whose record is not in the
     * file, so the store has to say which those are.
     */
    @Test
    fun `the items whose record is not in the file are named, until a save goes through`() {
        val flaky = FlakyPrefs(prefs)
        val s = store { flaky }
        assertEquals("nothing recorded, nothing unsaved", emptySet<String>(), s.unsaved())

        s.acceptUnsaved(item, alice, 3L)
        s.acceptUnsaved(item2.uppercase(), bob)
        assertEquals("recorded and not yet saved, in canonical ids", setOf(item, item2), s.unsaved())

        flaky.refuse = true
        assertEquals(false, s.saveAccepted())
        assertEquals("a save that failed changes nothing", setOf(item, item2), s.unsaved())
        // Another one recorded meanwhile joins them.
        s.acceptUnsaved(item3, alice, 7L)
        assertEquals(setOf(item, item2, item3), s.unsaved())

        flaky.refuse = false
        assertEquals(true, s.saveAccepted())
        assertEquals("all of it is in the file now", emptySet<String>(), s.unsaved())

        // What is recorded after that is unsaved by itself: nothing saved before comes back.
        flaky.refuse = true
        s.acceptUnsaved(item, alice, 9L)
        assertEquals(false, s.saveAccepted())
        assertEquals(setOf(item), s.unsaved())
    }

    @Test
    fun `a share recorded again with nothing new is not unsaved`() {
        val s = store()
        s.acceptUnsaved(item, alice, 3L)
        assertEquals(true, s.saveAccepted())
        // The same owner, the same generation, and a lower one: nothing changes, nothing to save.
        s.acceptUnsaved(item, alice, 3L)
        s.acceptUnsaved(item, bob, 2L)
        assertEquals(emptySet<String>(), s.unsaved())
        // A higher generation does.
        s.acceptUnsaved(item, alice, 4L)
        assertEquals(setOf(item), s.unsaved())
    }

    @Test
    fun `a raised floor that could not be saved is unsaved too`() {
        val flaky = FlakyPrefs(prefs)
        val s = store { flaky }
        flaky.refuse = true
        s.bump(item, 5L)
        assertEquals(setOf(item), s.unsaved())
        flaky.refuse = false
        s.bump(item2, 2L)
        assertEquals("the next save wrote both", emptySet<String>(), s.unsaved())
        assertEquals(5L, storedFloors()!!.getLong(item))
    }

    @Test
    fun `nothing stays unsaved over a wipe or another account's load`() {
        val flaky = FlakyPrefs(prefs)
        val s = store { flaky }
        flaky.refuse = true
        s.acceptUnsaved(item, alice, 3L)
        assertEquals(false, s.saveAccepted())
        s.wipe()
        assertEquals("the maps went with the wipe, and what was unsaved with them", emptySet<String>(), s.unsaved())

        s.acceptUnsaved(item2, alice, 3L)
        assertEquals(setOf(item2), s.unsaved())
        every { session.getUserId() } returns other
        assertEquals("the next account has nothing unsaved", emptySet<String>(), s.unsaved())
        s.acceptUnsaved(item3, alice, 1L)
        assertEquals(setOf(item3), s.unsaved())
    }

    @Test
    fun `asking what is unsaved for an account that is no longer signed in is refused`() {
        val s = store()
        s.acceptUnsaved(item, alice, 3L)
        assertEquals(setOf(item), s.unsaved(me))
        assertThrows(StoreUnreadable::class.java) { s.unsaved(other) }
    }

    @Test
    fun `accept after a failed save writes what the failed one held too`() {
        val flaky = FlakyPrefs(prefs)
        val s = store { flaky }
        flaky.refuse = true
        s.accept(item, alice, 3L)
        assertNull(storedFloors())
        flaky.refuse = false
        // Nothing new for this item, and the earlier failed save is still made up for: returning without
        // saving would leave it unsaved for good.
        s.accept(item, alice, 3L)
        assertEquals(3L, storedFloors()!!.getLong(item))
        assertEquals(alice, storedOwners()!!.getString(item))
    }

    @Test
    fun `what one account holds unsaved is never written under another account's name`() {
        val counting = FlakyPrefs(prefs)
        val s = store { counting }
        s.acceptUnsaved(item, alice, 3L)
        // Another account signs in, and something reads the store for it.
        every { session.getUserId() } returns other
        assertNull(s.owner(item))
        assertEquals(true, s.saveAccepted())
        assertNull("nothing of the first account under the second's name", storedFloors(other))
        assertNull(storedOwners(other))
        assertEquals(0, counting.commits)
        // The second account's own shares save under its own name.
        s.acceptUnsaved(item2, bob, 5L)
        assertEquals(true, s.saveAccepted())
        assertEquals(5L, storedFloors(other)!!.getLong(item2))
        assertEquals(false, storedFloors(other)!!.has(item))
        assertNull(storedFloors(me))
    }

    @Test
    fun `saved after the session moved on but before anything read the store, they go under the account they were accepted for`() {
        val s = store()
        s.acceptUnsaved(item, alice, 3L)
        every { session.getUserId() } returns other
        assertEquals(true, s.saveAccepted())
        assertEquals(3L, storedFloors(me)!!.getLong(item))
        assertEquals(alice, storedOwners(me)!!.getString(item))
        assertNull(storedFloors(other))
        assertNull(storedOwners(other))
    }

    @Test
    fun `a wipe drops what was not saved, and the next save writes nothing`() {
        val counting = FlakyPrefs(prefs)
        val s = store { counting }
        s.acceptUnsaved(item, alice, 3L)
        s.wipe()
        assertEquals(true, s.saveAccepted())
        assertEquals(0, counting.commits)
        assertNull(storedFloors())
        assertNull(s.owner(item))
    }

    @Test
    fun `what was dropped stays dropped - a wipe and then a read leave nothing to save, and nothing is written`() {
        val counting = FlakyPrefs(prefs)
        val s = store { counting }
        s.accept(item2, bob, 2L)
        assertEquals(1, counting.commits)
        s.acceptUnsaved(item, alice, 3L)
        s.wipe()
        // The same account reads again: its maps are the file's, without the unsaved share.
        assertNull(s.owner(item))
        assertEquals(bob, s.owner(item2))
        assertEquals(true, s.saveAccepted())
        assertEquals("no write of maps that are the file's own", 1, counting.commits)
        assertEquals(false, storedOwners()!!.has(item))
    }

    @Test
    fun `away to another account and back - the first account's unsaved share is gone, and nothing is written for either`() {
        val counting = FlakyPrefs(prefs)
        val s = store { counting }
        s.acceptUnsaved(item, alice, 3L)
        every { session.getUserId() } returns other
        assertNull(s.owner(item))
        every { session.getUserId() } returns me
        assertNull("not brought back by coming back", s.owner(item))
        assertEquals(true, s.saveAccepted())
        assertEquals(0, counting.commits)
        assertNull(storedFloors(me))
        assertNull(storedFloors(other))
    }

    @Test
    fun `forgetting the account drops what was not saved`() {
        val s = store()
        s.accept(item2, bob, 2L)
        s.acceptUnsaved(item, alice, 3L)
        s.forget(me)
        assertEquals(true, s.saveAccepted())
        assertNull("an erased account's records are not written back", storedFloors())
        assertNull(storedOwners())
    }

    @Test
    fun `a raised floor is saved together with the owners held unsaved, in one commit`() {
        val counting = FlakyPrefs(prefs)
        val s = store { counting }
        s.acceptUnsaved(item, alice, 3L)
        s.bump(item2, 5L)
        assertEquals(1, counting.commits)
        // The floors map on file never runs ahead of the owners map.
        assertEquals(3L, storedFloors()!!.getLong(item))
        assertEquals(5L, storedFloors()!!.getLong(item2))
        assertEquals(alice, storedOwners()!!.getString(item))
        assertEquals(true, s.saveAccepted())
        assertEquals(1, counting.commits)
    }

    // --- A caller that names the account it works for
    // -----------------------------------------
    //
    // An inbox refresh runs for a while, and not under a lock a sign-out waits for. It names
    // the account it STARTED for at every call; once another account (or none) is signed in,
    // each call is refused. Resolving "the account" afresh at every call instead would bind
    // and floor the rest of one account's shares in the next account's record.

    @Test
    fun `a call for an account that is no longer the one signed in is refused, and reads and writes nothing`() {
        prefs.edit()
            .putString("gen_floors:$other", JSONObject().put(item, 9L).toString())
            .putString("item_owners:$other", JSONObject().put(item, bob).toString())
            .commit()
        val counting = FlakyPrefs(prefs)
        val s = store { counting }
        s.ensureReadable(me)
        // The refresh was started for `me`; `other` signs in while it runs.
        every { session.getUserId() } returns other
        assertThrows(StoreUnreadable::class.java) { s.ensureReadable(me) }
        assertThrows(StoreUnreadable::class.java) { s.floor(item, me) }
        assertThrows(StoreUnreadable::class.java) { s.owner(item, me) }
        assertThrows(StoreUnreadable::class.java) { s.acceptUnsaved(item2, alice, 4L, me) }
        assertThrows(StoreUnreadable::class.java) { s.saveAccepted(me) }
        assertEquals("nothing was written", 0, counting.commits)
        // Nothing of the first account's refresh is in the second's record, in memory or stored.
        assertNull(s.owner(item2))
        assertEquals(0L, s.floor(item2))
        assertEquals("the second account's own record is as it was", 9L, s.floor(item))
        assertEquals(bob, s.owner(item))
        assertEquals(false, storedOwners(other)!!.has(item2))
        assertEquals(false, storedFloors(other)!!.has(item2))
        assertNull(storedOwners(me))
    }

    @Test
    fun `the account is named in any spelling, and nobody signed in is not that account`() {
        val s = store()
        s.ensureReadable("{$me}")
        s.acceptUnsaved(item, alice, 3L, me.uppercase())
        assertEquals(3L, s.floor(item, me.replace("-", "")))
        assertEquals(alice, s.owner(item, "urn:uuid:$me"))
        assertEquals(true, s.saveAccepted(me))
        assertEquals(3L, storedFloors()!!.getLong(item))
        every { session.getUserId() } returns null
        assertThrows(StoreUnreadable::class.java) { s.floor(item, me) }
        assertThrows(StoreUnreadable::class.java) { s.acceptUnsaved(item2, alice, 4L, me) }
        assertThrows(StoreUnreadable::class.java) { s.saveAccepted(me) }
    }

    @Test
    fun `what a refresh accepted is not saved by it once another account is signed in`() {
        // A save that names no account still writes this under the account it was accepted for
        // (the test above this section). A refresh names its account, and stops instead.
        val counting = FlakyPrefs(prefs)
        val s = store { counting }
        s.acceptUnsaved(item, alice, 3L, me)
        every { session.getUserId() } returns other
        assertThrows(StoreUnreadable::class.java) { s.saveAccepted(me) }
        assertEquals(0, counting.commits)
        assertNull(storedFloors(me))
        assertNull(storedFloors(other))
    }

    @Test
    fun `a caller that names no account is served for whoever is signed in`() {
        val s = store()
        s.accept(item, alice, 3L)
        every { session.getUserId() } returns other
        assertEquals(0L, s.floor(item))
        assertNull(s.owner(item))
        s.ensureReadable()
    }
}
