package com.ltrademark.hourly

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ChimeReceiver : BroadcastReceiver() {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val serviceIntent = Intent(context, ChimeService::class.java)
            context.startForegroundService(serviceIntent)
        } else {
            val serviceIntent = Intent(context, ChimeService::class.java).apply {
                action = ChimeService.ACTION_PLAY_CHIME
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
