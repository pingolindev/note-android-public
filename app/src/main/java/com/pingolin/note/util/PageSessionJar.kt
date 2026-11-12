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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The session pair the page's cookie jar holds: its `auth_token`, and the `refresh_token` the auth
 * server sets with it. The one refresher reads it before it picks a refresh token
 * (TokenAuthenticator.refreshSession): the page can hold a later pair of the stored session, as
 * the account portal, run in the shell's WebView, refreshes on every load, and then the stored
 * refresh token is the session's older one.
 *
 * Installed by the shell from the main thread, at process start (NoteApplication posts it, so
 * CookieManager starts after Application.onCreate rather than in it) and again when the note
 * screen opens. A process's first refreshes can come before that post runs (the sync queued at
 * start, a periodic one in a process that never shows the page), so a read waits for an install
 * that is on its way, briefly; one that isn't coming (nothing expected it, as in a test) or
 * can't (the WebView could not start) is no jar at all.
 *
 * And the other way: after a refresh, the refresher writes the new pair into the jar at once
 * ([write]), so a page that stays long (the portal, Track web) never holds a token the server has
 * since retired.
 */
object PageSessionJar {

    /** The pair as the jar holds it; either half null when it holds none. */
    data class Held(val accessToken: String?, val refreshToken: String?)

    private val slot = Slot()

    /** The shell's install is on its way: a read until then waits for it ([read]'s wait). */
    fun expectInstall() = slot.expectInstall()

    /** Install [read] (one read of both cookies) and [write] (the last install wins). */
    fun install(read: () -> Held, write: (accessToken: String, refreshToken: String) -> Unit = { _, _ -> }) =
        slot.install(read, write)

    /** No jar in this process after all (the WebView could not start): nothing waits for one. */
    fun unavailable() = slot.unavailable()

    /** The jar's pair, waiting at most [waitMs] for an expected install; null when there is none. */
    fun read(waitMs: Long = 0): Held? = slot.read(waitMs)

    /** Write a refreshed pair into the jar; nothing when there is none. */
    fun write(accessToken: String, refreshToken: String) = slot.write(accessToken, refreshToken)

    /** The state of one jar, apart from the object's so it can be tested on its own. */
    class Slot {
        /** Released once the jar is installed, or found unavailable. */
        private val settled = CountDownLatch(1)

        @Volatile
        private var expected = false

        @Volatile
        private var reader: (() -> Held)? = null

        @Volatile
        private var writer: ((String, String) -> Unit)? = null

        fun expectInstall() {
            expected = true
        }

        fun install(read: () -> Held, write: (String, String) -> Unit = { _, _ -> }) {
            writer = write
            reader = read
            settled.countDown()
        }

        fun write(accessToken: String, refreshToken: String) {
            try {
                writer?.invoke(accessToken, refreshToken)
            } catch (e: Exception) {
                // The jar is the page's copy; the store holds the pair either way.
            }
        }

        fun unavailable() {
            settled.countDown()
        }

        fun read(waitMs: Long): Held? {
            if (reader == null && expected && waitMs > 0) {
                try {
                    settled.await(waitMs, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            return try {
                reader?.invoke()
            } catch (e: Exception) {
                null
            }
        }
    }
}
