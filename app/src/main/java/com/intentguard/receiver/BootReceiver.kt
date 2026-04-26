package com.intentguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("IntentGuard", "Boot completed - AccessibilityService will auto-start if enabled")
            // AccessibilityService tự restart sau boot nếu user đã enable
            // Không cần start thủ công
        }
    }
}
