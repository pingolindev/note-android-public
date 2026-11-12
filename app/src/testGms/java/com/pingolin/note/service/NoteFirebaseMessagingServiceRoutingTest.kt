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
import com.google.firebase.messaging.RemoteMessage
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.util.SessionManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The note server's REAL push payload (`{"type": "tickle", "kind": …, "refId":
 * …}`) through FCM's onMessageReceived. A "notebook_share" must not fall through
 * to "Ignoring push". The tickle handler and the UnifiedPush receiver are
 * covered by SharePushRoutingTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NoteFirebaseMessagingServiceRoutingTest {

    private lateinit var context: Context
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val noteApi = mockk<NoteApi>(relaxed = true)
    private val identityRecheck = mockk<com.pingolin.note.domain.repository.IdentityRecheck>(relaxed = true)
    private lateinit var service: NoteFirebaseMessagingService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        every { sessionManager.isLoggedIn() } returns true
        every { sessionManager.areShareNotificationsEnabled() } returns true
        // Not created: onCreate would run Hilt's injection. The fields are set
        // directly, as NoteFirebaseMessagingServiceTest does.
        service = Robolectric.buildService(NoteFirebaseMessagingService::class.java).get()
        for ((name, value) in listOf(
            "sessionManager" to sessionManager, "noteApi" to noteApi, "identityRecheck" to identityRecheck,
        )) {
            NoteFirebaseMessagingService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
    }

    private fun dispatched(kind: String) = RemoteMessage.Builder("note@fcm.googleapis.com")
        .setData(mapOf("type" to "tickle", "kind" to kind, "refId" to "3f2c8f4e-9a51-4b8e-8d2a-6c1e0b7d5a90"))
        .build()

    /** The share notice is posted from the service's IO scope. */
    private fun postedWithin(ms: Long): List<Pair<String?, String?>> {
        val nm = shadowOf(context.getSystemService(NotificationManager::class.java))
        val deadline = System.currentTimeMillis() + ms
        while (nm.allNotifications.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return nm.allNotifications.map {
            it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() to
                it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        }
    }

    @Test
    fun `the dispatcher's notebook_share payload posts the notebook notice`() {
        service.onMessageReceived(dispatched("notebook_share"))
        assertEquals(listOf("New notebook shared with you" to "Tap to accept or decline it"), postedWithin(3_000))
    }

    @Test
    fun `the dispatcher's share payload posts the note notice`() {
        service.onMessageReceived(dispatched("share"))
        assertEquals(listOf("New note shared with you" to "Tap to open Shared with me"), postedWithin(3_000))
    }

    /** Extra fields in the push make no difference. */
    @Test
    fun `an identities push asks for one re-check, whatever else it carries`() {
        service.onMessageReceived(
            RemoteMessage.Builder("note@fcm.googleapis.com")
                .setData(
                    mapOf(
                        "type" to "tickle", "kind" to "identities",
                        "refId" to "00000000-0000-0000-0000-000000000000",
                        "identities" to "[{\"nonce\":\"n\",\"ciphertext\":\"c\"}]", "title" to "x",
                    ),
                )
                .build(),
        )
        service.onMessageReceived(dispatched("identities"))
        io.mockk.verify(exactly = 2) { identityRecheck.onNudge() }
        assertEquals(emptyList<Pair<String?, String?>>(), postedWithin(300))
    }

    @Test
    fun `the Shared notes switch gates a notebook share too`() {
        every { sessionManager.areShareNotificationsEnabled() } returns false
        service.onMessageReceived(dispatched("notebook_share"))
        assertEquals(emptyList<Pair<String?, String?>>(), postedWithin(300))
    }

    /** Someone the owner shared a note with has signed up: the same silent immediate sync as
     *  "sync", marked as asked for, so the invitation finisher at its end runs. Nothing posted. */
    @Test
    fun `the dispatcher's finish_shares payload starts a silent immediate sync`() {
        io.mockk.mockkObject(com.pingolin.note.worker.SyncWorker.Companion)
        try {
            every { com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(any()) } returns Unit
            val asked = com.pingolin.note.domain.repository.InvitationFinisher.asked()
            service.onMessageReceived(dispatched(com.pingolin.note.domain.repository.InvitationFinisher.PUSH_KIND))
            io.mockk.verify(exactly = 1) { com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(any()) }
            assertEquals(asked + 1, com.pingolin.note.domain.repository.InvitationFinisher.asked())
            assertEquals(emptyList<Pair<String?, String?>>(), postedWithin(300))
        } finally {
            io.mockk.unmockkObject(com.pingolin.note.worker.SyncWorker.Companion)
        }
    }
}
