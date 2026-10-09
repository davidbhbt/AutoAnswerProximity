package com.example.autoanswer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

// מפעיל את השירות מחדש אחרי הפעלה מחדש של המכשיר
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ContextCompat.startForegroundService(
                context, Intent(context, CallWatcherService::class.java)
            )
        }
    }
}
