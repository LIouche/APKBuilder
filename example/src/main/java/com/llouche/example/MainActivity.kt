package com.llouche.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import com.llouche.apkbuilder.compiler.Compiler
import com.llouche.example.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), Compiler.CompilerListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var compiler: Compiler
    
    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private var currentInputFieldId: Int = -1
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val path = when (currentInputFieldId) {
                    R.id.input_androidJar, R.id.input_manifestDir -> getFilePathFromUri(uri)
                    else -> getDirectoryPathFromUri(uri)
                }
                
                when (currentInputFieldId) {
                    R.id.input_androidJar -> binding.inputAndroidJar.setText(path)
                    R.id.input_sourceDir -> binding.inputSourceDir.setText(path)
                    R.id.input_outputDir -> binding.inputOutputDir.setText(path)
                    R.id.input_resDir -> binding.inputResDir.setText(path)
                    R.id.input_manifestDir -> binding.inputManifestDir.setText(path)
                    R.id.input_librariesDir -> binding.inputLibrariesDir.setText(path)
                }
                
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    private fun getFilePathFromUri(uri: Uri): String {
        val path = uri.path ?: uri.toString()
        return path.replace("/document/primary:", "/storage/emulated/0/")
    }

    private fun getDirectoryPathFromUri(uri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":")
        return if (split.size > 1) {
            "/storage/emulated/0/${split[1]}/"
        } else {
            uri.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        compiler = Compiler(this)
        setupInputFieldIcons()
        checkPermissionsAndStart()
    }

    private fun setupInputFieldIcons() {
        listOf(
            binding.textinputAndroidJar to R.id.input_androidJar,
            binding.textinputSourceDir to R.id.input_sourceDir,
            binding.textinputOutputDir to R.id.input_outputDir,
            binding.textinputResDir to R.id.input_resDir,
            binding.textinputManifestDir to R.id.input_manifestDir,
            binding.textinputLibrariesDir to R.id.input_librariesDir
        ).forEach { (textInputLayout, fieldId) ->
            textInputLayout.setEndIconOnClickListener {
                currentInputFieldId = fieldId
                openFilePicker(fieldId)
            }
        }
    }

    private fun openFilePicker(fieldId: Int) {
        currentInputFieldId = fieldId
        
        when (fieldId) {
            R.id.input_androidJar, R.id.input_manifestDir -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/java-archive",
                        "text/xml"
                    ))
                }
                directoryPickerLauncher.launch(intent)
            }
            else -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                }
                directoryPickerLauncher.launch(intent)
            }
        }
    }

    private fun getPathFromUri(uri: Uri): String {
        return uri.path ?: uri.toString()
    }

    private fun checkPermissionsAndStart() {
        when {
            hasAllPermissions() -> startBackgroundBuild()
            else -> requestPermissions()
        }
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, 
            requiredPermissions, 
            STORAGE_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBackgroundBuild()
        } else {
            onLog("[Main] Storage permissions denied\n")
        }
    }

    private fun startBackgroundBuild() {
        binding.comp.setOnClickListener { 
            binding.logs.setText("")
            val targetSdkText = binding.inputTargetSdk.text.toString().trim()
            val targetSdk = when {
                targetSdkText.isEmpty() -> 29
                targetSdkText.toIntOrNull()?.let { it > 29 } == true -> {
                    onLog("[WARN] Target SDK cannot be greater than 29. Using 29 instead.\n")
                    29
                }
                else -> targetSdkText.toIntOrNull() ?: 29
            }

            val config = Compiler.BuildConfig(
                androidJar = binding.inputAndroidJar.text.toString().trim(),
                sourceDir = binding.inputSourceDir.text.toString().trim(),
                outputDir = binding.inputOutputDir.text.toString().trim(),
                resDir = binding.inputResDir.text.toString().trim(),
                manifestFile = binding.inputManifestDir.text.toString().trim(),
                librariesDir = binding.inputLibrariesDir.text.toString().trim(),
                minSdk = binding.inputMinSdk.text.toString().trim().toIntOrNull() ?: 21,
                targetSdk = targetSdk,
                versionCode = binding.inputVersionCode.text.toString().trim().toIntOrNull() ?: 1,
                versionName = binding.inputVersionName.text.toString().trim().takeIf { it.isNotEmpty() } ?: "1.0",
                keyStorePath = "",
                keyStorePass = "",
                keyStoreAlias = ""
            )

            if (config.androidJar.isEmpty() || config.sourceDir.isEmpty() || config.outputDir.isEmpty() ||
                config.resDir.isEmpty() || config.manifestFile.isEmpty() || config.librariesDir.isEmpty()) {
                onLog("[Main] Please fill in all required fields before starting the build.\n")
                return@setOnClickListener
            }

            compiler.build(config, this)
        }
    }

    override fun onLog(message: CharSequence) {
        runOnUiThread {
            val spannableMessage = SpannableString(message)
            val messageStr = message.toString()
        
            val mainIndex = messageStr.indexOf("[Main]")
            if (mainIndex != -1) {
                val colorSpan = ForegroundColorSpan(Color.parseColor("#FFA500")) // Orange
                val boldSpan = StyleSpan(Typeface.BOLD)
                spannableMessage.setSpan(
                    colorSpan,
                    mainIndex,
                    mainIndex + 6,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannableMessage.setSpan(
                    boldSpan,
                    mainIndex,
                    mainIndex + 6,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        
            val warnIndex = messageStr.indexOf("[WARN]")
            if (warnIndex != -1) {
                val colorSpan = ForegroundColorSpan(Color.parseColor("#FFFF00")) // Yellow
                val boldSpan = StyleSpan(Typeface.BOLD)
                spannableMessage.setSpan(
                    colorSpan,
                    warnIndex,
                    warnIndex + 6,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannableMessage.setSpan(
                    boldSpan,
                    warnIndex,
                    warnIndex + 6,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            
            binding.logs.append(spannableMessage)
            binding.logs.append("\n")
        }
    }

    override fun onBuildStarted() {
        runOnUiThread {
            val message = "[Main] Starting build process...\n"
            val spannableMessage = SpannableString(message)
            
            val colorSpan = ForegroundColorSpan(Color.parseColor("#FFA500"))
            val boldSpan = StyleSpan(Typeface.BOLD)
            spannableMessage.setSpan(
                colorSpan,
                0,
                6,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableMessage.setSpan(
                boldSpan,
                0,
                6,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            
            binding.logs.append(spannableMessage)
        }
    }

    override fun onBuildSuccess(apkPath: String) {
        runOnUiThread {
            val apkFile = File(apkPath)
        
            if (!apkFile.exists()) {
                val errorMessage = "[Main] Build failed: APK file not found at $apkPath\n"
                val spannableError = SpannableString(errorMessage)
            
                val errorColorSpan = ForegroundColorSpan(Color.RED)
                val boldSpan = StyleSpan(Typeface.BOLD)
            
                spannableError.setSpan(
                    errorColorSpan,
                    0,
                    12, // "[Main] Build"
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannableError.setSpan(
                    boldSpan,
                    0,
                    12,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            
                binding.logs.append(spannableError)
                return@runOnUiThread
            }
        
            val message = "[Main] Build successful! APK created at: $apkPath\n"
            val spannableMessage = SpannableString(message)
            
            val colorSpan = ForegroundColorSpan(Color.parseColor("#FFA500"))
            val boldSpan = StyleSpan(Typeface.BOLD)
            spannableMessage.setSpan(
                colorSpan,
                0,
                6,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableMessage.setSpan(
                boldSpan,
                0,
                6,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        
            binding.logs.append(spannableMessage)
        }
    }

    override fun onBuildFailed(error: String?) {
        runOnUiThread {
            val message = "[Main] Build failed: ${error ?: "Unknown error"}\n"
            val spannableMessage = SpannableString(message)
            
            val colorSpan = ForegroundColorSpan(Color.parseColor("#FFA500"))
            val boldSpan = StyleSpan(Typeface.BOLD)
            spannableMessage.setSpan(
                colorSpan,
                0,
                6,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableMessage.setSpan(
                boldSpan,
                0,
                6,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            
            binding.logs.append(spannableMessage)
        }
    }

    override fun onDestroy() {
        compiler.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val STORAGE_PERMISSION_REQUEST = 1
    }
}