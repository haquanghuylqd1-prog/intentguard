package com.intentguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
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

// Trạng thái cooldown block sau khi hết session giải trí
object CooldownState {
    var blockedUntilMs = 0L
    var approvedUrl = "" // URL bro được phép dùng trong cooldown

    fun isInCooldown() = System.currentTimeMillis() < blockedUntilMs
    fun startCooldown(hours: Int = 1) {
        blockedUntilMs = System.currentTimeMillis() + hours * 3600_000L
        approvedUrl = ""
        DebugLog.add("🔒 Cooldown bắt đầu - block $hours giờ")
    }
    fun approveUrl(url: String) {
        approvedUrl = url
        DebugLog.add("✅ Approved URL trong cooldown: $url")
    }
    fun remainingMinutes() = maxOf(0, ((blockedUntilMs - System.currentTimeMillis()) / 60000).toInt())
}

class AppWatcherService : AccessibilityService() {

    private var lastForegroundPkg = ""
    private var popupShownForPkg = ""
    private var lastPopupTime = 0L
    private var lastScannedUrl = ""
    private val POPUP_COOLDOWN_MS = 2000L

    private lateinit var blockingOverlay: BlockingOverlayManager
    private val handler = Handler(Looper.getMainLooper())

    private val urlScanRunnable = object : Runnable {
        override fun run() {
            if (lastForegroundPkg == BROWSER_PKG) {
                scanBrowserUrl()
                ensureBrowserOverlayShowing()
            }
            handler.postDelayed(this, 500)
        }
    }

    private var browserDetectRetryCount = 0
    private val browserDetectRunnable = object : Runnable {
        override fun run() {
            if (lastForegroundPkg != BROWSER_PKG) return
            if (TimerService.isRunningFor(BROWSER_PKG)) return
            browserDetectRetryCount++
            val root = try { rootInActiveWindow } catch (e: Exception) { null }
            if (root != null) {
                root.recycle()
                triggerBrowserPopup()
            } else if (browserDetectRetryCount < 10) {
                handler.postDelayed(this, 400)
            } else {
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

    private fun isInputMethodPackage(pkg: String): Boolean {
        return try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.any { it.packageName == pkg } == true
        } catch (e: Exception) { false }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return blockedKeywords.any { lower.contains(it) }
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
        if (isInputMethodPackage(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleForegroundChange(pkg)
                if (pkg == BROWSER_PKG) {
                    lastScannedUrl = ""
                    handler.postDelayed({ scanBrowserUrl() }, 300)
                    handler.postDelayed({ scanBrowserUrl() }, 800)
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                try {
                    val focusedPkg = windows?.firstOrNull { it.isFocused }
                        ?.root?.packageName?.toString()
                    if (focusedPkg != null &&
                        focusedPkg != packageName &&
                        focusedPkg != "com.android.systemui" &&
                        !isInputMethodPackage(focusedPkg) &&
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

    private fun ensureBrowserOverlayShowing() {
        // Nếu timer đang chạy → không cần overlay popup
        if (TimerService.isRunningFor(BROWSER_PKG)) return
        if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return

        // Nếu đang cooldown mà URL hiện tại đã approved → không block
        if (CooldownState.isInCooldown()) {
            val currentUrl = lastScannedUrl
            if (currentUrl.isNotEmpty() &&
                CooldownState.approvedUrl.isNotEmpty() &&
                currentUrl.contains(CooldownState.approvedUrl, ignoreCase = true)) return
        }

        val now = System.currentTimeMillis()
        if (now - lastPopupTime < 1000L) return
        lastPopupTime = now
        popupShownForPkg = BROWSER_PKG
        triggerBrowserPopup()
    }

    private fun onBrowserForegrounded() {
        if (TimerService.isRunningFor(BROWSER_PKG)) return
        if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
        val now = System.currentTimeMillis()
        if (now - lastPopupTime < POPUP_COOLDOWN_MS) return

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
            if (CooldownState.isInCooldown()) {
                // Đang trong cooldown → hiện overlay cooldown
                DebugLog.add("🔒 Cooldown đang active (${CooldownState.remainingMinutes()}p còn lại)")
                blockingOverlay.showCooldown(CooldownState.remainingMinutes())
            } else {
                DebugLog.add("✅ Showing browser popup")
                blockingOverlay.show(DataStore.getAppName(this, BROWSER_PKG), BROWSER_PKG, null)
            }
        }
    }

    private fun scanBrowserUrl() {
        try {
            val root = rootInActiveWindow ?: return
            val url = extractUrlFromTree(root)
            root.recycle()

            if (url.isNullOrEmpty() || url == lastScannedUrl) return
            lastScannedUrl = url
            DebugLog.add("🌐 URL: $url")

            val isBlocked = isBlockedUrl(url)

            // Nếu timer đang chạy cho browser session này
            if (TimerService.isRunningFor(BROWSER_PKG)) {
                if (isBlocked) {
                    // URL bị block ngay cả trong session → dừng session, hiện overlay
                    val now = System.currentTimeMillis()
                    if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                    if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
                    lastPopupTime = now
                    DebugLog.add("🚫 BLOCKED trong session! url=$url")
                    TimerService.stop(this)
                    popupShownForPkg = ""
                    handler.post {
                        blockingOverlay.show("⚠️ Nội dung bị chặn!", BROWSER_PKG, url)
                    }
                }
                // URL hợp lệ trong session → để yên, không làm gì
                return
            }

            // Không có timer đang chạy
            if (isBlocked) {
                val now = System.currentTimeMillis()
                if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return

                // Kiểm tra cooldown
                if (CooldownState.isInCooldown()) {
                    lastPopupTime = now
                    DebugLog.add("🔒 COOLDOWN BLOCK! url=$url (${CooldownState.remainingMinutes()}p còn)")
                    handler.post { blockingOverlay.showCooldown(CooldownState.remainingMinutes()) }
                } else {
                    lastPopupTime = now
                    DebugLog.add("🚫 BLOCKED! url=$url")
                    popupShownForPkg = ""
                    handler.post {
                        blockingOverlay.show("⚠️ Nội dung bị chặn!", BROWSER_PKG, url)
                    }
                }
            }
            // URL ok + không có timer → ensureBrowserOverlayShowing sẽ xử lý
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
                    val text = nodes[0].text?.toString() ?: nodes[0].contentDescription?.toString()
                    nodes.forEach { it.recycle() }
                    if (!text.isNullOrEmpty() && text.length > 3) return text
                }
            } catch (_: Exception) {}
        }
        return deepScanForUrl(root, 0)
    }

    private fun deepScanForUrl(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 8) return null
        val candidate = node.text?.toString()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString() ?: ""
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
        lastPopupTime = 0L
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
