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

        worker = SyncWorker(context, workerParams, syncRepository, itemDao, reminderDao, quotaManager, syncStatus)
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
        worker = SyncWorker(context, workerParams, syncRepository, itemDao, reminderDao, quotaManager, syncStatus)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `doWork_secondAttemptFails_returnsRetry`() = runTest {
        every { workerParams.runAttemptCount } returns 2

        coEvery { syncRepository.sync() } throws IOException("Timeout")

        worker = SyncWorker(context, workerParams, syncRepository, itemDao, reminderDao, quotaManager, syncStatus)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
