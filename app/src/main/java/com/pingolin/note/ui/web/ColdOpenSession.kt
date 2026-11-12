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

package com.pingolin.note.ui.web

import com.pingolin.note.util.AccessTokenExpiry
import com.pingolin.note.util.TokenAuthenticator.RefreshOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A cold open's session, made good BEFORE the page loads (owner: users are never asked for their
 * password).
 *
 * WHY THIS EXISTS
 *   A native access token lives 15 minutes. A cold open more than that after the app's last
 *   refresh seeded the page a dead token: the page's boot asks `/auth/me` (and once more), and on
 *   the 401 signs out to the account portal, whose sign-in asks for the password. The app's own
 *   refresh (TokenAuthenticator, on the first sync's 401) came about a second too late: that sync
 *   starts only once the page has loaded.
 *
 * So when the page will BOOT (no document is booted in this process; a booted one doesn't check
 * again) on a stored token that has expired, expires within a minute, or can't be read, the shell
 * refreshes natively first, through TokenAuthenticator's one refresher, off the main thread and
 * for at most [WAIT_MS], while the load is held ([HeldLoad]). Then:
 *  - refreshed: the fresh pair is seeded, and the page loads;
 *  - refused (the session is over, and its credentials are already cleared): the native sign-in,
 *    never the portal;
 *  - no answer in time (offline, slow, a 5xx): what is stored is seeded, and the page loads as it
 *    always has (its offline path). A refresh still running lands for the next request.
 * Before any refresh, a later pair of the same session in the page's cookie jar is adopted instead,
 * inside the one refresher (TokenAuthenticator.refreshSession), as for every refresh.
 */
internal object ColdOpenSession {

    /** How long a cold open waits for its refresh. */
    const val WAIT_MS = 5_000L

    /** What the cold open does once its session is settled. */
    enum class Next { LOAD, SIGN_IN }

    /** Whether the page's load must wait for a native refresh of [accessToken]. */
    fun mustRefreshFirst(booted: Boolean, accessToken: String?, nowSeconds: Long): Boolean =
        !booted && AccessTokenExpiry.needsRefresh(accessToken, nowSeconds)

    /**
     * Refresh the stored session, stale in [staleToken], through [refresh] (the shared refresher,
     * TokenAuthenticator.refreshSession: blocking, waiting at most the time it is given), on [io],
     * and say what comes next. A refresh that hasn't answered by [boundMs] is no answer: the load
     * goes on.
     */
    suspend fun settle(
        staleToken: String?,
        refresh: (staleToken: String?, waitMs: Long) -> RefreshOutcome,
        io: CoroutineDispatcher = Dispatchers.IO,
        boundMs: Long = WAIT_MS + 1_000L,
    ): Next {
        val outcome = withTimeoutOrNull(boundMs) {
            runInterruptible(io) { refresh(staleToken, WAIT_MS) }
        } ?: RefreshOutcome.UNREACHABLE
        return if (outcome == RefreshOutcome.REFUSED) Next.SIGN_IN else Next.LOAD
    }

    /**
     * Restore the WebView's saved history, or, when it holds no page, load the intent's instead
     * ([navigate]). A state saved while a cold open held its first load holds none: the WebView
     * never navigated, so a recreation during the hold (a theme flip, a font scale, split screen)
     * or a process death and a return from Recents would otherwise restore nothing and leave the
     * screen blank until Back. [restore] gives how many entries it restored, or null for none.
     * True when it restored.
     */
    fun restoreOrNavigate(restore: () -> Int?, navigate: () -> Unit): Boolean {
        val restored = restore() ?: 0
        if (restored > 0) return true
        navigate()
        return false
    }
}

/**
 * The page load a cold open holds while its session is settled ([ColdOpenSession]): every load
 * asked for meanwhile replaces the one kept, and the one kept goes when it is released. Main
 * thread only.
 */
internal class HeldLoad {

    private var held = false
    private var kept: (() -> Unit)? = null

    val holding: Boolean get() = held

    fun hold() {
        held = true
    }

    /** Run [load] now, or, while held, keep it (instead of any load kept before) for [release]. */
    fun run(load: () -> Unit) {
        if (held) kept = load else load()
    }

    /** Stop holding, and run the load kept, if any. */
    fun release() {
        held = false
        val load = kept
        kept = null
        load?.invoke()
    }

    /** Stop holding, and forget the load kept: nothing loads (the activity is leaving). */
    fun drop() {
        held = false
        kept = null
    }
}

/**
 * The screen's one way out when its session ends: the native sign-in, asked for ONCE. Several can
 * ask (the login state's watcher, a refused refresh on a page request, the cold open, the page's
 * own sign-out), and two of them must never stack two sign-in screens, whatever their order. Main
 * thread only.
 */
internal class SignInOnce {

    private var left = false

    /** True the first time it is asked: go. False after: the screen already went. */
    fun leave(): Boolean {
        if (left) return false
        left = true
        return true
    }
}
