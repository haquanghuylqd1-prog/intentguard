package com.intentguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
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
    var cooldownPkg = "" // pkg nào trigger cooldown

    fun isInCooldown() = System.currentTimeMillis() < blockedUntilMs

    fun startCooldown(hours: Int = 1, pkg: String = "") {
        blockedUntilMs = System.currentTimeMillis() + hours * 3600_000L
        approvedUrl = ""
        cooldownPkg = pkg
        DebugLog.add("🔒 Cooldown bắt đầu - block ${hours}h")
    }

    fun approveUrl(url: String) {
        approvedUrl = url
        DebugLog.add("✅ Approved URL: $url")
    }

    fun remainingMinutes() = maxOf(0, ((blockedUntilMs - System.currentTimeMillis()) / 60000).toInt())
}

class AppWatcherService : AccessibilityService() {

    private var lastForegroundPkg = ""
    private var popupShownForPkg = ""
    private var lastPopupTime = 0L
    private var lastScannedUrl = ""
    private var lastBrowserContentType: String? = null
    private val POPUP_COOLDOWN_MS = 2000L
    private var lastEntertainDetectTime = 0L
    private val ENTERTAIN_DETECT_COOLDOWN_MS = 5000L

    // FB Reels counter state
    private var lastReelSignature = ""
    private var lastReelCountTime = 0L

    private lateinit var blockingOverlay: BlockingOverlayManager
    private val handler = Handler(Looper.getMainLooper())

    private val urlScanRunnable = object : Runnable {
        override fun run() {
            if (lastForegroundPkg == BROWSER_PKG) {
                scanBrowserUrl()
                ensureBrowserOverlayShowing()
                trackBrowserContinuousUsage()
            }
            handler.postDelayed(this, 500)
        }
    }

    // Track thời gian dùng liên tục cho web Facebook/YouTube/giải trí (dựa trên URL đang scan).
    // Web khác (không thuộc nhóm này) không bị áp rule 20p liên tục.
    private fun trackBrowserContinuousUsage() {
        if (CooldownState.isInCooldown()) return
        val contentType = lastBrowserContentType ?: DataStore.classifyBrowserContent(lastScannedUrl) ?: return
        trackContinuousAndMaybeBlock("browser:$contentType", BROWSER_PKG)
    }

    private val appContentScanRunnable = object : Runnable {
        override fun run() {
            val pkg = lastForegroundPkg
            // Scan FB/YT/Shopee mọi lúc — KỂ CẢ đang trong session 💼 làm việc
            // (logic skip khi đang trong session 🎮 hợp lệ nằm bên trong)
            if (pkg in SOCIAL_PKGS) {
                // Track thời gian dùng liên tục cho app này — >20p liên tục thì tự cooldown,
                // bất kể có đang trong 1 session hợp lệ hay không.
                if (!trackContinuousAndMaybeBlock(pkg, pkg)) {
                    scanAppForEntertainContent(pkg)
                }
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

    companion object {
        var instance: AppWatcherService? = null
        const val BROWSER_PKG = "com.sec.android.app.sbrowser"
        const val SHOPEE_PKG = "com.shopee.vn"
        val SOCIAL_PKGS = setOf(
            "com.facebook.katana",
            "com.facebook.lite",
            "com.google.android.youtube",
            "com.shopee.vn"
        )
        val FB_PKGS = setOf("com.facebook.katana", "com.facebook.lite")
        const val REELS_LIMIT = 3           // Cho xem tối đa 3 reels
        const val MIN_REEL_INTERVAL_MS = 3000L  // Tối thiểu 3s giữa 2 lần đếm reel mới
    }

    // Dùng InputMethodManager để detect keyboard pkg — đúng cách, không hardcode
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
        handler.postDelayed(appContentScanRunnable, 2000)
        DebugLog.add("✅ Service connected")
    }

    // ── Continuous-usage guard (rule: >20 phút liên tục 1 loại nội dung → cooldown 1h) ──
    // [trackingKey]: packageName cho app riêng (FB/YT/Shopee), hoặc "browser:<contentType>" cho web.
    // Gọi định kỳ mỗi lần scan xác nhận vẫn đang xem đúng nội dung đó.
    // Trả về true nếu VỪA trigger cooldown (caller nên dừng xử lý tiếp sau khi gọi).
    private fun trackContinuousAndMaybeBlock(trackingKey: String, pkgForCooldown: String): Boolean {
        if (CooldownState.isInCooldown()) return false
        val minutes = DataStore.updateContinuousUsage(this, trackingKey)
        if (minutes >= DataStore.CONTINUOUS_LIMIT_MINUTES) {
            DebugLog.add("🔒 Dùng liên tục ${minutes}p ($trackingKey) → COOLDOWN 1h!")
            DataStore.clearContinuousUsage(this, trackingKey)
            DataStore.clearRepeatState(this, trackingKey)
            if (TimerService.isRunningFor(pkgForCooldown)) TimerService.stop(this)
            CooldownState.startCooldown(1, pkgForCooldown)
            popupShownForPkg = ""
            handler.post { blockingOverlay.showCooldown(CooldownState.remainingMinutes()) }
            return true
        }
        return false
    }

    // ── Repeated-session guard (rule: 3 session liên tiếp cùng app/nội dung → cooldown 1h) ──
    // Gọi khi một session MỚI bắt đầu. Mục đích có thể khác nhau; cùng trackingKey vẫn được đếm.
    // Nếu đây là session thứ REPEAT_LIMIT liên tiếp trở lên → cooldown ngay,
    // không cho session này tiếp tục chạy.
    fun checkRepeatedIntentionAndMaybeBlock(trackingKey: String, intention: String, pkgForCooldown: String): Boolean {
        val count = DataStore.registerIntentionAttempt(this, trackingKey, intention)
        if (count >= DataStore.REPEAT_LIMIT) {
            DebugLog.add("🔒 Đã mở $count session liên tiếp ($trackingKey) → COOLDOWN 1h!")
            DataStore.clearRepeatState(this, trackingKey)
            DataStore.clearContinuousUsage(this, trackingKey)
            if (TimerService.isRunningFor(pkgForCooldown)) TimerService.stop(this)
            CooldownState.startCooldown(1, pkgForCooldown)
            popupShownForPkg = ""
            handler.post { blockingOverlay.showCooldown(CooldownState.remainingMinutes()) }
            return true
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        // Dùng InputMethodManager để filter keyboard — không dismiss overlay khi gõ
        if (isInputMethodPackage(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Khi về home → reset lastForegroundPkg để lần sau vào browser trigger lại
                if (pkg.contains("launcher", ignoreCase = true) ||
                    pkg == "com.samsung.android.app.resolver") {
                    lastForegroundPkg = ""
                }
                // Luôn update lastForegroundPkg cho browser và reset URL khi tab switch
                if (pkg == BROWSER_PKG) {
                    lastForegroundPkg = BROWSER_PKG
                    lastScannedUrl = ""
                    handler.postDelayed({ scanBrowserUrl() }, 300)
                    handler.postDelayed({ scanBrowserUrl() }, 800)
                }
                handleForegroundChange(pkg)
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

    // ── FB/YT Reels/Shorts Detection ─────────────────────────────────────────

    private fun scanAppForEntertainContent(pkg: String) {
        if (CooldownState.isInCooldown()) return

        // ── Facebook: đếm reels, reel thứ 4 → cooldown 1h ──
        if (pkg in FB_PKGS) {
            scanFacebookReels(pkg)
            return
        }

        // ── YouTube: detect Shorts KỂ CẢ trong session 💼 làm việc ──
        // Chỉ skip khi đang trong session giải trí hợp lệ (🎮 hoặc ⚠️)
        val currentSession = TimerService.currentIntention
        if (TimerService.isRunningFor(pkg) && !currentSession.startsWith("💼")) return

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
                DebugLog.add("🎮 Detected entertain content in $pkg")
                if (TimerService.isRunningFor(pkg)) TimerService.stop(this)
                popupShownForPkg = ""
                handler.post {
                    blockingOverlay.show("⚠️ Nội dung giải trí!", pkg, "reels_detected")
                }
            }
        } catch (_: Exception) {}
    }

    private fun detectEntertainContent(root: AccessibilityNodeInfo, pkg: String): Boolean {
        val allTexts = mutableListOf<String>()
        collectAllText(root, allTexts, 0)
        val text = allTexts.joinToString(" ").lowercase()
        return when (pkg) {
            "com.facebook.katana", "com.facebook.lite" -> {
                val hasReels = text.contains("reels") || text.contains("reel")
                val hasPattern = (text.contains("âm thanh gốc") || text.contains("original audio")) &&
                    text.contains("theo dõi")
                if (hasReels) DebugLog.add("🎯 FB Reels detected")
                if (hasPattern) DebugLog.add("🎯 FB Reels pattern detected")
                hasReels || hasPattern
            }
            "com.google.android.youtube" -> {
                val hasShorts = text.contains("shorts")
                if (hasShorts) DebugLog.add("🎯 YT Shorts detected")
                hasShorts
            }
            else -> false
        }
    }

    // ── FB Reels: popup khoá 🎮 ngay khi detect (kể cả session 💼),
    //    đếm 3 reels free → reel thứ 4 = cooldown 1h ──────────────────────────

    private fun scanFacebookReels(pkg: String) {
        // Đang hiện overlay (popup/cooldown) → không đếm reels phía sau
        if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
        try {
            val root = rootInActiveWindow ?: return
            val texts = mutableListOf<String>()
            collectAllText(root, texts, 0)
            root.recycle()

            val joined = texts.joinToString(" ").lowercase()
            val isReel = joined.contains("reels") || joined.contains("reel") ||
                ((joined.contains("âm thanh gốc") || joined.contains("original audio")) &&
                    joined.contains("theo dõi"))
            if (!isReel) return

            // Cooldown đã hết mà count còn > limit → chu kỳ mới, reset về 0
            if (DataStore.getReelsCount(this) > REELS_LIMIT) {
                DataStore.setReelsCount(this, 0)
                lastReelSignature = ""
                DebugLog.add("🔄 Reels counter reset (cooldown đã hết)")
            }

            // ── Bước 1: Đếm reel mới (chống đếm trùng + chống lướt quá nhanh) ──
            var count = DataStore.getReelsCount(this)
            var justCounted = false
            val signature = buildReelSignature(texts)
            if (signature.isNotEmpty() && !isSameReel(signature, lastReelSignature)) {
                val now = System.currentTimeMillis()
                if (now - lastReelCountTime >= MIN_REEL_INTERVAL_MS) {
                    lastReelSignature = signature
                    lastReelCountTime = now
                    count += 1
                    DataStore.setReelsCount(this, count)
                    justCounted = true
                    DebugLog.add("🎬 FB Reel #$count")
                }
            }

            // ── Bước 2: Reel thứ 4 → khoá Facebook 1h ──
            if (count > REELS_LIMIT) {
                if (justCounted) {
                    DebugLog.add("🔒 Reel thứ $count → COOLDOWN 1h, khoá Facebook!")
                    if (TimerService.isRunningFor(pkg)) TimerService.stop(this)
                    CooldownState.startCooldown(1, pkg)
                    popupShownForPkg = ""
                    handler.post {
                        blockingOverlay.showCooldown(CooldownState.remainingMinutes())
                    }
                }
                return
            }

            // ── Bước 3: Chưa có session giải trí hợp lệ → popup khoá 🎮 NGAY
            //    (kể cả đang trong session 💼 làm việc — dừng session đó luôn) ──
            val inEntertainSession = TimerService.isRunningFor(pkg) &&
                (TimerService.currentIntention.startsWith("🎮") ||
                    TimerService.currentIntention.startsWith("⚠️"))
            if (!inEntertainSession) {
                val now = System.currentTimeMillis()
                if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                lastPopupTime = now
                if (TimerService.isRunningFor(pkg)) {
                    DebugLog.add("⚠️ Reel trong session 💼 → dừng session làm việc!")
                    TimerService.stop(this)
                }
                popupShownForPkg = ""
                DebugLog.add("🎮 Reel detected → popup khoá Giải trí (reel $count/$REELS_LIMIT)")
                handler.post {
                    blockingOverlay.show(
                        "⚠️ Nội dung giải trí! (Reel ${maxOf(count, 1)}/$REELS_LIMIT)",
                        pkg, "reels_detected"
                    )
                }
                return
            }

            // ── Bước 4: Đang trong session giải trí → toast đếm ──
            if (justCounted) {
                handler.post {
                    Toast.makeText(
                        this,
                        "🎬 Reel $count/$REELS_LIMIT — reel thứ ${REELS_LIMIT + 1} là khoá Facebook 1h đó nha!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            DebugLog.add("❌ Reels scan error: ${e.message}")
        }
    }

    // Tạo "chữ ký" của reel hiện tại từ text trên màn hình
    // (tên tác giả, caption...) — lọc bỏ số like/view và chữ chung chung
    private val reelGenericWords = setOf(
        "reels", "reel", "theo dõi", "đang theo dõi", "thích", "bình luận",
        "chia sẻ", "gửi", "âm thanh gốc", "original audio", "follow",
        "following", "like", "comment", "share", "send", "facebook"
    )

    private fun buildReelSignature(texts: List<String>): String {
        return texts.asSequence()
            .map { it.trim() }
            .filter { it.length in 6..120 }
            .filter { t -> t.lowercase() !in reelGenericWords }
            .filter { !it.matches(Regex("^[\\d.,kKmMtr\\s]+$")) } // bỏ số like/view
            .distinct()
            .take(6)
            .joinToString("|")
    }

    // 2 chữ ký trùng >40% dòng → vẫn là reel cũ (like count đổi, mở comment...)
    private fun isSameReel(a: String, b: String): Boolean {
        if (b.isEmpty()) return false
        if (a == b) return true
        val setA = a.split("|").toSet()
        val setB = b.split("|").toSet()
        if (setA.isEmpty() || setB.isEmpty()) return false
        val common = setA.intersect(setB).size
        return common.toDouble() / minOf(setA.size, setB.size) > 0.4
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

    // ── Foreground & Browser Logic ────────────────────────────────────────────

    private fun handleForegroundChange(pkg: String) {
        // Dismiss overlay chỉ khi chuyển sang watched app khác (không phải keyboard)
        val isWatchedOther = pkg != BROWSER_PKG && DataStore.isWatchedApp(this, pkg)
        if (isWatchedOther && ::blockingOverlay.isInitialized && blockingOverlay.isShowing) {
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
            // Cooldown → block tất cả social apps
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
            val controlledContent = lastBrowserContentType ?: DataStore.classifyBrowserContent(currentUrl)
            if (currentUrl.isEmpty() && controlledContent == null) return
            if (isBlockedUrl(currentUrl) || controlledContent != null) return // nội dung bị quản lý → scanner xử lý cooldown
            // URL công việc bình thường trong cooldown → show popup nhập mục đích làm việc mới
            if (TimerService.isRunningFor(BROWSER_PKG)) return
            val now = System.currentTimeMillis()
            if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
            lastPopupTime = now
            popupShownForPkg = BROWSER_PKG
            DebugLog.add("✅ Work URL in cooldown → show work popup")
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

    // ── URL Scanner ───────────────────────────────────────────────────────────

    private fun scanBrowserUrl() {
        try {
            val root = rootInActiveWindow ?: return
            val url = extractUrlFromTree(root)
            val contentType = detectBrowserContent(root, url)
            root.recycle()

            if (url.isNullOrEmpty() && contentType == null) return
            val safeUrl = url ?: ""
            val isBlocked = isBlockedUrl(safeUrl)
            val isControlled = contentType != null
            lastBrowserContentType = contentType
            if (!isBlocked && !isControlled && safeUrl == lastScannedUrl) return
            if (safeUrl.isNotEmpty() && safeUrl != lastScannedUrl) {
                lastScannedUrl = safeUrl
                DebugLog.add("🌐 URL: $safeUrl | type=${contentType ?: "other"}")
            }

            // COOLDOWN CHECK TRƯỚC TIÊN — không cho bypass dù đang có session làm việc
            if ((isBlocked || isControlled) && CooldownState.isInCooldown()) {
                val now = System.currentTimeMillis()
                if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
                lastPopupTime = now
                // Dừng session làm việc nếu có
                if (TimerService.isRunningFor(BROWSER_PKG)) {
                    DebugLog.add("🔒 Cooldown: stop work session, block entertain url=$safeUrl")
                    TimerService.stop(this)
                } else {
                    DebugLog.add("🔒 COOLDOWN BLOCK! url=$safeUrl (${CooldownState.remainingMinutes()}p còn)")
                }
                handler.post { blockingOverlay.showCooldown(CooldownState.remainingMinutes()) }
                return
            }

            if (TimerService.isRunningFor(BROWSER_PKG)) {
                if (isBlocked || isControlled) {
                    val approvedDomain = TimerService.approvedDomain
                    if (approvedDomain.isNotEmpty()) {
                        DebugLog.add("✅ Blocked content allowed in session")
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                    if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
                    lastPopupTime = now
                    DebugLog.add("🚫 BLOCKED trong session! url=$safeUrl")
                    TimerService.stop(this)
                    popupShownForPkg = ""
                    handler.post {
                        blockingOverlay.show("⚠️ Nội dung bị chặn!", BROWSER_PKG, safeUrl.ifEmpty { contentType ?: "controlled_content" })
                    }
                }
                return
            }

            if (isBlocked || isControlled) {
                val now = System.currentTimeMillis()
                if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
                lastPopupTime = now
                DebugLog.add("🚫 BLOCKED! url=$safeUrl")
                popupShownForPkg = ""
                handler.post {
                    blockingOverlay.show("⚠️ Nội dung bị chặn!", BROWSER_PKG, safeUrl.ifEmpty { contentType ?: "controlled_content" })
                }
            }
        } catch (e: Exception) {
            DebugLog.add("❌ Scan error: ${e.message}")
        }
    }

    private fun detectBrowserContent(root: AccessibilityNodeInfo, url: String?): String? {
        DataStore.classifyBrowserContent(url.orEmpty())?.let { return it }
        val texts = mutableListOf<String>()
        collectAllText(root, texts, 0)
        val joined = texts.joinToString(" ").lowercase()
        return when {
            joined.contains("facebook") || joined.contains("reels") ||
                joined.contains("bảng tin") || joined.contains("news feed") -> "facebook"
            joined.contains("youtube") || joined.contains("shorts") -> "youtube"
            blockedKeywords.any { joined.contains(it) } -> "entertain"
            else -> null
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

    private fun extractDomain(url: String): String {
        return url.lowercase()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").split("/")[0].split("?")[0]
    }

    fun getLastScannedUrl(): String = lastScannedUrl
    fun getLastBrowserContentType(): String? = lastBrowserContentType

    fun resetPopupState(keepUrl: String = "") {
        popupShownForPkg = ""
        lastScannedUrl = if (keepUrl.isNotEmpty()) keepUrl else ""
        if (keepUrl.isEmpty()) lastBrowserContentType = null
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
