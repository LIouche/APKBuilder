---

# APKBuilder

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple?logo=kotlin)](https://kotlinlang.org/)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen?logo=github)](#-build-process)
[![Contributions Welcome](https://img.shields.io/badge/contributions-welcome-orange.svg)](#-contributing)

>  A **lightweight Kotlin build system** that compiles your **Java source + Android resources** into a fully signed `.apk`.  
> Think of it as a **mini-Gradle**: simple, fast, and hackable.

---

## Features

- 🔧 **Full pipeline**: Java → Dex → APK → Signed  
- 📚 **Library support**: Handles JAR + AAR dependencies  
- ⚡ **Configurable** via a single `BuildConfig`  
- 🔒 **Built-in signing** (v1 scheme, Bouncy Castle)  
- 📝 **Unified logging** with callbacks  
- 🧹 Auto cleanup of temp files  

---

## Project Structure

```text
apkbuilder/
 ├── build.gradle.kts
 ├── proguard-rules.pro
 └── src/main
      ├── assets/
      │    └── libaapt2.so   # Native AAPT2 binary
      └── java/com/llouche/apkbuilder/
           ├── compiler/     # Core pipeline
           │    ├── Compiler.kt
           │    ├── AAPT2Compiler.kt
           │    ├── ECJCompiler.kt
           │    ├── D8Compiler.kt
           │    └── APKCompiler.kt
           ├── helper/
           │    └── APKSigner.kt
           └── logger/
                └── LogListener.kt

```
---

 Build Pipeline
```
[ Resources ] --AAPT2--> [ R.java + Binary ] 
        ↓
[ Java Sources ] --ECJ--> [ .class Files ]
        ↓
[ Bytecode ] --D8--> [ .dex Files ]
        ↓
[ Dex + Resources ] --APKCompiler--> [ Unsigned APK ]
        ↓
[ APKSigner ] --> [ ✅ Signed APK ]

```
---

 Usage Example
```
val compiler = Compiler(context)

val config = Compiler.BuildConfig(
    androidJar = "/path/to/android.jar",
    sourceDir = "/path/to/sources",
    outputDir = "/path/to/output",
    resDir = "/path/to/resources",
    manifestFile = "/path/to/AndroidManifest.xml",
    librariesDir = "/path/to/libs",
    minSdk = 21,
    targetSdk = 29,
    versionCode = 1,
    versionName = "1.0",
    keyStorePath = "/storage/emulated/0/myKeyPath/keystore.p12",
    keyStorePass = "myKeystorePass",
    keyStoreAlias = "myKeystoreAlias"
)

compiler.build(config, object : Compiler.CompilerListener {
    override fun onLog(message: CharSequence) = println(message)
    override fun onBuildStarted() = println("⚙️ Build started...")
    override fun onBuildSuccess(apkPath: String) = println("✅ Success: $apkPath")
    override fun onBuildFailed(error: String?) = println("❌ Failed: $error")
})

```
---

Dependencies
```
🔑 Bouncy Castle → cryptography/signing

💻 ECJ → Java compiler

📦 D8/R8 → Dex conversion

⚡ libaapt2.so → Native resource compilation


```
---

---
<details>
<summary>⚠️ Notes</summary>
Does not support Scoped Storage

libaapt2.so must be bundled inside assets/

You need to create your own keystore.

Runs on a single background thread

Temp files are automatically removed
</details>
---

## 🤝 Contributing

Contributions are very welcome! 🎉

1. Fork the repo


2. Create a branch: git checkout -b feature/amazing-idea


3. Commit changes: git commit -m "Added amazing feature"


4. Push to your fork: git push origin feature/amazing-idea


5. Open a Pull Request 🚀




---

📜 License

This project is licensed under the LGPL-3.0.
See LICENSE for full details.


---

