package com.jervis.infrastructure.notification

import com.jervis.preferences.DeviceTokenRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Firebase Cloud Messaging push notification service.
 *
 * Sends FCM messages with BOTH notification + data payloads to all registered devices.
 * Notification payload ensures the system tray notification is shown even when app is backgrounded.
 * Data payload carries structured info for in-app handling when app is in foreground.
 */
@Service
class FcmPushService(
    private val deviceTokenRepository: DeviceTokenRepository,
) {
    private val logger = KotlinLogging.logger {}

    // Firebase Admin is initialized lazily — not all deployments have FCM configured
    private val firebaseInitialized: Boolean by lazy {
        try {
            val options = com.google.firebase.FirebaseOptions.builder()
                .setCredentials(com.google.auth.oauth2.GoogleCredentials.getApplicationDefault())
                .build()
            com.google.firebase.FirebaseApp.initializeApp(options)
            logger.info { "Firebase Admin SDK initialized successfully" }
            true
        } catch (e: Exception) {
            logger.warn { "Firebase Admin SDK not available: ${e.message}. Push notifications disabled." }
            false
        }
    }

    /**
     * Send a push notification to all registered FCM devices for a client.
     * Searches for both 'android' and 'desktop' platform tokens.
     */
    suspend fun sendPushNotification(
        clientId: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
    ) {
        if (!firebaseInitialized) {
            logger.debug { "FCM not initialized, skipping push for client=$clientId" }
            return
        }

        // Find all FCM-capable devices (android + desktop use FCM)
        val tokens = (
            deviceTokenRepository.findByClientIdAndPlatform(clientId, "android").toList() +
            deviceTokenRepository.findByClientIdAndPlatform(clientId, "desktop").toList()
        )
        if (tokens.isEmpty()) {
            logger.info { "No FCM devices for client=$clientId, skipping push" }
            return
        }

        logger.info { "Sending FCM push to ${tokens.size} device(s) for client=$clientId: $title" }

        val messaging = com.google.firebase.messaging.FirebaseMessaging.getInstance()

        for (tokenDoc in tokens) {
            try {
                val isUrgent = data["interruptAction"] in listOf("o365_mfa", "o365_relogin")

                val messageBuilder = com.google.firebase.messaging.Message.builder()
                    .setToken(tokenDoc.token)
                    // Notification payload — ensures system tray notification when app is backgrounded
                    .setNotification(
                        com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build()
                    )
                    // Data payload — for in-app handling when foregrounded
                    .putAllData(data + mapOf(
                        "title" to title,
                        "body" to body,
                        "clientId" to clientId,
                    ))

                // MFA notifications need immediate delivery with short TTL
                if (isUrgent) {
                    messageBuilder.setAndroidConfig(
                        com.google.firebase.messaging.AndroidConfig.builder()
                            .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                            .setTtl(120_000) // 2 minutes — MFA has a short window
                            .setNotification(
                                com.google.firebase.messaging.AndroidNotification.builder()
                                    .setChannelId("jervis_urgent")
                                    .setPriority(com.google.firebase.messaging.AndroidNotification.Priority.MAX)
                                    .build()
                            )
                            .build()
                    )
                }

                val message = messageBuilder.build()
                val messageId = messaging.send(message)
                logger.info { "FCM push sent: device=${tokenDoc.deviceId} platform=${tokenDoc.platform} messageId=$messageId" }
            } catch (e: Exception) {
                logger.warn { "FCM push failed: device=${tokenDoc.deviceId} error=${e.message}" }
                // If token is invalid, clean it up
                if (e.message?.contains("not registered", ignoreCase = true) == true ||
                    e.message?.contains("invalid registration", ignoreCase = true) == true
                ) {
                    logger.info { "Removing invalid FCM token for device=${tokenDoc.deviceId}" }
                    deviceTokenRepository.deleteByDeviceId(tokenDoc.deviceId)
                }
            }
        }
    }
}
