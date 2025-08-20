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

import com.android.tools.r8.*
import com.llouche.apkbuilder.logger.LogListener
import java.io.File

class D8Compiler(
    private val logListener: LogListener? = null,
    private val minSdk: Int = 21
) {
    private val libraryPaths = mutableListOf<String>()

    fun addLibraryPath(path: String) {
        File(path).takeIf { it.exists() && it.isFile }?.let { file ->
            when (file.extension) {
                "jar" -> addJarLibrary(file)
                "aar" -> extractAndAddAarLibrary(file)
                else -> logListener?.onLog("Unsupported library format: ${file.name}")
            }
        } ?: logListener?.onLog("Not a valid file: $path")
    }

    private fun addJarLibrary(file: File) {
        libraryPaths.add(file.absolutePath)
        logListener?.onLog("Added JAR: ${file.name}")
    }

    private fun extractAndAddAarLibrary(aarFile: File) {
        extractClassesJar(aarFile)?.let { jar ->
            libraryPaths.add(jar.absolutePath)
            logListener?.onLog("Extracted classes.jar from AAR: ${aarFile.name}")
        } ?: logListener?.onLog("No classes.jar found inside AAR: ${aarFile.name}")
    }

    fun addLibrariesFromDir(dirPath: String) {
        File(dirPath).takeIf { it.exists() && it.isDirectory }?.let { dir ->
            dir.listFiles { _, name -> name.endsWith(".jar") || name.endsWith(".aar") }
                ?.forEach { addLibraryPath(it.absolutePath) }
                ?: logListener?.onLog("No .jar or .aar files found in $dirPath")
        } ?: logListener?.onLog("Library directory does not exist: $dirPath")
    }

    private fun extractClassesJar(aarFile: File): File? {
        return File(aarFile.parentFile, "extracted-aar/${aarFile.nameWithoutExtension}").run {
            mkdirs()
            java.util.zip.ZipFile(aarFile).use { zip ->
                zip.getEntry("classes.jar")?.let { entry ->
                    File(this, "classes.jar").apply {
                        zip.getInputStream(entry).use { input ->
                            outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        }
    }

    fun listLibraryPaths(): List<String> = libraryPaths.toList()

    fun compileToDex(
        inputPaths: List<String>,
        outputDir: String,
        outputMode: OutputMode = OutputMode.DexIndexed
    ): Boolean {
        if (inputPaths.isEmpty()) {
            logListener?.onLog("No input paths provided for D8")
            return false
        }

        return File(outputDir).run {
            mkdirs()
            try {
                D8Command.builder().apply {
                    setMinApiLevel(minSdk)
                    setOutput(toPath(), outputMode)
                    addInputFiles(inputPaths)
                    addLibraryFiles()
                }.build().let { command ->
                    D8.run(command)
                    logListener?.onLog("D8 compilation succeeded. Dex at: ${absolutePath}")
                    true
                }
            } catch (e: CompilationFailedException) {
                logListener?.onLog("D8 compilation failed: ${e.message}")
                false
            }
        }
    }

    private fun D8Command.Builder.addInputFiles(inputPaths: List<String>) {
        inputPaths.map(::File).forEach { file ->
            when {
                file.isDirectory -> addClassFilesFromDirectory(file)
                file.isFile && file.extension == "jar" -> addJarFile(file)
                else -> logListener?.onLog("Skipping unsupported file: ${file.absolutePath}")
            }
        }
    }

    private fun D8Command.Builder.addClassFilesFromDirectory(dir: File) {
        dir.walk()
            .filter { it.isFile && it.extension == "class" }
            .map { it.toPath() }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.let { classFiles ->
                addProgramFiles(classFiles)
                logListener?.onLog("Added ${classFiles.size} .class files from ${dir.absolutePath}")
            } ?: logListener?.onLog("No .class files found in ${dir.absolutePath}")
    }

    private fun D8Command.Builder.addJarFile(file: File) {
        addProgramFiles(file.toPath())
        logListener?.onLog("Added JAR: ${file.name}")
    }

    private fun D8Command.Builder.addLibraryFiles() {
        libraryPaths.map(::File).map { it.toPath() }.forEach { addLibraryFiles(it) }
    }
}