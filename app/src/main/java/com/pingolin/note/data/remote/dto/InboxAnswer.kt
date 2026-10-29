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

package com.pingolin.note.data.remote.dto

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParseException

/**
 * One answer of `GET /api/v1/shares/inbox`, read from either of the server's two shapes:
 *
 *  - `{ "shares": [...], "has_more": true|false, "declined_notebooks": [{ "id", "declined_at" }],
 *    "offered_notebooks": ["id", …] }`, which the server sends when asked with `page_info=true`;
 *  - the bare list `[...]`, from a server that predates that and ignores the parameter.
 *
 * WHERE THE INBOX ENDS is the server's to say, in `has_more`. Inferring it — taking a page
 * shorter than this app's own copy of the server's page limit for the last one — fails when that
 * copy goes stale: an app whose copy is larger than the server's limit takes every full page for
 * the whole inbox, and one sync then removes every share past the first page. The inference is
 * kept for the bare list only, where there is nothing better.
 *
 * [declined] is the notebooks this account has declined, and since when; see
 * SharesInboxRepository, which keeps a declined notebook's later notes out of the local database.
 * [offered] is every notebook on offer to it; see NotebooksOnOffer.
 *
 * Read by hand, row by row: an answer in neither shape, or a row that is no row, is a page that
 * was not read, and that has to be THIS code's decision rather than a converter's exception. (The
 * rows themselves are read by Gson into [InboxShareDto], whose field names and the generic
 * signature of the API's return type are kept in a minified build by proguard-rules.pro.)
 */
data class InboxAnswer(
    val shares: List<InboxShareDto>,
    /** Older shares follow this page. */
    val more: Boolean,
    /**
     * Notebook id → the time of its decline, as the server wrote both. Null when the answer does
     * not say: an older server, a page after the first (it describes the inbox, so only the first
     * page carries it), or the server's own lookup failing. Null is "not told", never "none".
     */
    val declined: Map<String, String>? = null,
    /**
     * EVERY notebook on offer to this account, accepted or not, as the server wrote the ids. Null
     * when the answer does not say (as for [declined]; the server also leaves the list out when
     * it would not be whole). Null is "not told", never "none".
     */
    val offered: Set<String>? = null,
) {
    companion object {
        private val gson = Gson()

        /**
         * @param legacyPageLimit the page size to infer "more" from when the server sent the bare list
         * @return null when [data] is in neither shape or one of its rows cannot be read. NEVER an
         *         empty inbox: an empty inbox read to its end means every share was withdrawn, and
         *         the rows removed on that must not rest on an answer this could not read.
         */
        fun read(data: JsonElement?, legacyPageLimit: Int): InboxAnswer? = try {
            when {
                data == null -> null
                data.isJsonArray -> rows(data).let { InboxAnswer(it, more = it.size >= legacyPageLimit) }
                data.isJsonObject -> {
                    val page = data.asJsonObject
                    val shares = page.get("shares")
                    val more = page.get("has_more")
                    if (shares == null || !shares.isJsonArray) null
                    else if (more == null || !more.isJsonPrimitive || !more.asJsonPrimitive.isBoolean) null
                    else InboxAnswer(
                        rows(shares),
                        more = more.asBoolean,
                        declined = declined(page.get("declined_notebooks")),
                        offered = offered(page.get("offered_notebooks")),
                    )
                }
                else -> null
            }
        } catch (_: JsonParseException) {
            null
        }

        /**
         * The fields every row carries (the server's columns are NOT NULL, and it always sends
         * them). Gson fills a Kotlin non-null property with null when its field is missing, and
         * the first Kotlin function that is handed it throws: one such row would make the whole
         * refresh throw, so that no shared note is written or updated while it is served. A row
         * without one of these is no row, and by this file's rule the page is then not read:
         * nothing is opened, pruned or removed on it.
         */
        private val REQUIRED = listOf(
            "share_id", "item_id", "owner_id", "permission",
            "encrypted_content", "content_nonce", "updated_at", "created_at",
        )

        private fun rows(list: JsonElement): List<InboxShareDto> = list.asJsonArray.map { row ->
            if (!row.isJsonObject) throw JsonParseException("an inbox row that is no object")
            for (field in REQUIRED) {
                val value = row.asJsonObject.get(field)
                if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                    throw JsonParseException("an inbox row without its $field")
                }
            }
            gson.fromJson(row, InboxShareDto::class.java) ?: throw JsonParseException("an inbox row that is null")
        }

        /**
         * The notebooks on offer, or null when the page does not say. The WHOLE list or nothing:
         * unlike the declined list, one entry that is no id makes it null. What is missing from
         * this list is read as "not on offer", and its notes are then shown; a list with a hole
         * looks whole and is not. It never costs the page its shares.
         */
        private fun offered(list: JsonElement?): Set<String>? {
            if (list == null || !list.isJsonArray) return null
            val out = LinkedHashSet<String>()
            for (entry in list.asJsonArray) {
                val id = entry.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (id.isNullOrBlank()) return null
                out += id
            }
            return out
        }

        /**
         * The declined list, or null when the page carries none. An entry that is not an id with a
         * time is left out: it says nothing usable, and must not cost the page its shares.
         */
        private fun declined(list: JsonElement?): Map<String, String>? {
            if (list == null || !list.isJsonArray) return null
            val out = LinkedHashMap<String, String>()
            for (entry in list.asJsonArray) {
                if (!entry.isJsonObject) continue
                val id = entry.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                val at = entry.asJsonObject.get("declined_at")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (!id.isNullOrBlank() && !at.isNullOrBlank()) out[id] = at
            }
            return out
        }
    }
}
