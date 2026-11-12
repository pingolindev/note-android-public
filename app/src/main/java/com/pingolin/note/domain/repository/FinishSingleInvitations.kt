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

package com.pingolin.note.domain.repository

import com.pingolin.note.data.remote.dto.UnfinishedShareDto
import kotlinx.coroutines.CancellationException

/**
 * A note shared ON ITS OWN with an address that had no account, finished once the address has
 * one: the web editor's `lib/finishSingleInvitations.ts`, ported rule for rule, so that the phone
 * finishes exactly the shares the owner's page would. [InvitationFinisher] runs it in the
 * background with the app's own steps; the page runs its copy while it is open.
 *
 * ## The rule (the page's)
 *
 * - The server lists the caller's shares an invitation left without a key
 *   (`GET /api/v1/shares/unfinished`). Only CLAIMED ones are finished: the address has an account,
 *   and the account has asked for its shares. Not one that ran out, and only notes.
 * - Only one made in the note's own Share panel (`single`): a notebook's share writes a row per
 *   note, and rows a removal from the notebook has not reached yet look the same.
 * - A note in a notebook whose standing list has the address (by the server's `notebook_id` or the
 *   note's notebook here) belongs to the NOTEBOOK's invitation, finished by its own rule.
 * - The directory is asked for the account's keys. No answer is not "no account": nothing is
 *   concluded, and it is asked again.
 * - Each row is tried at most once per [FINISH_RETRY_MS], and at most [FINISH_PER_PASS] rows are
 *   sealed in one pass: the server takes a set number of shares a minute from an owner.
 * - A 402 (the plan no longer shares) stops the pass: every other one would be refused the same way.
 *
 * What the page does not need: when each share was last tried is KEPT ([Tried]: a worker has no
 * page memory), and the server's answers to a finish are told apart ([Outcome]): a note re-keyed
 * meanwhile is tried again at the next pass; a 429 stops the pass, as a 402 does (the server takes
 * 30 shares a minute from an owner, and pushes can bunch); a pass whose account changed stops.
 */
object FinishSingleInvitations {

    /** A row is tried at most once in this long (the page's `FINISH_RETRY_MS`). */
    const val FINISH_RETRY_MS = 10 * 60_000L

    /** How many shares one pass seals at most (the page's `FINISH_PER_PASS`). */
    const val FINISH_PER_PASS = 10

    private const val NOTE = 1

    /** A person's CURRENT keys as the account directory publishes them (the page's recipientKeys.ts
     *  `DirectoryKeys`): their user id, and their X25519 sharing and Ed25519 signing keys, hex. */
    data class DirectoryKeys(val userId: String, val sharingPub: String, val signingPub: String)

    /** What finishing one share came to. */
    enum class Outcome {
        /** Finished: the share has its key. */
        DONE,
        /** This device can't open the note's content key (the page: 'not-here'). */
        NOT_HERE,
        /** Not sealed: the person's key isn't one trusted here (the page: 'key-changed'). */
        KEY_CHANGED,
        /** The wrap could not be signed, so it was not sent (the page: 'cannot-sign'). */
        CANNOT_SIGN,
        /** 402: the plan no longer includes sharing. Stops the pass (the page: 'upgrade'). */
        UPGRADE,
        /** Anything else (the page: 'failed'). */
        FAILED,
        /** 409 `share_not_unfinished`: no keyless share of the address to finish (removed, finished
         *  on another device, or the address belongs to another account now). Left alone. */
        NOT_UNFINISHED,
        /** 409 `share_generation_stale`: the note was re-keyed meanwhile. Not counted as tried, so
         *  the next pass reads the note again. */
        STALE,
        /** 5xx: tried again later. */
        LATER,
        /** 429: the server takes no more shares from this owner just now (30 a minute). Stops the
         *  pass; the rows it didn't reach are untried, and due at the next pass. */
        RATE_LIMITED,
        /** Another account (or none) is signed in now: the pass stops, sending nothing more. */
        ABORTED,
    }

    /** What the pass asks of the app (the page's `SingleFinishSteps`). */
    interface Steps {
        /** The server's list; null when it could not be read. */
        suspend fun unfinished(): List<UnfinishedShareDto>?
        /** Whether the notebook's standing list has this address, waiting or finished. */
        fun inStanding(notebookId: String, email: String): Boolean
        /** The notebook this device has the note in, if it has the note and it is in one. */
        suspend fun localNotebookOf(itemId: String): String?
        /** The directory's keys for these addresses, by lower-cased address; null when it could
         *  not be asked. */
        suspend fun lookup(emails: List<String>): Map<String, DirectoryKeys>?
        /** Seal the note for [keys] and send it as a FINISH. */
        suspend fun finish(share: UnfinishedShareDto, keys: DirectoryKeys): Outcome
        fun now(): Long
    }

    /** When each share was last tried, by share id (the page's `SingleTried`). */
    interface Tried {
        fun get(shareId: String): Long?
        fun set(shareId: String, at: Long)
        fun delete(shareId: String)
    }

    /** The shares this pass may finish, in the server's order (the page's `dueToFinish`). */
    suspend fun dueToFinish(
        rows: List<UnfinishedShareDto>,
        inStanding: (notebookId: String, email: String) -> Boolean,
        localNotebookOf: suspend (itemId: String) -> String?,
        tried: Tried,
        now: Long,
    ): List<UnfinishedShareDto> {
        val due = mutableListOf<UnfinishedShareDto>()
        for (r in rows) {
            if (!(r.claimed == true && r.expired != true && r.itemType == NOTE)) continue
            // Only a share made on its own. A notebook's share wrote a row per note, and a removal
            // from the notebook deletes them one at a time: rows left by a removal that stopped
            // halfway look like this too, and must not be finished by another of the owner's
            // devices (the page's review of 2026-11-11).
            if (r.single != true) continue
            // What finishing it needs (the server always sends them).
            val shareId = r.shareId ?: continue
            val itemId = r.itemId ?: continue
            val email = r.recipientEmail ?: continue
            // Nor one in a notebook whose standing list has the address, by the server's word or
            // this device's: that invitation is the notebook's.
            val books = listOf(r.notebookId, localNotebookOf(itemId))
            if (books.any { nb -> !nb.isNullOrEmpty() && inStanding(nb, email) }) continue
            // A try "in the future" (the clock went back since: the tries are kept) holds nothing
            // back; the page's tries die with the page and never meet that.
            val last = tried.get(shareId)
            if (last != null && now - last in 0 until FINISH_RETRY_MS) continue
            due += r
            if (due.size == FINISH_PER_PASS) break
        }
        return due
    }

    /**
     * Finish the caller's single-note shares whose people have an account now (the page's
     * `finishSingleInvitations`).
     *
     * @return the share ids that were finished.
     */
    suspend fun finishSingleInvitations(steps: Steps, tried: Tried): List<String> {
        val rows = try {
            steps.unfinished()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val due = dueToFinish(rows, steps::inStanding, steps::localNotebookOf, tried, steps.now())
        if (due.isEmpty()) return emptyList()
        // No answer is not "no account": nothing is concluded, and it is asked again.
        val directory = try {
            steps.lookup(due.mapNotNull { it.recipientEmail?.lowercase() }.distinct())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val finished = mutableListOf<String>()
        for (row in due) {
            val shareId = row.shareId ?: continue
            val email = row.recipientEmail ?: continue
            tried.set(shareId, steps.now())
            val keys = directory[email.lowercase()] ?: continue
            val outcome = try {
                steps.finish(row, keys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.FAILED
            }
            when (outcome) {
                Outcome.DONE -> {
                    finished += shareId
                    tried.delete(shareId)
                }
                // The owner's plan no longer includes sharing, or the server takes no more shares
                // just now: every other one would be refused the same way. Or another account is
                // signed in now: nothing more goes out.
                Outcome.UPGRADE, Outcome.RATE_LIMITED, Outcome.ABORTED -> break
                // Re-keyed meanwhile: read again at the next pass, not in ten minutes.
                Outcome.STALE -> tried.delete(shareId)
                else -> {}
            }
        }
        return finished
    }
}
