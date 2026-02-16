package com.jervis.service.notification

import com.jervis.repository.DeviceTokenRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Firebase Cloud Messaging push notification service.
 *
 * Sends FCM data messages to all registered devices for a given client.
 * Data messages (not notification messages) ensure that
 * onMessageReceived is always called, even when the app is in foreground.
 *
 * NOTE: Firebase Admin SDK dependency is required.
 * Add to gradle/libs.versions.toml and backend/server/build.gradle.kts:
 *   firebase-admin = "9.3.0"
 *   implementation("com.google.firebase:firebase-admin:${libs.versions.firebase.admin}")
 *
 * Also requires a Firebase service account JSON in the deployment environment.
 * Set GOOGLE_APPLICATION_CREDENTIALS env var or configure programmatically.
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
     * Send a push notification to all registered devices for a client.
     *
     * Uses FCM data messages for full control over notification display.
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

        val tokens = deviceTokenRepository.findByClientIdAndPlatform(clientId, "android").toList()
        if (tokens.isEmpty()) {
            logger.debug { "No registered devices for client=$clientId, skipping push" }
            return
        }

        logger.info { "Sending push notification to ${tokens.size} device(s) for client=$clientId" }

        val messaging = com.google.firebase.messaging.FirebaseMessaging.getInstance()

        for (tokenDoc in tokens) {
            try {
                val message = com.google.firebase.messaging.Message.builder()
                    .setToken(tokenDoc.token)
                    .putAllData(data + mapOf(
                        "title" to title,
                        "body" to body,
                        "clientId" to clientId,
                    ))
                    .build()

                messaging.send(message)
                logger.debug { "Push sent to device=${tokenDoc.deviceId} platform=${tokenDoc.platform}" }
            } catch (e: Exception) {
                logger.warn { "Failed to send push to device=${tokenDoc.deviceId}: ${e.message}" }
                // If token is invalid, clean it up
                if (e.message?.contains("not registered", ignoreCase = true) == true ||
                    e.message?.contains("invalid registration", ignoreCase = true) == true
                ) {
                    logger.info { "Removing invalid token for device=${tokenDoc.deviceId}" }
                    deviceTokenRepository.deleteByDeviceId(tokenDoc.deviceId)
                }
            }
        }
    }
}
