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

import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.PublicKeyLookupEntry
import com.pingolin.note.data.remote.dto.PublicKeyLookupResponse
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response as RetrofitResponse

/**
 * The member fan-out: the notebook's owner comes from the inbox only for a notebook opened
 * through an accepted share, never for one our vault opens — a notebook that is ours has
 * nobody above it, whatever the inbox serves.
 */
class NotebookShareFanOutRosterTest {

    private val noteApi = mockk<NoteApi>()
    private val roster = mockk<NotebookRoster>()
    private val notebook = "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val vault = ByteArray(32) { 5 }
    private val member = NotebookRoster.Member(
        userId = "2a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", email = "cara@x.y",
        sharingPublicKey = "ee".repeat(32), signingPublicKey = "ff".repeat(32), permission = "edit",
    )
    private lateinit var fanOut: NotebookShareFanOut

    @Before
    fun setUp() {
        fanOut = NotebookShareFanOut(noteApi, mockk<NoteCrypto>(), mockk<SessionManager>(), mockk<SenderKeyPinStore>(), roster)
        coEvery { roster.members(notebook, vault) } returns listOf(member)
        coEvery { noteApi.lookupPublicKeys(any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = PublicKeyLookupResponse(listOf(PublicKeyLookupEntry("mallory@x.y", "3a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "aa".repeat(32), "bb".repeat(32)))),
            ),
        )
    }

    @Test
    fun `a notebook our vault opens takes no owner from the inbox`() = runTest {
        coEvery { roster.contentKey(notebook, vault) } returns ByteArray(32) { 1 }
        val out = fanOut.rosterRecipientsFor(notebook, vault, ownerEmail = "mallory@x.y", myEmail = "me@x.y")
        assertEquals(listOf("cara@x.y"), out.map { it.email })
        coVerify(exactly = 0) { noteApi.lookupPublicKeys(any()) }
    }

    @Test
    fun `a notebook shared with us takes its owner, looked up`() = runTest {
        coEvery { roster.contentKey(notebook, vault) } returns null
        val out = fanOut.rosterRecipientsFor(notebook, vault, ownerEmail = "mallory@x.y", myEmail = "me@x.y")
        assertEquals(listOf("cara@x.y", "mallory@x.y"), out.map { it.email })
    }
}
