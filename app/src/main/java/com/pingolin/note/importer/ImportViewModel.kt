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
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.note.importer.model.ImportSource
import com.pingolin.note.importer.spec.AuthMethod
import com.pingolin.note.importer.spec.ProviderSpec
import com.pingolin.note.importer.spec.ProviderSpecRegistry
import com.pingolin.note.widget.WidgetUpdateHelper
import com.pingolin.note.worker.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ImportUiState {
    /** Two-section provider grid. */
    data object SelectSource : ImportUiState()

    /** Per-provider explainer. Activity renders the walkthrough view and
     *  wires the primary CTA to one of the action emitters below. */
    data class ProviderWalkthrough(val spec: ProviderSpec) : ImportUiState()

    /** Launch the system file picker with `mimeTypes`. */
    data class SelectFile(val mimeTypes: List<String>) : ImportUiState()

    /** Launch an external URL (Google Takeout) then return to the
     *  walkthrough so the user can pick the downloaded file. */
    data class OpenExternalUrl(val url: String, val spec: ProviderSpec) : ImportUiState()

    data class Importing(val current: Int, val currentTitle: String?) : ImportUiState()
    data class Complete(val count: Int) : ImportUiState()
    data class Error(val message: String) : ImportUiState()
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val app: Application,
    private val importRepository: ImportRepository,
    private val providerRegistry: ProviderSpecRegistry,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.SelectSource)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private var selectedSpec: ProviderSpec? = null

    /** True while the picked file is a Pingolin backup rather than a provider
     *  export. Mutually exclusive with [selectedSpec] — a restore has no
     *  provider — and cleared by [reset] so a later import isn't misread as a
     *  restore, which would send it down the id-preserving path. */
    private var restoringBackup = false

    /** Tap on a provider card: show the per-provider walkthrough. */
    fun selectProvider(providerId: String) {
        val spec = providerRegistry.byId(providerId)
        if (spec == null) {
            _state.value = ImportUiState.Error("Unknown provider: $providerId")
            return
        }
        selectedSpec = spec
        _state.value = ImportUiState.ProviderWalkthrough(spec)
    }

    /** Primary CTA on the walkthrough. Routes by AuthMethod. */
    fun continueFromWalkthrough() {
        val spec = selectedSpec ?: return
        _state.value = when (val auth = spec.authMethod) {
            is AuthMethod.FileUpload -> ImportUiState.SelectFile(auth.mimeTypes)
            is AuthMethod.TakeoutLink -> ImportUiState.OpenExternalUrl(auth.externalUrl, spec)
            is AuthMethod.OAuth2Pkce,
            is AuthMethod.Credentials ->
                ImportUiState.Error(
                    "${spec.displayName} import requires the connected-account flow, " +
                        "which isn't wired up yet."
                )
        }
    }

    /** User returned from the Takeout deep link; show the file picker
     *  scoped to the takeout MIME types. */
    fun returnedFromTakeout() {
        val spec = selectedSpec ?: return
        val auth = spec.authMethod as? AuthMethod.TakeoutLink ?: return
        _state.value = ImportUiState.SelectFile(auth.mimeTypes)
    }

    /**
     * Restore a Pingolin backup.
     *
     * Deliberately NOT one of the providers: those describe other apps' formats
     * and are generated from a Rust spec shared with the web client, while a
     * restore is a different action — it brings back notebooks, label colours
     * and editor pins, none of which a foreign import has. So it gets its own
     * entry point and skips the provider walkthrough entirely.
     */
    fun startBackupRestore() {
        selectedSpec = null
        restoringBackup = true
        _state.value = ImportUiState.SelectFile(listOf("application/json"))
    }

    fun onFilePicked(uri: Uri) {
        val source = if (restoringBackup) {
            ImportSource.PINGOLIN_BACKUP
        } else {
            val spec = selectedSpec ?: return
            sourceForSpec(spec) ?: run {
                _state.value = ImportUiState.Error("No parser available for ${spec.displayName}")
                return
            }
        }
        viewModelScope.launch {
            importRepository.importNotes(
                contentResolver = app.contentResolver,
                uri = uri,
                source = source,
            ).collect(::applyImportState)
        }
    }

    fun reset() {
        selectedSpec = null
        restoringBackup = false
        _state.value = ImportUiState.SelectSource
    }

    private fun applyImportState(s: ImportState) {
        _state.value = when (s) {
            is ImportState.Idle -> ImportUiState.Importing(0, null)
            is ImportState.Importing -> ImportUiState.Importing(s.current, s.currentTitle)
            is ImportState.Complete -> {
                SyncWorker.enqueueImmediateSync(app)
                WidgetUpdateHelper.notifyDataChanged(app)
                ImportUiState.Complete(s.count)
            }
            is ImportState.Error -> ImportUiState.Error(s.message)
        }
    }

    private fun sourceForSpec(spec: ProviderSpec): ImportSource? = when (spec.id) {
        "google_keep" -> ImportSource.GOOGLE_KEEP
        "evernote_file" -> ImportSource.EVERNOTE
        "standard_notes_file" -> ImportSource.STANDARD_NOTES
        "plain_text" -> ImportSource.PLAIN_TEXT
        else -> null
    }
}
