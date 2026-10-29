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

package com.pingolin.note.receiver

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import android.Manifest
import android.os.Build
import androidx.test.rule.GrantPermissionRule
import com.pingolin.note.data.local.NoteDatabase
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.util.SessionManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import javax.inject.Inject

/**
 * Instrumented tests for [ReminderReceiver] — the JVM-ignored counterpart is
 * `app/src/test/java/com/pingolin/note/receiver/ReminderReceiverTest.kt`.
 *
 * ## What these tests guard
 *
 * An alarm outlives logout and account switch, so it must not carry the
 * DECRYPTED note title in its PendingIntent extras: a fired reminder would
 * post account A's title into a logged-out or *different* account's tray. The
 * receiver carries only the note id and, at fire time, requires a live
 * session and re-resolves the note from Room (wiped on logout, repopulated
 * per-account on switch).
 *
 * These tests cover those gates — absent session, absent row, App Lock
 * redaction — because those are the properties that keep one account's
 * plaintext out of another's notification shade.
 *
 * ## Why sendBroadcast, not a direct onReceive() call
 *
 * `onReceive` calls `goAsync()`, which returns null when the receiver was not
 * invoked by the system, and the `finally { pending.finish() }` then NPEs on
 * a bare `CoroutineScope` — taking the process with it. `exported="false"`
 * only bars OTHER apps; instrumentation runs in the target app's own UID, so
 * an explicit-component broadcast is delivered normally and the framework
 * supplies the real PendingResult that goAsync() hands back.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@MediumTest
class ReminderReceiverInstrumentedTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Android 13+ (SDK 33) makes POST_NOTIFICATIONS a runtime permission.
    // GrantPermissionRule granted-at-test-time survives gradle's automatic
    // APK reinstall between runs; `adb shell pm grant …` does not.
    @get:Rule(order = 1)
    val notificationPermissionRule: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant() // no-op on older SDKs
        }

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var db: NoteDatabase

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val noteId = "note-1234"
    private val noteTitle = "Standup at 10"

    @Before
    fun setUp() {
        hiltRule.inject()
        nm.cancelAll()

        // Seed on the HILT singleton, which is the same instance the
        // receiver resolves through EntryPointAccessors.fromApplication.
        sessionManager.saveTokens("test-access-token", "test-refresh-token")
        sessionManager.saveUserId("test-user")
        sessionManager.setReminderNotificationsEnabled(true)
        // App Lock off and detail level FULL: the default path where the
        // resolved title is allowed to reach the tray. Set explicitly rather
        // than relying on defaults, because both are sticky prefs that an
        // earlier test in the suite could have left flipped.
        sessionManager.setAppLockEnabled(false)
        sessionManager.setNotificationDetailLevel(SessionManager.NOTIF_DETAIL_FULL)

        runBlocking { db.itemDao().upsert(note(noteId, noteTitle)) }
    }

    @After
    fun tearDown() {
        nm.cancelAll()
        runBlocking { db.itemDao().deleteById(noteId) }
    }

    @Test
    fun reminderForAnExistingNote_postsTheTitleResolvedFromRoom() {
        fireReminder(noteId)

        val posted = awaitNotification(noteId)
        assertNotNull("Expected a reminder notification for $noteId", posted)
        assertEquals(
            "Body must be the title resolved from Room at fire time",
            noteTitle,
            posted!!.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun reminderWithNoNoteId_postsNothing() {
        val before = activeIds()
        fireReminder(noteId = null)
        assertSilent(before, "no noteId in the intent")
    }

    // NOTE for the gate tests below: the positive control must run with the
    // gate RESTORED. Firing it while the gate is still shut would suppress the
    // control too, and a control that cannot pass is not a control.

    /**
     * Account switch: the alarm outlives the account that scheduled it, and
     * item ids are unique per note, so the next account simply does not hold
     * that row. No row → no notification, rather than a notification carrying
     * the other account's plaintext.
     */
    @Test
    fun reminderForANoteThisAccountDoesNotHave_postsNothing() {
        val before = activeIds()
        fireReminder("note-belonging-to-another-account")
        assertSilent(before, "the note id resolves to no row in Room")
    }

    /** The other half of the same gate: a fired alarm after logout stays
     * silent.
     */
    @Test
    fun reminderWhileLoggedOut_postsNothing() {
        val before = activeIds()
        sessionManager.clear()
        fireReminder(noteId)
        assertSilent(before, "there is no active session") {
            sessionManager.saveTokens("test-access-token", "test-refresh-token")
            sessionManager.saveUserId("test-user")
        }
    }

    /**
     * The tray sits OUTSIDE the in-app biometric/PIN gate, so with App Lock on
     * the title must not appear there — the receiver forces GENERIC detail.
     */
    @Test
    fun withAppLockOn_theTitleIsRedacted() {
        sessionManager.setAppLockEnabled(true)
        try {
            fireReminder(noteId)

            val posted = awaitNotification(noteId)
            assertNotNull("A redacted reminder should still be posted", posted)
            val body = posted!!.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            assertEquals("Tap to view your note", body)
        } finally {
            sessionManager.setAppLockEnabled(false)
        }
    }

    @Test
    fun reminderDisabledForThisDevice_postsNothing() {
        val before = activeIds()
        sessionManager.setReminderNotificationsEnabled(false)
        fireReminder(noteId)
        assertSilent(before, "reminder notifications are switched off on this device") {
            sessionManager.setReminderNotificationsEnabled(true)
        }
    }

    @Test
    fun ensuresNotificationChannelExists() {
        fireReminder(noteId)
        awaitNotification(noteId)

        assertNotNull(
            "ReminderReceiver should create/ensure the ${ReminderReceiver.CHANNEL_ID} channel",
            nm.getNotificationChannel(ReminderReceiver.CHANNEL_ID),
        )
    }

    // ===== Helpers =====

    private fun note(id: String, title: String) = ItemEntity(
        id = id,
        userId = "test-user",
        itemType = 1,
        encryptedContent = "",
        contentNonce = "",
        decryptedContent = """{"title":"$title","body":"","note_type":"text","labels":[]}""",
        title = title,
        version = 1,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun fireReminder(noteId: String?) {
        val intent = Intent(appContext, ReminderReceiver::class.java).apply {
            noteId?.let { putExtra(ReminderReceiver.EXTRA_NOTE_ID, it) }
        }
        appContext.sendBroadcast(intent)
    }

    private fun activeIds(): Set<Int> = nm.activeNotifications.map { it.id }.toSet()

    /** The notification id is `noteId.hashCode()`. Polls: delivery hops the
     *  framework, then goAsync() defers the post onto an IO coroutine. */
    private fun awaitNotification(noteId: String, timeoutMs: Long = 5_000L): Notification? {
        val expectedId = noteId.hashCode()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            nm.activeNotifications.firstOrNull { it.id == expectedId }?.let { return it.notification }
            Thread.sleep(50)
        }
        return null
    }

    /**
     * Assert nothing was posted — WITH a positive control.
     *
     * Without the control this whole file could pass while delivering no
     * broadcasts at all: every "posts nothing" test would be satisfied by a
     * receiver that never ran. So after asserting silence, fire a reminder
     * that MUST post and require it to arrive; if that fails, the negative
     * above proved nothing and the test says so.
     *
     * [reopenGate] restores whatever the caller shut (session, device toggle)
     * before the control fires — a control run against a closed gate would be
     * silent for the same reason as the assertion above and could never fail.
     */
    private fun assertSilent(idsBefore: Set<Int>, because: String, reopenGate: () -> Unit = {}) {
        // Compare the whole active set rather than polling one id: these tests
        // fire a missing id, or an id belonging to another account, so there is
        // no single notification id to look for — "nothing new appeared" is the
        // property, and it holds whatever the receiver might have posted.
        Thread.sleep(1_500L)
        assertEquals(
            "No reminder notification should be posted when $because",
            idsBefore,
            activeIds(),
        )

        reopenGate()

        val control = "control-${System.nanoTime()}"
        runBlocking { db.itemDao().upsert(note(control, "Control")) }
        try {
            fireReminder(control)
            assertNotNull(
                "POSITIVE CONTROL FAILED: a known-good reminder did not post either, so " +
                    "the silence above is not evidence that '$because' is what suppressed it",
                awaitNotification(control),
            )
        } finally {
            runBlocking { db.itemDao().deleteById(control) }
        }
    }
}
