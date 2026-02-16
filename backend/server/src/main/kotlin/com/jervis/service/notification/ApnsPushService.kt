package com.jervis.service.notification

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import com.eatthepath.pushy.apns.util.SimpleApnsPayloadBuilder
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import com.eatthepath.pushy.apns.util.TokenUtil
import com.jervis.repository.DeviceTokenRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File

/**
 * Apple Push Notification service (APNs) via HTTP/2.
 *
 * Uses token-based authentication (.p8 key) via the Pushy library.
 * Configured through environment variables:
 * - APNS_KEY_PATH: path to .p8 auth key file
 * - APNS_KEY_ID: Key ID from Apple Developer
 * - APNS_TEAM_ID: Team ID from Apple Developer
 * - APNS_BUNDLE_ID: app bundle identifier (e.g. com.jervis.mobile)
 * - APNS_PRODUCTION: "true" for production, defaults to production
 */
@Service
class ApnsPushService(
    private val deviceTokenRepository: DeviceTokenRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val keyPath = System.getenv("APNS_KEY_PATH")
    private val keyId = System.getenv("APNS_KEY_ID")
    private val teamId = System.getenv("APNS_TEAM_ID")
    private val bundleId = System.getenv("APNS_BUNDLE_ID") ?: "com.jervis.mobile"
    private val isProduction = System.getenv("APNS_PRODUCTION")?.lowercase() != "false"

    private val apnsClient: ApnsClient? by lazy {
        if (keyPath.isNullOrBlank() || keyId.isNullOrBlank() || teamId.isNullOrBlank()) {
            logger.warn { "APNs not configured (missing APNS_KEY_PATH, APNS_KEY_ID, or APNS_TEAM_ID). iOS push disabled." }
            return@lazy null
        }

        val keyFile = File(keyPath)
        if (!keyFile.exists()) {
            logger.warn { "APNs key file not found at $keyPath. iOS push disabled." }
            return@lazy null
        }

        try {
            val client = ApnsClientBuilder()
                .setApnsServer(
                    if (isProduction) ApnsClientBuilder.PRODUCTION_APNS_HOST
                    else ApnsClientBuilder.DEVELOPMENT_APNS_HOST,
                )
                .setSigningKey(ApnsSigningKey.loadFromPkcs8File(keyFile, teamId, keyId))
                .build()

            logger.info { "APNs client initialized (production=$isProduction, bundleId=$bundleId)" }
            client
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize APNs client" }
            null
        }
    }

    /**
     * Send a push notification to all registered iOS devices for a client.
     */
    suspend fun sendPushNotification(
        clientId: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
    ) {
        val client = apnsClient
        if (client == null) {
            logger.debug { "APNs not initialized, skipping push for client=$clientId" }
            return
        }

        val tokens = deviceTokenRepository.findByClientIdAndPlatform(clientId, "ios").toList()
        if (tokens.isEmpty()) {
            logger.debug { "No iOS devices for client=$clientId, skipping APNs push" }
            return
        }

        logger.info { "Sending APNs push to ${tokens.size} iOS device(s) for client=$clientId" }

        val isApproval = data["isApproval"] == "true"

        val payloadBuilder = SimpleApnsPayloadBuilder()
            .setAlertTitle(title)
            .setAlertBody(body)
            .setSound("default")
            .setContentAvailable(true)

        // Set category for actionable notifications (Approve/Deny buttons)
        if (isApproval) {
            payloadBuilder.setCategoryName("APPROVAL")
        }

        // Add custom data fields
        for ((key, value) in data) {
            payloadBuilder.addCustomProperty(key, value)
        }
        // Add badge count if present
        data["badgeCount"]?.toIntOrNull()?.let { payloadBuilder.setBadgeNumber(it) }

        val payload = payloadBuilder.build()

        for (tokenDoc in tokens) {
            try {
                val sanitizedToken = TokenUtil.sanitizeTokenString(tokenDoc.token)
                val notification = SimpleApnsPushNotification(sanitizedToken, bundleId, payload)
                val response = client.sendNotification(notification).get()

                if (response.isAccepted) {
                    logger.debug { "APNs push sent to device=${tokenDoc.deviceId}" }
                } else {
                    val reason = response.rejectionReason.orElse("unknown")
                    logger.warn { "APNs push rejected for device=${tokenDoc.deviceId}: $reason" }
                    // Clean up invalid tokens
                    if (reason == "BadDeviceToken" || reason == "Unregistered" || reason == "ExpiredToken") {
                        logger.info { "Removing invalid APNs token for device=${tokenDoc.deviceId}" }
                        deviceTokenRepository.deleteByDeviceId(tokenDoc.deviceId)
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to send APNs push to device=${tokenDoc.deviceId}: ${e.message}" }
            }
        }
    }
}
