/**
 * APKBuilder Library
 *
 * This library is licensed under the GNU Lesser General Public License v3 (LGPL-3.0).
 *
 * Copyright (C) 2025 Llouch Val Morvelle
 *
 * You may redistribute and/or modify this library under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library. If not, see <https://www.gnu.org/licenses/>.
 */

package com.llouche.apkbuilder.compiler

import com.llouche.apkbuilder.helper.APKSigner
import com.llouche.apkbuilder.logger.LogListener
import java.io.File
import java.io.FileOutputStream
import java.util.zip.*

class APKCompiler(
    private val dexDir: File,
    private val apkResFile: File,
    private val outputApk: File,
    private val keystore: String,
    private val keyPass: String,
    private val aliass: String,
    private val logListener: LogListener? = null
) {
    fun packageApk(): Boolean {
        if (!validateInputs()) return false

        return try {
            createApk().also { success ->
                if (success) {
                    signApk()
                }
            }
        } catch (e: Exception) {
            logListener?.onLog("APK packaging failed: ${e.message}")
            false
        }
    }

    private fun validateInputs(): Boolean {
        return when {
            !dexDir.exists() -> {
                logListener?.onLog("Dex directory missing: ${dexDir.absolutePath}")
                false
            }
            !apkResFile.exists() -> {
                logListener?.onLog("AAPT2 resources missing: ${apkResFile.absolutePath}")
                false
            }
            dexDir.listFiles { _, name -> name.endsWith(".dex") }?.isEmpty() != false -> {
                logListener?.onLog("No .dex files found in ${dexDir.absolutePath}")
                false
            }
            else -> true
        }
    }

    private fun createApk(): Boolean {
        logListener?.onLog("Creating APK...")
        return ZipOutputStream(FileOutputStream(outputApk)).use { zip ->
            addDexFiles(zip)
            addResources(zip)
            true
        }.also {
            logListener?.onLog("APK created: ${outputApk.absolutePath}")
        }
    }

    private fun addDexFiles(zip: ZipOutputStream) {
    dexDir.listFiles { _, name -> name.endsWith(".dex") }?.forEach { dex ->
        val bytes = dex.readBytes()
        val entry = ZipEntry(dex.name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = java.util.zip.CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
        logListener?.onLog("Added dex (uncompressed): ${dex.name}")
    }
}

    private fun addResources(zip: ZipOutputStream) {
        ZipInputStream(apkResFile.inputStream()).use { zis ->
            generateSequence { zis.nextEntry }
                .filterNot { it.isDirectory }
                .forEach { entry ->
                    val path = when (entry.name) {
                        "AndroidManifest.xml" -> "AndroidManifest.xml" // root
                        "resources.arsc" -> "resources.arsc"           // root
                        else -> "${entry.name}"                     // everything else
                    }
                    zip.putNextEntry(ZipEntry(path))
                    zis.copyTo(zip)
                    zip.closeEntry()
                    logListener?.onLog("Added resource: $path")
                }
        }
    }

    private fun signApk(): Boolean {
        val keystoreFile = File(keystore)
        return APKSigner.signApk(
            apkFile = outputApk,
            keystoreFile = keystoreFile,
            keystorePassword = keyPass,
            alias = aliass,
            keyPassword = keyPass,
            logListener = logListener
        ).also { success ->
            if (success) logListener?.onLog("The APK is now available for installation.")
            else logListener?.onLog("APK signing failed")
        }
    }
}