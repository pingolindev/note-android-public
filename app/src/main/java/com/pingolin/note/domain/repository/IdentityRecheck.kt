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
import android.os.SystemClock
import android.util.Log
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.util.SessionManager
import com.pingolin.note.worker.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pick up a restore made elsewhere while the app runs, so older notes open
 * without a restart.
 *
 * Triggers: the "identities" push (the nudge) and the app coming to the
 * foreground while the local cache still holds notes that did not open. The
 * push carries nothing and grants nothing: the re-check is the normal signed-in
 * `GET /auth/previous-identities` plus the SDK import, and it only ADDS
 * ([PreviousIdentityLoader.recheck] imports the union). Only when something new
 * opened does the app re-decrypt, through an immediate sync.
 *
 * Limits, per device: at most one re-check per [WINDOW_MS]. A trigger refused
 * by the window or by a running re-check leaves ONE pending flag, which runs a
 * single re-check when the window ends (or at a foreground after it), and that
 * run is the new window's one re-check. So a flood of nudges costs at most one
 * GET per window. Sign-out and account switch ([clear], from LocalDataWiper)
 * drop the flag and the window; the loader's reset drops the held blobs.
 *
 * The web page opens its own earlier identities (the Android shell has no
 * bridge op for them), so every nudge is also forwarded to it ([pageEvents]);
 * the page applies the same limits to its own fetch.
 */
@Singleton
class IdentityRecheck @Inject constructor(
    private val loader: PreviousIdentityLoader,
    private val itemDao: ItemDao,
    private val sessionManager: SessionManager,
    @param:ApplicationContext private val context: Context,
) {
    /** Seams for tests: a virtual clock and scope, and what "re-decrypt" runs. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var afterNewIdentity: () -> Unit = { SyncWorker.enqueueImmediateSync(context) }

    private val gate = RecheckGate(WINDOW_MS)
    private val lock = Any()
    private var scheduled: Job? = null
    private var generation = loader.generation()

    private val _pageEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** One event per nudge, for the web page (WebAppActivity forwards it). */
    val pageEvents: SharedFlow<Unit> = _pageEvents

    /** The "identities" push. Everything in it but the kind is ignored. */
    fun onNudge() {
        _pageEvents.tryEmit(Unit)
        request()
    }

    /** The app came to the foreground: re-check only if something is still
     *  locked, or a refused trigger is still pending. */
    fun onForeground() {
        scope.launch {
            val pending = synchronized(lock) { gate.hasPending }
            if (pending || itemDao.countUndecryptedItems() > 0) request()
        }
    }

    /** Sign-out / account switch: no pending re-check, no window. */
    fun clear() {
        synchronized(lock) {
            gate.clear()
            scheduled?.cancel()
            scheduled = null
            generation = loader.generation()
        }
    }

    private fun request() {
        val decision = synchronized(lock) {
            dropIfAccountChanged()
            gate.request(clock())
        }
        when (decision) {
            RecheckGate.Decision.Run -> scope.launch { run() }
            is RecheckGate.Decision.Pending -> decision.atMs?.let(::schedule)
        }
    }

    private suspend fun run() {
        try {
            val gen = loader.generation()
            val opened = sessionManager.isLoggedIn() && loader.recheck()
            // Re-checked after the await: a sign-out meanwhile means whatever
            // opened was not this session's to act on.
            if (opened && loader.generation() == gen) afterNewIdentity()
        } catch (e: Exception) {
            Log.w(TAG, "re-check failed: ${e.message}")
        } finally {
            val at = synchronized(lock) { gate.finished() }
            if (at != null) schedule(at)
        }
    }

    /** Run the pending re-check at [atMs], once. */
    private fun schedule(atMs: Long) = synchronized(lock) {
        if (scheduled?.isActive == true) return@synchronized
        scheduled = scope.launch {
            delay((atMs - clock()).coerceAtLeast(0))
            val run = synchronized(lock) {
                scheduled = null
                dropIfAccountChanged()
                gate.runPending(clock())
            }
            if (run) run()
        }
    }

    /** Under [lock]: a wipe since the last trigger means the state was the
     *  previous account's. */
    private fun dropIfAccountChanged() {
        val now = loader.generation()
        if (now != generation) {
            gate.clear()
            generation = now
        }
    }

    companion object {
        private const val TAG = "IdentityRecheck"
        const val WINDOW_MS = 3 * 60_000L

        /** The event the page listens for, origin guard first, as every script
         *  this shell injects ([com.pingolin.note.ui.web.WebAppDeviceState]). */
        fun pageEventScript(origin: String): String =
            "(function(){try{if(location.origin!==${org.json.JSONObject.quote(origin)})return;" +
                "window.dispatchEvent(new CustomEvent('pingolin-identities'));}catch(e){}})();"
    }
}

/**
 * The re-check limits as a pure state machine over clock readings. Not
 * thread-safe: [IdentityRecheck] calls it under its lock.
 */
internal class RecheckGate(private val windowMs: Long) {
    private var running = false
    private var windowStart: Long? = null

    var hasPending = false
        private set

    sealed interface Decision {
        /** Start one now; the window starts now. */
        data object Run : Decision

        /** Refused: the pending flag is set. It may run at [atMs], or, while
         *  one is running (null), when that one ends and its window has passed. */
        data class Pending(val atMs: Long?) : Decision
    }

    fun request(now: Long): Decision {
        if (running) {
            hasPending = true
            return Decision.Pending(null)
        }
        val start = windowStart
        if (start != null && now - start < windowMs) {
            hasPending = true
            return Decision.Pending(start + windowMs)
        }
        start(now)
        return Decision.Run
    }

    /** The pending re-check, if one is set and its time has come. */
    fun runPending(now: Long): Boolean {
        val start = windowStart
        if (!hasPending || running || (start != null && now - start < windowMs)) return false
        start(now)
        return true
    }

    /** A re-check ended: when its pending successor may run, or null. */
    fun finished(): Long? {
        running = false
        return if (hasPending) (windowStart ?: 0L) + windowMs else null
    }

    fun clear() {
        running = false
        hasPending = false
        windowStart = null
    }

    private fun start(now: Long) {
        running = true
        hasPending = false
        windowStart = now
    }
}
