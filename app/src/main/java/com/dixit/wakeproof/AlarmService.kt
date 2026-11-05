package com.dixit.wakeproof

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * Plays the alarm. Runs until the routine is finished.
 * "Quiet" mode is only granted while the alarm screen keeps checking in (a heartbeat);
 * if the check-ins stop (you left the app, locked the phone, it crashed), the sound comes back.
 */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var volume = 1f
    private var firstLoud = true

    private val autoLoud = Runnable { goLoud() }
    private val ramp = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (volume < 1f) {
                volume = (volume + 0.03f).coerceAtMost(1f)
                try { p.setVolume(volume, volume) } catch (e: Exception) {}
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (!Prefs(this).ringing) {
            stopEverything()
            return START_NOT_STICKY
        }
        handle(intent?.action ?: ACTION_START, intent?.getLongExtra(EXTRA_GRACE, 5000L) ?: 5000L)
        return START_STICKY
    }

    fun handle(action: String, grace: Long) {
        when (action) {
            ACTION_QUIET -> goQuiet(grace)
            ACTION_LOUD -> goLoud()
            ACTION_STOP -> stopEverything()
            else -> goLoud()
        }
    }

    private fun goLoud() {
        handler.removeCallbacks(autoLoud)
        if (isLoud) return
        isLoud = true
        acquireWake()
        startVibration()
        val p = Prefs(this)
        if (!p.vibrateOnly) startSound(firstLoud && p.rampUp)
        firstLoud = false
    }

    private fun goQuiet(grace: Long) {
        handler.removeCallbacks(autoLoud)
        if (isLoud) {
            isLoud = false
            stopSound()
            stopVibration()
        }
        handler.postDelayed(autoLoud, grace)
    }

    private fun startSound(rampUp: Boolean) {
        stopSound()
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            try { am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.7).toInt().coerceAtLeast(1), 0) } catch (e: Exception) {}
        }
        val candidates = listOfNotNull<Uri>(
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_RINGTONE_URI,
            Settings.System.DEFAULT_NOTIFICATION_URI
        )
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                volume = if (rampUp) 0.15f else 1f
                mp.setVolume(volume, volume)
                mp.start()
                player = mp
                if (rampUp) handler.postDelayed(ramp, 1000)
                return
            } catch (e: Exception) {
                try { mp.release() } catch (x: Exception) {}
            }
        }
    }

    private fun stopSound() {
        handler.removeCallbacks(ramp)
        player?.let {
            try { it.stop() } catch (e: Exception) {}
            try { it.release() } catch (e: Exception) {}
        }
        player = null
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        vibrator = v
        try {
            v.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            )
        } catch (e: Exception) {}
    }

    private fun stopVibration() {
        try { vibrator?.cancel() } catch (e: Exception) {}
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakeproof:alarm").also {
            it.acquire(60 * 60 * 1000L)
        }
    }

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        stopVibration()
        isLoud = false
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        stopVibration()
        isLoud = false
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            this, 2001, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val study = Prefs(this).mode == Prefs.MODE_STUDY
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (study) "Desk time — show your Anki" else "Wake up — prove it")
            .setContentText(if (study) "Tap when you're at your desk" else "Tap to start your push-ups")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "wakeproof_alarm"
        const val NOTIF_ID = 77
        const val ACTION_START = "start"
        const val ACTION_QUIET = "quiet"
        const val ACTION_LOUD = "loud"
        const val ACTION_STOP = "stop"
        const val EXTRA_GRACE = "grace"

        @Volatile var instance: AlarmService? = null
        @Volatile var isLoud = false

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows the alarm screen"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            nm.createNotificationChannel(ch)
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, AlarmService::class.java).setAction(ACTION_START))
        }

        /** Sends a command to the running alarm (or restarts it if it was killed). */
        fun command(ctx: Context, action: String, grace: Long = 5000L) {
            val s = instance
            if (s != null) {
                s.handler.post { s.handle(action, grace) }
                return
            }
            if (action == ACTION_STOP) return
            if (!Prefs(ctx).ringing) return
            try {
                ctx.startForegroundService(
                    Intent(ctx, AlarmService::class.java).setAction(action).putExtra(EXTRA_GRACE, grace)
                )
            } catch (e: Exception) {}
        }
    }
}
