package com.jervis.ui.notification

import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.service.IDeviceTokenService

actual object PushTokenRegistrar {
    private var lastRegisteredKey: String? = null

    actual suspend fun registerIfNeeded(clientId: String, deviceTokenService: IDeviceTokenService) {
        try {
            val hostname = try {
                java.net.InetAddress.getLocalHost().hostName
            } catch (_: Exception) {
                "unknown"
            }
            val username = System.getProperty("user.name") ?: "unknown"
            val deviceId = "desktop-$hostname-$username"

            val key = "$clientId:$deviceId"
            if (key == lastRegisteredKey) return

            val result = deviceTokenService.registerToken(
                DeviceTokenDto(
                    clientId = clientId,
                    token = "",
                    platform = "desktop",
                    deviceId = deviceId,
                ),
            )

            if (result.success) {
                lastRegisteredKey = key
                println("Desktop device registered: $deviceId")
            } else {
                println("Desktop device registration failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("Desktop device registration error: ${e.message}")
        }
    }
}
