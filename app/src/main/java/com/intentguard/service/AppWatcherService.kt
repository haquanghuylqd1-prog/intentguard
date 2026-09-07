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

    fun isInCooldownFor(pkg: String): Boolean {
        if (!isInCooldown()) return false
        return cooldownPkg.isEmpty() || cooldownPkg == pkg
    }

    fun startCooldown(hours: Int = 1, pkg: String = "") {
        blockedUntilMs = System.currentTimeMillis() + hours * 3600_000L
        approvedUrl = ""
        cooldownPkg = pkg
        DebugLog.add("🔒 Cooldown bắt đầu - block ${hours}h cho $pkg")
    }

    fun clearCooldown() {
        blockedUntilMs = 0L
        approvedUrl = ""
        cooldownPkg = ""
        DebugLog.add("🔓 Cooldown đã được xoá")
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
    private val POPUP_COOLDOWN_MS = 1500L
    private var lastEntertainDetectTime = 0L
    private val ENTERTAIN_DETECT_COOLDOWN_MS = 5000L

    // FB Reels counter state
    private var lastReelSignature = ""
    private var lastReelCountTime = 0L

    lateinit var blockingOverlay: BlockingOverlayManager
    private val handler = Handler(Looper.getMainLooper())

    // Watchdog định kỳ chạy mỗi 500ms:
    // 1. Nếu app đang xem là watched app mà CHƯA có TimerService chạy và overlay chưa hiện -> hiện popup control ngay
    // 2. Nếu đang xem browser trong session -> quét URL và track continuous usage
    private val periodicWatchdogRunnable = object : Runnable {
        override fun run() {
            val pkg = lastForegroundPkg
            if (pkg.isNotEmpty() && DataStore.isWatchedApp(this@AppWatcherService, pkg)) {
                if (!TimerService.isRunningFor(pkg)) {
                    // Không có session chạy -> bắt buộc phải hiển thị overlay control hoặc cooldown
                    // Cho phép chạy khi: overlay chưa hiện, HOẶC overlay session-ended còn sót
                    // (session-ended sẽ bị dismiss và thay bằng control popup)
                    if (!::blockingOverlay.isInitialized ||
                        !blockingOverlay.isShowing ||
                        blockingOverlay.isSessionEndedShowing) {
                        checkAndShowOverlayForWatchedApp(pkg)
                    }
                } else {
                    // Đang trong session hợp lệ: nếu là browser thì scan URL và track liên tục
                    if (pkg == BROWSER_PKG) {
                        scanBrowserUrl()
                        trackBrowserContinuousUsage()
                    }
                }
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
        const val CHROME_PKG = "com.android.chrome"
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
        handler.postDelayed(periodicWatchdogRunnable, 500)
        handler.postDelayed(appContentScanRunnable, 2000)
        DebugLog.add("✅ Service connected")
    }

    // ── Continuous-usage guard (rule: >20 phút liên tục 1 loại nội dung → cooldown 1h) ──
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
        // Không dismiss overlay khi đang gõ phím
        if (isInputMethodPackage(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Khi về home launcher -> reset state để lần sau mở lại app sẽ trigger popup ngay
                if (pkg.contains("launcher", ignoreCase = true) ||
                    pkg == "com.samsung.android.app.resolver" ||
                    pkg == "com.sec.android.app.launcher") {
                    lastForegroundPkg = ""
                    popupShownForPkg = ""
                    // Nếu đang hiện intention popup của app trước mà user bấm home -> dismiss
                    if (::blockingOverlay.isInitialized && blockingOverlay.isShowing && !blockingOverlay.isSessionEndedShowing) {
                        blockingOverlay.dismiss()
                    }
                    return
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
                        !isInputMethodPackage(focusedPkg)) {
                        // Nếu focusedPkg đổi HOẶC là watched app mà không có timer chạy và chưa hiện overlay
                        val isWatched = DataStore.isWatchedApp(this, focusedPkg)
                        val needsOverlay = isWatched && !TimerService.isRunningFor(focusedPkg) &&
                                (!::blockingOverlay.isInitialized || !blockingOverlay.isShowing)
                        if (focusedPkg != lastForegroundPkg || needsOverlay) {
                            handleForegroundChange(focusedPkg)
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
        return when {
            pkg == "com.facebook.katana" || pkg == "com.facebook.lite" -> {
                val hasReels = text.contains("reels") || text.contains("reel")
                val hasPattern = (text.contains("âm thanh gốc") || text.contains("original audio")) &&
                    text.contains("theo dõi")
                if (hasReels) DebugLog.add("🎯 FB Reels detected")
                if (hasPattern) DebugLog.add("🎯 FB Reels pattern detected")
                hasReels || hasPattern
            }
            pkg == "com.google.android.youtube" -> {
                val hasShorts = text.contains("shorts")
                if (hasShorts) DebugLog.add("🎯 YT Shorts detected")
                hasShorts
            }
            else -> false
        }
    }

    private fun scanFacebookReels(pkg: String) {
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

            if (DataStore.getReelsCount(this) > REELS_LIMIT) {
                DataStore.setReelsCount(this, 0)
                lastReelSignature = ""
                DebugLog.add("🔄 Reels counter reset (cooldown đã hết)")
            }

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
            .filter { !it.matches(Regex("^[\\d.,kKmMtr\\s]+$")) }
            .distinct()
            .take(6)
            .joinToString("|")
    }

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

    // ── Foreground & Control Logic ────────────────────────────────────────────

    fun handleForegroundChange(pkg: String) {
        val isWatched = DataStore.isWatchedApp(this, pkg)

        // Dismiss intention overlay nếu người dùng chuyển hẳn sang app khác không bị theo dõi
        if (!isWatched && !isInputMethodPackage(pkg) && pkg != packageName) {
            if (::blockingOverlay.isInitialized && blockingOverlay.isShowing && !blockingOverlay.isSessionEndedShowing) {
                blockingOverlay.dismiss()
            }
        }

        val pkgChanged = (pkg != lastForegroundPkg)
        lastForegroundPkg = pkg

        if (pkgChanged) {
            DebugLog.add("📱 Foreground: $pkg")
        }

        if (!isWatched) return
        DataStore.checkAndRotateWeek(this)

        // Nếu là browser -> quét URL
        if (pkg == BROWSER_PKG) {
            lastScannedUrl = ""
            handler.postDelayed({ scanBrowserUrl() }, 300)
            handler.postDelayed({ scanBrowserUrl() }, 800)
        }

        // Nếu app đang trong session hợp lệ (timer đang chạy) -> KHÔNG chặn
        if (TimerService.isRunningFor(pkg)) {
            return
        }

        // Nếu app đang theo dõi và KHÔNG có timer chạy -> BẮT BUỘC hiển thị màn hình control
        checkAndShowOverlayForWatchedApp(pkg)
    }

    /**
     * Kiểm tra và hiển thị overlay chặn cho watched app khi chưa có session hoạt động.
     * Áp dụng thống nhất cho TẤT CẢ các app được theo dõi (Samsung Internet, Chrome, Facebook, YouTube...).
     */
    fun checkAndShowOverlayForWatchedApp(pkg: String) {
        if (!DataStore.isWatchedApp(this, pkg)) return
        if (TimerService.isRunningFor(pkg)) return
        // Cho phép tiếp tục nếu session-ended overlay còn sót (sẽ bị dismiss bên trong show/showCooldown)
        if (::blockingOverlay.isInitialized && blockingOverlay.isShowing && !blockingOverlay.isSessionEndedShowing) return

        val appName = DataStore.getAppName(this, pkg)

        // 1. Kiểm tra Cooldown: nếu app này đang trong cooldown thì hiển thị màn hình Cooldown
        if (CooldownState.isInCooldownFor(pkg)) {
            val now = System.currentTimeMillis()
            if (now - lastPopupTime < POPUP_COOLDOWN_MS && popupShownForPkg == pkg) return
            lastPopupTime = now
            popupShownForPkg = pkg
            DebugLog.add("🔒 Cooldown block cho $pkg (${CooldownState.remainingMinutes()}p còn)")
            handler.post {
                blockingOverlay.showCooldown(CooldownState.remainingMinutes())
            }
            return
        }

        // 2. Không trong Cooldown: Hiển thị màn hình Control để nhập mục đích và thời gian
        val now = System.currentTimeMillis()
        if (now - lastPopupTime < POPUP_COOLDOWN_MS && popupShownForPkg == pkg) return
        lastPopupTime = now
        popupShownForPkg = pkg
        DebugLog.add("🛑 Hiện màn hình control cho $pkg ($appName)")
        handler.post {
            blockingOverlay.show(appName, pkg, null)
        }
    }

    // ── URL Scanner (cho Samsung Internet) ────────────────────────────────────

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

            // Cooldown check trong khi duyệt web
            if ((isBlocked || isControlled) && CooldownState.isInCooldown()) {
                val now = System.currentTimeMillis()
                if (now - lastPopupTime < POPUP_COOLDOWN_MS) return
                if (::blockingOverlay.isInitialized && blockingOverlay.isShowing) return
                lastPopupTime = now
                if (TimerService.isRunningFor(BROWSER_PKG)) {
                    DebugLog.add("🔒 Cooldown: stop session, block entertain url=$safeUrl")
                    TimerService.stop(this)
                }
                handler.post { blockingOverlay.showCooldown(CooldownState.remainingMinutes()) }
                return
            }

            // Nếu đang trong session
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

            // Không có session nào đang chạy -> nếu gặp blocked/controlled content thì show popup cảnh báo
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

    fun getLastForegroundPkg(): String = lastForegroundPkg
    fun getLastScannedUrl(): String = lastScannedUrl
    fun getLastBrowserContentType(): String? = lastBrowserContentType

    fun resetPopupState(keepUrl: String = "") {
        popupShownForPkg = ""
        lastScannedUrl = if (keepUrl.isNotEmpty()) keepUrl else ""
        if (keepUrl.isEmpty()) lastBrowserContentType = null
        // Đặt lastPopupTime về 0 để sự kiện foreground kế tiếp được xử lý ngay lập tức
        lastPopupTime = 0L
    }

    fun showSessionEnded(appName: String, plannedMinutes: Int, actualMinutes: Int, intention: String, isEntertain: Boolean) {
        handler.post {
            if (::blockingOverlay.isInitialized) {
                blockingOverlay.showSessionEnded(appName, plannedMinutes, actualMinutes, intention, isEntertain)
            }
        }
    }

    fun showIntentionPopup(pkg: String, appName: String, blockedUrl: String? = null) {
        handler.post {
            if (::blockingOverlay.isInitialized) {
                blockingOverlay.show(appName, pkg, blockedUrl)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicWatchdogRunnable)
        handler.removeCallbacks(appContentScanRunnable)
        if (::blockingOverlay.isInitialized) blockingOverlay.dismiss()
        instance = null
        DebugLog.add("🔴 Service destroyed")
    }
}
