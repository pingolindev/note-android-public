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

package com.pingolin.note.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.pingolin.note.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.pingolin_mobile.CryptoManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.pm.PackageInfoCompat

/**
 * Privacy-focused crash reporter with optional E2EE remote reporting.
 *
 * Opt-in twice over: local crash logging is off by default, and sending those
 * logs off the device is a second, separate opt-in. Enabling local logging
 * installs this as the default uncaught exception handler; the user can read,
 * share and delete the logs from Settings, and they auto-expire after 7 days.
 *
 * No personally identifiable information is collected:
 * - No user IDs, emails, or names
 * - No device fingerprints or advertising IDs
 * - No location data
 * - No exact device model (only a broad category)
 *
 * Remote reporting (when enabled):
 * - Report content is encrypted client-side with XChaCha20-Poly1305, so the
 *   server holds a blob it cannot read
 * - The key derives from a random installation ID, nothing user-specific, so
 *   reports don't correlate to an account
 * - A report carries only: stack trace, generic device info, app version
 */
@Singleton
class CrashReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: SharedPreferences,
    // LAZY, both of them, and it matters at launch.
    //
    // This class is injected into NoteApplication and constructed during
    // Application.onCreate, so every constructor parameter is built before
    // the first Activity exists. These two are the expensive ones:
    // CryptoManager dlopens the 8MB uniffi library and resolves its
    // symbols, and the shared OkHttpClient pulls in SessionManager, whose
    // EncryptedSharedPreferences open is 6 Keystore round trips (~38ms on
    // an A50, the first of 42 measured across a launch). Neither is touched
    // until a crash is actually REPORTED — cryptoManager only in the two
    // encrypt calls below, the client only inside the `httpClient` lazy —
    // so paying for them on the startup path would buy nothing.
    //
    // initialize() still runs early and still installs the
    // uncaught-exception handler synchronously; that is the part that has
    // to happen before anything can crash, and it needs neither of these.
    private val cryptoManager: dagger.Lazy<CryptoManager>,
    private val sharedOkHttpClient: dagger.Lazy<OkHttpClient>
) {
    companion object {
        private const val TAG = "CrashReporter"
        private const val PREF_CRASH_REPORTING_ENABLED = "crash_reporting_enabled"
        private const val PREF_REMOTE_REPORTING_ENABLED = "crash_remote_reporting_enabled"
        private const val PREF_INSTALLATION_ID = "crash_reporter_installation_id"
        private const val CRASH_LOG_DIR = "crash_logs"
        private const val MAX_CRASH_LOGS = 10
        private const val CRASH_LOG_EXPIRY_DAYS = 7L
        private const val REMOTE_REPORT_ENDPOINT = "/api/v1/diagnostics/crash-report"

        @Volatile
        private var instance: CrashReporter? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val crashLogDir: File by lazy {
        File(context.filesDir, CRASH_LOG_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var isInstalled = false

    // HTTP client derived from the shared OkHttpClient (inherits connection pool, interceptors, etc.)
    // No auth header needed for crash reporting - uses anonymous endpoint
    private val httpClient: OkHttpClient by lazy {
        sharedOkHttpClient.get().newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Get or create a random installation ID for crash report encryption.
     * This ID is NOT linked to user accounts - it's purely for E2EE key derivation.
     */
    private fun getInstallationId(): String {
        return prefs.getString(PREF_INSTALLATION_ID, null) ?: run {
            val newId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(PREF_INSTALLATION_ID, newId).apply()
            newId
        }
    }

    /** Local crash logging: opt-in, so absent preference means disabled. */
    fun isEnabled(): Boolean {
        return prefs.getBoolean(PREF_CRASH_REPORTING_ENABLED, false)
    }

    /** Remote reporting is a second opt-in, independent of local logging. */
    fun isRemoteReportingEnabled(): Boolean {
        return prefs.getBoolean(PREF_REMOTE_REPORTING_ENABLED, false)
    }

    /**
     * Toggle local crash logging, installing or restoring the uncaught
     * exception handler to match. Turning it off also forces remote reporting
     * off: remote transmission has nothing to send without a local log, and
     * leaving it armed would resume uploads the moment logging came back.
     */
    fun setEnabled(enabled: Boolean) {
        val wasEnabled = isEnabled()
        prefs.edit().putBoolean(PREF_CRASH_REPORTING_ENABLED, enabled).apply()

        if (enabled && !wasEnabled) {
            install()
            Log.i(TAG, "Local crash reporting enabled by user")
        } else if (!enabled && wasEnabled) {
            uninstall()
            setRemoteReportingEnabled(false)
            Log.i(TAG, "Crash reporting disabled by user")
        }
    }

    /**
     * Toggle E2EE remote crash reporting. Refuses to turn on unless local crash
     * logging is already enabled, so it can never become the only thing the user
     * consented to.
     */
    fun setRemoteReportingEnabled(enabled: Boolean) {
        if (enabled && !isEnabled()) {
            Log.w(TAG, "Cannot enable remote reporting without local crash reporting")
            return
        }
        prefs.edit().putBoolean(PREF_REMOTE_REPORTING_ENABLED, enabled).apply()
        if (enabled) {
            Log.i(TAG, "E2EE remote crash reporting enabled by user")
        } else {
            Log.i(TAG, "E2EE remote crash reporting disabled by user")
        }
    }

    /**
     * Initialize crash reporting based on user preference.
     * Call this from Application.onCreate().
     */
    fun initialize() {
        instance = this
        if (isEnabled()) {
            install()
            Log.i(TAG, "Crash reporting initialized (enabled)")
        } else {
            Log.i(TAG, "Crash reporting initialized (disabled - opt-in required)")
        }
        // Clean up old crash logs regardless of enabled state.
        //
        // OFF THE MAIN THREAD: this runs from Application.onCreate, and the body
        // walks the crash-log directory twice and stats every file — plus the
        // `crashLogDir` lazy does a mkdirs on first touch. None of it is urgent
        // (the logs expire on age, not on promptness), and this class already
        // owns an IO scope for exactly this kind of work.
        scope.launch { cleanupOldLogs() }
    }

    private fun install() {
        if (isInstalled) return

        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler())
        isInstalled = true
    }

    /** Restore whichever handler was installed before us. */
    private fun uninstall() {
        if (!isInstalled) return

        originalHandler?.let {
            Thread.setDefaultUncaughtExceptionHandler(it)
        }
        originalHandler = null
        isInstalled = false
    }

    /** Crash log files, newest first. */
    fun getCrashLogs(): List<CrashLogInfo> {
        return crashLogDir.listFiles()
            ?.filter { it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                CrashLogInfo(
                    fileName = file.name,
                    timestamp = Date(file.lastModified()),
                    sizeBytes = file.length()
                )
            }
            ?: emptyList()
    }

    fun readCrashLog(fileName: String): String? {
        val file = File(crashLogDir, fileName)
        return if (file.exists() && file.parentFile == crashLogDir) {
            file.readText()
        } else {
            null
        }
    }

    fun deleteCrashLog(fileName: String): Boolean {
        val file = File(crashLogDir, fileName)
        return if (file.exists() && file.parentFile == crashLogDir) {
            file.delete()
        } else {
            false
        }
    }

    fun deleteAllCrashLogs() {
        crashLogDir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "All crash logs deleted")
    }

    /**
     * Drop logs older than CRASH_LOG_EXPIRY_DAYS, then cap what's left at
     * MAX_CRASH_LOGS so a crash loop can't fill the data directory.
     */
    private fun cleanupOldLogs() {
        val expiryTime = System.currentTimeMillis() - (CRASH_LOG_EXPIRY_DAYS * 24 * 60 * 60 * 1000)
        val files = crashLogDir.listFiles() ?: return

        var deletedCount = 0
        files.filter { it.lastModified() < expiryTime }
            .forEach {
                if (it.delete()) deletedCount++
            }

        val remainingFiles = crashLogDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?: return

        if (remainingFiles.size > MAX_CRASH_LOGS) {
            remainingFiles.drop(MAX_CRASH_LOGS).forEach {
                if (it.delete()) deletedCount++
            }
        }

        if (deletedCount > 0) {
            Log.i(TAG, "Cleaned up $deletedCount old crash logs")
        }
    }

    /**
     * Log an exception without crashing.
     * Useful for non-fatal errors that should be tracked.
     */
    fun logException(throwable: Throwable, context: String? = null) {
        if (!isEnabled()) return

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "exception_${timestamp}.log"
            val file = File(crashLogDir, fileName)

            val logContent = buildLogContent(throwable, context, isCrash = false)
            file.writeText(logContent)
            Log.i(TAG, "Exception logged to $fileName")

            if (isRemoteReportingEnabled()) {
                sendEncryptedReport(logContent, isCrash = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log exception", e)
        }
    }

    /**
     * Send an encrypted crash report to the diagnostics endpoint. The server
     * sees only the encrypted blob plus timestamp, app version and Android
     * version — no user id, no device id, no other PII.
     */
    private fun sendEncryptedReport(content: String, isCrash: Boolean) {
        scope.launch {
            try {
                // The report key comes from the installation ID alone, never from
                // anything account-linked.
                val installationId = getInstallationId()
                val keyMaterial = hashWithSha256(installationId.toByteArray())
                val keyBase64 = android.util.Base64.encodeToString(keyMaterial, android.util.Base64.NO_WRAP)

                val encryptedData = cryptoManager.get().encryptWithKey(content, keyBase64)

                // Metadata rides in the clear — non-identifying fields only.
                val metadata = buildString {
                    append("{")
                    append("\"type\":\"${if (isCrash) "crash" else "exception"}\",")
                    append("\"app_version\":\"${BuildConfig.VERSION_NAME}\",")
                    append("\"android_api\":${Build.VERSION.SDK_INT},")
                    append("\"timestamp\":${System.currentTimeMillis()},")
                    // Generic device category (not exact model to prevent fingerprinting)
                    append("\"device_category\":\"${getGenericDeviceCategory()}\",")
                    append("\"encrypted_nonce\":\"${encryptedData.nonce}\",")
                    append("\"encrypted_data\":\"${encryptedData.ciphertext}\"")
                    append("}")
                }

                val request = Request.Builder()
                    .url(BuildConfig.AUTH_BASE_URL + REMOTE_REPORT_ENDPOINT)
                    .post(metadata.toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "Note/${BuildConfig.VERSION_NAME} CrashReporter")
                    // Anonymous marker: the endpoint lives on the AUTH host, which is
                    // in @ApiHosts, so WITHOUT this the shared OkHttpClient's auth
                    // interceptor would attach the session Bearer — relinking a report
                    // that is contractually "not linked to user accounts". This
                    // sentinel makes AppModule's interceptor strip the header and send
                    // NO Authorization (and TokenAuthenticator skip re-attach on 401).
                    .header("X-Pingolin-Anon", "1")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "E2EE crash report sent successfully")
                    } else {
                        Log.w(TAG, "Failed to send crash report: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                // Silently fail - don't crash while reporting a crash
                Log.e(TAG, "Failed to send E2EE crash report", e)
            }
        }
    }

    /**
     * Get a generic device category to prevent device fingerprinting.
     * Returns broad categories like "phone", "tablet", "tv" instead of exact models.
     */
    private fun getGenericDeviceCategory(): String {
        val screenLayout = context.resources.configuration.screenLayout
        val screenSize = screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK

        return when {
            context.packageManager.hasSystemFeature("android.software.leanback") -> "tv"
            context.packageManager.hasSystemFeature("android.hardware.type.watch") -> "watch"
            context.packageManager.hasSystemFeature("android.hardware.type.automotive") -> "automotive"
            screenSize >= android.content.res.Configuration.SCREENLAYOUT_SIZE_XLARGE -> "tablet"
            screenSize >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE -> "tablet"
            else -> "phone"
        }
    }

    private fun hashWithSha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }


    /**
     * Build the content of a crash/exception log.
     * Includes device info and stack trace but NO personal data.
     */
    private fun buildLogContent(throwable: Throwable, context: String?, isCrash: Boolean): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        return buildString {
            appendLine("=".repeat(60))
            appendLine(if (isCrash) "CRASH REPORT" else "EXCEPTION REPORT")
            appendLine("=".repeat(60))
            appendLine()
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine()
            appendLine("--- Device Info ---")
            // Generic device category only (phone/tablet/tv/…), NOT the exact
            // manufacturer/model — honors this class's contract ("No exact device
            // model") for both the local log and the encrypted remote report body,
            // which are both this same buildLogContent output.
            appendLine("Device: ${getGenericDeviceCategory()}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("App Version: ${getAppVersion()}")
            appendLine()
            if (context != null) {
                appendLine("--- Context ---")
                appendLine(context)
                appendLine()
            }
            appendLine("--- Exception ---")
            appendLine("Type: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message ?: "(no message)"}")
            appendLine()
            appendLine("--- Stack Trace ---")
            appendLine(stackTrace)
            appendLine()
            appendLine("=".repeat(60))
            appendLine("This log was generated locally and NOT sent to any server.")
            appendLine("You can share it manually for support if needed.")
            appendLine("=".repeat(60))
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            // PackageInfoCompat, not `pInfo.longVersionCode`: that
            // getter is API 28 against minSdk 26, so on Android
            // 8.0-8.1 it throws NoSuchMethodError — which the
            // `catch (e: Exception)` below cannot catch, because
            // NoSuchMethodError is an Error, not an Exception. The
            // crash reporter would itself crash, on exactly the
            // devices whose crashes we most want reported.
            "${pInfo.versionName} (${PackageInfoCompat.getLongVersionCode(pInfo)})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Custom uncaught exception handler that logs crashes locally
     * and optionally sends E2EE encrypted reports.
     */
    private inner class CrashHandler : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "crash_${timestamp}.log"
                val file = File(crashLogDir, fileName)

                @Suppress("DEPRECATION") // threadId() requires API 34, we support API 26+
                val threadContext = "Thread: ${thread.name} (id=${thread.id})"
                val logContent = buildLogContent(throwable, threadContext, isCrash = true)
                file.writeText(logContent)
                Log.e(TAG, "Crash logged to $fileName")

                // Synchronous send: the process is about to die, so there is no
                // later to defer to.
                if (isRemoteReportingEnabled()) {
                    sendEncryptedReportSync(logContent, isCrash = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log crash", e)
            }

            // Always call the original handler to allow normal crash behavior
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Synchronous version of sendEncryptedReport for use during crash handling.
     * Must complete before the process terminates.
     */
    private fun sendEncryptedReportSync(content: String, isCrash: Boolean) {
        try {
            val installationId = getInstallationId()
            val keyMaterial = hashWithSha256(installationId.toByteArray())
            val keyBase64 = android.util.Base64.encodeToString(keyMaterial, android.util.Base64.NO_WRAP)

            val encryptedData = cryptoManager.get().encryptWithKey(content, keyBase64)

            // Build minimal metadata (no PII)
            val metadata = buildString {
                append("{")
                append("\"type\":\"${if (isCrash) "crash" else "exception"}\",")
                append("\"app_version\":\"${BuildConfig.VERSION_NAME}\",")
                append("\"android_api\":${Build.VERSION.SDK_INT},")
                append("\"timestamp\":${System.currentTimeMillis()},")
                append("\"device_category\":\"${getGenericDeviceCategory()}\",")
                append("\"encrypted_nonce\":\"${encryptedData.nonce}\",")
                append("\"encrypted_data\":\"${encryptedData.ciphertext}\"")
                append("}")
            }

            val request = Request.Builder()
                .url(BuildConfig.AUTH_BASE_URL + REMOTE_REPORT_ENDPOINT)
                .post(metadata.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Note/${BuildConfig.VERSION_NAME} CrashReporter")
                // Anonymous marker — see sendEncryptedReport: keeps the session
                // Bearer off this contractually anonymous crash report.
                .header("X-Pingolin-Anon", "1")
                .build()

            // Tighter timeouts than the async path — the process is on its way out.
            val crashClient = httpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            crashClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "E2EE crash report sent successfully (sync)")
                } else {
                    Log.w(TAG, "Failed to send crash report: ${response.code}")
                }
            }
        } catch (e: Exception) {
            // Silently fail - don't interfere with crash handling
            Log.e(TAG, "Failed to send E2EE crash report (sync)", e)
        }
    }

    /**
     * Information about a crash log file.
     */
    data class CrashLogInfo(
        val fileName: String,
        val timestamp: Date,
        val sizeBytes: Long
    ) {
        val isCrash: Boolean get() = fileName.startsWith("crash_")
        val isException: Boolean get() = fileName.startsWith("exception_")

        fun getFormattedTimestamp(): String {
            return SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(timestamp)
        }

        fun getFormattedSize(): String {
            return when {
                sizeBytes < 1024 -> "$sizeBytes B"
                sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
                else -> "${sizeBytes / (1024 * 1024)} MB"
            }
        }
    }
}
