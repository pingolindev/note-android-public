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

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.pingolin.note.data.remote.dto.InboxShareDto

/**
 * What the inbox request answers with in tests.
 *
 * [bareList] is the answer of a server that predates `has_more`: the rows alone. Most inbox tests
 * are about what is made of the rows, and serve them this way.
 *
 * The two texts below are the server's answer BYTE FOR BYTE, as the note server serialises it.
 * They are here because a fixture built from this app's own DTO passes whatever the server calls
 * its fields. Keep them identical to what the server sends.
 */
fun bareList(rows: List<InboxShareDto>): JsonElement = Gson().toJsonTree(rows)

/** A page that says more follows, or that this is the end. It says nothing of declined notebooks. */
fun inboxPage(rows: List<InboxShareDto>, hasMore: Boolean): JsonElement =
    JsonParser.parseString("""{"shares":${Gson().toJson(rows)},"has_more":$hasMore}""")

/** A first page that also says which notebooks the account has declined: id → since when. */
fun inboxPage(rows: List<InboxShareDto>, hasMore: Boolean, declined: Map<String, String>): JsonElement {
    val list = declined.map { (id, at) -> mapOf("id" to id, "declined_at" to at) }
    return JsonParser.parseString(
        """{"shares":${Gson().toJson(rows)},"has_more":$hasMore,"declined_notebooks":${Gson().toJson(list)}}""",
    )
}

/** A first page that also says which notebooks are ON OFFER to the account (and, when given, which it declined). */
fun inboxPageOffering(
    rows: List<InboxShareDto>,
    hasMore: Boolean,
    offered: List<String>,
    declined: Map<String, String>? = null,
): JsonElement {
    val page = JsonParser.parseString("""{"shares":${Gson().toJson(rows)},"has_more":$hasMore}""").asJsonObject
    page.add("offered_notebooks", Gson().toJsonTree(offered))
    if (declined != null) {
        page.add("declined_notebooks", Gson().toJsonTree(declined.map { (id, at) -> mapOf("id" to id, "declined_at" to at) }))
    }
    return page
}

const val PAGE_ON_THE_WIRE =
    """{"success":true,"data":{"shares":[{"share_id":"5a5a5a5a-0000-4000-8000-000000000001","item_id":"6b6b6b6b-0000-4000-8000-000000000001","owner_id":"22222222-2222-4222-8222-222222222222","permission":"edit","wrapped_key":"d3JhcHBlZA==","content_key_generation":3,"sharer_public_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","owner_email":"sender@example.com","wrap_signature":null,"item_type":2,"encrypted_content":"Y2lwaGVy","content_nonce":"bm9uY2U=","updated_at":"2026-10-07T01:02:03.456789Z","created_at":"2026-10-07T01:02:03.456789Z","owner_directory_user_id":"22222222-2222-4222-8222-222222222222","owner_directory_sharing_key":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}],"has_more":true,"declined_notebooks":[{"id":"99999999-1111-4000-8000-000000000009","declined_at":"2026-10-06T23:00:00.123456Z"}]}}"""

const val BARE_LIST_ON_THE_WIRE =
    """{"success":true,"data":[{"share_id":"5a5a5a5a-0000-4000-8000-000000000001","item_id":"6b6b6b6b-0000-4000-8000-000000000001","owner_id":"22222222-2222-4222-8222-222222222222","permission":"edit","wrapped_key":"d3JhcHBlZA==","content_key_generation":3,"sharer_public_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","owner_email":"sender@example.com","wrap_signature":null,"item_type":2,"encrypted_content":"Y2lwaGVy","content_nonce":"bm9uY2U=","updated_at":"2026-10-07T01:02:03.456789Z","created_at":"2026-10-07T01:02:03.456789Z"}]}"""

/**
 * A first page that names the notebooks on offer, and one that says none is: byte for byte what
 * the note server serialises. Keep them identical to what the server sends.
 */
const val OFFERED_ON_THE_WIRE =
    """{"success":true,"data":{"shares":[],"has_more":false,"declined_notebooks":[],"offered_notebooks":["6b6b6b6b-0000-4000-8000-000000000001","6b6b6b6b-0000-4000-8000-000000000002"]}}"""

const val NONE_OFFERED_ON_THE_WIRE =
    """{"success":true,"data":{"shares":[],"has_more":false,"offered_notebooks":[]}}"""
