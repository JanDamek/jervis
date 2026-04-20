package com.jervis.ui.notification

import com.jervis.dto.notification.DeviceContextDto
import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.service.notification.IDeviceTokenService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

actual object PushTokenRegistrar {
    // In-memory dedup: last token value that was successfully uploaded.
    // Persisted only per-process; a cold start will re-upload once which
    // is fine — the backend is idempotent on deviceId.
    private var lastRegisteredToken: String? = null

    actual suspend fun registerTokenIfNeeded(deviceTokenService: IDeviceTokenService) {
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

            if (token == lastRegisteredToken) return

            val result = deviceTokenService.registerToken(
                DeviceTokenDto(
                    deviceId = deviceId!!,
                    token = token!!,
                    platform = "ios",
                ),
            )

            if (result.success) {
                lastRegisteredToken = token
                println("APNs token registered, device $deviceId")
            } else {
                println("APNs token registration failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("APNs token registration error: ${e.message}")
        }
    }

    actual suspend fun announceContext(clientId: String, deviceTokenService: IDeviceTokenService) {
        try {
            val deviceId = IosTokenHolder.deviceId
            if (deviceId.isNullOrBlank()) {
                // No deviceId yet — registerTokenIfNeeded hasn't run / token not arrived.
                // Context will be announced later on next reconnect cycle.
                return
            }
            val result = deviceTokenService.setActiveContext(
                DeviceContextDto(deviceId = deviceId, clientId = clientId),
            )
            if (!result.success) {
                println("APNs context announce failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("APNs context announce error: ${e.message}")
        }
    }
}
