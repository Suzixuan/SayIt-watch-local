package com.sayit.watch.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Build-agnostic manifest policy checks: the debug overlay manifest must
 * explicitly permit cleartext, and the main (release) manifest must not
 * enable it. These files are inspected at source level; the merged manifest
 * behavior is additionally enforced by CleartextPolicyTest and the release
 * factory guard in Transport.
 */
class ManifestCleartextTest {

    private val mainManifest: String by lazy {
        // JVM unit tests run with CWD = the app module directory.
        File("src/main/AndroidManifest.xml").readText()
    }

    private val debugManifest: String by lazy {
        File("src/debug/AndroidManifest.xml").readText()
    }

    @Test
    fun `debug overlay manifest explicitly permits cleartext`() {
        assertTrue(
            "debug manifest must set android:usesCleartextTraffic=\"true\"",
            debugManifest.contains("android:usesCleartextTraffic=\"true\"")
        )
    }

    @Test
    fun `main release manifest does not enable cleartext`() {
        // Match only the XML attribute, not prose in comments.
        assertTrue(
            "main manifest must not set usesCleartextTraffic=true",
            !mainManifest.contains("android:usesCleartextTraffic=\"true\"")
        )
        // The application element must not carry any cleartext override at all.
        assertTrue(
            "main manifest must not reference android:usesCleartextTraffic",
            !mainManifest.contains("android:usesCleartextTraffic")
        )
    }

    @Test
    fun `debug manifest exists as overlay not main`() {
        assertTrue(File("src/debug/AndroidManifest.xml").isFile)
        assertTrue(File("src/main/AndroidManifest.xml").isFile)
    }
}
