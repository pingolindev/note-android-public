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
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.dao.ReminderDao
import com.pingolin.note.domain.QuotaExceededException
import com.pingolin.note.domain.repository.InvitationFinisher
import com.pingolin.note.domain.repository.QuotaManager
import com.pingolin.note.domain.repository.SyncRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SyncWorkerTest {

    private companion object {
        const val ACCOUNT = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    }

    @MockK
    private lateinit var context: Context

    @MockK
    private lateinit var workerParams: WorkerParameters

    @MockK
    private lateinit var syncRepository: SyncRepository

    @MockK
    private lateinit var itemDao: ItemDao

    @MockK
    private lateinit var reminderDao: ReminderDao

    @MockK
    private lateinit var quotaManager: QuotaManager

    @MockK
    private lateinit var invitationFinisher: InvitationFinisher

    // Plain holder, no deps — use a real instance so clear()/markFailure()
    // behave as in production.
    private val syncStatus = com.pingolin.note.util.SyncStatus()

    private lateinit var worker: SyncWorker

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        // WorkerParameters needs these
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
        every { workerParams.id } returns java.util.UUID.randomUUID()
        every { workerParams.tags } returns setOf("NoteSync")
        every { workerParams.runAttemptCount } returns 0

        every { context.applicationContext } returns context
        every { context.getSystemService(any<String>()) } returns null
        every { context.packageName } returns "com.pingolin.note"

        // Only the QuotaExceededException path touches the manager
        // (refresh-so-the-UI-shows-real-counts); stub it for those tests.
        every { quotaManager.refresh() } just Runs

        // The account the sync is for, named before it starts, and the
        // invitation finisher run after a successful one.
        every { invitationFinisher.signedInAccount() } returns ACCOUNT
        coEvery { invitationFinisher.afterSync(any()) } returns emptyList()

        worker = SyncWorker(context, workerParams, syncRepository, itemDao, reminderDao, quotaManager, syncStatus, invitationFinisher)
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    // =============================================================================
    // SUCCESS TESTS
    // =============================================================================

    @Test
    fun `doWork_syncSuccess_returnsSuccess`() = runTest {
        coEvery { syncRepository.sync() } returns SyncRepository.SyncResult(
            pushed = 5,
            pulled = 3,
            conflicts = 0,
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { syncRepository.sync() }
    }

    @Test
    fun `doWork_syncSuccessZeroItems_returnsSuccess`() = runTest {
        coEvery { syncRepository.sync() } returns SyncRepository.SyncResult(
            pushed = 0,
            pulled = 0,
            conflicts = 0,
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    // =============================================================================
    // FINISHING INVITATIONS (InvitationFinisher)
    // =============================================================================

    @Test
    fun `a successful sync finishes the invitations of the account it began as`() = runTest {
        coEvery { syncRepository.sync() } coAnswers {
            // Whoever is signed in by the end, the pass is named for the
            // account read BEFORE the sync.
            every { invitationFinisher.signedInAccount() } returns "someone-else"
            SyncRepository.SyncResult(pushed = 0, pulled = 1, conflicts = 0)
        }

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerifyOrder {
            invitationFinisher.signedInAccount()
            syncRepository.sync()
            invitationFinisher.afterSync(ACCOUNT)
        }
    }

    @Test
    fun `a failed sync finishes nothing`() = runTest {
        coEvery { syncRepository.sync() } throws IOException("Connection refused")

        worker.doWork()

        coVerify(exactly = 0) { invitationFinisher.afterSync(any()) }
    }

    @Test
    fun `a finisher that fails never fails or retries the sync`() = runTest {
        coEvery { syncRepository.sync() } returns SyncRepository.SyncResult(pushed = 0, pulled = 0, conflicts = 0)
        coEvery { invitationFinisher.afterSync(any()) } throws IllegalStateException("boom")

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    /**
     * The one delayed sync a push held back by the invitation finisher's once-a-minute cap gets:
     * unique and replaced by the next, set for its delay, only online, and cancelled with every
     * other sync at sign-out (the wipe's cancelAllSync).
     */
    @Test
    fun `a finish-later sync is one unique delayed sync, replaced by the next, and cancelled with every sync`() {
        val wm = mockk<androidx.work.WorkManager>(relaxed = true)
        mockkObject(androidx.work.WorkManager.Companion)
        try {
            every { androidx.work.WorkManager.getInstance(any()) } returns wm
            val request = slot<androidx.work.OneTimeWorkRequest>()

            SyncWorker.enqueueFinishLaterSync(context, 51_000L)

            verify {
                wm.enqueueUniqueWork(SyncWorker.FINISH_LATER, androidx.work.ExistingWorkPolicy.REPLACE, capture(request))
            }
            val spec = request.captured.workSpec
            assertEquals(SyncWorker::class.java.name, spec.workerClassName)
            assertEquals(51_000L, spec.initialDelay)
            assertEquals(androidx.work.NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
            assertTrue(request.captured.tags.contains(SyncWorker.FINISH_LATER))
            assertEquals("NoteSync_finish_later", SyncWorker.FINISH_LATER)

            SyncWorker.cancelAllSync(context)
            verify { wm.cancelUniqueWork("NoteSync") }
            verify { wm.cancelUniqueWork("NoteSync_immediate") }
            verify { wm.cancelUniqueWork(SyncWorker.FINISH_LATER) }
        } finally {
            unmockkObject(androidx.work.WorkManager.Companion)
        }
    }

    // =============================================================================
    // QUOTA EXCEEDED TESTS
    // =============================================================================

    @Test
    @org.junit.Ignore("showQuotaNotification builds an android.app.Notification which requires the Android runtime (needs Robolectric).")
    fun `doWork_quotaExceeded_returnsFailure`() = runTest {
        coEvery { syncRepository.sync() } throws QuotaExceededException("Note limit reached")

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    // =============================================================================
    // ERROR / RETRY TESTS
    // =============================================================================

    @Test
    fun `doWork_networkError_firstAttempt_returnsRetry`() = runTest {
        coEvery { syncRepository.sync() } throws IOException("Connection refused")

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork_genericException_firstAttempt_returnsRetry`() = runTest {
        coEvery { syncRepository.sync() } throws RuntimeException("Unknown error")

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork_thirdAttemptFails_returnsFailure`() = runTest {
        every { workerParams.runAttemptCount } returns 3

        coEvery { syncRepository.sync() } throws IOException("Still failing")

        // Recreate worker with updated params
        worker = SyncWorker(context, workerParams, syncRepository, itemDao, reminderDao, quotaManager, syncStatus, invitationFinisher)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `doWork_secondAttemptFails_returnsRetry`() = runTest {
        every { workerParams.runAttemptCount } returns 2

        coEvery { syncRepository.sync() } throws IOException("Timeout")

        worker = SyncWorker(context, workerParams, syncRepository, itemDao, reminderDao, quotaManager, syncStatus, invitationFinisher)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
