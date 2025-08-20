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

import org.eclipse.jdt.internal.compiler.batch.Main
import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import com.llouche.apkbuilder.logger.LogListener
import java.util.zip.ZipFile

class ECJCompiler(
    private val androidJarPath: String,
    private val lambdaStubsPath: String? = null,
    private val logListener: LogListener? = null
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
            ZipFile(aarFile).use { zip ->
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

    fun compile(
        sourcePaths: List<String>,
        outputPath: String,
        classpath: List<String> = emptyList()
    ): Boolean {
        if (sourcePaths.isEmpty()) {
            logListener?.onLog("No source paths provided")
            return false
        }

        val outputDir = File(outputPath).apply { mkdirs() }
        val javaFiles = collectJavaFiles(sourcePaths)
        
        if (javaFiles.isEmpty()) {
            logListener?.onLog("No Java files found to compile")
            return false
        }

        return executeCompilation(javaFiles, outputDir, classpath)
    }

    private fun collectJavaFiles(sourcePaths: List<String>): List<File> {
        return sourcePaths.flatMap { path ->
            File(path).let { file ->
                when {
                    file.isDirectory -> findJavaFiles(file)
                    file.isFile && file.extension == "java" -> listOf(file)
                    else -> {
                        logListener?.onLog("Skipping non-Java path: $path")
                        emptyList()
                    }
                }
            }
        }
    }

    private fun findJavaFiles(directory: File): List<File> {
        return directory.walk()
            .filter { it.isFile && it.extension == "java" }
            .toList()
    }

    private fun executeCompilation(javaFiles: List<File>, outputDir: File, classpath: List<String>): Boolean {
        val args = mutableListOf(
            "-1.8",
            "-proc:none",
            "-nowarn",
            "-d", outputDir.absolutePath,
            "-encoding", "UTF-8",
            "-cp", buildClasspath(classpath),
            "-sourcepath", ""
        ).apply {
            addAll(javaFiles.map { it.absolutePath })
        }

        val outStream = CompilerOutputStream(logListener)
        val printWriter = PrintWriter(outStream, true)
        val compiler = Main(printWriter, printWriter, false, null, null)

        val success = compiler.compile(args.toTypedArray())
        logCompilationResult(compiler, success)
        return success
    }

    private fun buildClasspath(extraClasspath: List<String>): String {
        val paths = mutableListOf<String>().apply {
            add(androidJarPath)
            addAll(libraryPaths)
            lambdaStubsPath?.let { add(it) }
            addAll(extraClasspath)
        }
        return paths.joinToString(File.pathSeparator)
    }

    private fun logCompilationResult(compiler: Main, success: Boolean) {
        logListener?.onLog("Compilation ${if (success) "succeeded" else "failed"}")
        if (compiler.globalErrorsCount > 0) {
            logListener?.onLog("Found ${compiler.globalErrorsCount} error(s)")
        }
    }

    private inner class CompilerOutputStream(
        private val logListener: LogListener?
    ) : OutputStream() {
        private val buffer = StringBuilder()

        override fun write(b: Int) {
            if (b == '\n'.code) {
                val line = buffer.toString().trim()
                if (line.isNotEmpty()) logListener?.onLog(line)
                buffer.clear()
            } else {
                buffer.append(b.toChar())
            }
        }
    }
}