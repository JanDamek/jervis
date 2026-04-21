package com.jervis.ui.notification

import com.jervis.dto.notification.DeviceContextDto
import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.service.notification.IDeviceTokenService
import kotlinx.coroutines.withTimeoutOrNull

actual object PushTokenRegistrar {
    // Desktop has no OS-level push token on Windows/Linux — we only
    // register the device row so Meeting Helper / device registry sees
    // it. The token field stays empty. Dedup once per process.
    //
    // On macOS we expect the apps/macApp Swift host to have spawned us
    // with `JERVIS_MACAPP_SOCKET` pointing at a Unix socket that streams
    // the APNs token + incoming push payloads. When present we register
    // with platform="macos" and the real APNs token; otherwise we fall
    // back to the bare desktop registration.
    private var tokenRegistered = false

    private fun desktopDeviceId(): String {
        val hostname = try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "unknown"
        }
        val username = System.getProperty("user.name") ?: "unknown"
        return "desktop-$hostname-$username"
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("mac") == true

    actual suspend fun registerTokenIfNeeded(deviceTokenService: IDeviceTokenService) {
        if (tokenRegistered) return

        if (isMacOs()) {
            // Default to the well-known socket path — the apps/macApp Swift
            // host (launched separately from Xcode) owns it. `JERVIS_MACAPP_SOCKET`
            // env var only exists for overrides (tests, custom layouts).
            val socket = System.getenv("JERVIS_MACAPP_SOCKET")?.takeIf { it.isNotBlank() }
                ?: "/tmp/jervis-macapp-apns.sock"
            val bridge = MacAppSocketBridge.ensureStarted(socket)
            val token = withTimeoutOrNull(15_000L) { bridge.awaitToken() }
            if (token != null) {
                registerMacOs(token, deviceTokenService)
                return
            }
            println("macApp: APNs socket $socket has no token after 15s — falling back to plain desktop registry. Is the Xcode-launched Jervis.app helper running?")
        }

        registerPlainDesktop(deviceTokenService)
    }

    private suspend fun registerMacOs(
        token: MacAppSocketBridge.TokenMessage,
        deviceTokenService: IDeviceTokenService,
    ) {
        try {
            val result = deviceTokenService.registerToken(
                DeviceTokenDto(
                    deviceId = token.deviceId,
                    token = token.hexToken,
                    platform = "macos",
                ),
            )
            if (result.success) {
                tokenRegistered = true
                println("macOS APNs token registered, device ${token.deviceId}")
            } else {
                println("macOS APNs token registration failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("macOS APNs token registration error: ${e.message}")
        }
    }

    private suspend fun registerPlainDesktop(deviceTokenService: IDeviceTokenService) {
        try {
            val deviceId = desktopDeviceId()
            val result = deviceTokenService.registerToken(
                DeviceTokenDto(
                    deviceId = deviceId,
                    token = "",
                    platform = "desktop",
                ),
            )
            if (result.success) {
                tokenRegistered = true
                println("Desktop device registered: $deviceId")
            } else {
                println("Desktop device registration failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("Desktop device registration error: ${e.message}")
        }
    }

    actual suspend fun announceContext(clientId: String, deviceTokenService: IDeviceTokenService) {
        try {
            val deviceId = if (isMacOs()) {
                MacAppSocketBridge.currentDeviceId() ?: desktopDeviceId()
            } else {
                desktopDeviceId()
            }
            val result = deviceTokenService.setActiveContext(
                DeviceContextDto(deviceId = deviceId, clientId = clientId),
            )
            if (!result.success) {
                println("Desktop context announce failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("Desktop context announce error: ${e.message}")
        }
    }
}
