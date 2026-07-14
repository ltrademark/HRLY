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
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
        const val ACTION_TEST_VIBRATE = "com.ltrademark.hourly.ACTION_TEST_VIBRATE"

        const val EXTRA_TEST_HOUR = "com.ltrademark.hourly.EXTRA_TEST_HOUR"

        // Two channels back the ongoing notification: a normal, visible one and a
        // minimized one (no status-bar icon, collapsed in the shade). Which one is
        // used follows the "Minimize notification" toggle. Distinct IDs from the
        // pre-1.7 "chime_channel" so the new importances actually take effect
        // (channel importance is fixed once created).
        const val CHANNEL_VISIBLE = "chime_channel_visible"
        const val CHANNEL_MINIMIZED = "chime_channel_min"

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

        // Vibration length is matched to the tone length (#11) but clamped so a
        // buzz is always long enough to feel and never runs uncomfortably long.
        const val MIN_VIBRATION_MS = 150L
        const val MAX_VIBRATION_MS = 1500L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

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
            }
            ACTION_SKIP_NEXT -> {
                skipNextChime()
            }
            ACTION_TEST_VIBRATE -> {
                // Debug: buzz short then long so the matched-duration feel is
                // obvious back to back (#11).
                serviceScope.launch {
                    vibrate(shortToneDuration)
                    delay(shortToneDuration + 250)
                    vibrate(longToneDuration)
                }
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
        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)

        // Chime mode (#11): sound, vibrate, or both. Falls back to the legacy
        // vibrate_enabled pref for users updating from before the mode selector.
        val mode = prefs.getString("chime_mode", null)
            ?: if (prefs.getBoolean("vibrate_enabled", false)) "both" else "sound"
        val wantSound = mode == "sound" || mode == "both"
        val wantVibrate = mode == "vibrate" || mode == "both"
        val overrideSilent = prefs.getBoolean("override_silent", false)

        // Vibrate-only mode: no audio, just buzz for the tone's length so the
        // hourly count still comes through, then hold for the sequence spacing.
        if (!wantSound) {
            if (wantVibrate) vibrate(duration)
            delay(duration)
            return
        }

        val mediaPlayer = MediaPlayer()

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

            // #11: only when the user opts in do we use the alarm usage, which
            // plays through the phone's silent/vibrate ringer mode. Otherwise the
            // notification usage keeps the old behavior (respects the ringer). DND
            // and quiet hours are gated upstream in onStartCommand either way.
            val usage = if (overrideSilent) {
                AudioAttributes.USAGE_ALARM
            } else {
                AudioAttributes.USAGE_NOTIFICATION
            }
            val attributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            mediaPlayer.setAudioAttributes(attributes)

            mediaPlayer.prepare()

            val playFor = if (hasCrop) {
                // Sample-accurate seek so the crop start lands where the user set it
                // (the default seek snaps to the nearest keyframe).
                mediaPlayer.seekTo(cropStart.toLong(), MediaPlayer.SEEK_CLOSEST)
                (cropEnd - cropStart).toLong()
            } else {
                duration
            }

            // Vibrate alongside the sound when the mode asks for it, matched to the
            // tone length so long and short are distinguishable by feel (#11).
            // Guard: if sound was chosen but the ringer is silent/vibrate and the
            // user did not opt in to override it, the tone is inaudible, so buzz
            // once anyway rather than let the hour pass with no cue at all.
            if (wantVibrate || (!overrideSilent && isRingerSilent())) {
                vibrate(playFor)
            }

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
        val notification = NotificationCompat.Builder(this, CHANNEL_VISIBLE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.custom_tone_error))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(998, notification)
    }

    /** True when the phone is on vibrate or silent (not the normal ringer mode). */
    private fun isRingerSilent(): Boolean {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        return audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }

    private fun vibrate(durationMs: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        val clamped = durationMs.coerceIn(MIN_VIBRATION_MS, MAX_VIBRATION_MS)
        vibrator.vibrate(VibrationEffect.createOneShot(clamped, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onCreate() {
        super.onCreate()
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

    /**
     * Creates both notification channels (idempotent) and drops the pre-1.7
     * single channel. The minimized channel is IMPORTANCE_MIN (no status-bar
     * icon, collapsed); the visible one is IMPORTANCE_LOW (shown, still silent,
     * an ongoing service notification should never make noise).
     */
    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("chime_channel")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VISIBLE, "Hourly Chime Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing notification for the hourly chime service"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MINIMIZED, "Hourly Chime Service (minimized)", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Minimized ongoing notification for the hourly chime service"
                setShowBadge(false)
            }
        )
    }

    private fun createNotification(): Notification {
        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)
        val isSuspended = prefs.getBoolean("is_suspended", false)
        val minimize = prefs.getBoolean("hide_next_chime", false)

        ensureChannels()
        val channelId = if (minimize) CHANNEL_MINIMIZED else CHANNEL_VISIBLE

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
            .setPriority(if (minimize) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
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
            if (!minimize) {
                builder.setContentText(getString(R.string.notif_next_chime_at, getNextChimeTime()))
            }
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.action_suspend), suspendPending)
            builder.addAction(android.R.drawable.ic_media_next, getString(R.string.action_skip_next), skipPending)
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_disable), stopPendingIntent)

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

        val prefs = getSharedPreferences("hourly_prefs", MODE_PRIVATE)
        val minimize = prefs.getBoolean("hide_next_chime", false)
        ensureChannels()
        val channelId = if (minimize) CHANNEL_MINIMIZED else CHANNEL_VISIBLE
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.status_active))
            .setSmallIcon(R.drawable.ic_stat_chime)
            .setPriority(if (minimize) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_next, getString(R.string.action_skip_next),
                PendingIntent.getService(this, 1, Intent(this, ChimeService::class.java).apply { action = ACTION_SKIP_NEXT }, PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_disable),
                PendingIntent.getService(this, 2, Intent(this, ChimeService::class.java).apply { action = ACTION_STOP_SERVICE }, PendingIntent.FLAG_IMMUTABLE))
        if (!minimize) {
            builder.setContentText(getString(R.string.notif_next_chime_at, getNextChimeTime(2)))
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
    }
}
