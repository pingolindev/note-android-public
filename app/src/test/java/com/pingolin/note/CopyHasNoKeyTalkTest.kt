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

package com.pingolin.note

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule for everything a person reads: no talk of keys — say what happens
 * instead. The product nouns "recovery key" and "passkey" are the only
 * exceptions. Scans every string the app ships, in every values-xx locale
 * there is.
 */
class CopyHasNoKeyTalkTest {

    private val banned = listOf(
        Regex("""(?<!recovery )\bkeys?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwrap(s|ped)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bre-?key""", RegexOption.IGNORE_CASE),
        Regex("""\brotat""", RegexOption.IGNORE_CASE),
        Regex("""safety number""", RegexOption.IGNORE_CASE),
    )

    @Test
    fun `no string resource talks about keys`() {
        val res = File("src/main/res")
        val files = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().toList() }
        val strings = files.flatMap { file ->
            Regex("""<(string|item)\b[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .map { file.parentFile!!.name + "/" + file.name to it.groupValues[2] }
                .toList()
        }
        assertTrue("no strings found under ${res.absolutePath}", strings.size > 100)

        val offenders = strings.filter { (_, text) -> banned.any { it.containsMatchIn(text) } }
        assertTrue("key talk in user-facing strings: $offenders", offenders.isEmpty())
    }
}
