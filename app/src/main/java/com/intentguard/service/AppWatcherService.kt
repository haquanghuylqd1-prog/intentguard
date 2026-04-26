package com.intentguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.intentguard.data.DataStore
import com.intentguard.ui.IntentionPopupActivity

class AppWatcherService : AccessibilityService() {

    private var lastPackage = ""
    private var popupShownFor = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Ignore our own app and system UI
        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return
        if (pkg == lastPackage) return

        lastPackage = pkg

        // Check if this is a watched app
        if (!DataStore.isWatchedApp(this, pkg)) return

        // Don't show popup again if already shown for this package in this "session"
        // TimerService will reset popupShownFor when session ends
        if (popupShownFor == pkg && TimerService.isRunningFor(pkg)) return

        Log.d("IntentGuard", "Watched app opened: $pkg")

        // Check weekly budget
        DataStore.checkAndRotateWeek(this)

        popupShownFor = pkg

        // Show intention popup
        val intent = Intent(this, IntentionPopupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentionPopupActivity.EXTRA_PACKAGE, pkg)
            putExtra(IntentionPopupActivity.EXTRA_APP_NAME, DataStore.getAppName(this@AppWatcherService, pkg))
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.d("IntentGuard", "AccessibilityService interrupted")
    }

    fun resetPopupState() {
        popupShownFor = ""
    }

    companion object {
        var instance: AppWatcherService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("IntentGuard", "AppWatcherService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
