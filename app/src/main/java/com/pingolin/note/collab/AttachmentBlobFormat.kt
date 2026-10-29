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

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * The attachment blob formats, as the Note web editor defines them (its
 * attachmentFormat.ts is the reference; keep them in step).
 *
 * v1 — `nonce(24) || XChaCha20-Poly1305(plaintext)`: one AEAD over the whole file. Read
 * for compatibility with older Android uploads; new attachments use v2 through the
 * streaming path below.
 *
 * v2 — what the web editor writes, so the WebView never holds a whole large file in
 * memory:
 * ```
 * offset  size  field
 * 0       4     magic "PNAT" (50 4E 41 54)
 * 4       1     version = 2
 * 5       3     reserved, must be zero
 * 8       4     chunk size, u32 little-endian (plaintext bytes per chunk)
 * 12      24    crypto_secretstream_xchacha20poly1305 header
 * 36      ...   chunks: secretstream push output (plaintext + 17), TAG_FINAL on the last
 * ```
 * Every chunk but the last carries exactly `chunk size` bytes. Readers take the v2 path
 * only on an exact 8-byte prefix match, bound the chunk size before allocating, and
 * require TAG_FINAL on the last chunk with nothing after it — so a truncated, reordered
 * or spliced blob is refused, never opened.
 *
 * This object only knows v2; callers dispatch on [isV2] and keep using the uniffi frame
 * functions for v1 (they need the native library, which JVM unit tests cannot load).
 */
object AttachmentBlobFormat {
    const val PREFIX_LEN = 12
    const val HEADER_LEN = PREFIX_LEN + SecretStream.HEADERBYTES
    const val CHUNK_SIZE = 1024 * 1024
    const val MIN_CHUNK = 1024
    const val MAX_CHUNK = 8 * 1024 * 1024
    private val MAGIC = byteArrayOf(0x50, 0x4E, 0x41, 0x54)
    private const val VERSION: Byte = 2

    /** Whether [blob] starts with the v2 prefix (needs 8 bytes). */
    fun isV2(blob: ByteArray): Boolean =
        blob.size >= 8 &&
            blob[0] == MAGIC[0] && blob[1] == MAGIC[1] && blob[2] == MAGIC[2] && blob[3] == MAGIC[3] &&
            blob[4] == VERSION && blob[5].toInt() == 0 && blob[6].toInt() == 0 && blob[7].toInt() == 0

    /** Exact ciphertext size for [plainLen] bytes. */
    fun encryptedSize(plainLen: Long, chunkSize: Int = CHUNK_SIZE): Long {
        val chunks = maxOf(1L, (plainLen + chunkSize - 1) / chunkSize)
        return HEADER_LEN + plainLen + chunks * SecretStream.ABYTES
    }

    /** Open a v2 blob under [key]; null on any structural or authentication failure. */
    fun decryptV2(key: ByteArray, blob: ByteArray): ByteArray? {
        if (!isV2(blob) || blob.size < HEADER_LEN) return null
        val chunkSize = (blob[8].toInt() and 0xff) or ((blob[9].toInt() and 0xff) shl 8) or
            ((blob[10].toInt() and 0xff) shl 16) or ((blob[11].toInt() and 0xff) shl 24)
        // Signed compare is safe: anything >= 2^31 reads negative and fails the low bound.
        if (chunkSize < MIN_CHUNK || chunkSize > MAX_CHUNK) return null
        val body = blob.size - HEADER_LEN
        val full = chunkSize + SecretStream.ABYTES
        // Chunk count is fixed by the size: all full but the last, which holds
        // 17..full bytes (an empty file is one 17-byte chunk).
        if (body < SecretStream.ABYTES) return null
        val chunks = (body + full - 1) / full
        val plainLen = body.toLong() - chunks.toLong() * SecretStream.ABYTES
        if (plainLen < 0 || plainLen > Int.MAX_VALUE) return null
        val stream = try {
            SecretStream.initPull(key, blob.copyOfRange(PREFIX_LEN, HEADER_LEN))
        } catch (_: IllegalArgumentException) {
            return null
        }
        val out = ByteArray(plainLen.toInt())
        var off = HEADER_LEN
        var o = 0
        for (i in 0 until chunks) {
            val end = minOf(off + full, blob.size)
            val pulled = stream.pull(blob.copyOfRange(off, end)) ?: return null
            val last = i == chunks - 1
            if (last) {
                if (pulled.tag != SecretStream.TAG_FINAL) return null
            } else {
                if (pulled.tag != SecretStream.TAG_MESSAGE || pulled.message.size != chunkSize) return null
            }
            System.arraycopy(pulled.message, 0, out, o, pulled.message.size)
            o += pulled.message.size
            off = end
        }
        return out
    }

    /** Seal [data] as a v2 blob under [key]. */
    fun encryptV2(
        key: ByteArray,
        data: ByteArray,
        random: SecureRandom = SecureRandom(),
        chunkSize: Int = CHUNK_SIZE,
    ): ByteArray {
        val (stream, header) = SecretStream.initPush(key, random)
        return seal(stream, header, data, chunkSize)
    }

    /** The input exceeded the caller's plaintext-byte ceiling. */
    class SizeLimitExceededException : Exception()

    /**
     * Seal [input] to [output] as the same v2 stream [encryptV2] produces,
     * without ever holding the whole file or ciphertext in memory.
     *
     * One chunk plus its 17-byte tag is the largest attachment allocation.
     * [maxPlainBytes] bounds providers that do not advertise a trustworthy
     * size; the partially-written output is the caller's to delete on failure.
     */
    fun encryptV2ToStream(
        key: ByteArray,
        input: InputStream,
        output: OutputStream,
        random: SecureRandom = SecureRandom(),
        chunkSize: Int = CHUNK_SIZE,
        maxPlainBytes: Long = Long.MAX_VALUE,
    ): Long {
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "chunk size out of range: $chunkSize" }
        require(maxPlainBytes >= 0) { "max plaintext size must be non-negative" }

        val (stream, header) = SecretStream.initPush(key, random)
        val prefix = ByteArray(HEADER_LEN)
        System.arraycopy(MAGIC, 0, prefix, 0, 4)
        prefix[4] = VERSION
        prefix[8] = chunkSize.toByte()
        prefix[9] = (chunkSize ushr 8).toByte()
        prefix[10] = (chunkSize ushr 16).toByte()
        prefix[11] = (chunkSize ushr 24).toByte()
        System.arraycopy(header, 0, prefix, PREFIX_LEN, SecretStream.HEADERBYTES)
        output.write(prefix)

        val buffer = ByteArray(chunkSize)
        var carry = -1
        var total = 0L
        while (true) {
            var used = 0
            if (carry >= 0) {
                buffer[0] = carry.toByte()
                carry = -1
                used = 1
            }
            while (used < chunkSize) {
                val read = input.read(buffer, used, chunkSize - used)
                if (read < 0) break
                used += read
            }
            total += used
            if (total > maxPlainBytes) throw SizeLimitExceededException()

            val next = input.read()
            val last = next < 0
            if (!last) carry = next

            val plain = buffer.copyOf(used)
            val sealed = stream.push(
                plain,
                if (last) SecretStream.TAG_FINAL else SecretStream.TAG_MESSAGE,
            )
            output.write(sealed)
            plain.fill(0)
            sealed.fill(0)
            buffer.fill(0, used)
            if (last) return total
        }
    }

    /** [encryptV2] with a chosen stream header: reproduces vectors in tests. */
    internal fun encryptV2WithHeader(key: ByteArray, header: ByteArray, data: ByteArray, chunkSize: Int): ByteArray =
        seal(SecretStream.initPushWithHeader(key, header), header, data, chunkSize)

    private fun seal(stream: SecretStream, header: ByteArray, data: ByteArray, chunkSize: Int): ByteArray {
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "chunk size out of range: $chunkSize" }
        val total = encryptedSize(data.size.toLong(), chunkSize)
        require(total <= Int.MAX_VALUE) { "attachment too large for an in-memory v2 seal" }
        val out = ByteArray(total.toInt())
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = VERSION
        out[8] = chunkSize.toByte()
        out[9] = (chunkSize ushr 8).toByte()
        out[10] = (chunkSize ushr 16).toByte()
        out[11] = (chunkSize ushr 24).toByte()
        System.arraycopy(header, 0, out, PREFIX_LEN, SecretStream.HEADERBYTES)
        var off = HEADER_LEN
        var pos = 0
        do {
            val end = minOf(pos + chunkSize, data.size)
            val last = end == data.size
            val ct = stream.push(data.copyOfRange(pos, end), if (last) SecretStream.TAG_FINAL else SecretStream.TAG_MESSAGE)
            System.arraycopy(ct, 0, out, off, ct.size)
            off += ct.size
            pos = end
        } while (pos < data.size)
        return out
    }
}
