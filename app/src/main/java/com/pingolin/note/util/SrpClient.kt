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

import android.util.Base64
import org.mindrot.jbcrypt.BCrypt
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

/**
 * SRP (Secure Remote Password) client implementation for zero-knowledge authentication.
 *
 * The password NEVER leaves the device. Instead:
 * 1. Login: Client proves knowledge of password without revealing it
 *
 * Uses 2048-bit group from RFC 5054 with SHA-256.
 * Matches the Rust `srp` crate formulas:
 * - M1 = H(A | B | K)
 * - K = S (padded)
 *
 * Includes Proton-style bcrypt preprocessing for extra key stretching.
 */
class SrpClient {

    companion object {
        // 2048-bit group from RFC 5054
        private val N_HEX =
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
            "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
            "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
            "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
            "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
            "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
            "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
            "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73"
        private val g_HEX = "02"

        private val N = BigInteger(N_HEX, 16)
        private val g = BigInteger(g_HEX, 16)
        private val N_BYTES = hexToBytes(N_HEX)

        private val random = SecureRandom()

        // BCrypt cost factor (matches Rust's DEFAULT_COST = 12)
        private const val BCRYPT_COST = 12

        // Compute k = H(N | PAD(g)) - RFC 5054 compliant
        private val k: BigInteger by lazy {
            val gPadded = padToLength(hexToBytes(g_HEX), N_BYTES.size)
            val kBytes = sha256(N_BYTES + gPadded)
            BigInteger(1, kBytes)
        }

        fun bcryptPreprocess(password: String, bcryptSalt: String): String {
            return BCrypt.hashpw(password, bcryptSalt)
        }

        /** Generate a bcrypt salt — `$2a$12$22charsalt`. */
        fun generateBcryptSalt(): String = BCrypt.gensalt(BCRYPT_COST)

        /**
         * Generate SRP registration credentials with bcrypt
         * preprocessing. Returns salt, verifier, and bcrypt_salt for
         * POST /auth/register. Mirrors the Track Android app's
         * SrpClient so the auth server treats both apps' new
         * accounts identically.
         *
         * SECURITY: clears the password CharArray after use.
         */
        fun generateRegistrationData(email: String, password: CharArray): SrpRegistrationData {
            val normalizedEmail = email.lowercase()
            val bcryptSalt = generateBcryptSalt()
            // Copy because bcryptPreprocess needs a String and we don't
            // want to leak the original CharArray contents — String
            // intern + GC outlive the explicit clear.
            val preprocessedPassword = bcryptPreprocess(String(password), bcryptSalt)
            clearCharArray(password)

            val saltBytes = ByteArray(32)
            random.nextBytes(saltBytes)

            val x = computeX(saltBytes, normalizedEmail, preprocessedPassword)
            val verifier = g.modPow(x, N)

            return SrpRegistrationData(
                salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP),
                verifier = Base64.encodeToString(bigIntToBytes(verifier), Base64.NO_WRAP),
                bcryptSalt = bcryptSalt,
            )
        }

        fun createSession(): SrpClientSession {
            return SrpClientSession()
        }

        /** Zero out a CharArray that held secret material. */
        fun clearCharArray(chars: CharArray) {
            Arrays.fill(chars, '\u0000')
        }

        /** Zero out a ByteArray that held secret material. */
        fun clearByteArray(bytes: ByteArray) {
            Arrays.fill(bytes, 0.toByte())
        }

        internal fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        internal fun padToLength(bytes: ByteArray, length: Int): ByteArray {
            if (bytes.size >= length) return bytes
            val padded = ByteArray(length)
            System.arraycopy(bytes, 0, padded, length - bytes.size, bytes.size)
            return padded
        }

        internal fun sha256(data: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data)
        }

        internal fun sha256Concat(vararg arrays: ByteArray): ByteArray {
            val totalLen = arrays.sumOf { it.size }
            val combined = ByteArray(totalLen)
            var offset = 0
            for (arr in arrays) {
                System.arraycopy(arr, 0, combined, offset, arr.size)
                offset += arr.size
            }
            return sha256(combined)
        }

        internal fun computeX(salt: ByteArray, identity: String, password: String): BigInteger {
            val identityPassword = "$identity:$password".toByteArray(Charsets.UTF_8)
            val identityHash = sha256(identityPassword)
            val xBytes = sha256Concat(salt, identityHash)
            return BigInteger(1, xBytes)
        }

        internal fun computeU(A: BigInteger, B: BigInteger): BigInteger {
            val aBytes = padToLength(bigIntToBytes(A), N_BYTES.size)
            val bBytes = padToLength(bigIntToBytes(B), N_BYTES.size)
            val uBytes = sha256Concat(aBytes, bBytes)
            return BigInteger(1, uBytes)
        }

        internal fun bigIntToBytes(n: BigInteger): ByteArray {
            var bytes = n.toByteArray()
            if (bytes.size > 1 && bytes[0] == 0.toByte()) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            return bytes
        }

        internal fun getN(): BigInteger = N
        internal fun getG(): BigInteger = g
        internal fun getK(): BigInteger = k
        internal fun getNBytes(): ByteArray = N_BYTES.copyOf()
    }
}

/** Output of [SrpClient.generateRegistrationData] — the triple the
 *  server needs to verify future SRP logins without ever seeing the
 *  password. All fields are base64-encoded byte strings except
 *  [bcryptSalt] which is in bcrypt's own `$2a$cost$22charsalt`
 *  format. */
data class SrpRegistrationData(
    val salt: String,
    val verifier: String,
    val bcryptSalt: String,
)

/**
 * SRP client session for the authentication handshake.
 */
class SrpClientSession {
    private val random = SecureRandom()

    private lateinit var privateKey: BigInteger
    private lateinit var publicKey: BigInteger
    private lateinit var sessionKey: ByteArray
    private lateinit var clientProofM1: ByteArray

    private var email: String = ""
    private var preprocessedPassword: String? = null

    /**
     * Step 1: Initialize with user credentials.
     *
     * @param email User's email
     * @param password User's password
     * @param bcryptSalt Optional bcrypt salt for password preprocessing
     */
    fun step1(email: String, password: String, bcryptSalt: String? = null) {
        this.email = email.lowercase()

        this.preprocessedPassword = if (bcryptSalt != null) {
            SrpClient.bcryptPreprocess(password, bcryptSalt)
        } else {
            password
        }

        val aBytes = ByteArray(32)
        random.nextBytes(aBytes)
        privateKey = BigInteger(1, aBytes)
        publicKey = SrpClient.getG().modPow(privateKey, SrpClient.getN())
    }

    /**
     * Step 2: Process server's challenge (salt and B).
     * Returns client public value A and proof M1.
     */
    fun step2(saltBase64: String, serverPublicBase64: String): SrpStep2Result {
        val password = this.preprocessedPassword
            ?: throw IllegalStateException("step1 must be called before step2")

        val salt = Base64.decode(saltBase64, Base64.DEFAULT)
        val serverPublicBytes = Base64.decode(serverPublicBase64, Base64.DEFAULT)
        val B = BigInteger(1, serverPublicBytes)

        val N = SrpClient.getN()
        val g = SrpClient.getG()
        val k = SrpClient.getK()
        val nBytes = SrpClient.getNBytes()

        if (B.mod(N) == BigInteger.ZERO) {
            this.preprocessedPassword = null
            throw IllegalArgumentException("Invalid server public ephemeral")
        }

        val u = SrpClient.computeU(publicKey, B)
        val x = SrpClient.computeX(salt, email, password)

        this.preprocessedPassword = null

        val gx = g.modPow(x, N)
        val kgx = k.multiply(gx).mod(N)
        var base = B.subtract(kgx)
        if (base < BigInteger.ZERO) {
            base = base.add(N)
        }
        val exp = privateKey.add(u.multiply(x)).mod(N.subtract(BigInteger.ONE))
        val S = base.modPow(exp, N)

        sessionKey = SrpClient.padToLength(SrpClient.bigIntToBytes(S), nBytes.size)

        val aBytes = SrpClient.padToLength(SrpClient.bigIntToBytes(publicKey), nBytes.size)
        val bBytes = SrpClient.padToLength(SrpClient.bigIntToBytes(B), nBytes.size)
        clientProofM1 = SrpClient.sha256Concat(aBytes, bBytes, sessionKey)

        return SrpStep2Result(
            clientPublic = Base64.encodeToString(SrpClient.bigIntToBytes(publicKey), Base64.NO_WRAP),
            clientProof = Base64.encodeToString(clientProofM1, Base64.NO_WRAP),
        )
    }

    /** K = PAD(S) from [step2], base64 — for the SDK's server-proof check
     *  (`srpVerifyServerProof`, padded encoding). */
    fun sessionKeyBase64(): String = Base64.encodeToString(sessionKey, Base64.NO_WRAP)

    /**
     * Step 3: Verify server's proof M2.
     */
    fun step3(serverProofBase64: String): Boolean {
        val serverProof = Base64.decode(serverProofBase64, Base64.DEFAULT)
        val nBytes = SrpClient.getNBytes()

        val aBytes = SrpClient.padToLength(SrpClient.bigIntToBytes(publicKey), nBytes.size)
        val expectedM2 = SrpClient.sha256Concat(aBytes, clientProofM1, sessionKey)

        return serverProof.contentEquals(expectedM2)
    }
}

data class SrpStep2Result(
    val clientPublic: String,
    val clientProof: String,
)
