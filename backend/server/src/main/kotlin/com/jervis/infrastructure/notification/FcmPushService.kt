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

        // Find all FCM-capable devices (android + desktop use FCM).
        // Win/Linux desktop registers with an empty token (FCM on desktop
        // JVM isn't wired yet — see project-win-linux-desktop-push-deferred);
        // filter those out, otherwise FirebaseMessaging.send() throws
        // "Exactly one of token, topic or condition must be specified".
        val tokens = (
            deviceTokenRepository.findByClientIdAndPlatform(clientId, "android").toList() +
            deviceTokenRepository.findByClientIdAndPlatform(clientId, "desktop").toList()
        ).filter { it.token.isNotBlank() }
        if (tokens.isEmpty()) {
            logger.info { "No FCM devices for client=$clientId, skipping push" }
            return
        }

        logger.info { "Sending FCM push to ${tokens.size} device(s) for client=$clientId: $title" }

        val messaging = com.google.firebase.messaging.FirebaseMessaging.getInstance()

        for (tokenDoc in tokens) {
            try {
                val isUrgent = data["interruptAction"] in listOf("o365_mfa", "o365_relogin")
                // Login consent must be data-only so JervisFcmService.onMessageReceived
                // fires in background → builds a local notification with the four
                // action buttons (Now / Defer 15 min / Defer 1 hod / Cancel) via
                // PlatformNotificationManager. With a `notification` payload set
                // Android FCM auto-shows a button-less system notification and
                // never wakes our service.
                val isLoginConsent = data["type"] == "login_consent"

                val messageBuilder = com.google.firebase.messaging.Message.builder()
                    .setToken(tokenDoc.token)
                    .putAllData(data + mapOf(
                        "title" to title,
                        "body" to body,
                        "clientId" to clientId,
                    ))

                if (!isLoginConsent) {
                    // Standard path: notification payload + data. System tray
                    // shows the basic notification when app is backgrounded.
                    messageBuilder.setNotification(
                        com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build()
                    )
                }

                // MFA + login_consent need immediate delivery with short TTL
                if (isUrgent || isLoginConsent) {
                    messageBuilder.setAndroidConfig(
                        com.google.firebase.messaging.AndroidConfig.builder()
                            .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                            .setTtl(if (isLoginConsent) 600_000 else 120_000)
                            .apply {
                                if (!isLoginConsent) {
                                    // Login consent has no notification payload, so
                                    // AndroidNotification config is inert; only set
                                    // it for the MFA notification path.
                                    setNotification(
                                        com.google.firebase.messaging.AndroidNotification.builder()
                                            .setChannelId("jervis_urgent")
                                            .setPriority(com.google.firebase.messaging.AndroidNotification.Priority.MAX)
                                            .build()
                                    )
                                }
                            }
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
