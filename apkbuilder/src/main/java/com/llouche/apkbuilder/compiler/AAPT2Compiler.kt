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

import android.content.Context
import com.llouche.apkbuilder.logger.LogListener
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class AAPT2Compiler(
    private val context: Context,
    private val projectDirPath: String,
    private val resourcesDirPath: String,
    private val manifestPath: String,
    private val androidJarPath: String,
    private val minSdk: Int,
    private val targetSdk: Int,
    private val versionCode: Int,
    private val versionName: String,
    private val logListener: LogListener? = null
) {
    private val genDirPath = "$projectDirPath/gen"
    private val binDirPath = "$projectDirPath/bin"
    private val resBinDirPath = "$binDirPath/res"
    private val executor = Executors.newSingleThreadExecutor()

    fun run() {
        executor.execute {
            try {
                executeCompilationPipeline()
            } catch (e: Exception) {
                logListener?.onLog("AAPT2 compilation failed: ${e.message?.take(200)}...")
            }
        }
    }

    private fun executeCompilationPipeline() {
    prepareDirectories()
    compileResources()
    linkResources()
    logListener?.onLog("AAPT2 compilation complete (genDir = $genDirPath)")
}

    private fun prepareDirectories() {
        listOf(genDirPath, binDirPath, resBinDirPath).forEach { path ->
            File(path).mkdirs()
        }
        logListener?.onLog("Prepared directories")
    }

    private fun compileResources() {
        val resDir = File(resourcesDirPath).takeIf { it.exists() } 
            ?: throw IOException("Resources directory not found")
        
        val projectZip = "$resBinDirPath/project.zip"
        executeCommand(
            listOf(
                getAAPT2Binary(),
                "compile",
                "--dir", resourcesDirPath,
                "-o", projectZip
            )
        )
        logListener?.onLog("Resources compiled to: $projectZip")
    }

    private fun linkResources() {
        val outputApkRes = "$binDirPath/generated.apk.res"
        val command = mutableListOf(
            getAAPT2Binary(),
            "link",
            "--allow-reserved-package-id",
            "--no-version-vectors",
            "--no-version-transitions",
            "--auto-add-overlay",
            "--min-sdk-version", minSdk.toString(),
            "--target-sdk-version", targetSdk.toString(),
            "--version-code", versionCode.toString(),
            "--version-name", versionName,
            "-I", androidJarPath,
            "--java", genDirPath,
            "--manifest", manifestPath,
            "-o", outputApkRes
        )

        File(resBinDirPath).walk()
            .filter { it.isFile && it.extension == "zip" }
            .forEach { zip ->
                command.add("-R")
                command.add(zip.absolutePath)
            }

        executeCommand(command)
        logListener?.onLog("Linked resources output: $outputApkRes")
    }

    private fun executeCommand(command: List<String>): String {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (process.exitValue() != 0) throw IOException(output)
            output
        } catch (e: Exception) {
            throw IOException("Command failed: ${e.message}")
        }
    }

    @Throws(IOException::class)
    private fun getAAPT2Binary(): String {
        return File(context.filesDir, "libaapt2.so").apply {
            if (!exists()) {
                context.assets.open("libaapt2.so").use { input ->
                    outputStream().use { output -> input.copyTo(output) }
                }
                setExecutable(true)
            }
        }.absolutePath
    }
}