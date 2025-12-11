package com.ltrademark.hourly

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
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
import kotlinx.coroutines.*
import java.util.*

class ChimeService : Service() {

    companion object {
        const val ACTION_PLAY_CHIME = "com.ltrademark.hourly.ACTION_PLAY_CHIME"
        const val EXTRA_TEST_HOUR = "com.ltrademark.hourly.EXTRA_TEST_HOUR"
        const val ACTION_TEST_VISUAL = "com.ltrademark.hourly.ACTION_TEST_VISUAL"
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
            else -> {
                scheduleNextChime()
            }
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, createNotification())
        scheduleNextChime()
    }

    private fun getNextChimeTime(): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.HOUR_OF_DAY, 1)
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

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("HRLY Active")
            .setContentText("Next chime at ${getNextChimeTime()}")
            .setSmallIcon(R.drawable.ic_stat_chime)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun playSequenceForHour(hour24: Int) {
        serviceScope.launch {
            // (hour + 24) % 12 === 0 ? 12 : (hour + 24) % 12
            val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12

            val longTones = hour12 / 5
            val shortTones = hour12 % 5

            repeat(longTones) {
                playTone(R.raw.tone_long, longToneDuration)
                delay(15) // Gap between tones
            }
            repeat(shortTones) {
                playTone(R.raw.tone_short, shortToneDuration)
                delay(15) // Gap between tones
            }
        }
    }

    private suspend fun playTone(resId: Int, duration: Long) {
        // 1. Create the player
        val mediaPlayer = MediaPlayer()

        // 2. Define Audio Attributes (The important part!)
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION) // Or USAGE_ALARM
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        mediaPlayer.setAudioAttributes(attributes)

        // 3. Set Source and Prepare
        val assetFileDescriptor = resources.openRawResourceFd(resId)
        mediaPlayer.setDataSource(
            assetFileDescriptor.fileDescriptor,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.length
        )
        assetFileDescriptor.close()

        mediaPlayer.prepare()

        // 4. Play
        showVisualPulse(duration)
        mediaPlayer.start()
        delay(duration)
        mediaPlayer.release()
    }

    private fun showVisualPulse(duration: Long, forceShow: Boolean = false) {
        val prefs = getSharedPreferences("hourly_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("visual_enabled", false)) return

        if (!Settings.canDrawOverlays(this)) return

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isScreenOn = powerManager.isInteractive
        // Only show visuals on the lock screen, unless forced for testing.
        if (isScreenOn && !forceShow) {
            return
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Lets clicks pass through
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        val imageView = ImageView(this)
        imageView.setImageResource(R.drawable.sphere_glow)
        imageView.alpha = 0f

        windowManager.addView(imageView, params)

        // Animate and remove the view
        imageView.animate()
            .alpha(1f)
            .scaleX(1.2f).scaleY(1.2f)
            .setDuration(duration / 4)
            .withEndAction {
                imageView.animate()
                    .alpha(0f)
                    .scaleX(1.0f).scaleY(1.0f)
                    .setDuration(duration / 2)
                    .setStartDelay(duration / 4) // Hold for a bit
                    .withEndAction {
                        try {
                            windowManager.removeView(imageView) // Clean up to prevent leaks
                        } catch (e: Exception) {
                            e.printStackTrace() // View might already be gone
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