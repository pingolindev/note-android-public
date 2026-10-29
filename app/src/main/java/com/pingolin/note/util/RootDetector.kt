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
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Detects if the device is rooted/compromised.
 *
 * Root detection is a defense-in-depth measure. While determined attackers
 * can bypass these checks, they raise the bar for casual attackers and
 * protect against automated malware.
 */
object RootDetector {

    data class RootCheckResult(
        val isRooted: Boolean,
        val reasons: List<String>
    )

    fun checkRoot(context: Context): RootCheckResult {
        val reasons = mutableListOf<String>()

        if (checkRootBinaries()) reasons.add("Root binaries found")
        if (checkSuBinary()) reasons.add("su binary accessible")
        if (checkRootManagementApps(context)) reasons.add("Root management app installed")
        if (checkDangerousProps()) reasons.add("Dangerous system properties detected")
        if (checkRWPaths()) reasons.add("System paths are writable")
        if (checkTestKeys()) reasons.add("Test-keys build detected")
        if (checkMagiskDetection()) reasons.add("Magisk indicators found")
        if (checkBusyBox()) reasons.add("BusyBox installed")
        if (checkXposed(context)) reasons.add("Xposed framework detected")

        return RootCheckResult(isRooted = reasons.isNotEmpty(), reasons = reasons)
    }

    fun isRooted(context: Context): Boolean = checkRoot(context).isRooted

    private fun checkRootBinaries(): Boolean {
        val paths = listOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
            "/su/bin/su", "/system/app/SuperSU.apk", "/system/app/SuperSU/SuperSU.apk",
            "/data/adb/su/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkSuBinary(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            process.inputStream.bufferedReader().readLine() != null
        } catch (e: Exception) {
            arrayOf("/sbin/su", "/system/bin/su", "/system/xbin/su", "/su/bin/su")
                .any { path -> try { File(path).exists() } catch (e: Exception) { false } }
        }
    }

    private fun checkRootManagementApps(context: Context): Boolean {
        val knownRootApps = listOf(
            "com.noshufou.android.su", "eu.chainfire.supersu",
            "com.koushikdutta.superuser", "com.topjohnwu.magisk",
            "com.kingroot.kinguser", "com.kingo.root",
            "de.robv.android.xposed.installer", "io.github.vvb2060.magisk"
        )
        val pm = context.packageManager
        return knownRootApps.any { pkg ->
            try { pm.getPackageInfo(pkg, 0); true }
            catch (e: PackageManager.NameNotFoundException) { false }
        }
    }

    private fun checkDangerousProps(): Boolean {
        val dangerousProps = mapOf("ro.debuggable" to "1", "ro.secure" to "0")
        return dangerousProps.any { (prop, dangerousValue) ->
            try { getSystemProperty(prop) == dangerousValue } catch (e: Exception) { false }
        }
    }

    private fun getSystemProperty(propName: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", propName))
            process.inputStream.bufferedReader().readLine()?.trim()
        } catch (e: Exception) { null }
    }

    private fun checkRWPaths(): Boolean {
        val paths = listOf("/system", "/system/bin", "/system/sbin", "/vendor/bin")
        return paths.any { path ->
            try { val file = File(path); file.exists() && file.canWrite() }
            catch (e: Exception) { false }
        }
    }

    private fun checkTestKeys(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkMagiskDetection(): Boolean {
        val magiskPaths = listOf(
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/magisk.img",
            "/data/adb/magisk.db", "/cache/magisk.log", "/data/adb/modules"
        )
        return magiskPaths.any { File(it).exists() }
    }

    private fun checkBusyBox(): Boolean {
        val busyboxPaths = listOf(
            "/system/bin/busybox", "/system/xbin/busybox",
            "/sbin/busybox", "/data/local/bin/busybox"
        )
        return busyboxPaths.any { File(it).exists() }
    }

    private fun checkXposed(context: Context): Boolean {
        val xposedPackages = listOf(
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "org.lsposed.manager"
        )
        val pm = context.packageManager
        val hasXposedApp = xposedPackages.any { pkg ->
            try { pm.getPackageInfo(pkg, 0); true }
            catch (e: PackageManager.NameNotFoundException) { false }
        }
        if (hasXposedApp) return true

        return try {
            throw Exception("Xposed check")
        } catch (e: Exception) {
            e.stackTrace.any { element ->
                element.className.contains("xposed", ignoreCase = true) ||
                element.className.contains("lsposed", ignoreCase = true)
            }
        }
    }
}
