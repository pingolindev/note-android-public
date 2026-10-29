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

package com.pingolin.note.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pingolin.note.R
import com.pingolin.note.domain.repository.ChecklistItem
import com.pingolin.note.domain.repository.NoteContent
import com.pingolin.note.domain.repository.NoteRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import android.os.Build

/**
 * Entry point for the Quick Capture widget's "New note" / "New checklist"
 * actions. Routes by lock state:
 *
 * - **Unlocked** → forward to [MainActivity] to open a fresh note in the FULL
 *   editor. There's no privacy barrier when the device is already unlocked, so
 *   the user gets the complete experience (and App Lock, if enabled, still
 *   gates MainActivity).
 *
 * - **Locked** → a MINIMAL capture form over the keyguard (mode label, title,
 *   body, Save, Discard) with setShowWhenLocked + FLAG_SECURE.
 *
 * The form below is the whole feature: nothing here can READ an existing note,
 * so no content is exposed on the lock screen and App Lock is not bypassed.
 *
 * The save path is [NoteRepository.createNote], which is local-first (the dirty
 * row lands before any network) and encrypts immediately under the vault key —
 * available while locked — so saving works fully offline and the sync worker
 * pushes after unlock. A "checklist" capture names itself as a checklist and
 * says that each non-blank line becomes an unchecked item. That keeps the form
 * deliberately simple while making the widget's checklist action visibly
 * different from New note.
 */
@AndroidEntryPoint
class LockScreenCaptureActivity : AppCompatActivity() {

    @Inject
    lateinit var noteRepository: NoteRepository

    private var saving = false
    private var noteType = "text"
    private val checklistRows = mutableListOf<ChecklistRow>()

    private data class ChecklistRow(
        val root: View,
        val checked: CheckBox,
        val text: EditText,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots / task-switcher thumbnails, and show over the
        // lock screen without requiring an unlock first.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        // setShowWhenLocked / setTurnScreenOn exist from API 27; below that
        // the same behaviour comes from window flags. minSdk is 26, so an
        // unguarded call from onCreate would throw NoSuchMethodError on
        // Android 8.0 (an Error, not an Exception, so a `catch (e: Exception)`
        // would not help) and the activity would die the moment it was opened.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        noteType = intent.getStringExtra(EXTRA_NOTE_TYPE) ?: "text"

        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isKeyguardLocked) {
            // Unlocked: hand off to the full editor in the app.
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_NEW_NOTE_TYPE, noteType)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
            )
            finish()
            return
        }

        setContentView(R.layout.activity_lock_capture)
        val isChecklist = noteType == "checklist"
        findViewById<TextView>(R.id.lockModeLabel).setText(
            if (isChecklist) R.string.lock_capture_mode_checklist
            else R.string.lock_capture_mode_note,
        )
        val title = findViewById<EditText>(R.id.lockTitle)
        val body = findViewById<EditText>(R.id.lockBody)
        val checklistSection = findViewById<View>(R.id.lockChecklistSection)
        val checklistContainer = findViewById<LinearLayout>(R.id.lockChecklistRows)
        val addItem = findViewById<Button>(R.id.lockAddItem)
        title.setHint(
            if (isChecklist) R.string.lock_capture_checklist_title_hint
            else R.string.lock_capture_title_hint,
        )
        body.setHint(R.string.lock_capture_body_hint)
        body.visibility = if (isChecklist) View.GONE else View.VISIBLE
        checklistSection.visibility = if (isChecklist) View.VISIBLE else View.GONE

        if (isChecklist) {
            val savedTexts = savedInstanceState?.getStringArrayList(STATE_CHECKLIST_TEXT)
            val savedChecked = savedInstanceState?.getBooleanArray(STATE_CHECKLIST_CHECKED)
            val count = maxOf(1, savedTexts?.size ?: 0)
            repeat(count) { index ->
                addChecklistRow(
                    container = checklistContainer,
                    text = savedTexts?.getOrNull(index).orEmpty(),
                    checked = savedChecked?.getOrNull(index) == true,
                )
            }
            addItem.setOnClickListener {
                addChecklistRow(checklistContainer, focus = true)
            }
        }

        val save = findViewById<Button>(R.id.lockSave)
        findViewById<Button>(R.id.lockDiscard).setOnClickListener { finish() }

        save.setOnClickListener {
            val titleText = title.text?.toString().orEmpty().trim()
            val bodyText = body.text?.toString().orEmpty()
            val checklistItems = if (isChecklist) checkedChecklistItems() else null
            if (titleText.isBlank() && bodyText.isBlank() && checklistItems.isNullOrEmpty()) {
                finish()
                return@setOnClickListener
            }
            // Guard the double-tap: createNote is suspend, and two taps before
            // the first returns would create two notes.
            if (saving) return@setOnClickListener
            saving = true
            save.isEnabled = false
            lifecycleScope.launch {
                try {
                    noteRepository.createNote(contentFor(titleText, bodyText, checklistItems))
                    Toast.makeText(
                        applicationContext,
                        R.string.lock_capture_saved,
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                } catch (e: Exception) {
                    // Most likely no vault key (signed out) — the text is
                    // still in the fields, so the user loses nothing by
                    // reading the message and unlocking.
                    Log.w(TAG, "lock capture save failed", e)
                    Toast.makeText(
                        applicationContext,
                        R.string.lock_capture_save_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    saving = false
                    save.isEnabled = true
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (noteType == "checklist") {
            outState.putStringArrayList(
                STATE_CHECKLIST_TEXT,
                ArrayList(checklistRows.map { it.text.text?.toString().orEmpty() }),
            )
            outState.putBooleanArray(
                STATE_CHECKLIST_CHECKED,
                checklistRows.map { it.checked.isChecked }.toBooleanArray(),
            )
        }
    }

    private fun addChecklistRow(
        container: LinearLayout,
        text: String = "",
        checked: Boolean = false,
        focus: Boolean = false,
    ) {
        val root = layoutInflater.inflate(R.layout.item_lock_capture_checklist, container, false)
        val check = root.findViewById<CheckBox>(R.id.lockItemChecked)
        val input = root.findViewById<EditText>(R.id.lockItemText)
        check.isChecked = checked
        check.contentDescription = getString(R.string.lock_capture_checklist_check)
        input.setText(text)
        root.findViewById<View>(R.id.lockItemRemove).setOnClickListener {
            if (saving) return@setOnClickListener
            checklistRows.removeAll { it.root === root }
            container.removeView(root)
            if (checklistRows.isEmpty()) addChecklistRow(container, focus = true)
        }
        container.addView(root)
        checklistRows += ChecklistRow(root, check, input)
        if (focus) {
            input.requestFocus()
            input.setSelection(input.text.length)
        }
    }

    private fun checkedChecklistItems(): List<ChecklistItem> =
        checklistRows.mapNotNull { row ->
            row.text.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { ChecklistItem(text = it, checked = row.checked.isChecked) }
        }

    private fun contentFor(
        title: String,
        body: String,
        checklistItems: List<ChecklistItem>?,
    ): NoteContent =
        if (checklistItems != null) {
            NoteContent(
                title = title,
                noteType = "checklist",
                checklistItems = checklistItems,
            )
        } else {
            NoteContent(title = title, body = body)
        }

    companion object {
        const val EXTRA_NOTE_TYPE = "note_type"
        private const val STATE_CHECKLIST_TEXT = "lock_checklist_text"
        private const val STATE_CHECKLIST_CHECKED = "lock_checklist_checked"
        private const val TAG = "LockCapture"
    }
}
