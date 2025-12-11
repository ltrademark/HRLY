package com.ltrademark.hourly

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.*
import java.util.*

class ChimeService : Service() {

    companion object {
        const val ACTION_PLAY_CHIME = "com.ltrademark.hourly.ACTION_PLAY_CHIME"
        const val ACTION_TEST_VISUAL = "com.ltrademark.hourly.ACTION_TEST_VISUAL"
        const val ACTION_SKIP_NEXT = "com.ltrademark.hourly.ACTION_SKIP_NEXT"
        const val ACTION_STOP_SERVICE = "com.ltrademark.hourly.ACTION_STOP_SERVICE"

        const val EXTRA_TEST_HOUR = "com.ltrademark.hourly.EXTRA_TEST_HOUR"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    // Configuration
    private val longToneDuration = 1500L
    private val shortToneDuration = 500L

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())

        when (intent?.action) {
            ACTION_PLAY_CHIME -> {
                val testHour = intent.getIntExtra(EXTRA_TEST_HOUR, -1)
                val hourToPlay = if (testHour != -1) testHour else Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                playSequenceForHour(hourToPlay)

                if (testHour == -1) {
                    scheduleNextChime()
                }
            }
            ACTION_TEST_VISUAL -> {
                serviceScope.launch(Dispatchers.Main) {
                    showVisualPulse(3000L, forceShow = true)
                }
            }
            ACTION_SKIP_NEXT -> {
                skipNextChime()
            }
            ACTION_STOP_SERVICE -> {
                stopChimeService()
            }
            else -> {
                scheduleNextChime()
            }
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun playTone(defaultResId: Int, duration: Long, type: String) {
        val mediaPlayer = MediaPlayer()
        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)

        val isCustomEnabled = prefs.getBoolean("custom_sounds_enabled", false)

        val customKey = when {
            !isCustomEnabled -> null
            type == "short" -> "custom_tone_short"
            type == "long" -> "custom_tone_long"
            else -> null
        }

        val customUriString = if (customKey != null) prefs.getString(customKey, null) else null

        try {
            if (customUriString != null) {
                mediaPlayer.setDataSource(this, customUriString.toUri())
            } else {
                val afd = resources.openRawResourceFd(defaultResId)
                mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            mediaPlayer.setAudioAttributes(attributes)

            mediaPlayer.prepare()
            showVisualPulse(duration)
            mediaPlayer.start()
            delay(duration)
            mediaPlayer.release()

        } catch (e: Exception) {
            e.printStackTrace()
            if (customUriString != null) {
                mediaPlayer.release()
                playTone(defaultResId, duration, "fallback")
            } else {
                mediaPlayer.release()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, createNotification())
        scheduleNextChime()
    }

    private fun getNextChimeTime(hourOffset: Int = 1): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.HOUR_OF_DAY, hourOffset)
        calendar.set(Calendar.MINUTE, 0)

        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val amPm = if (hour >= 12) "PM" else "AM"
        val hour12 = if (hour % 12 == 0) 12 else hour % 12

        return "$hour12:00 $amPm"
    }

    private fun createNotification(): Notification {
        val channelId = "chime_channel"
        val importance = NotificationManager.IMPORTANCE_MIN
        val channel = NotificationChannel(channelId, "Hourly Chime Service", importance).apply {
            description = "Background service for hourly chimes"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val skipIntent = Intent(this, ChimeService::class.java).apply { action = ACTION_SKIP_NEXT }
        val skipPendingIntent = PendingIntent.getService(
            this, 1, skipIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ChimeService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("HRLY Active")
            .setContentText("Next chime at ${getNextChimeTime()}")
            .setSmallIcon(R.drawable.ic_stat_chime)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Skip Next", skipPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disable Hourly Chime", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun stopChimeService() {
        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)
        prefs.edit {
            putBoolean("service_enabled", false)
        }

        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ChimeReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun skipNextChime() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ChimeReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        }

        val channelId = "chime_channel"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("HRLY Active")
            .setContentText("Next chime at ${getNextChimeTime(2)}")
            .setSmallIcon(R.drawable.ic_stat_chime)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(android.R.drawable.ic_media_next, "Skip Next",
                PendingIntent.getService(this, 1, Intent(this, ChimeService::class.java).apply { action = ACTION_SKIP_NEXT }, PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disable",
                PendingIntent.getService(this, 2, Intent(this, ChimeService::class.java).apply { action = ACTION_STOP_SERVICE }, PendingIntent.FLAG_IMMUTABLE))
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, notification)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun playSequenceForHour(hour24: Int) {
        serviceScope.launch {
            val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
            val longTones = hour12 / 5
            val shortTones = hour12 % 5

            repeat(longTones) {
                playTone(R.raw.tone_long, longToneDuration, "long")
                delay(15)
            }
            repeat(shortTones) {
                playTone(R.raw.tone_short, shortToneDuration, "short")
                delay(15)
            }
        }
    }

    private fun showVisualPulse(duration: Long, forceShow: Boolean = false) {
        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("visual_enabled", false)) return

        if (!Settings.canDrawOverlays(this)) return

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isScreenOn = powerManager.isInteractive
        if (isScreenOn && !forceShow) {
            return
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        val imageView = ImageView(this)
        imageView.setImageResource(R.drawable.sphere_glow)
        imageView.alpha = 0f

        windowManager.addView(imageView, params)

        imageView.animate()
            .alpha(1f)
            .scaleX(1.2f).scaleY(1.2f)
            .setDuration(duration / 4)
            .withEndAction {
                imageView.animate()
                    .alpha(0f)
                    .scaleX(1.0f).scaleY(1.0f)
                    .setDuration(duration / 2)
                    .setStartDelay(duration / 4)
                    .withEndAction {
                        try {
                            windowManager.removeView(imageView)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    .start()
            }
            .start()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun scheduleNextChime() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ChimeReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (overlayView != null) windowManager?.removeView(overlayView)
    }
}