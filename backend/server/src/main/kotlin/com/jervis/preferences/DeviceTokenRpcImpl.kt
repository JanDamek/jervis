package com.jervis.preferences

import com.jervis.dto.meeting.DeviceInfoDto
import com.jervis.dto.notification.DeviceContextDto
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
 * Two-endpoint split:
 *  - [registerToken] — OS-level push token, rarely changes. Client calls
 *    on first acquisition or rotation.
 *  - [setActiveContext] — which client this device is scoped to right
 *    now. Client calls on every RPC (re)connect so push routing and
 *    lastSeen stay fresh without re-uploading the token blob.
 */
@Component
class DeviceTokenRpcImpl(
    private val deviceTokenRepository: DeviceTokenRepository,
) : IDeviceTokenService {
    private val logger = KotlinLogging.logger {}

    override suspend fun registerToken(dto: DeviceTokenDto): DeviceTokenRegistrationResult {
        return try {
            val deviceType = try { DeviceType.valueOf(dto.deviceType) } catch (_: Exception) { DeviceType.UNKNOWN }
            val now = Instant.now()
            val existing = deviceTokenRepository.findByDeviceId(dto.deviceId)
            if (existing != null) {
                val updated = existing.copy(
                    token = dto.token,
                    platform = dto.platform,
                    deviceName = dto.deviceName.ifBlank { existing.deviceName },
                    deviceType = if (deviceType != DeviceType.UNKNOWN) deviceType else existing.deviceType,
                    capabilities = dto.capabilities.ifEmpty { existing.capabilities },
                    lastSeen = now,
                    updatedAt = now,
                )
                deviceTokenRepository.save(updated)
                logger.info { "Updated push token for device=${dto.deviceId} platform=${dto.platform}" }
            } else {
                // clientId is unknown at this point — setActiveContext will fill it
                // on the first post-connect announcement.
                val document = DeviceTokenDocument(
                    clientId = "",
                    token = dto.token,
                    platform = dto.platform,
                    deviceId = dto.deviceId,
                    deviceName = dto.deviceName,
                    deviceType = deviceType,
                    capabilities = dto.capabilities,
                )
                deviceTokenRepository.save(document)
                logger.info { "Registered new push token for device=${dto.deviceId} platform=${dto.platform}" }
            }
            DeviceTokenRegistrationResult(success = true)
        } catch (e: Exception) {
            logger.error(e) { "Failed to register push token for device=${dto.deviceId}" }
            DeviceTokenRegistrationResult(success = false, message = e.message)
        }
    }

    override suspend fun setActiveContext(dto: DeviceContextDto): DeviceTokenRegistrationResult {
        return try {
            val existing = deviceTokenRepository.findByDeviceId(dto.deviceId)
                ?: return DeviceTokenRegistrationResult(
                    success = false,
                    message = "device not registered — call registerToken first",
                )
            val now = Instant.now()
            val updated = existing.copy(
                clientId = dto.clientId,
                lastSeen = now,
                updatedAt = now,
            )
            deviceTokenRepository.save(updated)
            logger.info { "Device context updated: deviceId=${dto.deviceId} clientId=${dto.clientId}" }
            DeviceTokenRegistrationResult(success = true)
        } catch (e: Exception) {
            logger.error(e) { "Failed to update device context for device=${dto.deviceId}" }
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
