/**
 * APKBuilder Library
 *
 * This library is licensed under the GNU Lesser General Public License v3 (LGPL-3.0).
 *
 * Copyright (C) 2025 Llouche
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
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import com.llouche.apkbuilder.logger.LogListener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Configuration for the APK build process
 *
 * @property androidJar Path to android.jar
 * @property sourceDir Directory containing Java source files
 * @property outputDir Directory for build outputs
 * @property resDir Directory containing resources
 * @property manifestFile Path to AndroidManifest.xml
 * @property librariesDir Directory containing library JARs
 * @property minSdk Minimum SDK version (default: 21)
 * @property targetSdk Target SDK version (default: 29)
 * @property versionCode App version code (default: 1)
 * @property versionName App version name (default: "1.0")
 *
 * Example usage:
 * // Using defaults for SDK versions and version info
 * val config1 = BuildConfig(
 *     androidJar = "...",
 *     sourceDir = "...",
 *     outputDir = "...",
 *     resDir = "...",
 *     manifestFile = "...",
 *     librariesDir = "..."
 * )
 *
 * // Overriding default values
 * val config2 = BuildConfig(
 *     androidJar = "...",
 *     // ... other required paths ...,
 *     minSdk = 23,
 *     targetSdk = 33,
 *     versionCode = 42,
 *     versionName = "2.0"
 * )
 */
 
class Compiler(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val isBuilding = AtomicBoolean(false)
    
    data class BuildConfig(
        val androidJar: String,
        val sourceDir: String,
        val outputDir: String,
        val resDir: String,
        val manifestFile: String,
        val librariesDir: String,
        val minSdk: Int = 21,
        val targetSdk: Int = 29,
        val versionCode: Int = 1,
        val versionName: String = "1.0",
        val keyStorePath: String,
        val keyStorePass: String,
        val keyStoreAlias: String
    ) {
        val classOutputDir = "$outputDir/classes/"
        val dexOutputDir = "$outputDir/dex/"
        val finalApk = "$outputDir/app.apk"
    }

    interface CompilerListener {
        fun onLog(message: CharSequence)
        fun onBuildStarted()
        fun onBuildSuccess(apkPath: String)
        fun onBuildFailed(error: String?)
    }

    fun build(config: BuildConfig, listener: CompilerListener) {
        if (isBuilding.getAndSet(true)) {
            listener.onLog("[Compiler] Build already in progress\n")
            return
        }

        listener.onBuildStarted()
        executor.execute {
            try {
                runBuildProcess(config, listener)
                listener.onBuildSuccess(config.finalApk)
            } catch (e: Exception) {
                listener.onLog("[Compiler] Build failed: ${e.message}\n")
                listener.onBuildFailed(e.message)
            } finally {
                isBuilding.set(false)
            }
        }
    }

    private fun runBuildProcess(config: BuildConfig, listener: CompilerListener) {
        cleanOutputDirectory(config.outputDir, listener)
        executeBuildSteps(config, listener)
    }

    private fun cleanOutputDirectory(outputDir: String, listener: CompilerListener) {
        log(listener, "[Compiler] Cleaning output directory...")
        File(outputDir).deleteRecursively()
    }

    private fun executeBuildSteps(config: BuildConfig, listener: CompilerListener) {
        val aapt2GenDir = executeAapt2Step(config, listener)
        if (aapt2GenDir != null) {
            val compiled = executeEcjStep(config, aapt2GenDir, listener)
            if (compiled) {
                val dexSuccess = executeD8Step(config, listener)
                if (dexSuccess) {
                    executeApkPackagingStep(config, listener)
                }
            }
        }
    }

    private fun executeAapt2Step(config: BuildConfig, listener: CompilerListener): String? {
        log(listener, "[Compiler] Compiling resources...")
        val latch = CountDownLatch(1)
        var genDir = ""

        AAPT2Compiler(
            context = context,
            projectDirPath = config.outputDir,
            resourcesDirPath = config.resDir,
            manifestPath = config.manifestFile,
            androidJarPath = config.androidJar,
            minSdk = config.minSdk,
            targetSdk = config.targetSdk,
            versionCode = config.versionCode,
            versionName = config.versionName,
            logListener = createLogListener("[AAPT2]", listener, latch) { genDir = it }
        ).run()

        latch.await()
        return genDir.takeIf { it.isNotEmpty() }
    }

    private fun executeEcjStep(config: BuildConfig, genDir: String, listener: CompilerListener): Boolean {
        log(listener, "[Compiler] Compiling Java sources...")
        return ECJCompiler(
            config.androidJar,
            logListener = createLogListener("[ECJ]", listener)
        ).apply {
            addLibrariesFromDir(config.librariesDir)
        }.compile(
            sourcePaths = collectJavaFiles(config.sourceDir, genDir),
            outputPath = config.classOutputDir
        )
    }

    private fun collectJavaFiles(sourceDir: String, genDir: String): List<String> {
        return File(sourceDir).walk()
            .filter { it.isFile && it.extension == "java" }
            .map { it.absolutePath }
            .toMutableList()
            .apply {
                addAll(File(genDir).walk()
                    .filter { it.isFile && it.extension == "java" }
                    .map { it.absolutePath })
            }
    }

    private fun executeD8Step(config: BuildConfig, listener: CompilerListener): Boolean {
        log(listener, "[Compiler] Converting to dex format...")
        return D8Compiler(
            logListener = createLogListener("[D8]", listener),
            minSdk = config.minSdk
        ).apply {
            addLibraryPath(config.androidJar)
            addLibrariesFromDir(config.librariesDir)
        }.compileToDex(
            inputPaths = listOf(config.classOutputDir),
            outputDir = config.dexOutputDir
        )
    }

    private fun executeApkPackagingStep(config: BuildConfig, listener: CompilerListener) {
        log(listener, "[Compiler] Packaging APK...")
        APKCompiler(
            dexDir = File(config.dexOutputDir),
            apkResFile = File("${config.outputDir}bin/generated.apk.res"),
            outputApk = File(config.finalApk),
            logListener = createLogListener("[APK]", listener),
            keystore = config.keyStorePath,
            keyPass = config.keyStorePass,
            aliass = config.keyStoreAlias
        ).packageApk()
    }

    private fun createLogListener(
        prefix: String,
        mainListener: CompilerListener,
        latch: CountDownLatch? = null,
        onComplete: ((String) -> Unit)? = null
    ): LogListener {
        return object : LogListener {
            override fun onLog(message: String) {
                log(mainListener, "$prefix $message")
                if (message.contains("complete")) {
                    onComplete?.invoke(message.substringAfter("genDir = ").substringBefore(")"))
                    latch?.countDown()
                }
            }
        }
    }

    private fun log(listener: CompilerListener, message: String) {
        /*val spannable = SpannableStringBuilder(message)
        val tagRegex = Regex("""\[(.*?)\]""")
        tagRegex.findAll(message).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            spannable.setSpan(
                ForegroundColorSpan(Color.GREEN),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        listener.onLog(spannable)*/
        listener.onLog(message)
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}