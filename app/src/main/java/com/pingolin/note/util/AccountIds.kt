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
 * An id as this app keeps and compares it: the lower-case, hyphenated 8-4-4-4-12 form of a UUID's 16
 * bytes, whatever spelling the server sent. Account ids and item ids both go through here: the SDK
 * binds each one's parsed bytes into a signature, so a store or a lookup keyed by the raw string
 * would miss for another spelling of the same id.
 *
 * [canonical] reads every spelling the SDK's parser (uuid 1.x) takes: 36 characters in any case, 32
 * hex, `{…}` and `urn:uuid:…`. Anything else (whitespace, any other string) is no id: an ERROR, never
 * another id and never "not found". [canonical] returns null only for call sites that then refuse;
 * the stores call [parse], which throws. The same parser as the Track Android app's.
 */
object AccountIds {

    private val HYPHENATED = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val PLAIN = Regex("[0-9a-fA-F]{32}")
    private const val URN = "urn:uuid:"

    /** A value that is not a UUID in any spelling the SDK takes. */
    class NotAnAccountId : IllegalArgumentException("not an id")

    /** [raw]'s canonical form, or null when it isn't a UUID in any spelling the SDK takes. */
    fun canonical(raw: String?): String? = when {
        raw == null -> null
        // 36 characters inside either wrapper: only the hyphenated form fits.
        raw.length == 38 && raw.startsWith('{') && raw.endsWith('}') -> strict(raw.substring(1, 37))
        raw.length == 45 && raw.startsWith(URN) -> strict(raw.substring(URN.length))
        else -> strict(raw)
    }

    /** [raw]'s canonical form; [NotAnAccountId] when it has none. */
    fun parse(raw: String?): String = canonical(raw) ?: throw NotAnAccountId()

    /** [raw]'s canonical form when it is 8-4-4-4-12 hex in any case, or 32 hex; null for anything else. */
    fun strict(raw: String?): String? {
        val hex = when {
            raw == null -> return null
            HYPHENATED.matches(raw) -> raw.replace("-", "")
            PLAIN.matches(raw) -> raw
            else -> return null
        }.lowercase()
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /** Whether [a] and [b] spell the same id: both UUIDs, with the same bytes. */
    fun same(a: String?, b: String?): Boolean {
        val one = canonical(a) ?: return false
        return one == canonical(b)
    }
}
