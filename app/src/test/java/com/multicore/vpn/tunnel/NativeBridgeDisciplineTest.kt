package com.multicore.vpn.tunnel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard rails for the next engine, not for the current code: a new JNI bridge
 * that forgets the gate would bring back the overlapping-start crash, and a
 * renamed bridge class would break the exported JNI symbols at runtime (the
 * libraries resolve Java_com_multicore_vpn_tunnel_<Class>_<method> by name).
 *
 * These read the sources instead of the bytecode because the failure has to be
 * caught when the code is written, before anything can be packaged.
 */
class NativeBridgeDisciplineTest {
    private val javaRoot = File("src/main/java")

    private fun sources(): List<File> =
        javaRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no JNI declaration or bridge call escapes the gate`() {
        val directCall = Regex("""\b\w+Bridge\s*\.\s*native[A-Z]\w*""")
        val declaration = Regex("""external fun native[A-Z]\w*""")
        val offenders = sources().filter { file ->
            val text = file.readText()
            (declaration.containsMatchIn(text) || directCall.containsMatchIn(text)) &&
                !text.contains("NativeGate")
        }
        assertTrue(
            "native entry points must be declared and called in a file that serialises " +
                "them with NativeGate; otherwise a second engine can start while the " +
                "first is still dialling and the Go runtime aborts the process. Files: " +
                offenders.joinToString { it.path },
            offenders.isEmpty()
        )
    }

    @Test
    fun `bridge classes keep the names the native libraries export`() {
        val tunnelSources = File(javaRoot, "com/multicore/vpn/tunnel")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to it.readText() }
        // Psiphon is no longer a JNI bridge of ours: it is the official AAR, whose
        // library is loaded by psi.Psi. Sun and Lion still resolves its entry
        // points by class name.
        for (bridge in listOf("SunAndLionBridge")) {
            assertTrue(
                "$bridge must stay declared in com.multicore.vpn.tunnel: the core " +
                    "libraries bind Java_com_multicore_vpn_tunnel_${bridge}_nativeStart",
                tunnelSources.values.any { it.contains("object $bridge") }
            )
        }
    }
}
