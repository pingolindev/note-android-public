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

import android.util.Log
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.QuotaDto
import com.pingolin.note.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the user's current subscription tier and
 * usage. UI surfaces (the Settings tier badge) observe [quota] and
 * re-render when a refresh lands; synchronous reads go through
 * [isPaid].
 *
 * Server is still the authority. This cache is a hint to avoid
 * round-tripping to discover gating via 402 — it's deliberately
 * refresh-on-demand rather than long-lived: on login, on app
 * foreground, after creating a note, after attaching a file, and after
 * any 402 the rest of the stack catches.
 */
@Singleton
class QuotaManager @Inject constructor(
    private val noteApi: NoteApi,
    private val sessionManager: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    /** Bumped on every [clear] (logout / account switch). A [refreshNow]
     *  snapshots it before its network round-trip and discards the response if
     *  it changed meanwhile — so an in-flight refresh for the PRIOR account
     *  can't write its tier back on top of the null [clear] left, resurrecting
     *  a stale "paid" badge into the next account. */
    private val generation = AtomicLong(0L)

    private val _quota = MutableStateFlow<QuotaDto?>(null)
    /** Latest known quota or `null` if we've never fetched (e.g. signed
     *  out, or first launch before the first refresh lands). Callers
     *  should treat `null` as "assume free" for gating purposes — same
     *  effect as the server's fallback when auth is unreachable. */
    val quota: StateFlow<QuotaDto?> = _quota.asStateFlow()

    init {
        // Drop the cached tier as soon as the session is wiped so a
        // subsequent sign-in starts cold and a stale "paid" badge can't
        // leak across user switches. Refresh on a fresh sign-in too —
        // MainActivity also kicks one off in onCreate, but observing
        // here covers headless flows (e.g. SyncWorker scheduling).
        // StateFlow already collapses identical consecutive values, so
        // no distinctUntilChanged needed.
        sessionManager.isLoggedInFlow
            .onEach { loggedIn ->
                // Route the logged-out drop through clear(), not a bare null, so
                // it also bumps the generation.
                if (loggedIn) refreshNow() else clear()
            }
            .launchIn(scope)
    }

    /** `true` when the active tier grants the premium gates. */
    val isPaid: Boolean
        get() = _quota.value?.tier == TIER_PAID

    /** Fire-and-forget refresh. Safe to call from any thread / lifecycle;
     *  concurrent callers serialize on a mutex instead of racing each other's
     *  writes into [quota]. */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    /** Suspending variant for callers that want to wait for the result
     *  (e.g. after a 402 they want the latest tier before deciding how
     *  to surface the error). */
    suspend fun refreshNow() {
        if (!sessionManager.isLoggedIn()) {
            _quota.value = null
            return
        }
        refreshLock.withLock {
            // Snapshot the generation under the lock so a response landing after
            // an intervening clear() is dropped (see [generation]).
            val gen = generation.get()
            try {
                val resp = noteApi.getQuota()
                if (resp.isSuccessful) {
                    resp.body()?.data?.let {
                        if (generation.get() == gen) _quota.value = it
                    }
                } else {
                    // 401 will get sorted out by TokenAuthenticator on
                    // the next request; quota stays stale meanwhile.
                    Log.w(TAG, "refreshNow: getQuota returned ${resp.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshNow: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Drop cached state on sign-out / account switch so the next login starts
     *  clean. Bumps the generation FIRST so any refresh in flight for the prior
     *  account discards its response instead of writing it back after this
     *  null. */
    fun clear() {
        generation.incrementAndGet()
        _quota.value = null
    }

    companion object {
        const val TIER_FREE = "free"
        const val TIER_PAID = "paid"
        private const val TAG = "QuotaManager"
    }
}
