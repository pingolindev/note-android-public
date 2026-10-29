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

package com.pingolin.note.importer

import com.pingolin.note.data.local.entity.ItemEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Pingolin Note backup format, Android side.
 *
 * WHY THIS EXISTS
 *   One envelope for every client, defined by the Note web editor (its
 *   `backupFormat.ts`) and mirrored here: same keys, same version, same refusal
 *   rules — see [com.pingolin.note.importer.parser.PingolinBackupParser] for the
 *   read side. Two exports that disagree — different names for the same field
 *   (`content` vs `body`), neither a superset of the other — would give the same
 *   account two different answers depending on which device the user picked, and
 *   neither could be read back.
 *
 * WHAT IT DELIBERATELY DOES NOT CARRY
 *   Attachment bytes (object storage, up to 100MB a user) — the manifest names
 *   them so a restore can say what it could not bring back. And cached share
 *   recipient keys, because a key that has rotated since would wrap future notes
 *   for whoever held it; recipients are looked up fresh.
 */
object BackupFormat {

    const val FORMAT = "pingolin-note-backup"
    const val VERSION = 2

    /**
     * Build the backup document.
     *
     * Notes are emitted from their stored content JSON rather than from a typed
     * model, so keys this build does not know about — a newer client's fields —
     * ride along instead of being silently dropped. That is the same reason
     * `NoteContent.extras` exists one layer down.
     *
     * @param notes active (non-deleted) note rows
     * @param metadata the decrypted user-metadata blob, or null if unavailable
     * @param attachmentNames noteId → filenames the backup cannot carry, or null
     *   for a note whose list could not be read. Passed IN rather than fetched
     *   here so this stays pure and testable — naming the files needs a network
     *   call plus a per-attachment decrypt, which is the caller's job.
     */
    fun build(
        notes: List<ItemEntity>,
        metadata: JSONObject?,
        attachmentNames: Map<String, List<String>?> = emptyMap(),
    ): JSONObject {
        val notesArray = JSONArray()
        var withAttachments = 0
        val attachments = JSONArray()

        for (item in notes) {
            val content = item.decryptedContent
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()

            val hasAttachments = content.optBoolean("has_attachments", false)
            if (hasAttachments) {
                withAttachments++
                attachments.put(JSONObject().apply {
                    put("noteId", item.id)
                    // null means "couldn't read the list", which is a different
                    // thing from an empty list and must stay distinguishable.
                    val names = attachmentNames[item.id]
                    put("filenames", if (names == null) JSONObject.NULL else JSONArray(names))
                })
            }

            val note = JSONObject().apply {
                // Preserved so a re-import is idempotent: the local insert is
                // insert-if-absent and the server's is ON CONFLICT DO NOTHING,
                // so restoring the same file twice converges.
                put("id", item.id)
                put("title", content.optString("title", item.title ?: ""))
                put("body", content.optString("body", ""))
                put("noteType", content.optString("note_type", "text"))
                put("color", content.opt("color") ?: JSONObject.NULL)
                put("isPinned", content.optBoolean("is_pinned", item.isPinned))
                put("isArchived", content.optBoolean("is_archived", item.isArchived))
                put("labels", content.optJSONArray("labels") ?: JSONArray())
                content.optJSONArray("checklist_items")?.let { put("checklistItems", renameChecklist(it)) }
                put("sortOrder", content.optInt("sort_order", 0))
                put("editMode", content.optString("edit_mode", "markdown"))
                put("notebookId", content.opt("notebook_id") ?: item.notebookId ?: JSONObject.NULL)
                put("hasAttachments", hasAttachments)
                put("createdAt", item.createdAt.toString())
                put("updatedAt", item.updatedAt.toString())
                extrasOf(content)?.let { put("extras", it) }
            }
            notesArray.put(note)
        }

        val notebooks = metadata?.optJSONArray("notebooks") ?: JSONArray()
        val labelColors = metadata?.optJSONObject("labelColors") ?: JSONObject()
        val editorModes = metadata?.optJSONObject("editorModes") ?: JSONObject()
        val notebookShares = stripCachedKeys(metadata?.optJSONObject("notebookShares"))

        val reasons = JSONArray()
        if (withAttachments > 0) {
            reasons.put(
                "$withAttachments note(s) have attachments. Backups list attachments but do not contain the files themselves."
            )
        }
        if (metadata == null) {
            reasons.put("Notebooks, label colours and editor pins could not be read, so they are not in this backup.")
        }

        return JSONObject().apply {
            put("format", FORMAT)
            put("formatVersion", VERSION)
            put("client", "android")
            put("generatedAt", java.time.Instant.now().toString())
            put("isComplete", reasons.length() == 0)
            if (reasons.length() > 0) put("incompleteReasons", reasons)
            put("counts", JSONObject().apply {
                put("notes", notesArray.length())
                put("notebooks", notebooks.length())
                put("reminders", 0)
                put("notesWithAttachments", withAttachments)
            })
            put("notebooks", notebooks)
            put("labelColors", labelColors)
            put("editorModes", editorModes)
            put("notebookShares", notebookShares)
            put("notes", notesArray)
            // Reminders are read from the server, not from the local blob, and
            // this client does not hold them — declared empty rather than
            // omitted so a reader can tell "none" from "this client didn't try".
            put("reminders", JSONArray())
            put("attachments", attachments)
        }
    }

    /**
     * Merge a backup's metadata into the live blob, IN PLACE — the caller wraps this
     * in a single [com.pingolin.note.domain.repository.UserMetadataStore.mutate],
     * because all of this lives in ONE `item_type=10` row that every client rewrites
     * whole. Restoring twenty notebooks one call at a time would be twenty round-trips
     * racing over that row.
     *
     * ADDITIVE, never destructive. Fills in what is missing and leaves anything
     * present alone: a notebook renamed since the backup keeps its new name, because a
     * restore must not overwrite newer work with older content.
     *
     * Notebook ids are PRESERVED, which is what lets restored notes land in the right
     * notebook — the note carries `notebook_id`, and a fresh id would leave every one
     * of them unfiled.
     */
    fun mergeMetadata(root: JSONObject, backup: JSONObject): MergeResult {
        var notebooksAdded = 0
        val existingNotebooks = root.optJSONArray("notebooks") ?: JSONArray()
        val haveIds = buildSet {
            for (i in 0 until existingNotebooks.length()) {
                existingNotebooks.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        val incoming = backup.optJSONArray("notebooks") ?: JSONArray()
        for (i in 0 until incoming.length()) {
            val nb = incoming.optJSONObject(i) ?: continue
            val id = nb.optString("id").takeIf { it.isNotEmpty() } ?: continue
            if (id in haveIds) continue
            existingNotebooks.put(JSONObject().apply {
                put("id", id)
                put("name", nb.optString("name", ""))
            })
            notebooksAdded++
        }
        if (notebooksAdded > 0) root.put("notebooks", existingNotebooks)

        val labelsAdded = mergeMissingKeys(root, backup, "labelColors")
        val pinsAdded = mergeMissingKeys(root, backup, "editorModes")
        // No share list is merged back: a backup's is an old consent
        // state, and a restore never re-arms sharing.

        return MergeResult(notebooksAdded, labelsAdded, pinsAdded)
    }

    data class MergeResult(
        val notebooksAdded: Int,
        val labelColorsAdded: Int,
        val editorModesAdded: Int,
    ) {
        fun changedAnything() =
            notebooksAdded > 0 || labelColorsAdded > 0 || editorModesAdded > 0
    }

    /** Copy only the keys the live object does not already have. */
    private fun mergeMissingKeys(root: JSONObject, backup: JSONObject, key: String): Int {
        val incoming = backup.optJSONObject(key) ?: return 0
        val existing = root.optJSONObject(key) ?: JSONObject()
        var added = 0
        for (k in incoming.keys()) {
            if (existing.has(k)) continue
            existing.put(k, incoming.get(k))
            added++
        }
        if (added > 0) root.put(key, existing)
        return added
    }

    /** `{text, checked}` in the stored JSON is already the backup's shape. */
    private fun renameChecklist(items: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            out.put(JSONObject().apply {
                put("text", it.optString("text", ""))
                put("checked", it.optBoolean("checked", false))
            })
        }
        return out
    }

    /** Content-JSON keys the typed model does not own, so a newer client's
     *  fields survive the trip. Mirrors `NoteContent.KNOWN_KEYS`. */
    private fun extrasOf(content: JSONObject): JSONObject? {
        var extras: JSONObject? = null
        for (key in content.keys()) {
            if (key in KNOWN_CONTENT_KEYS) continue
            (extras ?: JSONObject().also { extras = it }).put(key, content.get(key))
        }
        return extras
    }

    /**
     * Recipients keep their email and permission; their cached public keys and
     * user ids do not travel. Reusing a cached key after it has rotated wraps a
     * note for whoever held the old one.
     */
    private fun stripCachedKeys(shares: JSONObject?): JSONObject {
        val out = JSONObject()
        if (shares == null) return out
        for (notebookId in shares.keys()) {
            val list = shares.optJSONArray(notebookId) ?: continue
            val cleaned = JSONArray()
            for (i in 0 until list.length()) {
                val r = list.optJSONObject(i) ?: continue
                val email = r.optString("email").takeIf { it.isNotEmpty() } ?: continue
                cleaned.put(JSONObject().apply {
                    put("email", email)
                    put("permission", if (r.optString("permission") == "view") "view" else "edit")
                })
            }
            out.put(notebookId, cleaned)
        }
        return out
    }

    private val KNOWN_CONTENT_KEYS = setOf(
        "title", "body", "note_type", "color", "is_pinned", "is_archived",
        "labels", "sort_order", "edit_mode", "notebook_id", "checklist_items",
        "has_attachments",
    )
}
