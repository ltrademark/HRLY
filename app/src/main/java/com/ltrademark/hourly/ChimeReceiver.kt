package com.ltrademark.hourly

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ChimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, ChimeService::class.java).apply {
            action = ChimeService.ACTION_PLAY_CHIME
        }
        context.startForegroundService(serviceIntent)
    }
}