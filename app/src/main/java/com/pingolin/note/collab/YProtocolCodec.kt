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

import java.io.ByteArrayOutputStream

/**
 * Minimal lib0-compatible varint + varbytes codec. The web side uses
 * `y-protocols/sync` on top of `lib0/encoding`, which encodes lengths
 * and message types as LEB128-style unsigned varints. We implement just
 * the two primitives we need (varUint + varUint8Array) so the wire
 * formats on both sides stay byte-identical.
 */
internal object YProtocolCodec {
    /** Write a non-negative integer as a lib0 varuint. */
    fun writeVarUint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v > 0x7F) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    /** Write a length-prefixed byte array (varuint length || raw bytes). */
    fun writeVarUint8Array(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeVarUint(out, bytes.size)
        out.write(bytes)
    }

    class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < bytes.size

        fun readVarUint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                require(pos < bytes.size) { "varuint truncated" }
                val b = bytes[pos].toInt() and 0xFF
                pos++
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
                require(shift < 32) { "varuint too large" }
            }
        }

        fun readVarUint8Array(): ByteArray {
            val len = readVarUint()
            require(pos + len <= bytes.size) { "varuint8array truncated" }
            val out = bytes.copyOfRange(pos, pos + len)
            pos += len
            return out
        }
    }
}
