package com.jervis.infrastructure.notification

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import com.eatthepath.pushy.apns.util.SimpleApnsPayloadBuilder
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import com.eatthepath.pushy.apns.util.TokenUtil
import com.jervis.preferences.DeviceTokenRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File

/**
 * Apple Push Notification service (APNs) via HTTP/2.
 *
 * Uses token-based authentication (.p8 key) via the Pushy library. The
 * same Team ID + .p8 Auth Key signs both iOS (apps/iosApp) and macOS
 * (apps/macApp) apps, but each has its own bundle identifier which
 * becomes the apns-topic header — iOS and macOS tokens must be routed
 * with their respective bundle IDs or APNs rejects with TopicDisallowed.
 *
 * Configured through environment variables:
 * - APNS_KEY_PATH: path to .p8 auth key file
 * - APNS_KEY_ID: Key ID from Apple Developer
 * - APNS_TEAM_ID: Team ID from Apple Developer
 * - APNS_BUNDLE_ID: iOS app bundle identifier (apns-topic for iOS devices)
 * - APNS_BUNDLE_ID_MACOS: macOS app bundle identifier (apns-topic for macOS devices)
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
    private val iosBundleId = System.getenv("APNS_BUNDLE_ID") ?: "com.jervis"
    private val macosBundleId = System.getenv("APNS_BUNDLE_ID_MACOS") ?: "com.jervis.macApp"
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

            logger.info { "APNs client initialized (production=$isProduction, iosBundleId=$iosBundleId, macosBundleId=$macosBundleId)" }
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

        val iosTokens = deviceTokenRepository.findByClientIdAndPlatform(clientId, "ios").toList()
        val macosTokens = deviceTokenRepository.findByClientIdAndPlatform(clientId, "macos").toList()
        val tokens = iosTokens.map { it to iosBundleId } + macosTokens.map { it to macosBundleId }
        if (tokens.isEmpty()) {
            logger.debug { "No APNs devices for client=$clientId, skipping APNs push" }
            return
        }

        logger.info { "Sending APNs push to ${iosTokens.size} iOS + ${macosTokens.size} macOS device(s) for client=$clientId" }

        val isApproval = data["isApproval"] == "true"
        val isLoginConsent = data["type"] == "login_consent"
        val isUrgent = isLoginConsent ||
            data["interruptAction"] in listOf("o365_mfa", "o365_relogin")

        // Alert push only — never set setContentAvailable(true) together with
        // setAlertTitle/Body. Apple treats (alert + content-available) as a
        // silent background-refresh push: iOS queues the payload but does NOT
        // show a banner until the app is next opened, which defeats the whole
        // point of a user-facing notification (MFA, meeting invite, etc.).
        val payloadBuilder = SimpleApnsPayloadBuilder()
            .setAlertTitle(title)
            .setAlertBody(body)
            .setSound("default")

        // time-sensitive interruption-level — Pushy 0.15+ has a typed setter
        // that places the field in the right place under `aps`. Earlier code
        // used addCustomProperty which inserts at the root and iOS silently
        // ignored it (sometimes dropping the entire payload). Time-sensitive
        // also requires the iOS app to declare the
        // `com.apple.developer.usernotifications.time-sensitive` entitlement;
        // without it Apple downgrades to the active interruption level, but
        // the alert is still displayed.
        if (isUrgent) {
            try {
                val cls = Class.forName("com.eatthepath.pushy.apns.util.InterruptionLevel")
                val timeSensitive = cls.enumConstants.firstOrNull { (it as Enum<*>).name == "TIME_SENSITIVE" }
                if (timeSensitive != null) {
                    SimpleApnsPayloadBuilder::class.java
                        .getMethod("setInterruptionLevel", cls)
                        .invoke(payloadBuilder, timeSensitive)
                } else {
                    logger.warn { "InterruptionLevel.TIME_SENSITIVE constant not found on Pushy class" }
                }
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to set time-sensitive interruption-level on APNs payload" }
            }
        }

        // Set category for actionable notifications. Login consent is checked
        // first because its push carries no `mfaType` / `isApproval` flags.
        val mfaType = data["mfaType"]
        val category = when {
            isLoginConsent -> "LOGIN_CONSENT"
            isUrgent && mfaType in listOf("authenticator_code", "sms_code") -> "MFA_CODE"
            isUrgent && mfaType != null -> "MFA_CONFIRM"
            isApproval -> "APPROVAL"
            else -> null
        }
        category?.let { payloadBuilder.setCategoryName(it) }

        // Add custom data fields — outside `aps`, accessible via
        // userInfo[...] in the iOS NotificationDelegate.
        for ((key, value) in data) {
            payloadBuilder.addCustomProperty(key, value)
        }
        // Add badge count if present
        data["badgeCount"]?.toIntOrNull()?.let { payloadBuilder.setBadgeNumber(it) }

        val payload = payloadBuilder.build()
        logger.info {
            "APNs payload (clientId=$clientId, isUrgent=$isUrgent, " +
                "isLoginConsent=$isLoginConsent, category=$category): $payload"
        }

        for ((tokenDoc, topic) in tokens) {
            try {
                val sanitizedToken = TokenUtil.sanitizeTokenString(tokenDoc.token)
                val expiration = when {
                    // Login consent has a 10-minute server-side hold; push TTL
                    // must match so the alert survives until the user opens the
                    // device. iOS keeps the push in apsd's queue until the
                    // device's APNs connection wakes up (background app open,
                    // screen unlock) — but only within this TTL.
                    isLoginConsent -> java.time.Instant.now().plusSeconds(600) // 10 min
                    // MFA challenges from Microsoft expire in ~60-90 s; keep
                    // the TTL tight so a stale code doesn't surface.
                    isUrgent -> java.time.Instant.now().plusSeconds(120) // 2 min
                    else -> java.time.Instant.now().plusSeconds(86400)
                }
                val priority = if (isUrgent) {
                    com.eatthepath.pushy.apns.DeliveryPriority.IMMEDIATE
                } else {
                    com.eatthepath.pushy.apns.DeliveryPriority.CONSERVE_POWER
                }
                val notification = SimpleApnsPushNotification(
                    sanitizedToken, topic, payload, expiration, priority,
                    com.eatthepath.pushy.apns.PushType.ALERT,
                )
                val response = client.sendNotification(notification).get()

                if (response.isAccepted) {
                    logger.info {
                        "APNs ACCEPTED device=${tokenDoc.deviceId} topic=$topic " +
                            "platform=${tokenDoc.platform} apnsId=${response.apnsId}"
                    }
                } else {
                    val reason = response.rejectionReason.orElse("unknown")
                    logger.warn { "APNs push rejected for device=${tokenDoc.deviceId} (topic=$topic): $reason" }
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
