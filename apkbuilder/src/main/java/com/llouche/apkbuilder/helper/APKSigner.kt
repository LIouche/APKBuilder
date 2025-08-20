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

package com.llouche.apkbuilder.helper

import com.llouche.apkbuilder.logger.LogListener
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * APK v1 (JAR) signer:
 * - Excludes META-INF
 * - MANIFEST.MF: SHA-256 digest of each entry's *bytes*
 * - CERT.SF: SHA-256 digest of each entry's *manifest section*
 * - CERT.RSA: CMS/PKCS#7 over CERT.SF using SHA256withRSA
 * - Output: <original>_signed.apk
 *
 */
object APKSigner {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun signApk(
        apkFile: File,
        keystoreFile: File,
        keystorePassword: String,
        alias: String,
        keyPassword: String,
        logListener: LogListener? = null
    ): Boolean {
        if (!verifyInputFiles(apkFile, keystoreFile, logListener)) return false

        return try {
            val (privateKey, cert) = loadKeyStore(
                keystoreFile, keystorePassword, alias, keyPassword, logListener
            )

            // Output path: keep original, write <name>_signed.apk
            val signedFile = File(apkFile.parentFile, apkFile.nameWithoutExtension + "_signed.apk")

            JarFile(apkFile).use { jar ->
                // 1) Read entries (excluding META-INF) and compute file digests
                val entries = jar.entries().toList()
                    .filter { !it.isDirectory && !it.name.startsWith("META-INF/", ignoreCase = true) }
                    .sortedBy { it.name } // stable order
                val fileDigests = LinkedHashMap<String, ByteArray>() // name -> SHA-256(fileBytes)

                entries.forEach { e ->
                    jar.getInputStream(e).use { inp ->
                        val bytes = inp.readBytes()
                        fileDigests[e.name] = sha256(bytes)
                    }
                }

                // 2) Build MANIFEST.MF (bytes + keep each section bytes for SF)
                val manifestBuild = buildManifest(fileDigests)

                // 3) Build CERT.SF (digest of manifest main + each section)
                val signatureFileBytes = buildSignatureFile(manifestBuild)

                // 4) Build CERT.RSA (PKCS#7/CMS over CERT.SF)
                val certRsaBytes = generateCmsSignature(privateKey, cert, signatureFileBytes)

                // 5) Write new APK
                writeSignedApk(
                    jar,
                    signedFile,
                    manifestBuild.fullManifestBytes,
                    signatureFileBytes,
                    certRsaBytes
                )
            }

            logListener?.onLog("APK v1-signed successfully → ${signedFile.absolutePath}")
            true
        } catch (e: Exception) {
            logListener?.onLog("Signing error: ${e.message}")
            false
        }
    }

    // -------------------- Core building blocks --------------------

    private data class ManifestBuild(
        val fullManifestBytes: ByteArray,
        val mainSectionBytes: ByteArray,
        val perEntrySectionBytes: Map<String, ByteArray>
    )

    /**
     * Build MANIFEST.MF in canonical JAR format:
     * Main section then one section per entry:
     *   Name: <entry>
     *   SHA-256-Digest: <base64(sha256(fileBytes))>
     *
     * We construct strings with CRLF and blank lines between sections,
     * and we *reuse exactly those bytes* later to hash per-section for CERT.SF.
     */
    private fun buildManifest(fileDigests: Map<String, ByteArray>): ManifestBuild {
        val nl = "\r\n"

        // Main section
        val main = StringBuilder()
        main.append("Manifest-Version: 1.0").append(nl)
        main.append("Created-By: MorIDE-APKSigner.class").append(nl)
        main.append(nl)
        val mainBytes = main.toString().toByteArray(Charsets.UTF_8)

        // Entry sections (keep raw bytes per entry for SF section hashing)
        val perEntryBytes = LinkedHashMap<String, ByteArray>()
        val full = StringBuilder()
        full.append(main)

        fileDigests.forEach { (name, digestBytes) ->
            val section = StringBuilder()
            section.append("Name: ").append(name).append(nl)
            section.append("SHA-256-Digest: ")
                .append(Base64.getEncoder().encodeToString(digestBytes))
                .append(nl)
            section.append(nl)
            val sBytes = section.toString().toByteArray(Charsets.UTF_8)
            perEntryBytes[name] = sBytes
            full.append(section)
        }

        val fullBytes = full.toString().toByteArray(Charsets.UTF_8)
        return ManifestBuild(fullBytes, mainBytes, perEntryBytes)
    }

    /**
     * Build CERT.SF:
     * - Signature-Version, Created-By
     * - SHA-256-Digest-Manifest-Main-Attributes: digest(mainSectionBytes)
     * - SHA-256-Digest-Manifest: digest(fullManifestBytes)
     * - For each entry: digest(sectionBytes)
     */
    private fun buildSignatureFile(m: ManifestBuild): ByteArray {
        val nl = "\r\n"
        val sf = StringBuilder()

        val manifestDigest = sha256(m.fullManifestBytes)
        val mainDigest = sha256(m.mainSectionBytes)

        sf.append("Signature-Version: 1.0").append(nl)
        sf.append("Created-By: MorIDE-APKSigner.class").append(nl)
        sf.append("SHA-256-Digest-Manifest-Main-Attributes: ")
            .append(Base64.getEncoder().encodeToString(mainDigest)).append(nl)
        sf.append("SHA-256-Digest-Manifest: ")
            .append(Base64.getEncoder().encodeToString(manifestDigest)).append(nl)
        sf.append(nl)

        m.perEntrySectionBytes.forEach { (name, sectionBytes) ->
            val secDigest = sha256(sectionBytes)
            sf.append("Name: ").append(name).append(nl)
            sf.append("SHA-256-Digest: ")
                .append(Base64.getEncoder().encodeToString(secDigest)).append(nl)
            sf.append(nl)
        }

        return sf.toString().toByteArray(Charsets.UTF_8)
    }

    private fun generateCmsSignature(
        privateKey: PrivateKey,
        cert: X509Certificate,
        dataToSign: ByteArray
    ): ByteArray {
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val sigInfoGen = JcaSignerInfoGeneratorBuilder(
            JcaDigestCalculatorProviderBuilder().build()
        ).build(contentSigner, cert)

        return CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(sigInfoGen)
            addCertificates(JcaCertStore(listOf(cert)))
        }.generate(CMSProcessableByteArray(dataToSign), true).encoded
    }

    private fun writeSignedApk(
        sourceJar: JarFile,
        outFile: File,
        manifestBytes: ByteArray,
        sfBytes: ByteArray,
        rsaBytes: ByteArray
    ) {
        JarOutputStream(FileOutputStream(outFile)).use { jos ->
            // Write META-INF first (typical layout)
            // MANIFEST.MF
            jos.putNextEntry(JarEntry("META-INF/"))
            jos.closeEntry()

            jos.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            jos.write(manifestBytes)
            jos.closeEntry()

            // CERT.SF
            jos.putNextEntry(JarEntry("META-INF/CERT.SF"))
            jos.write(sfBytes)
            jos.closeEntry()

            // CERT.RSA
            jos.putNextEntry(JarEntry("META-INF/CERT.RSA"))
            jos.write(rsaBytes)
            jos.closeEntry()

            // Copy all original entries EXCEPT META-INF/*
            val toCopy = sourceJar.entries().toList()
                .filter { !it.isDirectory && !it.name.startsWith("META-INF/", ignoreCase = true) }

            toCopy.forEach { e ->
                val newEntry = JarEntry(e.name)
                jos.putNextEntry(newEntry)
                sourceJar.getInputStream(e).use { it.copyTo(jos) }
                jos.closeEntry()
            }
        }
    }

    // -------------------- Utilities --------------------

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun verifyInputFiles(
        apkFile: File,
        keystoreFile: File,
        logListener: LogListener?
    ): Boolean {
        if (!apkFile.exists()) {
            logListener?.onLog("APK file not found: ${apkFile.absolutePath}")
            return false
        }
        if (!keystoreFile.exists()) {
            logListener?.onLog("Keystore not found: ${keystoreFile.absolutePath}")
            return false
        }
        return true
    }

    private fun loadKeyStore(
        keystoreFile: File,
        keystorePassword: String,
        alias: String,
        keyPassword: String,
        logListener: LogListener?
    ): Pair<PrivateKey, X509Certificate> {
        logListener?.onLog("Loading keystore...")
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(keystoreFile).use { ks.load(it, keystorePassword.toCharArray()) }
        val key = ks.getKey(alias, keyPassword.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        return key to cert
    }
}