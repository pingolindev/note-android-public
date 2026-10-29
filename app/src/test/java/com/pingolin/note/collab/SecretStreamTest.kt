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

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The Kotlin secretstream and the v2 attachment format against LIBSODIUM.
 *
 * Every expected value below was produced by libsodium itself (the web
 * editor's libsodium-wrappers, 1.0.22) — see the two JSON files under
 * `src/test/resources/pf2/`. A hand-written ChaCha20/Poly1305 that is wrong in
 * one limb still round-trips with itself, so self-consistency proves nothing;
 * byte equality with the reference implementation does. The attachment vectors
 * are the SAME file the web editor's attachmentFormat.test.ts and the iOS app
 * check, so the three clients cannot drift apart silently.
 */
class SecretStreamTest {

    private fun json(name: String): JSONObject =
        JSONObject(javaClass.getResource("/pf2/$name")!!.readText())

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        ((Character.digit(s[2 * i], 16) shl 4) + Character.digit(s[2 * i + 1], 16)).toByte()
    }

    private val prim = json("secretstream-primitive-vectors.json")
    private val att = json("attachment-v2-vectors.json")

    private fun pattern(n: Int) = ByteArray(n) { i -> ((i * 7 + 3) and 0xff).toByte() }

    @Test
    fun `HChaCha20 matches libsodium`() {
        val v = prim.getJSONObject("hchacha20")
        assertArrayEquals(hex(v.getString("out")), ChaCha20.hchacha20(hex(v.getString("key")), hex(v.getString("input"))))
    }

    @Test
    fun `ChaCha20-IETF keystream with an initial counter matches libsodium`() {
        val v = prim.getJSONObject("chacha20_ietf")
        val buf = hex(v.getString("message"))
        ChaCha20.xorInPlace(hex(v.getString("key")), hex(v.getString("nonce")), v.getInt("ic"), buf, 0, buf.size)
        assertArrayEquals(hex(v.getString("out")), buf)
    }

    @Test
    fun `Poly1305 matches libsodium at every length, fed whole and byte by byte`() {
        val arr = prim.getJSONArray("poly1305")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val key = hex(v.getString("key"))
            val m = hex(v.getString("message"))
            val whole = Poly1305(key).apply { update(m, 0, m.size) }.finish()
            assertArrayEquals("len ${m.size}", hex(v.getString("tag")), whole)
            val drip = Poly1305(key)
            for (b in m.indices) drip.update(m, b, 1)
            assertArrayEquals("len ${m.size} (incremental)", hex(v.getString("tag")), drip.finish())
        }
    }

    @Test
    fun `a libsodium stream with every tag, a rekey and an empty chunk opens chunk by chunk`() {
        val v = prim.getJSONObject("secretstream")
        val chunks = v.getJSONArray("chunks")
        val pull = SecretStream.initPull(hex(v.getString("key")), hex(v.getString("header")))
        for (i in 0 until chunks.length()) {
            val c = chunks.getJSONObject(i)
            val got = pull.pull(hex(c.getString("cipher")))
            assertNotNull("chunk $i", got)
            assertArrayEquals("chunk $i", hex(c.getString("message")), got!!.message)
            assertEquals("chunk $i tag", c.getInt("tag"), got.tag)
        }
    }

    @Test
    fun `pushing with the same header reproduces libsodium's ciphertext byte for byte`() {
        val v = prim.getJSONObject("secretstream")
        val chunks = v.getJSONArray("chunks")
        val push = SecretStream.initPushWithHeader(hex(v.getString("key")), hex(v.getString("header")))
        for (i in 0 until chunks.length()) {
            val c = chunks.getJSONObject(i)
            assertArrayEquals("chunk $i", hex(c.getString("cipher")), push.push(hex(c.getString("message")), c.getInt("tag")))
        }
    }

    @Test
    fun `a tampered chunk or a wrong key does not open`() {
        val v = prim.getJSONObject("secretstream")
        val first = hex(v.getJSONArray("chunks").getJSONObject(0).getString("cipher"))
        for (i in first.indices) {
            val bad = first.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", SecretStream.initPull(hex(v.getString("key")), hex(v.getString("header"))).pull(bad))
        }
        val wrongKey = ByteArray(32) { 9 }
        assertNull(SecretStream.initPull(wrongKey, hex(v.getString("header"))).pull(first))
        assertNull(SecretStream.initPull(hex(v.getString("key")), hex(v.getString("header"))).pull(ByteArray(16)))
    }

    // --- the v2 attachment format -------------------------------------------------

    @Test
    fun `the shared note-ui attachment vectors open to the expected plaintext`() {
        val key = hex(att.getString("key"))
        for (name in listOf("v2_multi", "v2_exact", "v2_empty")) {
            val v = att.getJSONObject(name)
            val blob = hex(v.getString("blob"))
            assertTrue(name, AttachmentBlobFormat.isV2(blob))
            assertArrayEquals(name, pattern(v.getInt("plaintext_len")), AttachmentBlobFormat.decryptV2(key, blob))
            assertEquals(name, blob.size.toLong(), AttachmentBlobFormat.encryptedSize(v.getLong("plaintext_len"), v.getInt("chunk_size")))
        }
    }

    @Test
    fun `a v1 blob is not mistaken for v2`() {
        val v1 = hex(att.getJSONObject("v1").getString("blob"))
        assertFalse(AttachmentBlobFormat.isV2(v1))
        assertNull(AttachmentBlobFormat.decryptV2(hex(att.getString("key")), v1))
    }

    @Test
    fun `sealing reproduces the note-ui vector when given its stream header`() {
        val v = att.getJSONObject("v2_multi")
        val blob = hex(v.getString("blob"))
        val header = blob.copyOfRange(AttachmentBlobFormat.PREFIX_LEN, AttachmentBlobFormat.HEADER_LEN)
        val sealed = AttachmentBlobFormat.encryptV2WithHeader(hex(att.getString("key")), header, pattern(v.getInt("plaintext_len")), v.getInt("chunk_size"))
        assertArrayEquals(blob, sealed)
    }

    @Test
    fun `round trips at chunk boundaries and with the default chunk size`() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val rnd = SecureRandom()
        for (n in listOf(0, 1, 1023, 1024, 1025, 4096, 5000)) {
            val data = pattern(n)
            val blob = AttachmentBlobFormat.encryptV2(key, data, rnd, 1024)
            assertEquals(AttachmentBlobFormat.encryptedSize(n.toLong(), 1024), blob.size.toLong())
            assertArrayEquals("n=$n", data, AttachmentBlobFormat.decryptV2(key, blob))
        }
        val big = pattern(3 * AttachmentBlobFormat.CHUNK_SIZE + 5)
        assertArrayEquals(big, AttachmentBlobFormat.decryptV2(key, AttachmentBlobFormat.encryptV2(key, big)))
    }

    @Test
    fun `streaming encryption writes the same decryptable v2 format`() {
        val key = pattern(32)
        val data = pattern(3 * AttachmentBlobFormat.CHUNK_SIZE + 5)
        val output = java.io.ByteArrayOutputStream()

        val plainLen = AttachmentBlobFormat.encryptV2ToStream(
            key = key,
            input = data.inputStream(),
            output = output,
            random = SecureRandom(byteArrayOf(9)),
        )
        val blob = output.toByteArray()

        assertEquals(data.size.toLong(), plainLen)
        assertEquals(AttachmentBlobFormat.encryptedSize(data.size.toLong()), blob.size.toLong())
        assertArrayEquals(data, AttachmentBlobFormat.decryptV2(key, blob))
    }

    @Test
    fun `streaming encryption refuses a source past its byte ceiling`() {
        assertThrows(AttachmentBlobFormat.SizeLimitExceededException::class.java) {
            AttachmentBlobFormat.encryptV2ToStream(
                key = pattern(32),
                input = pattern(2048).inputStream(),
                output = java.io.ByteArrayOutputStream(),
                chunkSize = 1024,
                maxPlainBytes = 2047,
            )
        }
    }

    @Test
    fun `truncation, reordering, appended bytes and header rewrites are refused`() {
        val key = hex(att.getString("key"))
        val blob = hex(att.getJSONObject("v2_multi").getString("blob")) // chunks 1024, 1024, 452
        val h = AttachmentBlobFormat.HEADER_LEN
        val full = 1024 + SecretStream.ABYTES
        fun opens(b: ByteArray) = AttachmentBlobFormat.decryptV2(key, b) != null

        assertTrue(opens(blob))
        assertFalse("final chunk dropped", opens(blob.copyOfRange(0, h + 2 * full)))
        assertFalse("cut mid-chunk", opens(blob.copyOfRange(0, blob.size - 1)))
        assertFalse("header only", opens(blob.copyOfRange(0, h)))
        assertFalse("appended byte", opens(blob + byteArrayOf(0)))
        val swapped = blob.copyOfRange(0, h) + blob.copyOfRange(h + full, h + 2 * full) +
            blob.copyOfRange(h, h + full) + blob.copyOfRange(h + 2 * full, blob.size)
        assertFalse("reordered", opens(swapped))
        for (i in listOf(5, 8, 9, 11, 12, 35, h, h + 700, blob.size - 1)) {
            val bad = blob.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse("byte $i", opens(bad))
        }
        // Out-of-range chunk sizes are refused before anything is allocated.
        for (size in listOf(0, 512, 16 * 1024 * 1024, -1)) {
            val bad = blob.copyOf()
            bad[8] = size.toByte(); bad[9] = (size ushr 8).toByte(); bad[10] = (size ushr 16).toByte(); bad[11] = (size ushr 24).toByte()
            assertNull("chunk size $size", AttachmentBlobFormat.decryptV2(key, bad))
        }
        // An exact multiple whose FINAL chunk is dropped must not look complete.
        val exact = hex(att.getJSONObject("v2_exact").getString("blob"))
        assertTrue(opens(exact))
        assertFalse(opens(exact.copyOfRange(0, h + full)))
    }
}
