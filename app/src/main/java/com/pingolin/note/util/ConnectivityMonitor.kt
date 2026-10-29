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

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.pingolin.note.worker.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes connectivity transitions and kicks off an expedited sync the
 * moment the device comes back online — so a note edited offline
 * doesn't wait up to 15 minutes (the periodic worker interval) for the
 * push to land.
 *
 * Sync state is read directly from SyncWorker's dirty-row scan, so no
 * queue monitor is notified of online/offline transitions.
 *
 * Call [start] from `NoteApplication.onCreate` (once per process). The
 * NetworkCallback survives until the process dies; no [stop] is needed
 * for the application-lifetime scope.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "ConnectivityMonitor"
    }

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val wasOnline = _isOnline.value
            _isOnline.value = true
            Log.i(TAG, "Network available (wasOnline=$wasOnline)")
            // Expedited flush: don't wait for the periodic worker tick.
            // enqueueImmediateSync uses ExistingWorkPolicy.REPLACE so
            // rapid onAvailable oscillations coalesce to a single job.
            SyncWorker.enqueueImmediateSync(context)
        }

        override fun onLost(network: Network) {
            _isOnline.value = hasAnyNetwork()
            Log.i(TAG, "Network lost (stillOnline=${_isOnline.value})")
        }
    }

    private var registered = false

    fun start() {
        if (registered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            registered = true
            _isOnline.value = hasAnyNetwork()
            Log.i(TAG, "NetworkCallback registered (initialOnline=${_isOnline.value})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkCallback", e)
        }
    }

    private fun hasAnyNetwork(): Boolean {
        return try {
            val active = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }
}
