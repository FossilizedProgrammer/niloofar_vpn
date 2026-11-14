package com.ffvpn.client.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The prebuilt cores are vendored binaries: a new engine release arrives as a
 * replaced .so, and nothing in the compiler can tell an upgrade from a corrupted
 * or half-copied file. This ties the tree to what was reviewed and shipped, so a
 * dropped-in core fails the tests until it is recorded on purpose with
 *
 *     ./tools/build-apks.sh --update-manifest
 *
 * The build preflight runs the same comparison, so the APKs cannot be packaged
 * with a silent binary either.
 */
class CoreManifestTest {
    private val root = listOf(File("."), File("..")).firstOrNull {
        File(it, "tools/cores.manifest").isFile && File(it, "app/src/main/jniLibs").isDirectory
    } ?: error("Cannot locate the repository root from ${File(".").absolutePath}")

    private val jniLibs = File(root, "app/src/main/jniLibs")
    private val manifest = File(root, "tools/cores.manifest")

    private fun recorded(): Map<String, String> =
        manifest.readLines()
            .mapNotNull { Regex("""^([0-9a-f]{64})\s+(\S+)$""").find(it) }
            .associate { it.groupValues[2] to it.groupValues[1] }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `every shipped native library matches the manifest`() {
        val recorded = recorded()
        val onDisk = jniLibs.walkTopDown()
            .filter { it.isFile && it.extension == "so" }
            .map { it.relativeTo(jniLibs).invariantSeparatorsPath }
            .toList()
        val unrecorded = onDisk - recorded.keys
        assertTrue(
            "unrecorded native libraries: $unrecorded. Record the reviewed cores with " +
                "./tools/build-apks.sh --update-manifest",
            unrecorded.isEmpty()
        )

        val mismatched = recorded.mapNotNull { (path, want) ->
            val file = File(jniLibs, path)
            when {
                !file.isFile -> "$path (listed but missing)"
                sha256(file) != want -> "$path"
                else -> null
            }
        }
        assertTrue(
            "these cores differ from the manifest: $mismatched. If this is a deliberate " +
                "engine upgrade, run ./tools/build-apks.sh --update-manifest and review the " +
                "diff, which records the new build revision and tags.",
            mismatched.isEmpty()
        )
        assertTrue("the manifest has no entries", recorded.isNotEmpty())
    }

    @Test
    fun `the vendored engines are recorded for both ABIs`() {
        val recorded = recorded().keys
        for (abi in listOf("armeabi-v7a", "arm64-v8a")) {
            // Psiphon ships as the official ca.psiphon.aar (app/libs) and is not
            // part of this prebuilt tree; Sun and Lion still is.
            for (engine in listOf("libsunlion.so", "libsunlionbridge.so")) {
                assertTrue("missing $abi/$engine in the manifest", "$abi/$engine" in recorded)
            }
        }
        assertEquals(
            "the manifest should cover the whole prebuilt tree",
            jniLibs.walkTopDown().count { it.isFile && it.extension == "so" },
            recorded.size
        )
    }
}
