package com.sawt.ai.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sawt.ai.MainActivity
import com.sawt.ai.R

class SpeechPlaybackService : Service() {
    private var player: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releasePlayer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PLAY_FILE -> {
                val path = intent.getStringExtra(EXTRA_FILE).orEmpty()
                if (path.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification())
                playFile(path)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun playFile(path: String) {
        releasePlayer()
        player = MediaPlayer().apply {
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                it.release()
                if (player === it) player = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                if (player === mp) player = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                true
            }
            try {
                setDataSource(path)
                prepareAsync()
            } catch (_: Exception) {
                release()
                player = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun releasePlayer() {
        val current = player
        player = null
        if (current != null) {
            try { current.stop() } catch (_: Exception) { }
            try { current.release() } catch (_: Exception) { }
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpeechPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sawt_notification)
            .setContentTitle("SAWT AI")
            .setContentText("تشغيل الصوت العربي")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "تشغيل الصوت", NotificationManager.IMPORTANCE_LOW)
                )
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "sawt_playback"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "com.sawt.ai.STOP"
        private const val ACTION_PLAY_FILE = "com.sawt.ai.PLAY_FILE"
        private const val EXTRA_FILE = "file"

        fun playFile(context: Context, path: String) {
            val intent = Intent(context, SpeechPlaybackService::class.java).apply {
                action = ACTION_PLAY_FILE
                putExtra(EXTRA_FILE, path)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SpeechPlaybackService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
