package com.jervis.ui.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

/**
 * Android notification manager with action buttons for approvals.
 *
 * Creates two notification channels:
 * - jervis_approval: HIGH importance (heads-up, sound, vibration)
 * - jervis_tasks: DEFAULT importance
 *
 * Approval notifications include "Povolit" (Approve) and "Zamítnout" (Deny) action buttons.
 */
actual class PlatformNotificationManager actual constructor() {
    companion object {
        const val CHANNEL_APPROVAL = "jervis_approval"
        const val CHANNEL_TASKS = "jervis_tasks"
        const val CHANNEL_URGENT = "jervis_urgent"
        const val ACTION_APPROVE = "com.jervis.APPROVE"
        const val ACTION_DENY = "com.jervis.DENY"
        const val ACTION_REPLY = "com.jervis.REPLY"
        const val ACTION_CONFIRM = "com.jervis.CONFIRM"
        const val ACTION_LOGIN_NOW = "com.jervis.LOGIN_NOW"
        const val ACTION_LOGIN_DEFER_15 = "com.jervis.LOGIN_DEFER_15"
        const val ACTION_LOGIN_DEFER_60 = "com.jervis.LOGIN_DEFER_60"
        const val ACTION_LOGIN_CANCEL = "com.jervis.LOGIN_CANCEL"
        const val KEY_MFA_CODE = "mfa_code"
    }

    actual fun initialize() {
        val context = AndroidContextHolder.applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // High-importance channel for approval requests
            val approvalChannel = NotificationChannel(
                CHANNEL_APPROVAL,
                "Schválení",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Urgentní schválení akcí (commit, push)"
                enableVibration(true)
            }

            // Default channel for regular task notifications
            val taskChannel = NotificationChannel(
                CHANNEL_TASKS,
                "Úlohy",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Oznámení o nových úlohách"
            }

            // Urgent channel for MFA and session expiry — MAX importance
            val urgentChannel = NotificationChannel(
                CHANNEL_URGENT,
                "MFA a přihlášení",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Urgentní MFA ověření a re-login"
                enableVibration(true)
                setBypassDnd(true)
            }

            manager.createNotificationChannel(approvalChannel)
            manager.createNotificationChannel(taskChannel)
            manager.createNotificationChannel(urgentChannel)
        }
    }

    actual fun requestPermission() {
        // Android 13+ permission is requested via Activity.requestPermissions()
        // This is handled in MainActivity — this method is a no-op placeholder.
        // The actual permission request needs Activity context.
    }

    actual val hasPermission: Boolean
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    AndroidContextHolder.applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

    actual fun showNotification(
        title: String,
        body: String,
        taskId: String?,
        isApproval: Boolean,
        interruptAction: String?,
        badgeCount: Int?,
        mfaType: String?,
        mfaNumber: String?,
        requestId: String?,
    ) {
        val context = AndroidContextHolder.applicationContext
        if (!hasPermission) return

        val isLoginConsent = interruptAction == "login_consent" && requestId != null
        val isUrgent = isLoginConsent || interruptAction in listOf("o365_mfa", "o365_relogin")
        val channel = when {
            isUrgent -> CHANNEL_URGENT
            isApproval -> CHANNEL_APPROVAL
            else -> CHANNEL_TASKS
        }
        val notificationId = taskId?.hashCode() ?: requestId?.hashCode() ?: System.currentTimeMillis().toInt()

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(
                if (isUrgent || isApproval) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .apply {
                if (badgeCount != null) setNumber(badgeCount)
            }

        // Add action buttons for approval notifications
        if (isApproval && taskId != null) {
            val approveIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_APPROVE
                putExtra("taskId", taskId)
            }
            val approvePi = PendingIntent.getBroadcast(
                context,
                notificationId * 2,
                approveIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val denyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_DENY
                putExtra("taskId", taskId)
            }
            val denyPi = PendingIntent.getBroadcast(
                context,
                notificationId * 2 + 1,
                denyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            builder.addAction(0, "Povolit", approvePi)
            builder.addAction(0, "Zamítnout", denyPi)
        }

        // Login consent actions — 4 buttons mirroring iOS UNNotificationCategory
        // (Now / Defer 15 min / Defer 1 hod / Cancel). Each posts directly to
        // the server `/api/v1/login-consent/{requestId}/respond` endpoint.
        if (isLoginConsent && requestId != null) {
            for ((idx, pair) in listOf(
                ACTION_LOGIN_NOW to "Teď",
                ACTION_LOGIN_DEFER_15 to "Za 15 min",
                ACTION_LOGIN_DEFER_60 to "Za 1 hod",
                ACTION_LOGIN_CANCEL to "Zrušit",
            ).withIndex()) {
                val (action, label) = pair
                val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                    this.action = action
                    putExtra("requestId", requestId)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    notificationId * 8 + idx,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(0, label, pi)
            }
        }

        // MFA actions based on type
        if (interruptAction == "o365_mfa" && taskId != null) {
            val needsCode = mfaType in listOf("authenticator_code", "sms_code")
            if (needsCode) {
                // Inline reply for code input
                val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = ACTION_REPLY
                    putExtra("taskId", taskId)
                }
                val replyPi = PendingIntent.getBroadcast(
                    context,
                    notificationId * 2 + 2,
                    replyIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val remoteInput = RemoteInput.Builder(KEY_MFA_CODE)
                    .setLabel("Kód")
                    .build()
                val replyAction = NotificationCompat.Action.Builder(0, "Zadat kód", replyPi)
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build()
                builder.addAction(replyAction)
            } else {
                // authenticator_number / phone_call — just a confirm button
                val confirmIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = ACTION_CONFIRM
                    putExtra("taskId", taskId)
                }
                val confirmPi = PendingIntent.getBroadcast(
                    context,
                    notificationId * 2 + 2,
                    confirmIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(0, "Potvrzeno", confirmPi)
            }
        }

        // Tap opens the app
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            launchIntent.putExtra("taskId", taskId)
            val contentPi = PendingIntent.getActivity(
                context,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(contentPi)
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    actual fun cancelNotification(taskId: String) {
        val context = AndroidContextHolder.applicationContext
        NotificationManagerCompat.from(context).cancel(taskId.hashCode())
    }
}
