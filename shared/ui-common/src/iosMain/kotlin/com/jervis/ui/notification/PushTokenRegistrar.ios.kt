package com.jervis.ui.notification

import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.service.IDeviceTokenService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

actual object PushTokenRegistrar {
    private var lastRegisteredKey: String? = null

    actual suspend fun registerIfNeeded(clientId: String, deviceTokenService: IDeviceTokenService) {
        try {
            var token = IosTokenHolder.apnsToken
            var deviceId = IosTokenHolder.deviceId

            // APNs token arrives asynchronously from Swift — wait if not ready yet
            if (token.isNullOrBlank() || deviceId.isNullOrBlank()) {
                val pair = withTimeoutOrNull(10_000L) {
                    IosTokenHolder.tokenFlow.filterNotNull().first()
                }
                if (pair == null) {
                    println("APNs token not available after 10s, skipping registration")
                    return
                }
                token = pair.first
                deviceId = pair.second
            }

            // Dedup: don't re-register same token for same client
            val key = "$clientId:$token"
            if (key == lastRegisteredKey) return

            val result = deviceTokenService.registerToken(
                DeviceTokenDto(
                    clientId = clientId,
                    token = token,
                    platform = "ios",
                    deviceId = deviceId,
                ),
            )

            if (result.success) {
                lastRegisteredKey = key
                println("APNs token registered for client $clientId")
            } else {
                println("APNs token registration failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("APNs token registration error: ${e.message}")
        }
    }
}
