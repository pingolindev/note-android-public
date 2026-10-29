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

package com.pingolin.note.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide last-sync outcome, shared by every sync path — foreground and
 * the background [com.pingolin.note.worker.SyncWorker] — so the "couldn't
 * reach server" banner reflects the genuinely most-recent attempt.
 *
 * Last writer wins: whichever sync finished most recently sets the state. That
 * way a successful background sync on reconnect clears a banner left by an
 * earlier failed foreground refresh, and a later foreground failure can
 * re-raise it.
 */
@Singleton
class SyncStatus @Inject constructor() {
    /** Timestamp of the last sync attempt that failed, or null when the most
     *  recent attempt succeeded (or was never run). */
    private val _lastSyncFailedAt = MutableStateFlow<Long?>(null)
    val lastSyncFailedAt: StateFlow<Long?> = _lastSyncFailedAt.asStateFlow()

    /** A sync (foreground or background) completed successfully. */
    fun clear() {
        _lastSyncFailedAt.value = null
    }

    /** A sync attempt failed to reach / complete against the server. */
    fun markFailure() {
        _lastSyncFailedAt.value = System.currentTimeMillis()
    }
}
