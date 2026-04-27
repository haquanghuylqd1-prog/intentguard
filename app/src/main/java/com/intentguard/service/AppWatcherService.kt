package com.intentguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.intentguard.data.DataStore
import com.intentguard.ui.IntentionPopupActivity

class AppWatcherService : AccessibilityService() {

    private var lastPackage = ""
    private var popupShownFor = ""
    private var lastCheckedUrl = ""

    // Domains/keywords 18+ và truyện
    private val blockedKeywords = listOf(
        "truyen", "truyện", "xvideo", "xnxx", "pornhub", "xhamster",
        "redtube", "youporn", "sex", "porn", "adult", "18+", "hentai",
        "nhentai", "hanime", "javhd", "jav", "av0"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleAppSwitch(pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Check URL bar changes in Samsung Internet
                if (pkg == "com.sec.android.app.sbrowser") {
                    checkBrowserUrl(event)
                }
            }
        }
    }

    private fun handleAppSwitch(pkg: String) {
        if (pkg == lastPackage) return
        lastPackage = pkg
        if (!DataStore.isWatchedApp(this, pkg)) return
        if (popupShownFor == pkg && TimerService.isRunningFor(pkg)) return

        DataStore.checkAndRotateWeek(this)
        Log.d("IntentGuard", "Watched app opened: $pkg")
        popupShownFor = pkg
        showPopup(pkg, DataStore.getAppName(this, pkg))
    }

    private fun checkBrowserUrl(event: AccessibilityEvent) {
        // Only check if no active timer for browser
        val browserPkg = "com.sec.android.app.sbrowser"
        if (TimerService.isRunningFor(browserPkg)) return

        try {
            val root = rootInActiveWindow ?: return
            val url = extractUrlFromBrowser(root) ?: return
            root.recycle()

            if (url == lastCheckedUrl) return
            lastCheckedUrl = url

            val urlLower = url.lowercase()
            val isBlocked = blockedKeywords.any { keyword -> urlLower.contains(keyword) }

            if (isBlocked) {
                Log.d("IntentGuard", "Blocked URL detected: $url")
                popupShownFor = ""
                showPopup(browserPkg, "Samsung Internet ⚠️")
            }
        } catch (e: Exception) {
            Log.e("IntentGuard", "URL check error: ${e.message}")
        }
    }

    private fun extractUrlFromBrowser(root: AccessibilityNodeInfo): String? {
        // Samsung Internet URL bar resource IDs
        val urlBarIds = listOf(
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/url_bar",
            "com.sec.android.app.sbrowser:id/location"
        )
        for (resId in urlBarIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(resId)
            if (nodes.isNotEmpty()) {
                val text = nodes[0].text?.toString()
                nodes.forEach { it.recycle() }
                if (!text.isNullOrEmpty()) return text
            }
        }
        // Fallback: search all nodes for URL-like text
        return findUrlInNodes(root)
    }

    private fun findUrlInNodes(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()
        if (!text.isNullOrEmpty() && (text.startsWith("http") || text.contains("www."))) {
            return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findUrlInNodes(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun showPopup(pkg: String, appName: String) {
        val intent = Intent(this, IntentionPopupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentionPopupActivity.EXTRA_PACKAGE, pkg)
            putExtra(IntentionPopupActivity.EXTRA_APP_NAME, appName)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.d("IntentGuard", "AccessibilityService interrupted")
    }

    fun resetPopupState() {
        popupShownFor = ""
        lastCheckedUrl = ""
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
