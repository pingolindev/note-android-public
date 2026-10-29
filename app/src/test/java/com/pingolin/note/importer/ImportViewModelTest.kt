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

package com.pingolin.note.importer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.note.importer.model.ImportSource
import com.pingolin.note.importer.spec.AuthMethod
import com.pingolin.note.importer.spec.ImportSection
import com.pingolin.note.importer.spec.ProviderSpec
import com.pingolin.note.importer.spec.ProviderSpecRegistry
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var app: Application

    @MockK
    private lateinit var importRepository: ImportRepository

    @MockK
    private lateinit var contentResolver: ContentResolver

    @MockK
    private lateinit var uri: Uri

    @MockK
    private lateinit var providerRegistry: ProviderSpecRegistry

    private lateinit var viewModel: ImportViewModel

    private val keepSpec = ProviderSpec(
        id = "google_keep",
        displayName = "Google Keep",
        section = ImportSection.FILE,
        authMethod = AuthMethod.TakeoutLink(
            externalUrl = "https://example.com/takeout",
            mimeTypes = listOf("application/zip"),
        ),
        iconName = "ic_provider_google_keep",
        brandColor = "#FBBC04",
        instructionsMarkdown = "",
        caveats = emptyList(),
    )

    private val evernoteSpec = ProviderSpec(
        id = "evernote_file",
        displayName = "Evernote (ENEX)",
        section = ImportSection.FILE,
        authMethod = AuthMethod.FileUpload(mimeTypes = listOf("application/xml")),
        iconName = "ic_provider_evernote",
        brandColor = "#00A82D",
        instructionsMarkdown = "",
        caveats = emptyList(),
    )

    private val plainTextSpec = ProviderSpec(
        id = "plain_text",
        displayName = "Text / Markdown",
        section = ImportSection.FILE,
        authMethod = AuthMethod.FileUpload(mimeTypes = listOf("text/plain")),
        iconName = "ic_provider_plain_text",
        brandColor = "#CBA6F7",
        instructionsMarkdown = "",
        caveats = emptyList(),
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { app.contentResolver } returns contentResolver

        every { providerRegistry.byId("google_keep") } returns keepSpec
        every { providerRegistry.byId("evernote_file") } returns evernoteSpec
        every { providerRegistry.byId("plain_text") } returns plainTextSpec
        every { providerRegistry.byId("unknown") } returns null

        mockkObject(com.pingolin.note.worker.SyncWorker.Companion)
        every { com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(any()) } just Runs

        mockkObject(com.pingolin.note.widget.WidgetUpdateHelper)
        every { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(any()) } just Runs

        viewModel = ImportViewModel(app, importRepository, providerRegistry)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `initialState_isSelectSource`() {
        assertTrue(viewModel.state.value is ImportUiState.SelectSource)
    }

    @Test
    fun `selectProvider_googleKeep_showsWalkthrough`() {
        viewModel.selectProvider("google_keep")

        val state = viewModel.state.value
        assertTrue("expected walkthrough, got $state", state is ImportUiState.ProviderWalkthrough)
        assertEquals("google_keep", (state as ImportUiState.ProviderWalkthrough).spec.id)
    }

    @Test
    fun `continueFromWalkthrough_fileUploadProvider_transitionsToSelectFile`() {
        viewModel.selectProvider("evernote_file")
        viewModel.continueFromWalkthrough()

        val state = viewModel.state.value
        assertTrue(state is ImportUiState.SelectFile)
        assertEquals(listOf("application/xml"), (state as ImportUiState.SelectFile).mimeTypes)
    }

    @Test
    fun `continueFromWalkthrough_takeoutProvider_transitionsToOpenExternalUrl`() {
        viewModel.selectProvider("google_keep")
        viewModel.continueFromWalkthrough()

        val state = viewModel.state.value
        assertTrue(state is ImportUiState.OpenExternalUrl)
        assertEquals("https://example.com/takeout", (state as ImportUiState.OpenExternalUrl).url)
    }

    @Test
    fun `selectProvider_unknownId_emitsError`() {
        viewModel.selectProvider("unknown")

        val state = viewModel.state.value
        assertTrue(state is ImportUiState.Error)
    }

    @Test
    fun `returnedFromTakeout_transitionsToSelectFile`() {
        viewModel.selectProvider("google_keep")
        viewModel.returnedFromTakeout()

        val state = viewModel.state.value
        assertTrue(state is ImportUiState.SelectFile)
        assertEquals(listOf("application/zip"), (state as ImportUiState.SelectFile).mimeTypes)
    }

    @Test
    fun `onFilePicked_withoutSelecting_doesNothing`() = runTest {
        viewModel.onFilePicked(uri)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is ImportUiState.SelectSource)
    }

    @Test
    fun `onFilePicked_routesPlainTextSpecToPlainTextSource`() = runTest {
        viewModel.selectProvider("plain_text")

        every {
            importRepository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT)
        } returns flowOf(ImportState.Idle, ImportState.Complete(1))

        viewModel.onFilePicked(uri)
        advanceUntilIdle()

        verify {
            importRepository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT)
        }
        val state = viewModel.state.value
        assertTrue(state is ImportUiState.Complete)
        assertEquals(1, (state as ImportUiState.Complete).count)
    }

    @Test
    fun `onFilePicked_routesEvernoteSpecToEvernoteSource`() = runTest {
        viewModel.selectProvider("evernote_file")

        every {
            importRepository.importNotes(contentResolver, uri, ImportSource.EVERNOTE)
        } returns flowOf(ImportState.Idle, ImportState.Error("Parse error"))

        viewModel.onFilePicked(uri)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is ImportUiState.Error)
        assertEquals("Parse error", (state as ImportUiState.Error).message)
    }

    @Test
    fun `onFilePicked_importIdle_transitionsToImporting`() = runTest {
        viewModel.selectProvider("plain_text")

        every {
            importRepository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT)
        } returns flowOf(ImportState.Idle)

        viewModel.onFilePicked(uri)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is ImportUiState.Importing)
    }

    @Test
    fun `onFilePicked_complete_triggersSyncAndWidgetUpdate`() = runTest {
        viewModel.selectProvider("plain_text")

        every {
            importRepository.importNotes(contentResolver, uri, ImportSource.PLAIN_TEXT)
        } returns flowOf(ImportState.Idle, ImportState.Complete(3))

        viewModel.onFilePicked(uri)
        advanceUntilIdle()

        verify { com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(app) }
        verify { com.pingolin.note.widget.WidgetUpdateHelper.notifyDataChanged(app) }
    }

    @Test
    fun `reset_returnsToSelectSource`() {
        viewModel.selectProvider("google_keep")
        assertTrue(viewModel.state.value is ImportUiState.ProviderWalkthrough)

        viewModel.reset()
        assertTrue(viewModel.state.value is ImportUiState.SelectSource)
    }

    @Test
    fun `reset_clearsSelectedSpec`() = runTest {
        viewModel.selectProvider("evernote_file")
        viewModel.reset()

        // After reset, onFilePicked should do nothing because selectedSpec is null.
        viewModel.onFilePicked(uri)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is ImportUiState.SelectSource)
    }
}
