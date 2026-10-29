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

/**
 * A trust store's file is there and can't be read now: the Keystore refused it, or its blob doesn't
 * parse. What it holds isn't known, so it is never "nothing stored": no pin reads as absent, no floor
 * as 0, nothing is written over it, and what it decides waits until it reads again (as in the Track
 * Android app).
 */
class StoreUnreadable(store: String, cause: Throwable? = null) : Exception("$store can't be read now", cause) {

    companion object {
        /** [read]'s value, or [StoreUnreadable] for [store] when it throws. */
        inline fun <T> reading(store: String, read: () -> T): T = try {
            read()
        } catch (e: StoreUnreadable) {
            throw e
        } catch (e: Exception) {
            throw StoreUnreadable(store, e)
        }
    }
}
