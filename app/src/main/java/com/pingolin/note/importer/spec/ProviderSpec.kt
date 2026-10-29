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

package com.pingolin.note.importer.spec

/**
 * Kotlin mirror of the canonical Rust `ProviderSpec` in the mobile
 * SDK (its import-providers module). The list is shipped as
 * `assets/import_providers.json`, regenerated from the SDK's provider
 * specs; never hand-edit either file in isolation.
 */
data class ProviderSpec(
    val id: String,
    val displayName: String,
    val section: ImportSection,
    val authMethod: AuthMethod,
    val iconName: String,
    /** Hex string like "#FBBC04" that tints the card icon. */
    val brandColor: String,
    val instructionsMarkdown: String,
    val caveats: List<String>,
)

enum class ImportSection { FILE, CONNECT }

sealed class AuthMethod {
    data class FileUpload(val mimeTypes: List<String>) : AuthMethod()
    data class TakeoutLink(
        val externalUrl: String,
        val mimeTypes: List<String>,
    ) : AuthMethod()
    data class OAuth2Pkce(val config: OAuthConfig) : AuthMethod()
    data class Credentials(val fields: List<CredentialField>) : AuthMethod()
}

data class OAuthConfig(
    val authorizationUrl: String,
    val tokenUrl: String,
    val scopes: List<String>,
    /** BuildConfig field name holding the client ID for this build flavor. */
    val clientIdEnvKey: String,
    /** Path appended to the note frontend origin to form the redirect URI. */
    val redirectPath: String,
)

data class CredentialField(
    val key: String,
    val label: String,
    val placeholder: String,
    val secret: Boolean,
)
