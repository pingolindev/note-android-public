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

package com.pingolin.note.domain.repository

import android.content.Context
import android.content.SharedPreferences
import com.pingolin.note.data.local.NoteDatabase
import com.pingolin.note.util.KeystoreEncryption
import com.pingolin.note.util.NoteGenFloorStore
import com.pingolin.note.util.RetainedContentKeyStore
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager
import java.io.File

/**
 * Unit tests for [LocalDataWiper.wipeBlocking] — the sign-out local-state
 * erase. Exercises the suspend body directly (not the fire-and-forget [wipe]
 * scope) so the assertions are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalDataWiperTest {

    private val context: Context = mockk(relaxed = true)
    private val database: NoteDatabase = mockk(relaxed = true)
    private val keystoreEncryption: KeystoreEncryption = mockk(relaxed = true)
    private val cryptoManager: CryptoManager = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)
    private val sharesInbox: SharesInboxRepository = mockk(relaxed = true)
    private val pinStore: SenderKeyPinStore = mockk(relaxed = true)
    private val genFloorStore: NoteGenFloorStore = mockk(relaxed = true)
    private val retainedContentKeyStore: RetainedContentKeyStore = mockk(relaxed = true)
    private val quotaManager: QuotaManager = mockk(relaxed = true)
    private val previousIdentityLoader: PreviousIdentityLoader = mockk(relaxed = true)
    private val identityRecheck: IdentityRecheck = mockk(relaxed = true)

    private var tempCacheDir: File? = null
    private var tempFilesDir: File? = null

    private fun wiper() = LocalDataWiper(
        context, database, keystoreEncryption, cryptoManager, sessionManager,
        sharesInbox, pinStore, genFloorStore, retainedContentKeyStore, quotaManager,
        previousIdentityLoader, identityRecheck,
    )

    @After
    fun teardown() {
        tempCacheDir?.deleteRecursively()
        tempFilesDir?.deleteRecursively()
    }

    @Test
    fun `wipeBlocking runs every wipe step`() = runTest {
        every { context.cacheDir } returns null

        wiper().wipeBlocking()

        verify { database.clearAllTables() }
        verify { cryptoManager.clearAllKeys() }
        verify { keystoreEncryption.deleteAllKeys() }
        verify { sessionManager.clearAccountScopedData() }
        verify { sharesInbox.wipe() }
        verify { pinStore.wipe() }
        verify { genFloorStore.wipe() }
        verify { retainedContentKeyStore.wipe() }
        verify { quotaManager.clear() }
        // The recovered-identity latch is @Singleton state that outlives the
        // account: left set, the incoming account never fetches its own
        // /previous-identities and its recovery silently does nothing.
        verify { previousIdentityLoader.reset() }
        // Sign-out clears the re-check's pending flag and window
        // too.
        verify { identityRecheck.clear() }
        // ...and resets the loader BEFORE the SDK key wipe, so a load still on
        // the network drops its answer instead of importing after the wipe.
        verifyOrder {
            previousIdentityLoader.reset()
            cryptoManager.clearAllKeys()
        }
        // The inbox is told that a wipe is under way BEFORE anything is cleared: a
        // refresh that is running stores and writes nothing from then on, not only from
        // the inbox's own step after the tables and the web view.
        // And that it is over only after the last step: no refresh starts in between.
        coVerifyOrder {
            sharesInbox.wipeIsStarting()
            database.clearAllTables()
            sharesInbox.wipe()
            sessionManager.clearAccountScopedData()
            sharesInbox.wipeIsOver()
        }
    }

    @Test
    fun `telling the inbox is best-effort like every step - the wipe runs, and is said to be over`() = runTest {
        every { context.cacheDir } returns null
        coEvery { sharesInbox.wipeIsStarting() } throws RuntimeException("boom")
        wiper().wipeBlocking()
        verify { database.clearAllTables() }
        verify { sharesInbox.wipe() }
        verify { sharesInbox.wipeIsOver() }
    }

    @Test
    fun `a wipe whose caller is cancelled part-way is still said to be over`() = kotlinx.coroutines.runBlocking {
        every { context.cacheDir } returns null
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        // The caller goes away while the tables are being cleared.
        every { database.clearAllTables() } answers { scope.cancel() }
        val job = scope.launch { wiper().wipeBlocking() }
        job.join()
        org.junit.Assert.assertTrue(job.isCancelled)
        coVerify { sharesInbox.wipeIsStarting() }
        verify { sharesInbox.wipeIsOver() }
    }

    /**
     * A caller that is ALREADY cancelled must still say that the wipe starts (the call is a
     * suspension point) before its `finally` says "over": otherwise it ends the count of another
     * wipe which is under way, and a refresh can start in the middle of it.
     */
    @Test
    fun `a wipe whose caller is cancelled before it begins still says that it starts, before it says that it is over`() = kotlinx.coroutines.runBlocking {
        every { context.cacheDir } returns null
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = scope.launch {
            started.complete(Unit)
            // Cancelled while it waits here: everything after this runs in a cancelled coroutine.
            try { gate.await() } finally { wiper().wipeBlocking() }
        }
        started.await()
        job.cancel()
        job.join()
        org.junit.Assert.assertTrue(job.isCancelled)
        coVerifyOrder {
            sharesInbox.wipeIsStarting()
            sharesInbox.wipeIsOver()
        }
        coVerify(exactly = 1) { sharesInbox.wipeIsStarting() }
        verify(exactly = 1) { sharesInbox.wipeIsOver() }
    }

    /**
     * The start takes a lock a refresh holds while it saves to disk, and an account switch
     * calls the wiper from the view model's scope: never the caller's thread to wait on.
     */
    @Test
    fun `the start of a wipe is said off the caller's thread`() = kotlinx.coroutines.runBlocking {
        every { context.cacheDir } returns null
        val callers = Thread.currentThread()
        var saidOn: Thread? = null
        coEvery { sharesInbox.wipeIsStarting() } answers { saidOn = Thread.currentThread() }
        wiper().wipeBlocking()
        org.junit.Assert.assertNotNull(saidOn)
        org.junit.Assert.assertNotSame(callers, saidOn)
    }

    @Test
    fun `wipeBlocking is best-effort - one failing step does not skip the rest`() = runTest {
        every { context.cacheDir } returns null
        // The Room wipe blows up; the key wipes must still run.
        every { database.clearAllTables() } throws RuntimeException("db locked")

        wiper().wipeBlocking()

        verify { cryptoManager.clearAllKeys() }
        verify { keystoreEncryption.deleteAllKeys() }
        verify { previousIdentityLoader.reset() }
    }

    @Test
    fun `wipeBlocking still resets the identity latch when the SDK key wipe throws`() = runTest {
        every { context.cacheDir } returns null
        // (d) and (d1) are separate steps for a reason: the SDK call is FFI and
        // can fail, and the latch must not ride on it.
        every { cryptoManager.clearAllKeys() } throws RuntimeException("ffi boom")

        wiper().wipeBlocking()

        verify { previousIdentityLoader.reset() }
    }

    @Test
    fun `wipeBlocking purges decrypted files from cacheDir`() = runTest {
        val dir = File.createTempFile("wiper-cache", "").let {
            it.delete(); it.mkdirs(); it
        }
        tempCacheDir = dir
        val leaked = File(dir, "attachment.jpg").apply { writeText("decrypted") }
        every { context.cacheDir } returns dir

        wiper().wipeBlocking()

        assertFalse("decrypted attachment temp file should be purged", leaked.exists())
    }

    @Test
    fun `wipeBlocking purges crash logs from filesDir`() = runTest {
        val filesDir = File.createTempFile("wiper-files", "").let {
            it.delete(); it.mkdirs(); it
        }
        tempFilesDir = filesDir
        val crashLog = File(filesDir, "crash_logs").apply { mkdirs() }
            .let { File(it, "crash_20260101_000000.log").apply { writeText("stacktrace") } }
        every { context.cacheDir } returns null
        every { context.filesDir } returns filesDir

        wiper().wipeBlocking()

        assertFalse("opt-in crash log should be purged on logout", crashLog.exists())
    }

    @Test
    fun `wipeBlocking clears the widget-to-note map`() = runTest {
        every { context.cacheDir } returns null
        val widgetPrefs: SharedPreferences = mockk(relaxed = true)
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        every {
            context.getSharedPreferences("pingolin_note_widgets", Context.MODE_PRIVATE)
        } returns widgetPrefs
        every { widgetPrefs.edit() } returns editor
        every { editor.clear() } returns editor

        wiper().wipeBlocking()

        verify { editor.clear() }
        verify { editor.apply() }
    }
}
