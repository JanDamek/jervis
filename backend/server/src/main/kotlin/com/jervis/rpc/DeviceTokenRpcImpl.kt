package com.jervis.rpc

import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.dto.notification.DeviceTokenRegistrationResult
import com.jervis.entity.DeviceTokenDocument
import com.jervis.repository.DeviceTokenRepository
import com.jervis.service.IDeviceTokenService
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * RPC implementation for device token management.
 *
 * Handles FCM/APNs token registration from mobile apps
 * so the backend can send push notifications.
 */
@Component
class DeviceTokenRpcImpl(
    private val deviceTokenRepository: DeviceTokenRepository,
) : IDeviceTokenService {
    private val logger = KotlinLogging.logger {}

    override suspend fun registerToken(dto: DeviceTokenDto): DeviceTokenRegistrationResult {
        return try {
            // Upsert by deviceId
            val existing = deviceTokenRepository.findByDeviceId(dto.deviceId)
            if (existing != null) {
                val updated = existing.copy(
                    clientId = dto.clientId,
                    token = dto.token,
                    platform = dto.platform,
                    updatedAt = Instant.now(),
                )
                deviceTokenRepository.save(updated)
                logger.info { "Updated device token for device=${dto.deviceId} platform=${dto.platform}" }
            } else {
                val document = DeviceTokenDocument(
                    clientId = dto.clientId,
                    token = dto.token,
                    platform = dto.platform,
                    deviceId = dto.deviceId,
                )
                deviceTokenRepository.save(document)
                logger.info { "Registered new device token for device=${dto.deviceId} platform=${dto.platform}" }
            }
            DeviceTokenRegistrationResult(success = true)
        } catch (e: Exception) {
            logger.error(e) { "Failed to register device token for device=${dto.deviceId}" }
            DeviceTokenRegistrationResult(success = false, message = e.message)
        }
    }

    override suspend fun unregisterToken(deviceId: String): DeviceTokenRegistrationResult {
        return try {
            deviceTokenRepository.deleteByDeviceId(deviceId)
            logger.info { "Unregistered device token for device=$deviceId" }
            DeviceTokenRegistrationResult(success = true)
        } catch (e: Exception) {
            logger.error(e) { "Failed to unregister device token for device=$deviceId" }
            DeviceTokenRegistrationResult(success = false, message = e.message)
        }
    }
}
