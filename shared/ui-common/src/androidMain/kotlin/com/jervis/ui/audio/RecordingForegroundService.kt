package com.jervis.ui.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that keeps audio recording alive when the app is backgrounded
 * or the screen is locked. Shows a persistent notification with a stop button.
 *
 * Does NOT own the AudioRecorder — it is purely a keep-alive mechanism.
 */
class RecordingForegroundService : Service() {

    companion object {
        const val CHANNEL_RECORDING = "jervis_recording"
        const val NOTIFICATION_ID = 9001
        const val ACTION_STOP = "com.jervis.STOP_RECORDING"
        private const val EXTRA_TITLE = "recording_title"

        fun start(context: Context, title: String) {
            val intent = Intent(context, RecordingForegroundService::class.java)
            intent.putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }

        fun updateNotification(context: Context, durationSeconds: Long) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(context, durationSeconds, currentTitle))
        }

        private var currentTitle: String = "Nahravani"

        private fun getAppIconBitmap(context: Context): Bitmap? {
            return try {
                val drawable = context.packageManager.getApplicationIcon(context.applicationInfo)
                if (drawable is BitmapDrawable) {
                    drawable.bitmap
                } else {
                    val bitmap = Bitmap.createBitmap(
                        drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888,
                    )
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun buildNotification(context: Context, durationSeconds: Long, title: String): Notification {
            val stopIntent = Intent(context, RecordingStopReceiver::class.java).apply {
                action = ACTION_STOP
            }
            val stopPi = PendingIntent.getBroadcast(
                context, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val contentPi = if (launchIntent != null) {
                PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else null

            val h = durationSeconds / 3600
            val m = (durationSeconds % 3600) / 60
            val s = durationSeconds % 60
            val timeText = if (h > 0) {
                "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
            } else {
                "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
            }

            return NotificationCompat.Builder(context, CHANNEL_RECORDING)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setLargeIcon(getAppIconBitmap(context))
                .setContentTitle(title)
                .setContentText(timeText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Zastavit", stopPi)
                .apply { if (contentPi != null) setContentIntent(contentPi) }
                .build()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Nahravani"
        currentTitle = title
        startForeground(NOTIFICATION_ID, buildNotification(this, 0L, title))
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_RECORDING,
                "Nahravani",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Probihajici nahravani meetingu"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
