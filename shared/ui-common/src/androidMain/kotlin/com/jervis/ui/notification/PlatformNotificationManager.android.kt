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
        const val ACTION_APPROVE = "com.jervis.APPROVE"
        const val ACTION_DENY = "com.jervis.DENY"
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

            manager.createNotificationChannel(approvalChannel)
            manager.createNotificationChannel(taskChannel)
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
    ) {
        val context = AndroidContextHolder.applicationContext
        if (!hasPermission) return

        val channel = if (isApproval) CHANNEL_APPROVAL else CHANNEL_TASKS
        val notificationId = taskId?.hashCode() ?: System.currentTimeMillis().toInt()

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(
                if (isApproval) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )

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
