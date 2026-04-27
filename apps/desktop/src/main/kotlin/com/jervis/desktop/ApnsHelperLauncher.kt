package com.jervis.desktop

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates and spawns the JervisAPNs Swift helper that holds the
 * `aps-environment` entitlement. The JVM cannot register for APNs
 * directly — the helper does it and streams the token + incoming
 * pushes to the JVM over a Unix socket.
 *
 * Lookup order:
 *  1. `JERVIS_APNS_HELPER_APP` env override (any path)
 *  2. PROD: `<myAppPath>/Contents/Resources/JervisAPNs.app`
 *     (jpackage Jervis.app bundle)
 *  3. DEV: `apps/macApp/build/Build/Products/Debug/JervisAPNs.app`
 *     (xcodebuild output, picked up by IntelliJ run)
 *  4. DEV: DerivedData fallback
 *     (`~/Library/Developer/Xcode/DerivedData/JervisAPNs-*`)
 */
object ApnsHelperLauncher {
    private val socketPath = Path.of("/tmp/jervis-macapp-apns.sock")

    fun ensureRunningIfMacOs() {
        if (!isMacOs()) return
        if (isSocketAlive()) return  // Helper genuinely running and listening.

        // Stale socket file from a crashed helper would otherwise block
        // the new instance's bind(). Helper unlinks before bind, but
        // remove proactively so the JVM connect succeeds without retries.
        if (Files.exists(socketPath)) {
            runCatching { Files.delete(socketPath) }
        }

        val helper = locateHelper() ?: run {
            println("APNs helper not found — push notifications will be unavailable. Build it with `xcodegen generate && xcodebuild -scheme JervisAPNs build` in apps/macApp.")
            return
        }

        try {
            ProcessBuilder("/usr/bin/open", "-a", helper.absolutePath)
                .redirectErrorStream(true)
                .start()
            println("APNs helper launched: ${helper.absolutePath}")
        } catch (e: Exception) {
            println("Failed to launch APNs helper: ${e.message}")
        }
    }

    private fun isSocketAlive(): Boolean {
        if (!Files.exists(socketPath)) return false
        return runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                ch.connect(UnixDomainSocketAddress.of(socketPath))
            }
            true
        }.getOrDefault(false)
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("mac") == true

    private fun locateHelper(): File? {
        System.getenv("JERVIS_APNS_HELPER_APP")?.takeIf { it.isNotBlank() }?.let { override ->
            val f = File(override)
            if (f.exists()) return f
        }

        val myAppPath = System.getProperty("jpackage.app-path")
            ?: System.getProperty("user.dir")
        File(myAppPath, "Contents/Resources/JervisAPNs.app").takeIf { it.exists() }?.let { return it }

        val cwd = File(System.getProperty("user.dir"))
        val devPath = sequenceOf(
            File(cwd, "apps/macApp/build/Build/Products/Debug/JervisAPNs.app"),
            File(cwd, "../macApp/build/Build/Products/Debug/JervisAPNs.app"),
            File(cwd, "../../apps/macApp/build/Build/Products/Debug/JervisAPNs.app"),
        ).firstOrNull { it.exists() }
        if (devPath != null) return devPath

        val derivedData = File(System.getProperty("user.home"), "Library/Developer/Xcode/DerivedData")
        if (derivedData.isDirectory) {
            derivedData.listFiles { f -> f.isDirectory && f.name.startsWith("JervisAPNs-") }
                ?.asSequence()
                ?.mapNotNull { File(it, "Build/Products/Debug/JervisAPNs.app").takeIf(File::exists) }
                ?.firstOrNull()
                ?.let { return it }
        }

        return null
    }
}
