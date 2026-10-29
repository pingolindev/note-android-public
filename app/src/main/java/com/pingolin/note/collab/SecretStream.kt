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

package com.pingolin.note.collab

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * libsodium's `crypto_secretstream_xchacha20poly1305`, in plain Kotlin.
 *
 * Why it exists: the Note web editor writes attachment blobs in a chunked
 * format ([AttachmentBlobFormat]) built on this stream, and the one native
 * reader here — [com.pingolin.note.domain.repository.ContentKeyRotator]'s
 * attachment re-key — must open those blobs. Neither the uniffi SDK nor the
 * platform exposes the stream: Conscrypt's ChaCha20-Poly1305 is the IETF AEAD,
 * which cannot express the stream's layout (a keystream block for the tag, the
 * nonce ratchet, the rekey), and a JNI libsodium would be a new native
 * dependency for one function.
 *
 * It is a line-for-line port of `crypto_secretstream/xchacha20poly1305/
 * secretstream_xchacha20poly1305.c` (libsodium 1.0.18+), including the one
 * spot that looks wrong and is not: the pad after the message is `(0x10 - 64 +
 * mlen) & 0xf`, i.e. `mlen mod 16`, not the usual AEAD `-mlen mod 16`.
 * SecretStreamTest checks this file against vectors produced by libsodium
 * itself (the web editor's libsodium-wrappers), for HChaCha20, the ChaCha20
 * keystream, Poly1305 and whole streams, so a divergence fails there rather
 * than on a user's attachment.
 *
 * Not thread-safe; one instance per stream, used in order.
 */
class SecretStream private constructor(
    /** HChaCha20-derived subkey; replaced on every rekey. */
    private val k: ByteArray,
    /** counter (4, little-endian) || inonce (8). */
    private val nonce: ByteArray,
) {
    class Pulled(val message: ByteArray, val tag: Int)

    /** Seal [m] as the next chunk, tagged [tag]. Output is m.size + [ABYTES]. */
    fun push(m: ByteArray, tag: Int): ByteArray {
        val block = ChaCha20.block(k, 0, nonce)
        val poly = Poly1305(block.copyOfRange(0, 32))
        block.fill(0)
        // Associated data: none. update(ad) and its pad are both empty.
        val tagBlock = ByteArray(64)
        tagBlock[0] = tag.toByte()
        ChaCha20.xorInPlace(k, nonce, 1, tagBlock, 0, 64)
        poly.update(tagBlock, 0, 64)
        val out = ByteArray(1 + m.size + MACBYTES)
        out[0] = tagBlock[0]
        System.arraycopy(m, 0, out, 1, m.size)
        ChaCha20.xorInPlace(k, nonce, 2, out, 1, m.size)
        poly.update(out, 1, m.size)
        finishMac(poly, m.size)
        val mac = poly.finish()
        System.arraycopy(mac, 0, out, 1 + m.size, MACBYTES)
        advance(mac, tag)
        return out
    }

    /** Open the next chunk. Null when it does not authenticate — the stream
     *  is then unusable, as in libsodium (its state is not advanced). */
    fun pull(c: ByteArray): Pulled? {
        if (c.size < ABYTES) return null
        val mlen = c.size - ABYTES
        val block = ChaCha20.block(k, 0, nonce)
        val poly = Poly1305(block.copyOfRange(0, 32))
        block.fill(0)
        val tagBlock = ByteArray(64)
        tagBlock[0] = c[0]
        ChaCha20.xorInPlace(k, nonce, 1, tagBlock, 0, 64)
        val tag = tagBlock[0].toInt() and 0xff
        tagBlock[0] = c[0]
        poly.update(tagBlock, 0, 64)
        poly.update(c, 1, mlen)
        finishMac(poly, mlen)
        val mac = poly.finish()
        val stored = c.copyOfRange(1 + mlen, c.size)
        if (!MessageDigest.isEqual(mac, stored)) return null
        val m = c.copyOfRange(1, 1 + mlen)
        ChaCha20.xorInPlace(k, nonce, 2, m, 0, mlen)
        advance(mac, tag)
        return Pulled(m, tag)
    }

    private fun finishMac(poly: Poly1305, mlen: Int) {
        // libsodium: _pad0, (0x10 - (sizeof block) + mlen) & 0xf. See the
        // class doc — this is mlen mod 16, and must stay exactly this.
        val padLen = ((0x10L - 64L + mlen.toLong()) and 0xfL).toInt()
        poly.update(ByteArray(16), 0, padLen)
        poly.update(le64(0L), 0, 8)                       // adlen
        poly.update(le64(64L + mlen.toLong()), 0, 8)      // sizeof block + mlen
    }

    private fun advance(mac: ByteArray, tag: Int) {
        for (i in 0 until INONCEBYTES) nonce[COUNTERBYTES + i] = (nonce[COUNTERBYTES + i].toInt() xor mac[i].toInt()).toByte()
        // sodium_increment on the 4-byte little-endian counter.
        var carry = 1
        for (i in 0 until COUNTERBYTES) {
            val v = (nonce[i].toInt() and 0xff) + carry
            nonce[i] = v.toByte()
            carry = v ushr 8
        }
        val counterIsZero = nonce[0].toInt() == 0 && nonce[1].toInt() == 0 &&
            nonce[2].toInt() == 0 && nonce[3].toInt() == 0
        if ((tag and TAG_REKEY) != 0 || counterIsZero) rekey()
    }

    private fun rekey() {
        val buf = ByteArray(KEYBYTES + INONCEBYTES)
        System.arraycopy(k, 0, buf, 0, KEYBYTES)
        System.arraycopy(nonce, COUNTERBYTES, buf, KEYBYTES, INONCEBYTES)
        ChaCha20.xorInPlace(k, nonce, 0, buf, 0, buf.size)
        System.arraycopy(buf, 0, k, 0, KEYBYTES)
        System.arraycopy(buf, KEYBYTES, nonce, COUNTERBYTES, INONCEBYTES)
        buf.fill(0)
        resetCounter(nonce)
    }

    companion object {
        const val KEYBYTES = 32
        const val HEADERBYTES = 24
        const val ABYTES = 17
        const val TAG_MESSAGE = 0
        const val TAG_PUSH = 1
        const val TAG_REKEY = 2
        const val TAG_FINAL = 3
        private const val MACBYTES = 16
        private const val COUNTERBYTES = 4
        private const val INONCEBYTES = 8

        /** A writer and the header it must be preceded by. */
        fun initPush(key: ByteArray, random: SecureRandom = SecureRandom()): Pair<SecretStream, ByteArray> {
            val header = ByteArray(HEADERBYTES).also { random.nextBytes(it) }
            return init(key, header) to header
        }

        /** A writer with a caller-chosen header: for reproducing vectors only. */
        internal fun initPushWithHeader(key: ByteArray, header: ByteArray): SecretStream = init(key, header)

        fun initPull(key: ByteArray, header: ByteArray): SecretStream = init(key, header)

        private fun init(key: ByteArray, header: ByteArray): SecretStream {
            require(key.size == KEYBYTES) { "secretstream key must be $KEYBYTES bytes" }
            require(header.size == HEADERBYTES) { "secretstream header must be $HEADERBYTES bytes" }
            val k = ChaCha20.hchacha20(key, header.copyOfRange(0, 16))
            val nonce = ByteArray(COUNTERBYTES + INONCEBYTES)
            resetCounter(nonce)
            System.arraycopy(header, 16, nonce, COUNTERBYTES, INONCEBYTES)
            return SecretStream(k, nonce)
        }

        private fun resetCounter(nonce: ByteArray) {
            nonce[0] = 1
            nonce[1] = 0
            nonce[2] = 0
            nonce[3] = 0
        }

        private fun le64(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (8 * i)).toByte() }
    }
}

/** ChaCha20 (RFC 8439, 96-bit nonce / 32-bit counter) and HChaCha20. */
internal object ChaCha20 {
    private const val C0 = 0x61707865
    private const val C1 = 0x3320646e
    private const val C2 = 0x79622d32
    private const val C3 = 0x6b206574

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    private fun put32(v: Int, out: ByteArray, off: Int) {
        out[off] = v.toByte()
        out[off + 1] = (v ushr 8).toByte()
        out[off + 2] = (v ushr 16).toByte()
        out[off + 3] = (v ushr 24).toByte()
    }

    private fun rounds(x: IntArray) {
        repeat(10) {
            qr(x, 0, 4, 8, 12); qr(x, 1, 5, 9, 13); qr(x, 2, 6, 10, 14); qr(x, 3, 7, 11, 15)
            qr(x, 0, 5, 10, 15); qr(x, 1, 6, 11, 12); qr(x, 2, 7, 8, 13); qr(x, 3, 4, 9, 14)
        }
    }

    private fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    private fun initialState(key: ByteArray, counter: Int, nonce: ByteArray): IntArray {
        val s = IntArray(16)
        s[0] = C0; s[1] = C1; s[2] = C2; s[3] = C3
        for (i in 0 until 8) s[4 + i] = le32(key, 4 * i)
        s[12] = counter
        s[13] = le32(nonce, 0); s[14] = le32(nonce, 4); s[15] = le32(nonce, 8)
        return s
    }

    /** One 64-byte keystream block. */
    fun block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val s = initialState(key, counter, nonce)
        val x = s.copyOf()
        rounds(x)
        val out = ByteArray(64)
        for (i in 0 until 16) put32(x[i] + s[i], out, 4 * i)
        return out
    }

    /** XOR `len` bytes of `buf` from `off` with the keystream starting at block [counter]. */
    fun xorInPlace(key: ByteArray, nonce: ByteArray, counter: Int, buf: ByteArray, off: Int, len: Int) {
        val s = initialState(key, counter, nonce)
        val x = IntArray(16)
        val ks = ByteArray(64)
        var pos = 0
        while (pos < len) {
            System.arraycopy(s, 0, x, 0, 16)
            rounds(x)
            for (i in 0 until 16) put32(x[i] + s[i], ks, 4 * i)
            val n = minOf(64, len - pos)
            for (i in 0 until n) buf[off + pos + i] = (buf[off + pos + i].toInt() xor ks[i].toInt()).toByte()
            pos += n
            s[12] += 1
        }
        ks.fill(0)
    }

    /** HChaCha20: the 32-byte subkey for a 16-byte input (default constants). */
    fun hchacha20(key: ByteArray, input16: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = C0; x[1] = C1; x[2] = C2; x[3] = C3
        for (i in 0 until 8) x[4 + i] = le32(key, 4 * i)
        for (i in 0 until 4) x[12 + i] = le32(input16, 4 * i)
        rounds(x)
        val out = ByteArray(32)
        for (i in 0 until 4) put32(x[i], out, 4 * i)
        for (i in 0 until 4) put32(x[12 + i], out, 16 + 4 * i)
        return out
    }
}

/** Poly1305 one-time authenticator (poly1305-donna, 26-bit limbs), incremental. */
internal class Poly1305(key: ByteArray) {
    private val r0: Long
    private val r1: Long
    private val r2: Long
    private val r3: Long
    private val r4: Long
    private val pad = LongArray(4)
    private var h0 = 0L
    private var h1 = 0L
    private var h2 = 0L
    private var h3 = 0L
    private var h4 = 0L
    private val leftover = ByteArray(16)
    private var leftoverLen = 0

    init {
        require(key.size == 32) { "poly1305 key must be 32 bytes" }
        val t0 = le32(key, 0); val t1 = le32(key, 4); val t2 = le32(key, 8); val t3 = le32(key, 12)
        r0 = t0 and 0x3ffffff
        r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03
        r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ff
        r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fff
        r4 = (t3 ushr 8) and 0x00fffff
        for (i in 0 until 4) pad[i] = le32(key, 16 + 4 * i)
    }

    fun update(m: ByteArray, off: Int, len: Int) {
        var p = off
        var n = len
        if (leftoverLen > 0) {
            val want = minOf(16 - leftoverLen, n)
            System.arraycopy(m, p, leftover, leftoverLen, want)
            leftoverLen += want
            p += want
            n -= want
            if (leftoverLen < 16) return
            blocks(leftover, 0, false)
            leftoverLen = 0
        }
        while (n >= 16) {
            blocks(m, p, false)
            p += 16
            n -= 16
        }
        if (n > 0) {
            System.arraycopy(m, p, leftover, 0, n)
            leftoverLen = n
        }
    }

    fun finish(): ByteArray {
        if (leftoverLen > 0) {
            leftover[leftoverLen] = 1
            for (i in leftoverLen + 1 until 16) leftover[i] = 0
            blocks(leftover, 0, true)
        }
        var c: Long
        c = h1 ushr 26; h1 = h1 and 0x3ffffff
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffff
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffff
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c

        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and 0x3ffffff
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffff
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffff
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffff
        var g4 = h4 + c - (1L shl 26)

        // h >= p ⇔ g4 >= 0: take g; else keep h. Branch-free.
        var mask = (g4 ushr 63) - 1
        g0 = g0 and mask; g1 = g1 and mask; g2 = g2 and mask; g3 = g3 and mask; g4 = g4 and mask
        mask = mask.inv()
        h0 = (h0 and mask) or g0
        h1 = (h1 and mask) or g1
        h2 = (h2 and mask) or g2
        h3 = (h3 and mask) or g3
        h4 = (h4 and mask) or g4

        val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL

        val out = ByteArray(16)
        var f = w0 + pad[0]
        put32(f, out, 0)
        f = w1 + pad[1] + (f ushr 32)
        put32(f, out, 4)
        f = w2 + pad[2] + (f ushr 32)
        put32(f, out, 8)
        f = w3 + pad[3] + (f ushr 32)
        put32(f, out, 12)
        return out
    }

    private fun blocks(m: ByteArray, off: Int, final: Boolean) {
        val hibit = if (final) 0L else (1L shl 24)
        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5
        val t0 = le32(m, off); val t1 = le32(m, off + 4); val t2 = le32(m, off + 8); val t3 = le32(m, off + 12)
        h0 += t0 and 0x3ffffff
        h1 += ((t0 ushr 26) or (t1 shl 6)) and 0x3ffffff
        h2 += ((t1 ushr 20) or (t2 shl 12)) and 0x3ffffff
        h3 += ((t2 ushr 14) or (t3 shl 18)) and 0x3ffffff
        h4 += (t3 ushr 8) or hibit

        val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
        var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
        var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
        var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
        var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

        var c = d0 ushr 26; h0 = d0 and 0x3ffffff
        d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffff
        d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffff
        d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffff
        d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c
    }

    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24)

    private fun put32(v: Long, out: ByteArray, off: Int) {
        out[off] = v.toByte()
        out[off + 1] = (v ushr 8).toByte()
        out[off + 2] = (v ushr 16).toByte()
        out[off + 3] = (v ushr 24).toByte()
    }
}
