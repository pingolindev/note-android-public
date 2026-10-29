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

package com.pingolin.note.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.note.R
import com.pingolin.note.data.local.NoteDatabase
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.util.DatabasePassphraseManager
import com.pingolin.note.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuration activity for the Single Note widget.
 * Shows a list of notes for the user to pick which one to display.
 */
@dagger.hilt.android.AndroidEntryPoint
class SingleNoteConfigActivity : AppCompatActivity() {

    @javax.inject.Inject
    lateinit var itemDao: com.pingolin.note.data.local.dao.ItemDao

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This picker renders decrypted note titles — keep them out of
        // screenshots and the recents thumbnail (parity with AuthActivity and
        // the lock-screen capture, which stay always-secure).
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        // Set CANCELED result in case user backs out
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_config)

        val recyclerView = findViewById<RecyclerView>(R.id.config_note_list)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.config_toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // No note is read while signed out: the picker shows the
        // app's name over "Sign in to continue", with nothing to
        // pick, and backing out leaves RESULT_CANCELED, so the
        // widget isn't added.
        val session = SessionManager(this)
        if (!session.isLoggedIn()) {
            toolbar.title = getString(R.string.app_name)
            toolbar.subtitle = getString(R.string.auth_subtitle)
            return
        }

        // App Lock gate: this activity is launched straight from the launcher's
        // APPWIDGET_CONFIGURE flow, bypassing MainActivity's biometric gate.
        // Don't load or render any decrypted note until the same App Lock check
        // MainActivity uses passes; a cancel/fail closes the picker.
        if (session.requiresAppLockAuth()) {
            promptAppLock(
                onSuccess = { loadNotes(recyclerView) },
                onFail = { finish() },
            )
        } else {
            loadNotes(recyclerView)
        }
    }

    private fun loadNotes(recyclerView: RecyclerView) {
        CoroutineScope(Dispatchers.IO).launch {
            // Injected singleton, not a private open: a private open pays
            // the full SQLCipher KDF just to show the note picker. Asked
            // again at the read: the App Lock prompt may have run a while,
            // and the session with it.
            val notes = notesToPick(SessionManager(this@SingleNoteConfigActivity), itemDao) ?: return@launch

            withContext(Dispatchers.Main) {
                recyclerView.adapter = NotePickerAdapter(notes) { note ->
                    onNotePicked(note)
                }
            }
        }
    }

    /**
     * Biometric / device-credential prompt mirroring MainActivity's App Lock.
     * When no authenticator is configured we fall through to [onSuccess] like
     * MainActivity does, so enabling App Lock without a usable credential can't
     * hard-brick the widget picker.
     */
    private fun promptAppLock(onSuccess: () -> Unit, onFail: () -> Unit) {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            onSuccess()
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onFail()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Pingolin Note")
            .setSubtitle("Authenticate to pick a note for the widget")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun onNotePicked(note: ItemEntity) {
        WidgetPreferences.saveNoteIdForWidget(this, appWidgetId, note.id)

        val appWidgetManager = AppWidgetManager.getInstance(this)
        SingleNoteWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)

        val result = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        /** The notes the picker offers, or null while signed out, and then none is read. */
        internal fun notesToPick(
            session: SessionManager,
            itemDao: com.pingolin.note.data.local.dao.ItemDao,
        ): List<ItemEntity>? = if (session.isLoggedIn()) itemDao.getRecentNotesSync(50) else null
    }

    private class NotePickerAdapter(
        private val notes: List<ItemEntity>,
        private val onPick: (ItemEntity) -> Unit,
    ) : RecyclerView.Adapter<NotePickerAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.config_item_title)
            val body: TextView = view.findViewById(R.id.config_item_body)
            val colorDot: View = view.findViewById(R.id.config_item_color)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_widget_config_note, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val note = notes[position]
            holder.title.text = note.title?.takeIf { it.isNotBlank() } ?: "Untitled"

            // Defense-in-depth: show the title only — don't render decrypted
            // body text in the widget picker.
            holder.body.visibility = View.GONE

            if (note.color != null) {
                try {
                    holder.colorDot.setBackgroundColor(android.graphics.Color.parseColor(note.color))
                    holder.colorDot.visibility = View.VISIBLE
                } catch (_: Exception) {
                    holder.colorDot.visibility = View.GONE
                }
            } else {
                holder.colorDot.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onPick(note) }
        }

        override fun getItemCount(): Int = notes.size
    }
}
