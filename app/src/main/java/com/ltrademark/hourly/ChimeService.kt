package com.ltrademark.hourly

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.net.Uri
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
    private suspend fun playTone(defaultResId: Int, duration: Long, type: String) {
        val mediaPlayer = MediaPlayer()
        val prefs = getSharedPreferences("hourly_prefs", Context.MODE_PRIVATE)

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
                mediaPlayer.setDataSource(this, Uri.parse(customUriString))
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
            // Fallback
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

    @RequiresApi(Build.VERSION_CODES.S)
    private fun playSequenceForHour(hour24: Int) {
        serviceScope.launch {
            // (hour + 24) % 12 === 0 ? 12 : (hour + 24) % 12
            val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12

            val longTones = hour12 / 5
            val shortTones = hour12 % 5

            repeat(longTones) {
                playTone(R.raw.tone_long, longToneDuration, "long") // Pass "long"
                delay(15)
            }
            repeat(shortTones) {
                playTone(R.raw.tone_short, shortToneDuration, "short") // Pass "short"
                delay(15)
            }
        }
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