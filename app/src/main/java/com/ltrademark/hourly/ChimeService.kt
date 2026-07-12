/*
 * HRLY, a simple hourly chime app for Android.
 * Copyright (C) 2025-2026 Ltrademark
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * The HRLY name, logo, and branding are not covered by this license.
 * See TRADEMARK.md.
 */
package com.ltrademark.hourly

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class ChimeService : Service() {

    companion object {
        const val ACTION_PLAY_CHIME = "com.ltrademark.hourly.ACTION_PLAY_CHIME"
        const val ACTION_SKIP_NEXT = "com.ltrademark.hourly.ACTION_SKIP_NEXT"
        const val ACTION_STOP_SERVICE = "com.ltrademark.hourly.ACTION_STOP_SERVICE"
        const val ACTION_TOGGLE_SUSPEND = "com.ltrademark.hourly.ACTION_TOGGLE_SUSPEND"

        const val EXTRA_TEST_HOUR = "com.ltrademark.hourly.EXTRA_TEST_HOUR"

        // Safe bounds for the user-configurable gap between tones (#6).
        const val MIN_TONE_GAP_MS = 0
        const val MAX_TONE_GAP_MS = 2000
        const val DEFAULT_TONE_GAP_MS = 150

        // Trim-length safeguards (#6 crop) that keep custom tones true to the
        // count-the-hour methodology: a short tone ("1") must stay clearly shorter
        // than a long tone ("5") or the hourly count can't be followed by ear.
        // MIN_CROP_MS is a HARD floor (can't be saved below it, too short to hear);
        // the recommended lengths + bands drive non-blocking warnings in the UI.
        const val MIN_CROP_MS = 150
        const val REC_SHORT_MS = 500          // default short-tone length ("1")
        const val REC_LONG_MS = 1500          // default long-tone length ("5")
        const val SHORT_BAND_MIN_MS = 300
        const val SHORT_BAND_MAX_MS = 800
        const val LONG_BAND_MIN_MS = 1000
        const val LONG_BAND_MAX_MS = 2500
        // Long must beat short by at least this much to stay distinguishable.
        const val DISTINCT_MARGIN_MS = 300
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    // Configuration
    private val longToneDuration = 1500L
    private val shortToneDuration = 500L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())

        when (intent?.action) {
            ACTION_TOGGLE_SUSPEND -> {
                val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)
                val currentState = prefs.getBoolean("is_suspended", false)
                prefs.edit { putBoolean("is_suspended", !currentState) }

                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(1, createNotification())
            }

            ACTION_PLAY_CHIME -> {
                val testHour = intent.getIntExtra(EXTRA_TEST_HOUR, -1)
                val hourToPlay = if (testHour != -1) testHour else Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

                if (testHour == -1) {
                    scheduleNextChime()
                }

                val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)
                val isSuspended = prefs.getBoolean("is_suspended", false)

                if (testHour == -1 && (isDndActive() || isQuietTime() || isSuspended)) {
                    if (!isSuspended) {
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }

                playSequenceForHour(hourToPlay)

                if (testHour == -1) {
                    scheduleNextChime()
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

        // Custom tones are copied into app-private storage at selection time, so we
        // play that local file here. This service runs in a background/restarted
        // process that has no read grant for the original content:// URI, which is
        // why playing the URI directly silently failed and fell back to default (#2).
        val customPath = if (customKey != null) prefs.getString(customKey, null) else null
        val customFile = customPath?.let { File(it) }?.takeIf { it.exists() }

        // Optional per-tone crop (#6): play only [start, end] of the custom file.
        // Only applies to a real custom tone with a valid, non-empty range.
        val cropStart = if (customKey != null) prefs.getInt("crop_${type}_start", 0) else 0
        val cropEnd = if (customKey != null) prefs.getInt("crop_${type}_end", 0) else 0
        val hasCrop = customFile != null && cropEnd > cropStart

        try {
            if (customFile != null) {
                mediaPlayer.setDataSource(customFile.absolutePath)
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

            // Vibrate alongside the sound (#11) so vibrate-only users still get the cue.
            // DND/quiet are already enforced upstream in onStartCommand, so this inherits them.
            if (prefs.getBoolean("vibrate_enabled", false)) vibrate()

            val playFor = if (hasCrop) {
                // Sample-accurate seek so the crop start lands where the user set it
                // (the default seek snaps to the nearest keyframe).
                mediaPlayer.seekTo(cropStart.toLong(), MediaPlayer.SEEK_CLOSEST)
                (cropEnd - cropStart).toLong()
            } else {
                duration
            }

            showVisualPulse(playFor)
            mediaPlayer.start()
            delay(playFor)
            mediaPlayer.release()

        } catch (e: Exception) {
            e.printStackTrace()
            mediaPlayer.release()
            if (customFile != null) {
                // A real custom tone failed to play, so tell the user instead of
                // silently substituting the default, then fall back so the hour is
                // still audibly marked.
                notifyPlaybackError()
                playTone(defaultResId, duration, "fallback")
            }
        }
    }

    private fun notifyPlaybackError() {
        val notification = NotificationCompat.Builder(this, "chime_channel")
            .setContentTitle("HRLY")
            .setContentText("Couldn't play the custom tone. Using the default instead.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(998, notification)
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

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
        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)
        val isSuspended = prefs.getBoolean("is_suspended", false)

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

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_chime)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)

        if (isSuspended) {
            val resumeIntent = Intent(this, ChimeService::class.java).apply { action = ACTION_TOGGLE_SUSPEND }
            val resumePending = PendingIntent.getService(this, 3, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            builder.setContentTitle(getString(R.string.status_suspended))
            builder.setContentText(getString(R.string.status_suspended_description))
            builder.addAction(android.R.drawable.ic_media_play, getString(R.string.action_resume), resumePending)

        } else {
            val skipIntent = Intent(this, ChimeService::class.java).apply { action = ACTION_SKIP_NEXT }
            val skipPending = PendingIntent.getService(this, 1, skipIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val suspendIntent = Intent(this, ChimeService::class.java).apply { action = ACTION_TOGGLE_SUSPEND }
            val suspendPending = PendingIntent.getService(this, 3, suspendIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            builder.setContentTitle(getString(R.string.status_active))
            if (!prefs.getBoolean("hide_next_chime", false)) {
                builder.setContentText("Next chime at ${getNextChimeTime()}")
            }
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.action_suspend), suspendPending)
            builder.addAction(android.R.drawable.ic_media_next, "Skip Next Chime", skipPending)
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disable", stopPendingIntent)

        return builder.build()
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

    private fun skipNextChime() {
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

        scheduleExactAlarm(calendar.timeInMillis, pendingIntent)

        val channelId = "chime_channel"
        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("HRLY Active")
            .setSmallIcon(R.drawable.ic_stat_chime)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(android.R.drawable.ic_media_next, "Skip Next",
                PendingIntent.getService(this, 1, Intent(this, ChimeService::class.java).apply { action = ACTION_SKIP_NEXT }, PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disable",
                PendingIntent.getService(this, 2, Intent(this, ChimeService::class.java).apply { action = ACTION_STOP_SERVICE }, PendingIntent.FLAG_IMMUTABLE))
        if (!prefs.getBoolean("hide_next_chime", false)) {
            builder.setContentText("Next chime at ${getNextChimeTime(2)}")
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, builder.build())
    }

    private fun isDndActive(): Boolean {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val currentFilter = notificationManager.currentInterruptionFilter

        return currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun isQuietTime(): Boolean {
        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("quiet_enabled", false)) return false

        val startH = prefs.getInt("quiet_start_h", 22)
        val startM = prefs.getInt("quiet_start_m", 0)
        val endH = prefs.getInt("quiet_end_h", 7)
        val endM = prefs.getInt("quiet_end_m", 0)

        val now = Calendar.getInstance()
        val currentH = now.get(Calendar.HOUR_OF_DAY)
        val currentM = now.get(Calendar.MINUTE)

        val nowMinutes = currentH * 60 + currentM
        val startMinutes = startH * 60 + startM
        val endMinutes = endH * 60 + endM

        return if (startMinutes < endMinutes) {
            nowMinutes in startMinutes until endMinutes
        } else {
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
    }

    private fun playSequenceForHour(hour24: Int) {
        serviceScope.launch {
            val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)

            // Single chime mode (#3 / #8): one tone per hour, identical every hour,
            // instead of counting the hour with long/short tones.
            if (prefs.getBoolean("simple_chime_enabled", false)) {
                playTone(R.raw.tone_short, shortToneDuration, "short")
                return@launch
            }

            // Gap between tones (#6). Opt-in: unchanged 15 ms spacing unless the user
            // enables custom timing, then the clamped user value applies.
            val gap = if (prefs.getBoolean("timing_enabled", false)) {
                prefs.getInt("tone_gap_ms", DEFAULT_TONE_GAP_MS)
                    .coerceIn(MIN_TONE_GAP_MS, MAX_TONE_GAP_MS).toLong()
            } else {
                15L
            }

            val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
            val longTones = hour12 / 5
            val shortTones = hour12 % 5

            repeat(longTones) {
                playTone(R.raw.tone_long, longToneDuration, "long")
                delay(gap)
            }
            repeat(shortTones) {
                playTone(R.raw.tone_short, shortToneDuration, "short")
                delay(gap)
            }
        }
    }

    private fun showVisualPulse(duration: Long, forceShow: Boolean = false) {
        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("visual_enabled", false)) return
        if (!Settings.canDrawOverlays(this)) return

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isScreenOn = powerManager.isInteractive

        if (isScreenOn && !forceShow) return

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 1. Calculate REAL Screen Size (Modern vs Legacy)
        val screenWidth: Int
        val screenHeight: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // New Way (Android 11+)
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            // Old Way (Android 10 and below)
            val displayMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }

        // 2. Window Params
        @Suppress("DEPRECATION")
        val layoutFlag = (
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )

        val params = WindowManager.LayoutParams(
            screenWidth,
            screenHeight + 200, // Extra height buffer for nav bars
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            layoutFlag,
            PixelFormat.TRANSLUCENT
        )

        // 3. Container with Immersive Flags
        val container = android.widget.FrameLayout(this)
        container.setBackgroundColor(android.graphics.Color.BLACK)
        container.alpha = 0f

        // Hide System Bars (Status/Nav)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            container.windowInsetsController?.hide(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            container.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LOW_PROFILE or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    )
        }

        // 4. Image Setup
        val sizeInDp = 85
        val scale = resources.displayMetrics.density
        val sizeInPx = (sizeInDp * scale + 0.5f).toInt()

        val imageView = ImageView(this)
        imageView.setImageResource(R.drawable.sphere_glow)
        val imageParams = android.widget.FrameLayout.LayoutParams(sizeInPx, sizeInPx).apply {
            gravity = Gravity.CENTER
        }
        container.addView(imageView, imageParams)

        // 5. Add to Window
        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        // 6. Animation
        val fadeInTime = 500L
        val holdTime = duration - (fadeInTime * 2)

        container.animate()
            .alpha(1f)
            .setDuration(fadeInTime)
            .withEndAction {
                imageView.animate().scaleX(1.1f).scaleY(1.1f).setDuration(holdTime).start()

                container.animate()
                    .alpha(0f)
                    .setDuration(fadeInTime)
                    .setStartDelay(holdTime)
                    .withEndAction {
                        try {
                            windowManager.removeView(container)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun scheduleNextChime() {
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

        scheduleExactAlarm(calendar.timeInMillis, pendingIntent)
    }

    /**
     * Schedules the chime alarm for the given time.
     *
     * Uses setAlarmClock() instead of setExactAndAllowWhileIdle(): the latter is still
     * deferrable by Doze / OEM alarm-batching, which made chimes fire minutes late (#10).
     * setAlarmClock is exempt from those delays. It also does NOT require the
     * SCHEDULE_EXACT_ALARM permission, so we no longer call canScheduleExactAlarms().
     * That API only exists on API 31+ and crashed on Android 8-11 (#1).
     *
     * Trade-off: setAlarmClock surfaces a standing alarm indicator in the status bar.
     */
    private fun scheduleExactAlarm(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val showIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (overlayView != null) windowManager?.removeView(overlayView)
    }
}
