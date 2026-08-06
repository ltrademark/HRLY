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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Plays the chime when the hourly alarm fires.
 *
 * Reached only by the alarm's own PendingIntent, which names this class explicitly, so it
 * needs no intent-filter and is not exported. Boot lives in [BootReceiver] instead: this
 * class starts a mediaPlayback foreground service, which Android 15+ forbids from a
 * BOOT_COMPLETED receiver, and Play flags statically on any boot-registered class that can
 * reach such a call.
 *
 * Starting the FGS here is allowed: setAlarmClock() grants a temporary
 * foreground-service-start exemption when the alarm goes off.
 */
class ChimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, ChimeService::class.java).apply {
            action = ChimeService.ACTION_PLAY_CHIME
        }
        context.startForegroundService(serviceIntent)
    }
}
