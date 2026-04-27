package com.intentguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.intentguard.data.DataStore
import com.intentguard.ui.IntentionPopupActivity
import java.text.SimpleDateFormat
import java.util.*

// ── Debug log singleton ───────────────────────────────────────────────────────
object DebugLog {
    private val _logs = ArrayDeque<String>()
    val logs: List<String> get() = _logs.toList()

    fun add(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logs.addFirst("[$t] $msg")
        if (_logs.size > 60) _logs.removeLast()
        Log.d("IntentGuard", msg)
    }

    fun clear() = _logs.clear()
}

class AppWatcherService : AccessibilityService() {

    private var lastForegroundPkg = ""
    private var popupShownForPkg = ""
    private var lastPopupTime = 0L
    private var lastScannedUrl = ""
    private val POPUP_COOLDOWN_MS = 4000L

    private val handler = Handler(Looper.getMainLooper())
    private val urlScanRunnable = object : Runnable {
        override fun run() {
            if (lastForegroundPkg == BROWSER_PKG) scanBrowserUrl()
            handler.postDelayed(this, 500)
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
        handler.postDelayed(urlScanRunnable, 1000)
        DebugLog.add("✅ Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleForegroundChange(pkg)
        }
    }

    private fun handleForegroundChange(pkg: String) {
        if (pkg == lastForegroundPkg) return
        lastForegroundPkg = pkg
        DebugLog.add("📱 Foreground: $pkg")

        if (!DataStore.isWatchedApp(this, pkg)) return
        if (pkg == BROWSER_PKG) return // browser handled by URL scanner

        if (popupShownForPkg == pkg && TimerService.isRunningFor(pkg)) return
        DataStore.checkAndRotateWeek(this)
        popupShownForPkg = pkg
        showAppPopup(pkg, DataStore.getAppName(this, pkg))
    }

    private fun scanBrowserUrl() {
        try {
            val root = rootInActiveWindow ?: run {
                DebugLog.add("⚠️ rootInActiveWindow = null")
                return
            }

            // Try all known Samsung Internet URL bar IDs
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

            var foundUrl: String? = null
            var foundVia = "none"

            for (resId in possibleIds) {
                try {
                    val nodes = root.findAccessibilityNodeInfosByViewId(resId)
                    if (nodes.isNotEmpty()) {
                        val text = nodes[0].text?.toString()
                            ?: nodes[0].contentDescription?.toString()
                        nodes.forEach { it.recycle() }
                        if (!text.isNullOrEmpty() && text.length > 3) {
                            foundUrl = text
                            foundVia = resId.substringAfter(":id/")
                            break
                        }
                    }
                } catch (_: Exception) {}
            }

            // Fallback: deep scan node tree
            if (foundUrl == null) {
                val scanned = deepScanForUrl(root, 0)
                if (scanned != null) {
                    foundUrl = scanned
                    foundVia = "deepScan"
                }
            }

            root.recycle()

            if (foundUrl == null) {
                // Log mỗi 10 giây để không spam
                if (System.currentTimeMillis() % 10000 < 600) {
                    DebugLog.add("🔍 Scan: không tìm thấy URL")
                }
                return
            }

            if (foundUrl == lastScannedUrl) return
            lastScannedUrl = foundUrl
            DebugLog.add("🌐 URL via [$foundVia]: $foundUrl")

            val urlLower = foundUrl.lowercase().trim()
            val blockedBy = blockedKeywords.firstOrNull { urlLower.contains(it) }

            if (blockedBy != null) {
                val now = System.currentTimeMillis()
                if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                lastPopupTime = now
                DebugLog.add("🚫 BLOCKED! keyword='$blockedBy' url=$foundUrl")
                TimerService.stop(this)
                popupShownForPkg = ""
                showBrowserBlockPopup(foundUrl)
            } else {
                if (!TimerService.isRunningFor(BROWSER_PKG)) {
                    val now = System.currentTimeMillis()
                    if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                    if (popupShownForPkg == BROWSER_PKG) return
                    lastPopupTime = now
                    popupShownForPkg = BROWSER_PKG
                    DebugLog.add("✅ URL OK, showing browser popup")
                    showAppPopup(BROWSER_PKG, DataStore.getAppName(this, BROWSER_PKG))
                }
            }
        } catch (e: Exception) {
            DebugLog.add("❌ Scan error: ${e.message}")
        }
    }

    private fun deepScanForUrl(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 8) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val candidate = if (text.isNotEmpty()) text else desc
        if (candidate.length in 4..300) {
            val lc = candidate.lowercase()
            if ((lc.startsWith("http") || lc.startsWith("www.") ||
                (lc.contains(".com") || lc.contains(".net") || lc.contains(".vn"))
                && !lc.contains(" ") && !lc.contains("\n"))) {
                return candidate
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = deepScanForUrl(child, depth + 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun showAppPopup(pkg: String, appName: String) {
        startActivity(Intent(this, IntentionPopupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentionPopupActivity.EXTRA_PACKAGE, pkg)
            putExtra(IntentionPopupActivity.EXTRA_APP_NAME, appName)
        })
    }

    private fun showBrowserBlockPopup(url: String) {
        startActivity(Intent(this, IntentionPopupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentionPopupActivity.EXTRA_PACKAGE, BROWSER_PKG)
            putExtra(IntentionPopupActivity.EXTRA_APP_NAME, "⚠️ Nội dung bị chặn!")
            putExtra(IntentionPopupActivity.EXTRA_BLOCKED_URL, url)
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
        instance = null
        DebugLog.add("🔴 Service destroyed")
    }
}
