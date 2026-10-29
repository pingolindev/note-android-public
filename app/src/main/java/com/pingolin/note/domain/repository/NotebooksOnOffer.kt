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

import java.time.Instant

/**
 * Which shared notes are held back because of an INVITATION, and which notebooks count as ON
 * OFFER when a refresh could not read the inbox to its end.
 *
 * The notes of an invitation nobody has answered are kept off the widgets ("Hide unanswered
 * invitations"). Whether a note's notebook is on offer is known from the notebook's OWN row, and
 * that row is among the oldest: a read that stops early (a later page failed, or the inbox runs
 * past the page limit) never reaches it. Taking the notebook for "not on offer" then would write
 * its notes, titles and first lines, into the database the widgets read.
 *
 * THE SERVER SAYS what is on offer, on the first page of every read (`offered_notebooks`): the
 * whole list, from the same moment as that page. With it a read knows what is on offer however
 * far it got, and for an inbox of any length.
 *
 * WITHOUT it (a server from before the list, one that has more notebooks on offer than its
 * answer names, or its lookup failing) the read works it out itself. A read that stopped early
 * also counts what the last read TO THE END found on offer; and when there has never been one on
 * this device, nothing says what is on offer and every note of a notebook that has not been
 * accepted waits ([unknown], [heldForNotKnowing]). That alone falls short: an invitation that
 * arrived AFTER the last read to the end, whose own row a later read did not reach, is in
 * neither set, and its notes would be written to the database the widgets read. That is the
 * common case for a new invitation with a page's worth of notes: its own row is the oldest.
 *
 * So, still without the list, a read that stopped early also holds what ARRIVED since anything
 * last knew what was on offer and names a notebook nothing is known about
 * ([heldForArrivingUnseen]). Only that: a note that was here when the last read that knew made
 * its decision keeps it, so a page that fails does not take notes off a widget that had every
 * right to show them.
 *
 * Pure, so each rule has a test (NotebooksOnOfferTest). All ids canonical.
 */
internal object NotebooksOnOffer {

    /**
     * The notebooks on offer.
     *
     * @param served the notebooks whose own row THIS read reached
     * @param complete whether this read reached the inbox's end
     * @param lastComplete what the last read to the end found on offer; null when there was none
     * @param declined the notebooks the server says are declined now. That is newer than
     *   [lastComplete] (it comes with the first page of this read): one of them has been
     *   answered, unless this read serves it again.
     * @param said the server's own list of what is on offer, from this read's first page; null
     *   when it did not say. Whole when it is there, so nothing else is asked: neither whether
     *   this read reached the end, nor what an earlier one found. (The rows this read reached
     *   are counted beside it; they are in it, and counting them costs nothing.)
     */
    fun of(
        served: Set<String>,
        complete: Boolean,
        lastComplete: Set<String>?,
        declined: Set<String>,
        said: Set<String>? = null,
    ): Set<String> = when {
        said != null -> said + served
        complete -> served
        else -> served + (lastComplete.orEmpty() - declined)
    }

    /**
     * Nothing can say which notebooks are on offer: the server did not, this read stopped early
     * and none ever reached the end.
     */
    fun unknown(complete: Boolean, lastComplete: Set<String>?, said: Set<String>? = null): Boolean =
        said == null && !complete && lastComplete == null

    /**
     * The server's list in canonical ids, or null when it did not say or one entry is no id. The
     * whole list or nothing (see InboxAnswer): an id dropped from it would read as a notebook
     * that is not on offer.
     */
    fun said(byTheServer: Set<String>?, canonical: (String) -> String?): Set<String>? {
        if (byTheServer == null) return null
        val out = HashSet<String>()
        for (id in byTheServer) out += canonical(id) ?: return null
        return out
    }

    /**
     * Is a share of a note that names [notebook] held because of an invitation?
     *
     * - Its notebook is on offer and not answered ([unanswered]).
     * - Or the note was held for that until now ([heldForOneBefore]) and its notebook has
     *   since been DECLINED. A declined notebook is not on offer, and the rule for a declined
     *   notebook keeps away only what was shared after the decline (DeclinedNotebooks):
     *   without this, saying no to an invitation would put its earlier notes on the widget,
     *   the ones the server could not dismiss with it because their rows do not name the
     *   notebook. The page dismisses those on the server when it declines; until that has
     *   happened, and where it failed, they stay held here for as long as this process runs.
     */
    fun heldForAnInvitation(
        notebook: String?,
        unanswered: Set<String>,
        declined: Set<String>,
        heldForOneBefore: Boolean,
    ): Boolean = notebook != null && (notebook in unanswered || (heldForOneBefore && notebook in declined))

    /**
     * Is a share of a note that names [notebook] held because it ARRIVED after anything last
     * knew what was on offer, in a notebook nothing is known about?
     *
     * For a read that stopped early with no word from the server ([stoppedEarlyUnsaid]). The
     * notebooks an earlier read found on offer are counted ([of]), and they say nothing of an
     * invitation made since: its notes are the inbox's newest rows, its own row the oldest of
     * it, on a page this read did not come to. Taken for a note shared on its own, such a note
     * would be written to the database the widgets read.
     *
     * @param known every notebook something IS known about: accepted, on offer (reached now, or
     *   found on offer by the last read that knew), or declined. Each has its own rule.
     * @param sharedAt the share's `created_at` as the server wrote it
     * @param knownAsOf the newest share the last read that knew what was on offer had seen
     *   ([newest]); null when no such read saw any
     *
     * A time that cannot be read holds: of a note that may be an unanswered invitation's, the
     * safe side is the one that shows nothing.
     */
    fun heldForArrivingUnseen(
        notebook: String?,
        stoppedEarlyUnsaid: Boolean,
        known: Set<String>,
        sharedAt: String?,
        knownAsOf: String?,
    ): Boolean {
        if (notebook == null || !stoppedEarlyUnsaid || notebook in known) return false
        val since = knownAsOf?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
        val shared = sharedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
        return shared.isAfter(since)
    }

    /** The newest of these times, as it was served; null when none of them can be read. */
    fun newest(sharedAt: Collection<String>): String? =
        sharedAt
            .mapNotNull { served -> runCatching { Instant.parse(served) }.getOrNull()?.let { it to served } }
            .maxByOrNull { it.first }
            ?.second

    /** Is a share of a note that names [notebook] held because nothing says what is on offer ([unknown])? */
    fun heldForNotKnowing(notebook: String?, unknown: Boolean, accepted: Set<String>): Boolean =
        notebook != null && unknown && notebook !in accepted

    /**
     * The items held back from now on.
     *
     * @param heldNow the items this read decided to hold
     * @param decidedNow every item this read reached and decided about, held or not
     * @param heldBefore what was held until now
     *
     * A read to the end decides about everything. One that stopped early says nothing about the
     * notes it did not reach: what was held among those stays held.
     */
    fun held(heldNow: Set<String>, decidedNow: Set<String>, heldBefore: Set<String>, complete: Boolean): Set<String> =
        if (complete) heldNow else heldNow + (heldBefore - decidedNow)
}
