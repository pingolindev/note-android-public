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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Manages the SQLCipher database passphrase.
 * Generates a random 256-bit passphrase on first launch, stored in Android Keystore.
 */
object DatabasePassphraseManager {

    private const val PREF_NAME = "note_db_key"
    private const val KEY_PASSPHRASE = "db_passphrase"

    fun getOrCreatePassphrase(context: Context): ByteArray {

        val prefs = SecurePrefs.open(context, PREF_NAME)

        val existing = prefs.getString(KEY_PASSPHRASE, null)
        if (existing != null) {
            return android.util.Base64.decode(existing, android.util.Base64.NO_WRAP)
        }

        // Generate new 256-bit passphrase
        val passphrase = ByteArray(32)
        SecureRandom().nextBytes(passphrase)

        prefs.edit()
            .putString(KEY_PASSPHRASE, android.util.Base64.encodeToString(passphrase, android.util.Base64.NO_WRAP))
            .apply()

        return passphrase
    }
}
