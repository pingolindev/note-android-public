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

package com.pingolin.note.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.util.SessionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.unifiedpush.android.connector.data.PushMessage

/**
 * The note server's REAL push payload
 * (`{"type":"tickle","kind":<kind>,"refId":<id>}`) through the tickle handler and
 * the UnifiedPush receiver. A "notebook_share" must not fall through to "Ignoring
 * push", which would show nothing for a notebook share. The FCM service has the same
 * test in testGms.
 *
 * `application = android.app.Application::class` keeps NoteApplication and its Hilt
 * graph out; the receiver takes its dependencies through `depsForTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SharePushRoutingTest {

    private lateinit var context: Context
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val noteApi = mockk<NoteApi>(relaxed = true)
    private val identityRecheck = mockk<com.pingolin.note.domain.repository.IdentityRecheck>(relaxed = true)
    private val deps = object : NoteUnifiedPushReceiver.Deps {
        override fun sessionManager() = sessionManager
        override fun identityRecheck() = identityRecheck
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        every { sessionManager.isLoggedIn() } returns true
        every { sessionManager.areShareNotificationsEnabled() } returns true
    }

    private fun dispatched(kind: String) =
        """{"type":"tickle","kind":"$kind","refId":"3f2c8f4e-9a51-4b8e-8d2a-6c1e0b7d5a90"}"""

    private fun posted(): List<Pair<String?, String?>> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.map {
            it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() to
                it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        }

    private val notebookNotice = "New notebook shared with you" to "Tap to accept or decline it"
    private val noteNotice = "New note shared with you" to "Tap to open Shared with me"

    private fun unifiedPush(kind: String) = NoteUnifiedPushReceiver().apply { depsForTest = deps }
        .onMessage(context, PushMessage(dispatched(kind).toByteArray(), true), "default")

    @Test
    fun `tickle handler - a notebook share posts its own notice`() = runTest {
        NoteTickleHandler(context, sessionManager, noteApi, identityRecheck).handle("notebook_share", "tickle")
        assertEquals(listOf(notebookNotice), posted())
    }

    @Test
    fun `tickle handler - a note share still posts the note notice`() = runTest {
        NoteTickleHandler(context, sessionManager, noteApi, identityRecheck).handle("share", "tickle")
        assertEquals(listOf(noteNotice), posted())
    }

    @Test
    fun `tickle handler - the Shared notes switch gates a notebook share too`() = runTest {
        every { sessionManager.areShareNotificationsEnabled() } returns false
        NoteTickleHandler(context, sessionManager, noteApi, identityRecheck).handle("notebook_share", "tickle")
        assertEquals(emptyList<Pair<String?, String?>>(), posted())
    }

    @Test
    fun `unifiedpush - the dispatcher's notebook_share payload posts the notebook notice`() {
        unifiedPush("notebook_share")
        assertEquals(listOf(notebookNotice), posted())
    }

    @Test
    fun `unifiedpush - the dispatcher's share payload posts the note notice`() {
        unifiedPush("share")
        assertEquals(listOf(noteNotice), posted())
    }

    /** The "identities" kind asks for a re-check and nothing else; extra fields
     * in the push make no difference, and no notice is posted. */
    @Test
    fun `unifiedpush - an identities push asks for one re-check, whatever else it carries`() {
        val withExtras = """{"type":"tickle","kind":"identities","refId":"00000000-0000-0000-0000-000000000000",""" +
            """"identities":[{"nonce":"n","ciphertext":"c"}],"data":{"kind":"share"},"title":"x"}"""
        NoteUnifiedPushReceiver().apply { depsForTest = deps }
            .onMessage(context, PushMessage(withExtras.toByteArray(), true), "default")
        unifiedPush("identities")
        io.mockk.verify(exactly = 2) { identityRecheck.onNudge() }
        assertEquals(emptyList<Pair<String?, String?>>(), posted())
    }

    @Test
    fun `tickle handler - an identities tickle asks for one re-check`() = runTest {
        NoteTickleHandler(context, sessionManager, noteApi, identityRecheck).handle("identities", "tickle")
        io.mockk.verify(exactly = 1) { identityRecheck.onNudge() }
        assertEquals(emptyList<Pair<String?, String?>>(), posted())
    }

    @Test
    fun `unifiedpush - the Shared notes switch gates a notebook share too`() {
        every { sessionManager.areShareNotificationsEnabled() } returns false
        unifiedPush("notebook_share")
        assertEquals(emptyList<Pair<String?, String?>>(), posted())
    }
}
