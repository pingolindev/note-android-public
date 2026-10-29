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

package com.pingolin.note.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.util.PendingPushUnregisterStore
import com.pingolin.note.util.PushNotificationManager
import com.pingolin.note.util.SessionManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deferred push delete compares the armed account with the signed-in one canonically: the
 * same account under another spelling is the same account, and its fresh row is kept. */
class PushUnregisterWorkerTest {

    private val me = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    @Test
    fun `a delete armed under another spelling of the signed-in account is dropped, not run`() = runTest {
        for (armed in listOf(me.uppercase(), me.replace("-", ""), "{$me}", "urn:uuid:$me")) {
            val api = mockk<NoteApi>(relaxed = true)
            val store = mockk<PendingPushUnregisterStore>()
            every { store.get() } returns PendingPushUnregisterStore.Pending("d", "t", "fcm", armed)
            every { store.clear() } just Runs
            val session = mockk<SessionManager>()
            every { session.getUserId() } returns me
            val worker = PushUnregisterWorker(
                mockk<Context>(relaxed = true), mockk<WorkerParameters>(relaxed = true),
                api, store, mockk<PushNotificationManager>(relaxed = true), session,
            )

            assertTrue(armed, worker.doWork() is ListenableWorker.Result.Success)
            verify(exactly = 1) { store.clear() }
            coVerify(exactly = 0) { api.unregisterFcmDeviceWithAuth(any(), any()) }
        }
    }
}
