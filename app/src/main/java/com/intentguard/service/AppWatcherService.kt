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

object CooldownState {
    var blockedUntilMs = 0L
    var approvedUrl = ""
    var cooldownPkg = ""

    fun isInCooldown() = System.currentTimeMillis() < blockedUntilMs

    fun startCooldown(hours: Int = 1, pkg: String = "") {
        blockedUntilMs = System.currentTimeMillis() + hours * 3600_000L
        approvedUrl = ""
        cooldownPkg = pkg
        DebugLog.add("🔒 Cooldown bắt đầu - block ${hours}h (pkg=$pkg)")
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

    // Track entertainment content detection
    private var lastEntertainDetectTime = 0L
    private val ENTERTAIN_DETECT_COOLDOWN_MS = 5000L // 5 giây giữa các lần detect

    private lateinit var blockingOverlay: BlockingOverlayManager
    private val handler = Handler(Looper.getMainLooper())

    // Scan browser URL mỗi 500ms
    private val urlScanRunnable = object : Runnable {
        override fun run() {
            if (lastForegroundPkg == BROWSER_PKG) {
                scanBrowserUrl()
                ensureBrowserOverlayShowing()
            }
            handler.postDelayed(this, 500)
        }
    }

    // Scan FB/YT content mỗi 2 giây
    private val appContentScanRunnable = object : Runnable {
        override fun run() {
            val pkg = lastForegroundPkg
            if (pkg in SOCIAL_PKGS && !TimerService.isRunningFor(pkg)) {
                // Chỉ scan nếu không đang trong session làm việc
                scanAppForEntertainContent(pkg)
            }
            handler.postDelayed(this, 2000)
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

    // Keywords detect Reels/Shorts trong FB/YT
    // Dùng lowercase, match partial
    private val reelsKeywords = listOf(
        "reels", "reel",
        "shorts", "short",
        "video ngắn", "video ngan"
    )

    // Text thường xuất hiện khi đang xem Reels/Shorts (UI elements)
    private val reelsUIPatterns = listOf(
        "thích", "bình luận", "chia sẻ", "theo dõi",   // FB Reels
        "like", "comment", "share", "follow",             // FB Reels EN
        "đăng ký", "đã đăng ký",                         // YT Shorts
        "subscribe", "subscribed",                         // YT Shorts EN
        "cuộn lên để xem video tiếp theo",
        "scroll for next"
    )

    companion object {
        var instance: AppWatcherService? = null
        const val BROWSER_PKG = "com.sec.android.app.sbrowser"
        val SOCIAL_PKGS = setOf(
            "com.facebook.katana",
            "com.facebook.lite",
            "com.google.android.youtube"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        blockingOverlay = BlockingOverlayManager(this)
        handler.postDelayed(urlScanRunnable, 1000)
        handler.postDelayed(appContentScanRunnable, 2000)
        DebugLog.add("✅ Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        // Nếu overlay đang hiện → ignore window events để tránh dismiss/re-show
        if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return

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

    // ── FB/YT Content Scanner ─────────────────────────────────────────────────

    private fun scanAppForEntertainContent(pkg: String) {
        // Nếu đang trong cooldown và chưa cho phép dùng để làm việc → bỏ qua
        if (CooldownState.isInCooldown()) return

        // Nếu đang trong session làm việc (💼) → không block
        val currentSession = TimerService.currentIntention
        if (currentSession.startsWith("💼")) return

        // Nếu đang trong session giải trí (🎮) → đã được approve, không cần detect
        if (currentSession.startsWith("🎮") && TimerService.isRunningFor(pkg)) return

        try {
            val root = rootInActiveWindow ?: return
            val detected = detectEntertainContent(root, pkg)
            root.recycle()

            if (detected) {
                val now = System.currentTimeMillis()
                if (now - lastEntertainDetectTime < ENTERTAIN_DETECT_COOLDOWN_MS) return
                if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
                if (now - lastPopupTime < POPUP_COOLDOWN_MS) return

                lastEntertainDetectTime = now
                lastPopupTime = now
                DebugLog.add("🎮 Detected entertain content in $pkg → blocking")

                // Dừng session làm việc nếu có (để start session giải trí mới)
                if (TimerService.isRunningFor(pkg)) {
                    TimerService.stop(this)
                }
                popupShownForPkg = ""
                handler.post {
                    blockingOverlay.show("⚠️ Nội dung giải trí!", pkg, "reels_detected")
                }
            }
        } catch (e: Exception) {
            // Không log để tránh spam
        }
    }

    private fun detectEntertainContent(root: AccessibilityNodeInfo, pkg: String): Boolean {
        // Collect tất cả text visible trên màn hình
        val allTexts = mutableListOf<String>()
        collectAllText(root, allTexts, depth = 0)
        val combinedText = allTexts.joinToString(" ").lowercase()

        return when (pkg) {
            "com.facebook.katana", "com.facebook.lite" -> detectFBReels(combinedText)
            "com.google.android.youtube" -> detectYTShorts(combinedText)
            else -> false
        }
    }

    private fun detectFBReels(text: String): Boolean {
        // FB Reels thường có: "Reels" label + Like/Comment/Share buttons theo chiều dọc
        val hasReelsLabel = text.contains("reels") || text.contains("reel")
        val hasEngagementButtons = (text.contains("thích") || text.contains("like")) &&
                (text.contains("bình luận") || text.contains("comment"))

        // Hoặc detect pattern video autoplay
        val hasVideoPattern = text.contains("âm thanh gốc") ||
                text.contains("original audio") ||
                text.contains("xem thêm") && text.contains("theo dõi")

        if (hasReelsLabel) {
            DebugLog.add("🎯 FB Reels detected (reels label)")
            return true
        }
        if (hasEngagementButtons && hasVideoPattern) {
            DebugLog.add("🎯 FB Reels detected (engagement pattern)")
            return true
        }
        return false
    }

    private fun detectYTShorts(text: String): Boolean {
        // YT Shorts thường có: "Shorts" label hoặc vertical video UI
        val hasShortsLabel = text.contains("shorts") || text.contains("short")
        val hasYTShortPattern = text.contains("đăng ký") &&
                (text.contains("thích") || text.contains("like")) &&
                text.contains("bình luận")

        if (hasShortsLabel) {
            DebugLog.add("🎯 YT Shorts detected (shorts label)")
            return true
        }
        if (hasYTShortPattern) {
            DebugLog.add("🎯 YT Shorts detected (UI pattern)")
            return true
        }
        return false
    }

    private fun collectAllText(node: AccessibilityNodeInfo, result: MutableList<String>, depth: Int) {
        if (depth > 6) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, result, depth + 1)
            child.recycle()
        }
    }

    // ── Browser URL Scanner ───────────────────────────────────────────────────

    private fun handleForegroundChange(pkg: String) {
        // Chỉ dismiss overlay khi chuyển sang watched app khác
        // KHÔNG dismiss khi keyboard/system foreground (vì sẽ làm overlay biến mất khi nhập text)
        val isWatchedOtherApp = pkg != BROWSER_PKG && DataStore.isWatchedApp(this, pkg)
        if (isWatchedOtherApp && ::blockingOverlay.isInitialized && blockingOverlay.isShowing) {
            blockingOverlay.dismiss()
        }
        if (pkg == lastForegroundPkg) return
        // Chỉ update lastForegroundPkg nếu là app thật (không phải keyboard/system)
        val isSystemPkg = pkg.startsWith("com.android.") ||
            pkg.startsWith("com.samsung.android.input") ||
            pkg.startsWith("com.sec.android.inputmethod") ||
            pkg == "com.google.android.inputmethod.latin"
        if (!isSystemPkg) lastForegroundPkg = pkg
        DebugLog.add("📱 Foreground: $pkg")

        if (!DataStore.isWatchedApp(this, pkg)) return
        DataStore.checkAndRotateWeek(this)

        if (pkg == BROWSER_PKG) {
            onBrowserForegrounded()
        } else {
            if (CooldownState.isInCooldown()) {
                val now = System.currentTimeMillis()
                if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
                lastPopupTime = now
                DebugLog.add("🔒 Cooldown block for $pkg (${CooldownState.remainingMinutes()}p)")
                handler.post { blockingOverlay.showCooldown(CooldownState.remainingMinutes()) }
                return
            }
            if (popupShownForPkg == pkg && TimerService.isRunningFor(pkg)) return
            popupShownForPkg = pkg
            showActivityPopup(pkg, DataStore.getAppName(this, pkg))
        }
    }

    private fun ensureBrowserOverlayShowing() {
        if (TimerService.isRunningFor(BROWSER_PKG)) return
        if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return

        if (CooldownState.isInCooldown()) {
            val currentUrl = lastScannedUrl
            if (currentUrl.isEmpty()) return
            if (isBlockedUrl(currentUrl)) return
            val now = System.currentTimeMillis()
            if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
            if (popupShownForPkg == BROWSER_PKG) return
            lastPopupTime = now
            popupShownForPkg = BROWSER_PKG
            DebugLog.add("✅ Work URL in cooldown → show work popup: $currentUrl")
            handler.post {
                blockingOverlay.show(DataStore.getAppName(this, BROWSER_PKG), BROWSER_PKG, null)
            }
            return
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

        if (CooldownState.isInCooldown()) {
            lastScannedUrl = ""
            lastPopupTime = 0L
            DebugLog.add("🔒 Cooldown active - reset state")
            handler.postDelayed({ scanBrowserUrl() }, 300)
            handler.postDelayed({ scanBrowserUrl() }, 800)
            return
        }

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
        if (CooldownState.isInCooldown()) return
        handler.post {
            DebugLog.add("✅ Showing browser popup")
            blockingOverlay.show(DataStore.getAppName(this, BROWSER_PKG), BROWSER_PKG, null)
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return blockedKeywords.any { lower.contains(it) }
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

            if (TimerService.isRunningFor(BROWSER_PKG)) {
                if (isBlocked) {
                    val approvedDomain = TimerService.approvedDomain
                    if (approvedDomain.isNotEmpty()) {
                        DebugLog.add("✅ Blocked content allowed in session")
                        return
                    }
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
                return
            }

            if (isBlocked) {
                val now = System.currentTimeMillis()
                if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return

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

    private fun extractDomain(url: String): String {
        return url.lowercase()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").split("/")[0].split("?")[0]
    }

    fun resetPopupState(keepUrl: String = "") {
        popupShownForPkg = ""
        lastScannedUrl = if (keepUrl.isNotEmpty()) keepUrl else ""
        lastPopupTime = System.currentTimeMillis()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(urlScanRunnable)
        handler.removeCallbacks(appContentScanRunnable)
        handler.removeCallbacks(browserDetectRunnable)
        if (::blockingOverlay.isInitialized) blockingOverlay.dismiss()
        instance = null
        DebugLog.add("🔴 Service destroyed")
    }
}
