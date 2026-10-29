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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * As the Track Android app's NoServerTextTest: no server `error` text, and no failure's own
 * message (the SDK's talk of keys, a socket's or a parser's words), ever reaches a screen, a
 * dialog, a toast or a notification. A refusal is told in this app's words: by its
 * `error_code`, else its HTTP status (ApiErrors.plainLine), and a failure through
 * `plainText`. The server's words can talk about keys, or say whatever a compromised server
 * wants a person to read; they are for logs.
 *
 * Note routes failures to the screen from more than its ui/ package (the importer, the sync
 * worker's notification), so both scans cover all of src/main/java.
 */
class NoServerTextTest {

    private val logLine = Regex("""\bLog\.[dviwe]\(""")

    private fun scan(read: Regex): Map<String, List<String>> {
        val found = mutableMapOf<String, MutableList<String>>()
        java.io.File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().map { it.trim() }
                .filter { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
                .filter { read.containsMatchIn(it) && !logLine.containsMatchIn(it) }
                .forEach { found.getOrPut(file.name) { mutableListOf() }.add(it) }
        }
        return found
    }

    private fun check(found: Map<String, List<String>>, allowed: Map<String, List<String>>, what: String) {
        val unexpected = found.flatMap { (file, lines) ->
            val left = allowed[file].orEmpty().toMutableList()
            lines.filterNot { left.remove(it) }.map { "$file: $it" }
        }
        assertEquals(what, emptyList<String>(), unexpected)
        // The list stays honest: every allowed read is still there.
        val stale = allowed.flatMap { (file, lines) ->
            val left = found[file].orEmpty().toMutableList()
            lines.filterNot { left.remove(it) }.map { "$file: $it" }
        }
        assertEquals("allowed reads that are gone (drop them here)", emptyList<String>(), stale)
    }

    /**
     * A failure's own `message` is read through `plainText` (util/PlainText.kt) or on a log
     * line, never shown as it is. What is left below shows nothing, or is not a failure told
     * to the person.
     */
    @Test
    fun `no screen shows a failure's own message`() {
        val read = Regex("""\b(e|it|err|ex|exception|throwable|cause|failure|lastFailure|t|error)\??\.(message|localizedMessage)\b""")
        val allowed = mapOf(
            // An attestation result nothing shows: its callers read only its token.
            "AttestationManager.kt" to listOf(
                "AttestationResult.Error(\"Attestation failed: \${e.message}\")",
                "AttestationResult.Error(\"Attestation failed: \${e.message}\")",
            ),
            // The crash record the person opens and exports from Settings: a diagnostic record.
            "CrashReporter.kt" to listOf("appendLine(\"Message: \${throwable.message ?: \"(no message)\"}\")"),
            // Firebase's or the UnifiedPush distributor's own text in
            // "Registration failed: …" (SettingsFragment): not a server's, so it
            // is allowed.
            "PushNotificationManager.kt" to listOf(
                "RegistrationResult.Failure(Provider.FCM, e.message ?: \"FCM register failed\")",
                "RegistrationResult.Failure(Provider.UNIFIEDPUSH, e.message ?: \"register failed\")",
                "RegistrationResult.Failure(Provider.UNIFIEDPUSH, e.message ?: \"register failed\")",
            ),
            // Inside a Log.w( … ) over two lines.
            "TokenAuthenticator.kt" to listOf("\"refreshToken: exception \${e.javaClass.simpleName}: \${e.message}\""),
        )
        check(scan(read), allowed, "a failure's own text reaches the screen")
    }

    /**
     * Nothing decides anything on a failure's own text: no `==`, `contains`, `startsWith`,
     * `endsWith`, `equals` or `when` over a failure's `message`, anywhere in src/main/java.
     * Decisions read a refusal's code or status. There are none, and this keeps it so.
     */
    @Test
    fun `nothing decides on a failure's own text`() {
        val read = """\b(e|it|err|ex|exception|throwable|cause|failure|lastFailure|t|error)\??\.(message|localizedMessage)\b"""
        val decision = Regex(
            """$read.*(==|!=|\.contains\(|\.startsWith\(|\.endsWith\(|\.equals\()|""" +
                """(==|!=|\.contains\(|\.startsWith\(|\.endsWith\(|\.equals\().*$read|\bwhen\s*\(.*$read"""
        )
        val found = mutableListOf<String>()
        java.io.File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().map { it.trim() }
                .filter { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
                .filter { decision.containsMatchIn(it) }
                .forEach { found.add("${file.name}: $it") }
        }
        assertEquals("a decision on a failure's own text", emptyList<String>(), found)
    }

    /**
     * Every read of a server's refusal text is on a log line, or one of the reads below, each
     * of which feeds only a log, a parse or a decision: never a message. A new read fails
     * here until it is one of those.
     */
    @Test
    fun `no read of a server's refusal text feeds anything but a log, a parse or a decision`() {
        val read = Regex("""errorBody\(\)|\b(?:body\(\)|body|apiResponse|refusal|parsed|resp|response)\??\.error\b(?![A-Za-z_])""")
        val allowed = mapOf(
            // Read for the Log.w on the next line.
            "AttestationManager.kt" to listOf("val errorBody = verifyResponse.errorBody()?.string()"),
            // The token refresh's log line after them; its Result is read only for isSuccess.
            "TokenAuthenticator.kt" to listOf(
                "val serverMsg = resp.body()?.error",
                "?: try { resp.errorBody()?.string() } catch (_: Exception) { null }",
            ),
            // ApiErrors.codeOf: the refusal's code, for plainLine and the decisions.
            "ApiErrors.kt" to listOf("runCatching { parse(response.errorBody()?.string())?.errorCode }.getOrNull()"),
        )
        check(scan(read), allowed, "server text read outside a log, a parse or a decision")
    }
}
