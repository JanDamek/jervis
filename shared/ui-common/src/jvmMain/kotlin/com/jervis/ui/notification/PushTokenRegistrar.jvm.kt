package com.jervis.ui.notification

import com.jervis.dto.notification.DeviceContextDto
import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.service.notification.IDeviceTokenService

actual object PushTokenRegistrar {
    // Desktop has no OS-level push token — we only register the device
    // row so Meeting Helper / device registry sees it. The token field
    // stays empty. Dedup once per process is enough.
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

    actual suspend fun registerTokenIfNeeded(deviceTokenService: IDeviceTokenService) {
        if (tokenRegistered) return
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
            val result = deviceTokenService.setActiveContext(
                DeviceContextDto(deviceId = desktopDeviceId(), clientId = clientId),
            )
            if (!result.success) {
                println("Desktop context announce failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("Desktop context announce error: ${e.message}")
        }
    }
}
