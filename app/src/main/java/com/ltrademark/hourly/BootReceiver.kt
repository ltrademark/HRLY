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
 * Re-arms the chime alarm after a reboot.
 *
 * Deliberately a separate class from [ChimeReceiver], which starts the mediaPlayback
 * foreground service. Android 15+ forbids starting that FGS type from a BOOT_COMPLETED
 * receiver, and Play Console's pre-launch check proves that statically: it walks the call
 * graph out of every boot-registered receiver class and cannot reason about which branch of
 * an `intent.action` check runs. Keeping both jobs in one receiver kept the app flagged even
 * after the reboot crash itself was fixed, so the split is what makes the guarantee
 * structural: this class references no service at all.
 *
 * This receiver ONLY handles boot. The alarm keeps targeting [ChimeReceiver] so alarms
 * already scheduled by an earlier install still land somewhere valid after an update.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Guard the action even though the manifest filter only declares these: a receiver
        // handling a protected broadcast should never act on anything it did not expect.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        // Respect the user's choice. Without this, a reboot silently switched chimes back on
        // for someone who had turned them off.
        val prefs = context.getSharedPreferences("hourly_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("service_enabled", false)) {
            ChimeScheduler.scheduleInHours(context, 1)
        }
    }
}
