package com.intentguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.intentguard.data.DataStore
import com.intentguard.ui.IntentionPopupActivity

class AppWatcherService : AccessibilityService() {

    private var lastPackage = ""
    private var popupShownFor = ""
    private var lastBlockedUrl = ""
    private var lastCheckedUrl = ""

    private val handler = Handler(Looper.getMainLooper())
    private val urlCheckRunnable = object : Runnable {
        override fun run() {
            if (lastPackage == BROWSER_PKG && !TimerService.isRunningFor(BROWSER_PKG)) {
                checkUrlFromWindowContent()
            }
            handler.postDelayed(this, 1500)
        }
    }

    private val blockedKeywords = listOf(
        "truyen", "truy\u1ec7n", "manga", "comic",
        "xvideo", "xnxx", "pornhub", "xhamster", "redtube",
        "youporn", "sex", "porn", "adult", "hentai",
        "nhentai", "hanime", "javhd", "jav", "18+",
        "erome", "spankbang", "tnaflix", "sexvid"
    )

    companion object {
        var instance: AppWatcherService? = null
        const val BROWSER_PKG = "com.sec.android.app.sbrowser"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Enable content retrieval at runtime too
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info
        handler.postDelayed(urlCheckRunnable, 2000)
        Log.d("IntentGuard", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleAppSwitch(pkg)
                // Also check URL from event text (Samsung Internet passes URL here)
                if (pkg == BROWSER_PKG) {
                    val eventTexts = event.text?.joinToString(" ") ?: ""
                    val desc = event.contentDescription?.toString() ?: ""
                    checkTextForBlockedContent("$eventTexts $desc")
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg == BROWSER_PKG && !TimerService.isRunningFor(BROWSER_PKG)) {
                    // Check event text directly — fast path
                    val texts = event.text?.joinToString(" ") ?: ""
                    if (texts.isNotEmpty()) checkTextForBlockedContent(texts)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Catch URL being typed
                if (pkg == BROWSER_PKG) {
                    val text = event.text?.joinToString(" ") ?: ""
                    checkTextForBlockedContent(text)
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
        popupShownFor = pkg
        showPopup(pkg, DataStore.getAppName(this, pkg))
    }

    private fun checkTextForBlockedContent(text: String) {
        if (TimerService.isRunningFor(BROWSER_PKG)) return
        val lower = text.lowercase()
        val isBlocked = blockedKeywords.any { lower.contains(it) }
        if (isBlocked && text != lastBlockedUrl) {
            lastBlockedUrl = text
            Log.d("IntentGuard", "Blocked content detected in: $text")
            popupShownFor = ""
            showPopup(BROWSER_PKG, "Samsung Internet ⚠️")
        }
    }

    private fun checkUrlFromWindowContent() {
        try {
            val root = rootInActiveWindow ?: return
            val url = extractUrl(root)
            root.recycle()
            if (url == null || url == lastCheckedUrl) return
            lastCheckedUrl = url
            Log.d("IntentGuard", "Periodic URL check: $url")
            checkTextForBlockedContent(url)
        } catch (e: Exception) {
            Log.e("IntentGuard", "Periodic check error: ${e.message}")
        }
    }

    private fun extractUrl(root: AccessibilityNodeInfo): String? {
        // Try known Samsung Internet URL bar IDs
        val ids = listOf(
            "$BROWSER_PKG:id/location_bar_edit_text",
            "$BROWSER_PKG:id/url_bar",
            "$BROWSER_PKG:id/location",
            "$BROWSER_PKG:id/search_edit_text",
            "$BROWSER_PKG:id/urlbar_text"
        )
        for (id in ids) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    val text = nodes[0].text?.toString()
                    nodes.forEach { it.recycle() }
                    if (!text.isNullOrEmpty()) return text
                }
            } catch (e: Exception) { /* try next */ }
        }
        // Fallback: scan all nodes for URL-like content
        return scanNodesForUrl(root)
    }

    private fun scanNodesForUrl(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString() ?: ""
        if (text.length > 4 && (
            text.startsWith("http") ||
            text.startsWith("www.") ||
            (text.contains(".") && !text.contains(" ") && text.length < 200)
        )) {
            return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = scanNodesForUrl(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    fun resetPopupState() {
        popupShownFor = ""
        lastBlockedUrl = ""
        lastCheckedUrl = ""
    }

    private fun showPopup(pkg: String, appName: String) {
        val intent = Intent(this, IntentionPopupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentionPopupActivity.EXTRA_PACKAGE, pkg)
            putExtra(IntentionPopupActivity.EXTRA_APP_NAME, appName)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(urlCheckRunnable)
        instance = null
    }
}
