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

/**
 * Minimal BLAKE2b-256 keyed hash implementation.
 * Matches libsodium's crypto_generichash(32, message, key).
 *
 * Derives the note vault key from the sharing private key, identical to the web UI's:
 * sodium.crypto_generichash(32, privateKey, "pingolin-note-vault")
 */
@OptIn(ExperimentalUnsignedTypes::class)
object Blake2b {
    private val IV = ulongArrayOf(
        0x6a09e667f3bcc908uL, 0xbb67ae8584caa73buL,
        0x3c6ef372fe94f82buL, 0xa54ff53a5f1d36f1uL,
        0x510e527fade682d1uL, 0x9b05688c2b3e6c1fuL,
        0x1f83d9abfb41bd6buL, 0x5be0cd19137e2179uL,
    )

    private val SIGMA = arrayOf(
        intArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15),
        intArrayOf(14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3),
        intArrayOf(11,8,12,0,5,2,15,13,10,14,3,6,7,1,9,4),
        intArrayOf(7,9,3,1,13,12,11,14,2,6,5,10,4,0,15,8),
        intArrayOf(9,0,5,7,2,4,10,15,14,1,11,12,6,8,3,13),
        intArrayOf(2,12,6,10,0,11,8,3,4,13,7,5,15,14,1,9),
        intArrayOf(12,5,1,15,14,13,4,10,0,7,6,3,9,2,8,11),
        intArrayOf(13,11,7,14,12,1,3,9,5,0,15,4,8,6,2,10),
        intArrayOf(6,15,14,9,11,3,0,8,12,2,13,7,1,4,10,5),
        intArrayOf(10,2,8,4,7,6,1,5,15,11,9,14,3,12,13,0),
        intArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15),
        intArrayOf(14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3),
    )

    /**
     * Compute BLAKE2b keyed hash.
     * Equivalent to libsodium crypto_generichash(outLen, message, key).
     */
    fun hash(message: ByteArray, key: ByteArray, outLen: Int = 32): ByteArray {
        require(outLen in 1..64) { "outLen must be 1..64" }
        require(key.size <= 64) { "key must be <= 64 bytes" }

        val h = ULongArray(8)
        for (i in 0..7) h[i] = IV[i]

        // Parameter block: fanout=1, depth=1, outLen, keyLen
        h[0] = h[0] xor (outLen.toULong() or (key.size.toULong() shl 8) or (1uL shl 16) or (1uL shl 24))

        var t = 0uL
        val block = ByteArray(128)

        // If key is provided, the first block is the padded key
        if (key.isNotEmpty()) {
            key.copyInto(block)
            t = 128uL
            if (message.isEmpty()) {
                compress(h, block, t, true)
                return finalize(h, outLen)
            }
            compress(h, block, t, false)
        }

        // Process message blocks
        var offset = 0
        while (offset < message.size) {
            val remaining = message.size - offset
            if (remaining > 128) {
                message.copyInto(block, 0, offset, offset + 128)
                t += 128uL
                compress(h, block, t, false)
                offset += 128
            } else {
                block.fill(0)
                message.copyInto(block, 0, offset, offset + remaining)
                t += remaining.toULong()
                compress(h, block, t, true)
                offset += remaining
            }
        }

        // Handle empty message with no key
        if (message.isEmpty() && key.isEmpty()) {
            block.fill(0)
            compress(h, block, 0uL, true)
        }

        return finalize(h, outLen)
    }

    private fun finalize(h: ULongArray, outLen: Int): ByteArray {
        val out = ByteArray(outLen)
        for (i in 0 until outLen) {
            out[i] = (h[i / 8] shr (8 * (i % 8))).toByte()
        }
        return out
    }

    private fun compress(h: ULongArray, block: ByteArray, t: ULong, last: Boolean) {
        val v = ULongArray(16)
        for (i in 0..7) { v[i] = h[i]; v[i + 8] = IV[i] }
        v[12] = v[12] xor t
        v[13] = v[13] xor 0uL // t high (we only use 64-bit counter)
        if (last) v[14] = v[14].inv()

        val m = ULongArray(16)
        for (i in 0..15) {
            m[i] = block.loadLE64(i * 8)
        }

        for (round in 0..11) {
            val s = SIGMA[round]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }

        for (i in 0..7) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(v: ULongArray, a: Int, b: Int, c: Int, d: Int, x: ULong, y: ULong) {
        v[a] = v[a] + v[b] + x; v[d] = (v[d] xor v[a]).rotateRight(32)
        v[c] = v[c] + v[d]; v[b] = (v[b] xor v[c]).rotateRight(24)
        v[a] = v[a] + v[b] + y; v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = v[c] + v[d]; v[b] = (v[b] xor v[c]).rotateRight(63)
    }

    private fun ULong.rotateRight(n: Int): ULong = (this shr n) or (this shl (64 - n))

    private fun ByteArray.loadLE64(offset: Int): ULong {
        var v = 0uL
        for (i in 0..7) v = v or ((this[offset + i].toUByte().toULong()) shl (i * 8))
        return v
    }
}
