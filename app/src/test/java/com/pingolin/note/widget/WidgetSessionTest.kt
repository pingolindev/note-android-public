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

package com.pingolin.note.widget

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pingolin.note.R
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.util.SessionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No widget reads a note while signed out. The list shows one line and the single note its title,
 * the app's name over "Sign in to continue", and the picker offers nothing; the DAO isn't asked at
 * all. Signed in, the widgets read as usual. The session's end (clearAuthOnly, every dead-session
 * path, the 401 included) refreshes them.
 *
 * The real SessionManager over plain preferences (EncryptedSharedPreferences needs the Keystore,
 * which Robolectric lacks), and a strict DAO mock: a read nobody stubbed fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WidgetSessionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: SharedPreferences
    private val dao = mockk<ItemDao>()

    private val appName get() = context.getString(R.string.app_name)
    private val signIn get() = context.getString(R.string.auth_subtitle)

    @Before
    fun setUp() {
        prefs = context.getSharedPreferences("widget_session_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        WidgetPreferences.clearAll(context)
    }

    private fun session(signedIn: Boolean, onSessionEnded: () -> Unit = {}) =
        SessionManager(prefs, onSessionEnded).also { if (signedIn) it.saveTokens("test-access", "test-refresh") }

    private fun note(id: String, title: String) = ItemEntity(
        id = id, userId = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", itemType = 1, encryptedContent = "", contentNonce = "",
        decryptedContent = """{"body":"$title body"}""", title = title, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    /** What a launcher would show: [views] applied, and the text of [id] in it. */
    private fun text(views: RemoteViews, id: Int): String =
        views.apply(context, FrameLayout(context)).findViewById<TextView>(id).text.toString()

    private fun visibility(views: RemoteViews, id: Int): Int =
        views.apply(context, FrameLayout(context)).findViewById<View>(id).visibility

    @Test
    fun `the list reads no note while signed out and shows one line, the app over sign in`() {
        val factory = NoteListRemoteViewsFactory(context, dao) { session(signedIn = false) }

        factory.onDataSetChanged()

        assertEquals(1, factory.count)
        val row = factory.getViewAt(0)
        assertEquals(appName, text(row, R.id.widget_item_title))
        assertEquals(signIn, text(row, R.id.widget_item_body))
        assertEquals(View.GONE, visibility(row, R.id.widget_item_pin))
        verify(exactly = 0) { dao.getRecentNotesSync(any()) }
    }

    @Test
    fun `the list signed in shows the recent notes`() {
        every { dao.getRecentNotesSync(10) } returns listOf(note("n1", "Groceries"), note("n2", "Ideas"))
        val factory = NoteListRemoteViewsFactory(context, dao) { session(signedIn = true) }

        factory.onDataSetChanged()

        assertEquals(2, factory.count)
        assertEquals("Groceries", text(factory.getViewAt(0), R.id.widget_item_title))
        assertEquals("Ideas", text(factory.getViewAt(1), R.id.widget_item_title))
        verify(exactly = 1) { dao.getRecentNotesSync(10) }
    }

    @Test
    fun `the list follows the session at its next refresh`() {
        every { dao.getRecentNotesSync(10) } returns listOf(note("n1", "Groceries"))
        var signedIn = true
        val factory = NoteListRemoteViewsFactory(context, dao) { session(signedIn) }
        factory.onDataSetChanged()
        assertEquals("Groceries", text(factory.getViewAt(0), R.id.widget_item_title))

        signedIn = false
        prefs.edit().clear().commit()
        factory.onDataSetChanged()

        assertEquals(1, factory.count)
        assertEquals(appName, text(factory.getViewAt(0), R.id.widget_item_title))
        verify(exactly = 1) { dao.getRecentNotesSync(10) }
    }

    @Test
    fun `the single note reads nothing while signed out and shows the app over sign in`() {
        WidgetPreferences.saveNoteIdForWidget(context, 7, "n1")

        val views = SingleNoteWidgetProvider.buildViews(context, 7, session(signedIn = false)) { dao }

        assertEquals(appName, text(views, R.id.widget_single_title))
        assertEquals(signIn, text(views, R.id.widget_single_body))
        verify(exactly = 0) { dao.getByIdSync(any()) }
    }

    @Test
    fun `the single note signed in shows its note`() {
        WidgetPreferences.saveNoteIdForWidget(context, 7, "n1")
        every { dao.getByIdSync("n1") } returns note("n1", "Groceries")

        val views = SingleNoteWidgetProvider.buildViews(context, 7, session(signedIn = true)) { dao }

        assertEquals("Groceries", text(views, R.id.widget_single_title))
        assertEquals("Groceries body", text(views, R.id.widget_single_body))
    }

    @Test
    fun `the picker offers nothing and reads nothing while signed out`() {
        assertNull(SingleNoteConfigActivity.notesToPick(session(signedIn = false), dao))
        verify(exactly = 0) { dao.getRecentNotesSync(any()) }

        every { dao.getRecentNotesSync(50) } returns listOf(note("n1", "Groceries"))
        assertEquals(listOf("n1"), SingleNoteConfigActivity.notesToPick(session(signedIn = true), dao)?.map { it.id })
    }

    @Test
    fun `the session's end refreshes the widgets`() {
        var refreshed = 0
        val session = session(signedIn = true) { refreshed++ }

        session.clearAuthOnly()

        assertEquals(1, refreshed)
        assertFalse(session.isLoggedIn())
    }

    private fun source(path: String) = java.io.File("src/main/java/com/pingolin/note/$path").readText()

    /** The app's SessionManager refreshes the widgets, and the picker asks before it reads. */
    @Test
    fun `the wiring the unit tests can't start`() {
        val session = source("util/SessionManager.kt")
        assertTrue(
            "the app's constructor wires the refresh",
            session.contains("""constructor(context: Context) : this(SecurePrefs.open(context, "note_session"), widgetsRefresh(context.applicationContext))"""),
        )
        assertTrue(session.contains("runCatching { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(appContext) }"))
        val picker = source("widget/SingleNoteConfigActivity.kt")
        assertTrue("signed out, it stops before App Lock and before any read", picker.contains(
            "        if (!session.isLoggedIn()) {\n" +
                "            toolbar.title = getString(R.string.app_name)\n" +
                "            toolbar.subtitle = getString(R.string.auth_subtitle)\n" +
                "            return\n" +
                "        }\n"
        ))
        assertTrue(picker.contains("val notes = notesToPick(SessionManager(this@SingleNoteConfigActivity), itemDao) ?: return@launch"))
        assertFalse("no read but through notesToPick", picker.contains("val notes = itemDao.getRecentNotesSync"))
    }
}
