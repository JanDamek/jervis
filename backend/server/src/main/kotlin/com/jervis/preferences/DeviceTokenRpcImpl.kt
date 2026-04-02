package com.jervis.preferences

import com.jervis.dto.meeting.DeviceInfoDto
import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.dto.notification.DeviceTokenRegistrationResult
import com.jervis.preferences.DeviceTokenDocument
import com.jervis.preferences.DeviceTokenRepository
import com.jervis.service.notification.IDeviceTokenService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * RPC implementation for device token management.
 *
 * Handles FCM/APNs token registration from mobile apps
 * so the backend can send push notifications.
 * Also serves as device registry for Meeting Helper and other real-time features.
 */
@Component
class DeviceTokenRpcImpl(
    private val deviceTokenRepository: DeviceTokenRepository,
) : IDeviceTokenService {
    private val logger = KotlinLogging.logger {}

    override suspend fun registerToken(dto: DeviceTokenDto): DeviceTokenRegistrationResult {
        return try {
            val deviceType = try { DeviceType.valueOf(dto.deviceType) } catch (_: Exception) { DeviceType.UNKNOWN }
            // Upsert by deviceId
            val existing = deviceTokenRepository.findByDeviceId(dto.deviceId)
            if (existing != null) {
                val updated = existing.copy(
                    clientId = dto.clientId,
                    token = dto.token,
                    platform = dto.platform,
                    deviceName = dto.deviceName.ifBlank { existing.deviceName },
                    deviceType = if (deviceType != DeviceType.UNKNOWN) deviceType else existing.deviceType,
                    capabilities = dto.capabilities.ifEmpty { existing.capabilities },
                    lastSeen = Instant.now(),
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
                    deviceName = dto.deviceName,
                    deviceType = deviceType,
                    capabilities = dto.capabilities,
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

    override suspend fun listDevices(clientId: String): List<DeviceInfoDto> {
        return deviceTokenRepository.findByClientId(clientId).toList().map { doc ->
            DeviceInfoDto(
                deviceId = doc.deviceId,
                deviceName = doc.deviceName.ifBlank { "${doc.platform} device" },
                deviceType = doc.deviceType.name,
                platform = doc.platform,
                capabilities = doc.capabilities,
                lastSeen = doc.lastSeen.toString(),
            )
        }
    }
}
