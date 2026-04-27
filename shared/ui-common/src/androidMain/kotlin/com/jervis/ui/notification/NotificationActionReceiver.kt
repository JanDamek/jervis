package com.jervis.ui.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for notification action buttons.
 *
 * Handles:
 * - "Povolit" / "Zamítnout" on approval notifications
 * - Inline MFA code reply via RemoteInput
 *
 * Posts to [NotificationActionChannel] for NotificationViewModel to dispatch.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Login consent — 4 actions, post directly to server, no taskId.
        when (intent.action) {
            PlatformNotificationManager.ACTION_LOGIN_NOW ->
                postLoginConsent(context, intent, "now")
            PlatformNotificationManager.ACTION_LOGIN_DEFER_15 ->
                postLoginConsent(context, intent, "defer_15")
            PlatformNotificationManager.ACTION_LOGIN_DEFER_60 ->
                postLoginConsent(context, intent, "defer_60")
            PlatformNotificationManager.ACTION_LOGIN_CANCEL ->
                postLoginConsent(context, intent, "cancel")
        }
        if (intent.action in setOf(
            PlatformNotificationManager.ACTION_LOGIN_NOW,
            PlatformNotificationManager.ACTION_LOGIN_DEFER_15,
            PlatformNotificationManager.ACTION_LOGIN_DEFER_60,
            PlatformNotificationManager.ACTION_LOGIN_CANCEL,
        )) {
            return
        }

        val taskId = intent.getStringExtra("taskId") ?: return

        // Check for inline MFA code reply (RemoteInput)
        if (intent.action == PlatformNotificationManager.ACTION_REPLY) {
            val remoteInputBundle = RemoteInput.getResultsFromIntent(intent)
            val mfaCode = remoteInputBundle
                ?.getCharSequence(PlatformNotificationManager.KEY_MFA_CODE)
                ?.toString()
                ?.trim()

            if (!mfaCode.isNullOrBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    NotificationActionChannel.actions.emit(
                        NotificationActionResult(
                            taskId = taskId,
                            action = NotificationAction.REPLY,
                            replyText = mfaCode,
                        ),
                    )
                }
                // Dismiss the notification
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(taskId.hashCode())
            }
            return
        }

        val action = when (intent.action) {
            PlatformNotificationManager.ACTION_APPROVE -> NotificationAction.APPROVE
            PlatformNotificationManager.ACTION_DENY -> NotificationAction.DENY
            PlatformNotificationManager.ACTION_CONFIRM -> {
                // MFA confirmed (authenticator_number / phone_call) — send "confirmed" reply
                CoroutineScope(Dispatchers.IO).launch {
                    NotificationActionChannel.actions.emit(
                        NotificationActionResult(
                            taskId = taskId,
                            action = NotificationAction.REPLY,
                            replyText = "confirmed",
                        ),
                    )
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(taskId.hashCode())
                return
            }
            else -> return
        }

        // Post to shared channel — NotificationViewModel collects and dispatches
        CoroutineScope(Dispatchers.IO).launch {
            NotificationActionChannel.actions.emit(
                NotificationActionResult(taskId = taskId, action = action),
            )
        }

        // Dismiss the notification
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(taskId.hashCode())
    }

    private fun postLoginConsent(context: Context, intent: Intent, action: String) {
        val requestId = intent.getStringExtra("requestId") ?: return
        val baseUrl = "https://jervis.damek-soft.eu"
        val url = "$baseUrl/api/v1/login-consent/$requestId/respond"
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                conn.outputStream.use { it.write("""{"action":"$action"}""".toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                println("[Jervis] login-consent POST action=$action code=$code")
            } catch (e: Exception) {
                println("[Jervis] login-consent POST failed: ${e.message}")
            }
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(requestId.hashCode())
    }
}
