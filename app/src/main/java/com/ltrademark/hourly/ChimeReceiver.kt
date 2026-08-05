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

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ChimeReceiver : BroadcastReceiver() {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            // Reschedule only, WITHOUT touching ChimeService. The service is a
            // mediaPlayback foreground service, and Android 15+ throws
            // ForegroundServiceStartNotAllowedException for that FGS type when it is
            // started from a BOOT_COMPLETED receiver, so the old startForegroundService()
            // here crashed on every reboot. Setting the alarm is all this path ever did,
            // and that needs no service. The ongoing notification comes back when the
            // first chime fires.
            val prefs = context.getSharedPreferences("hourly_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("service_enabled", false)) {
                ChimeScheduler.scheduleInHours(context, 1)
            }
        } else {
            // The alarm fired. Starting the FGS is allowed here: setAlarmClock() grants a
            // temporary foreground-service-start exemption when the alarm goes off.
            val serviceIntent = Intent(context, ChimeService::class.java).apply {
                action = ChimeService.ACTION_PLAY_CHIME
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
