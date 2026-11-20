package com.sawt.ai.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
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
                stopSelfResult(startId)
            }
            ACTION_PLAY_FILE -> {
                val path = intent.getStringExtra(EXTRA_FILE).orEmpty()
                if (path.isBlank()) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                try {
                    startPlaybackForeground()
                    playFile(path, startId)
                } catch (_: Exception) {
                    releasePlayer()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                } catch (_: LinkageError) {
                    releasePlayer()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
            else -> {
                releasePlayer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlaybackForeground() {
        val foregroundNotification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                foregroundNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, foregroundNotification)
        }
    }

    private fun playFile(path: String, requestStartId: Int) {
        releasePlayer()
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mediaPlayer.setOnPreparedListener { prepared ->
                if (player === prepared) {
                    try {
                        prepared.start()
                    } catch (_: Exception) {
                        releasePlayer()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(requestStartId)
                    }
                } else {
                    try { prepared.release() } catch (_: Exception) { }
                }
            }
            mediaPlayer.setOnCompletionListener { completed ->
                if (player === completed) {
                    player = null
                    try { completed.release() } catch (_: Exception) { }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(requestStartId)
                }
            }
            mediaPlayer.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    player = null
                    try { failed.release() } catch (_: Exception) { }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(requestStartId)
                }
                true
            }
            mediaPlayer.setDataSource(path)
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            if (player === mediaPlayer) player = null
            try { mediaPlayer.release() } catch (_: Exception) { }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(requestStartId)
        } catch (_: LinkageError) {
            if (player === mediaPlayer) player = null
            try { mediaPlayer.release() } catch (_: Exception) { }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(requestStartId)
        }
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        try { current.setOnPreparedListener(null) } catch (_: Exception) { }
        try { current.setOnCompletionListener(null) } catch (_: Exception) { }
        try { current.setOnErrorListener(null) } catch (_: Exception) { }
        try { current.stop() } catch (_: Exception) { }
        try { current.release() } catch (_: Exception) { }
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
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "تشغيل الصوت", NotificationManager.IMPORTANCE_LOW)
            )
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
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            // The service is already foreground while playing; stopping it directly
            // avoids starting a new foreground-service request just to stop playback.
            context.stopService(Intent(context, SpeechPlaybackService::class.java))
        }
    }
}
