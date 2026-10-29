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

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and caches the static provider list from
 * `assets/import_providers.json` (regenerated from the mobile SDK).
 *
 * The JSON shape is locked to the Rust serde output. If the Rust definition
 * changes structurally, regenerate the file from the SDK and update the
 * parser below in lockstep.
 */
@Singleton
class ProviderSpecRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cached: List<ProviderSpec> by lazy { load() }

    fun all(): List<ProviderSpec> = cached
    fun byId(id: String): ProviderSpec? = cached.firstOrNull { it.id == id }
    fun bySection(section: ImportSection): List<ProviderSpec> =
        cached.filter { it.section == section }

    private fun load(): List<ProviderSpec> {
        val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val array = JsonParser.parseString(raw) as JsonArray
        return array.map { parseSpec(it.asJsonObject) }
    }

    private fun parseSpec(obj: JsonObject): ProviderSpec = ProviderSpec(
        id = obj["id"].asString,
        displayName = obj["display_name"].asString,
        section = when (obj["section"].asString) {
            "file" -> ImportSection.FILE
            "connect" -> ImportSection.CONNECT
            else -> error("Unknown section: ${obj["section"]}")
        },
        authMethod = parseAuthMethod(obj["auth_method"].asJsonObject),
        iconName = obj["icon_name"].asString,
        brandColor = obj["brand_color"].asString,
        instructionsMarkdown = obj["instructions_markdown"].asString,
        caveats = obj["caveats"].asJsonArray.map { it.asString },
    )

    private fun parseAuthMethod(obj: JsonObject): AuthMethod {
        return when (val kind = obj["kind"].asString) {
            "file_upload" -> AuthMethod.FileUpload(
                mimeTypes = obj["mime_types"].asJsonArray.map { it.asString },
            )
            "takeout_link" -> AuthMethod.TakeoutLink(
                externalUrl = obj["external_url"].asString,
                mimeTypes = obj["mime_types"].asJsonArray.map { it.asString },
            )
            "o_auth2_pkce" -> {
                // serde with #[serde(tag="kind", rename_all="snake_case")]
                // turns the tuple variant OAuth2Pkce(OAuthConfig) into a
                // flat object with the OAuthConfig fields alongside
                // "kind": "o_auth2_pkce".
                AuthMethod.OAuth2Pkce(
                    OAuthConfig(
                        authorizationUrl = obj["authorization_url"].asString,
                        tokenUrl = obj["token_url"].asString,
                        scopes = obj["scopes"].asJsonArray.map { it.asString },
                        clientIdEnvKey = obj["client_id_env_key"].asString,
                        redirectPath = obj["redirect_path"].asString,
                    )
                )
            }
            "credentials" -> AuthMethod.Credentials(
                fields = obj["fields"].asJsonArray.map { fieldEl ->
                    val f = fieldEl.asJsonObject
                    CredentialField(
                        key = f["key"].asString,
                        label = f["label"].asString,
                        placeholder = f["placeholder"].asString,
                        secret = f["secret"].asBoolean,
                    )
                },
            )
            else -> error("Unknown auth_method kind: $kind")
        }
    }

    companion object {
        private const val ASSET_NAME = "import_providers.json"
    }
}
