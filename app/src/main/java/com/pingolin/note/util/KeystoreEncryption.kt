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

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides AES-GCM encryption using Android Keystore.
 *
 * Security properties:
 * - AES-256-GCM provides authenticated encryption
 * - Key is stored in Android Keystore (hardware-backed when available)
 * - Each encryption uses a unique IV/nonce
 * - 128-bit authentication tag prevents tampering
 */
@Singleton
class KeystoreEncryption @Inject constructor() {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encryptedData: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)

        if (combined.size < GCM_IV_LENGTH + 16) {
            throw IllegalArgumentException(
                "Encrypted data too short: ${combined.size} bytes (minimum ${GCM_IV_LENGTH + 16})"
            )
        }

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    data class EncryptionResult(
        val nonce: String,
        val ciphertext: String
    )

    fun encryptWithPassword(plaintext: String, password: CharArray, salt: String): String {
        val pbkdfSalt = ByteArray(PBKDF2_SALT_LENGTH)
        SecureRandom().nextBytes(pbkdfSalt)

        val combinedSalt = salt.toByteArray(Charsets.UTF_8) + pbkdfSalt
        val passwordKey = deriveKey(password, combinedSalt)
        try {
            val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
            val expandedKey = hkdfExpand(passwordKey, plaintextBytes.size)

            val xored = ByteArray(plaintextBytes.size)
            for (i in plaintextBytes.indices) {
                xored[i] = (plaintextBytes[i].toInt() xor expandedKey[i].toInt()).toByte()
            }
            Arrays.fill(expandedKey, 0.toByte())

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

            val iv = cipher.iv
            val ciphertext = cipher.doFinal(xored)

            val combined = ByteArray(PBKDF2_SALT_LENGTH + iv.size + ciphertext.size)
            System.arraycopy(pbkdfSalt, 0, combined, 0, PBKDF2_SALT_LENGTH)
            System.arraycopy(iv, 0, combined, PBKDF2_SALT_LENGTH, iv.size)
            System.arraycopy(ciphertext, 0, combined, PBKDF2_SALT_LENGTH + iv.size, ciphertext.size)

            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } finally {
            Arrays.fill(passwordKey, 0.toByte())
        }
    }

    fun decryptWithPassword(encryptedData: String, password: CharArray, salt: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)

        if (combined.size < GCM_IV_LENGTH + 16) {
            throw IllegalArgumentException("Encrypted data too short")
        }

        val minNewFormatSize = PBKDF2_SALT_LENGTH + GCM_IV_LENGTH + 16
        if (combined.size < minNewFormatSize) {
            throw SecurityException("Legacy encryption format detected. Please re-authenticate.")
        }

        val pbkdfSalt = combined.copyOfRange(0, PBKDF2_SALT_LENGTH)
        val iv = combined.copyOfRange(PBKDF2_SALT_LENGTH, PBKDF2_SALT_LENGTH + GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(PBKDF2_SALT_LENGTH + GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

        val xored = cipher.doFinal(ciphertext)

        val combinedSalt = salt.toByteArray(Charsets.UTF_8) + pbkdfSalt
        val passwordKey = deriveKey(password, combinedSalt)
        try {
            val expandedKey = hkdfExpand(passwordKey, xored.size)
            val plaintext = ByteArray(xored.size)
            for (i in xored.indices) {
                plaintext[i] = (xored[i].toInt() xor expandedKey[i].toInt()).toByte()
            }
            Arrays.fill(expandedKey, 0.toByte())
            return String(plaintext, Charsets.UTF_8)
        } finally {
            Arrays.fill(passwordKey, 0.toByte())
        }
    }

    fun getCipherForBiometricAuth(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateBiometricKey())
        return cipher
    }

    fun getCipherForBiometricDecrypt(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateBiometricKey(), spec)
        return cipher
    }

    fun deleteKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    fun deleteBiometricKey() {
        if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
    }

    fun deleteAllKeys() {
        deleteKey()
        deleteBiometricKey()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        try {
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hkdfExpand(prk: ByteArray, length: Int): ByteArray {
        val hashLen = 32
        val n = (length + hashLen - 1) / hashLen
        require(n <= 255) { "HKDF-Expand: requested length too large" }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0

        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(i.toByte())
            t = mac.doFinal()

            val copyLen = minOf(hashLen, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
        }

        return okm
    }

    private fun getOrCreateKey(): SecretKey {
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private fun getOrCreateBiometricKey(): SecretKey {
        val existingKey = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val keySpec = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .apply {
                // `setUserAuthenticationParameters` is API 30
                // and minSdk is 26, so on Android 8.0-10
                // calling it throws NoSuchMethodError — and the
                // caller's `catch (e: Exception)` cannot catch
                // it, because NoSuchMethodError is an Error.
                // The app-lock unlock path (MainActivity ->
                // getCipherForBiometricAuth) would crash
                // outright on those releases instead of falling
                // back to the PIN.
                //
                // Both branches mean the same thing:
                // authenticate for EVERY use. A 0 timeout on
                // 30+, and -1 pre-30, which is that API's
                // spelling of per-operation auth and is
                // biometric-only. Any positive value would
                // leave the key usable for that many seconds
                // after one prompt.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "pingolin_note_key_encryption"
        private const val BIOMETRIC_KEY_ALIAS = "pingolin_note_biometric_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val PBKDF2_ITERATIONS = 210_000
        private const val KEY_SIZE_BITS = 256
        private const val PBKDF2_SALT_LENGTH = 16
    }
}
