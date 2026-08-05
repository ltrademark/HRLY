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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Owns the chime alarm.
 *
 * This lives outside ChimeService because the boot path needs to reschedule without
 * starting the service at all: ChimeService is a mediaPlayback foreground service, and
 * Android 15+ forbids starting that FGS type from a BOOT_COMPLETED receiver, which
 * crashed the app on every reboot. Rescheduling only ever needed a Context.
 */
object ChimeScheduler {

    /** Schedules the chime for the top of the hour, [hoursAhead] hours from now. */
    fun scheduleInHours(context: Context, hoursAhead: Int) {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, hoursAhead)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        scheduleExactAlarm(context, calendar.timeInMillis)
    }

    /** Drops any pending chime alarm. */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(chimePendingIntent(context))
    }

    /**
     * Schedules the chime alarm for the given time.
     *
     * Uses setAlarmClock() instead of setExactAndAllowWhileIdle(): the latter is still
     * deferrable by Doze / OEM alarm-batching, which made chimes fire minutes late (#10).
     * setAlarmClock is exempt from those delays.
     *
     * setAlarmClock IS an exact alarm, so on API 31+ it requires USE_EXACT_ALARM or
     * SCHEDULE_EXACT_ALARM, both declared in the manifest. (Dropping them in 1.7 on the
     * mistaken belief that setAlarmClock was exempt caused a launch crash, #1.) We still
     * do NOT call canScheduleExactAlarms() here: USE_EXACT_ALARM is auto-granted, and that
     * API only exists on API 31+ and crashed on Android 8-11 in the original #1.
     *
     * Trade-off: setAlarmClock surfaces a standing alarm indicator in the status bar.
     */
    private fun scheduleExactAlarm(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val showIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
            chimePendingIntent(context)
        )
    }

    /**
     * The alarm's target. Request code 0 and FLAG_UPDATE_CURRENT are load-bearing: every
     * call site must produce the same PendingIntent so scheduling replaces the previous
     * alarm rather than stacking a second one, and so cancel() matches what was set.
     */
    private fun chimePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ChimeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
