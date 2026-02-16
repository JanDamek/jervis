package com.jervis.mobile

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jervis.ui.notification.AndroidContextHolder
import com.jervis.ui.notification.FcmTokenStorage
import com.jervis.ui.notification.PlatformNotificationManager

/**
 * Firebase Cloud Messaging service for receiving push notifications.
 *
 * Handles:
 * - Token refresh → persists new token for next registration
 * - Incoming data messages → shows local notification via PlatformNotificationManager
 */
class JervisFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Ensure context is available for SharedPreferences
        AndroidContextHolder.initialize(this)
        FcmTokenStorage.saveToken(applicationContext, token)
        println("FCM token refreshed: ${token.take(10)}...")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Ensure context is available
        AndroidContextHolder.initialize(this)

        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: "Jervis"
        val body = data["body"] ?: message.notification?.body ?: ""
        val taskId = data["taskId"]
        val isApproval = data["isApproval"]?.toBooleanStrictOrNull() ?: false
        val interruptAction = data["interruptAction"]

        val notificationManager = PlatformNotificationManager()
        notificationManager.initialize()
        notificationManager.showNotification(
            title = title,
            body = body,
            taskId = taskId,
            isApproval = isApproval,
            interruptAction = interruptAction,
        )
    }
}
