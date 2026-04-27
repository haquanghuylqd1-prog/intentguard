package com.intentguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.intentguard.data.DataStore
import com.intentguard.ui.IntentionPopupActivity
import java.text.SimpleDateFormat
import java.util.*

object DebugLog {
    private val _logs = ArrayDeque<String>()
    val logs: List<String> get() = _logs.toList()
    fun add(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logs.addFirst("[$t] $msg")
        if (_logs.size > 60) _logs.removeLast()
        android.util.Log.d("IntentGuard", msg)
    }
    fun clear() = _logs.clear()
}

class AppWatcherService : AccessibilityService() {

    private var lastForegroundPkg = ""
    private var popupShownForPkg = ""
    private var lastPopupTime = 0L
    private var lastScannedUrl = ""
    private val POPUP_COOLDOWN_MS = 3000L

    private lateinit var blockingOverlay: BlockingOverlayManager
    private val handler = Handler(Looper.getMainLooper())

    // Periodic scan mỗi 500ms để detect URL trong browser
    private val urlScanRunnable = object : Runnable {
        override fun run() {
            if (lastForegroundPkg == BROWSER_PKG) {
                scanBrowserUrl()
            }
            handler.postDelayed(this, 500)
        }
    }

    // Retry detect browser khi rootInActiveWindow = null
    private var browserDetectRetryCount = 0
    private val browserDetectRunnable = object : Runnable {
        override fun run() {
            if (lastForegroundPkg != BROWSER_PKG) return
            if (TimerService.isRunningFor(BROWSER_PKG)) return

            browserDetectRetryCount++
            val root = try { rootInActiveWindow } catch (e: Exception) { null }

            if (root != null) {
                root.recycle()
                DebugLog.add("🌐 Browser active window OK (retry #$browserDetectRetryCount) — show popup")
                triggerBrowserPopup()
            } else if (browserDetectRetryCount < 10) {
                DebugLog.add("⚠️ rootInActiveWindow = null (retry #$browserDetectRetryCount)")
                handler.postDelayed(this, 400)
            } else {
                // Sau 10 lần retry (~4s) vẫn null → force show popup
                DebugLog.add("⚠️ rootInActiveWindow null x10 — force show browser popup")
                triggerBrowserPopup()
            }
        }
    }

    private val blockedKeywords = listOf(
        "truyen", "truy\u1ec7n", "manga", "manhwa", "comic",
        "xvideo", "xnxx", "pornhub", "xhamster", "redtube",
        "youporn", "nhentai", "hanime", "javhd", "erome",
        "spankbang", "hentai", "sex", "porn", "adult18",
        "18adult", "phimse", "phim18", "javmost", "jav"
    )

    companion object {
        var instance: AppWatcherService? = null
        const val BROWSER_PKG = "com.sec.android.app.sbrowser"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        blockingOverlay = BlockingOverlayManager(this)
        handler.postDelayed(urlScanRunnable, 1000)
        DebugLog.add("✅ Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleForegroundChange(pkg)
                if (pkg == BROWSER_PKG) {
                    // Reset cache khi có tab/window change trong browser
                    lastScannedUrl = ""
                    handler.postDelayed({ scanBrowserUrl() }, 300)
                    handler.postDelayed({ scanBrowserUrl() }, 800)
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Android 8+ — bắt được cả khi resume từ background
                try {
                    val focusedPkg = windows?.firstOrNull { it.isFocused }
                        ?.root?.packageName?.toString()
                    if (focusedPkg != null &&
                        focusedPkg != packageName &&
                        focusedPkg != "com.android.systemui" &&
                        focusedPkg != lastForegroundPkg) {
                        handleForegroundChange(focusedPkg)
                        if (focusedPkg == BROWSER_PKG) {
                            lastScannedUrl = ""
                            handler.postDelayed({ scanBrowserUrl() }, 400)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleForegroundChange(pkg: String) {
        // Dismiss overlay nếu thoát khỏi browser
        if (pkg != BROWSER_PKG && ::blockingOverlay.isInitialized && blockingOverlay.isShowing) {
            blockingOverlay.dismiss()
        }

        if (pkg == lastForegroundPkg) return
        lastForegroundPkg = pkg
        DebugLog.add("📱 Foreground: $pkg")

        if (!DataStore.isWatchedApp(this, pkg)) return
        DataStore.checkAndRotateWeek(this)

        if (pkg == BROWSER_PKG) {
            onBrowserForegrounded()
        } else {
            if (popupShownForPkg == pkg && TimerService.isRunningFor(pkg)) return
            popupShownForPkg = pkg
            showActivityPopup(pkg, DataStore.getAppName(this, pkg))
        }
    }

    /**
     * Gọi mỗi khi Samsung Internet vào foreground.
     * Luôn show popup trừ khi đang có timer chạy.
     */
    private fun onBrowserForegrounded() {
        if (TimerService.isRunningFor(BROWSER_PKG)) {
            DebugLog.add("🌐 Browser foregrounded — timer đang chạy, skip")
            return
        }
        if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) {
            DebugLog.add("🌐 Browser foregrounded — overlay đang hiện, skip")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastPopupTime < POPUP_COOLDOWN_MS) {
            DebugLog.add("🌐 Browser foregrounded — cooldown, skip")
            return
        }

        DebugLog.add("🌐 Browser foregrounded — chuẩn bị hiện popup")
        lastPopupTime = now
        popupShownForPkg = BROWSER_PKG

        browserDetectRetryCount = 0
        handler.removeCallbacks(browserDetectRunnable)
        handler.postDelayed(browserDetectRunnable, 200)
    }

    private fun triggerBrowserPopup() {
        if (TimerService.isRunningFor(BROWSER_PKG)) return
        if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
        handler.post {
            DebugLog.add("✅ URL OK, showing browser popup")
            blockingOverlay.show(DataStore.getAppName(this, BROWSER_PKG), BROWSER_PKG, null)
        }
    }

    private fun scanBrowserUrl() {
        try {
            val root = rootInActiveWindow
            if (root == null) {
                DebugLog.add("⚠️ rootInActiveWindow = null")
                return
            }
            val url = extractUrlFromTree(root)
            root.recycle()

            if (url.isNullOrEmpty()) {
                DebugLog.add("🔍 Scan: không tìm thấy URL")
                return
            }
            if (url == lastScannedUrl) return
            lastScannedUrl = url
            DebugLog.add("🌐 URL via [location_bar_edit_text]: $url")

            val urlLower = url.lowercase().trim()
            val blockedBy = blockedKeywords.firstOrNull { urlLower.contains(it) }

            if (blockedBy != null) {
                val now = System.currentTimeMillis()
                if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
                lastPopupTime = now
                DebugLog.add("🚫 BLOCKED! keyword='$blockedBy' url=$url")

                TimerService.stop(this)
                popupShownForPkg = ""

                handler.post {
                    blockingOverlay.show("⚠️ Nội dung bị chặn!", BROWSER_PKG, url)
                }
            }
        } catch (e: Exception) {
            DebugLog.add("❌ Scan error: ${e.message}")
        }
    }

    private fun extractUrlFromTree(root: AccessibilityNodeInfo): String? {
        val possibleIds = listOf(
            "$BROWSER_PKG:id/location_bar_edit_text",
            "$BROWSER_PKG:id/url_bar",
            "$BROWSER_PKG:id/location",
            "$BROWSER_PKG:id/search_edit_text",
            "$BROWSER_PKG:id/urlbar_text",
            "$BROWSER_PKG:id/location_bar_text",
            "$BROWSER_PKG:id/omnibar_text",
            "$BROWSER_PKG:id/url_field"
        )
        for (resId in possibleIds) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(resId)
                if (nodes.isNotEmpty()) {
                    val text = nodes[0].text?.toString()
                        ?: nodes[0].contentDescription?.toString()
                    nodes.forEach { it.recycle() }
                    if (!text.isNullOrEmpty() && text.length > 3) return text
                }
            } catch (_: Exception) {}
        }
        return deepScanForUrl(root, 0)
    }

    private fun deepScanForUrl(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 8) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val candidate = if (text.isNotEmpty()) text else desc
        if (candidate.length in 4..300) {
            val lc = candidate.lowercase()
            if (lc.startsWith("http") || lc.startsWith("www.") ||
                ((lc.contains(".com") || lc.contains(".net") || lc.contains(".vn"))
                    && !lc.contains(" ") && !lc.contains("\n"))) return candidate
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = deepScanForUrl(child, depth + 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun showActivityPopup(pkg: String, appName: String) {
        startActivity(Intent(this, IntentionPopupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentionPopupActivity.EXTRA_PACKAGE, pkg)
            putExtra(IntentionPopupActivity.EXTRA_APP_NAME, appName)
        })
    }

    fun resetPopupState() {
        popupShownForPkg = ""
        lastScannedUrl = ""
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(urlScanRunnable)
        handler.removeCallbacks(browserDetectRunnable)
        if (::blockingOverlay.isInitialized) blockingOverlay.dismiss()
        instance = null
        DebugLog.add("🔴 Service destroyed")
    }
}
