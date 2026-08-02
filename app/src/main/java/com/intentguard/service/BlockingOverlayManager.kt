package com.intentguard.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.intentguard.R
import com.intentguard.data.DataStore

class BlockingOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: android.view.View? = null
    private var selectedMinutes = 20

    // Params cho popup nhập intention — cho phép touch modal (cần nhập text)
    private val overlayParams get() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.CENTER }

    // Params cho session ended overlay — KHÔNG cho touch ra ngoài, KHÔNG cần focus
    private val sessionEndedParams get() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.OPAQUE
    ).apply { gravity = Gravity.CENTER }

    // Params cho cooldown overlay — CẦN focus để nhập URL (bàn phím hoạt động)
    private val cooldownParams get() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.CENTER
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
    }

    // Hiện popup nhập mục đích + thời gian (lần đầu vào browser hoặc bị chặn)
    fun show(appName: String, pkg: String, blockedUrl: String?) {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.activity_intention_popup, null)
        overlayView = view

        // Default: nếu là blocked URL → Giải trí, còn lại → Làm việc
        var isEntertain = blockedUrl != null

        view.findViewById<TextView>(R.id.tvAppName).apply {
            text = appName
            if (blockedUrl != null) setTextColor(Color.parseColor("#E53935"))
        }

        val budget = DataStore.getWeeklyBudgetMinutes(context)
        val used = DataStore.getThisWeekSessions(context).sumOf { it.actualMinutes }
        view.findViewById<TextView>(R.id.tvBudgetRemaining).text =
            DataStore.formatMinutes(maxOf(0, budget - used))

        val tvSelectedTime = view.findViewById<TextView>(R.id.tvSelectedTime)
        val tvSelectedType = view.findViewById<TextView>(R.id.tvSelectedType)
        val etIntention = view.findViewById<EditText>(R.id.etIntention)
        val etCustomTime = view.findViewById<EditText>(R.id.etCustomTime)
        val btnTypeWork = view.findViewById<Button>(R.id.btnTypeWork)
        val btnTypeEntertain = view.findViewById<Button>(R.id.btnTypeEntertain)

        if (blockedUrl != null) etIntention.hint = "Tại sao bro cần xem nội dung này?"

        // Session type selector
        fun updateType(entertain: Boolean) {
            isEntertain = entertain
            tvSelectedType.text = if (entertain) "Đã chọn: 🎮 Giải trí" else "Đã chọn: 💼 Làm việc"
            btnTypeWork.alpha = if (!entertain) 1f else 0.5f
            btnTypeEntertain.alpha = if (entertain) 1f else 0.5f
        }
        updateType(isEntertain)

        // Reels/Shorts detected → KHOÁ CỨNG loại 🎮 Giải trí, không cho chọn Làm việc
        val isReelsLock = blockedUrl == "reels_detected"
        if (isReelsLock) {
            updateType(true)
            tvSelectedType.text = "🎮 Giải trí (Reels/Shorts — không đổi được)"
            btnTypeWork.alpha = 0.3f
            btnTypeWork.setOnClickListener {
                Toast.makeText(context,
                    "Đang xem Reels/Shorts → chỉ tính là 🎮 Giải trí thôi bro!",
                    Toast.LENGTH_SHORT).show()
            }
            btnTypeEntertain.setOnClickListener { updateType(true) }
            etIntention.hint = "Bro định giải trí gì?"
        } else {
            btnTypeWork.setOnClickListener { updateType(false) }
            btnTypeEntertain.setOnClickListener { updateType(true) }
        }

        val presetMap = mapOf(
            R.id.btn5 to 5, R.id.btn10 to 10,
            R.id.btn20 to 20, R.id.btn30 to 30, R.id.btn60 to 60
        )

        fun updateSel(mins: Int) {
            selectedMinutes = mins.coerceIn(1, 60)
            tvSelectedTime.text = "Đã chọn: $selectedMinutes phút"
            presetMap.forEach { (id, m) ->
                view.findViewById<Button>(id).alpha = if (m == mins) 1f else 0.5f
            }
        }
        updateSel(20)

        presetMap.forEach { (id, mins) ->
            view.findViewById<Button>(id).setOnClickListener {
                updateSel(mins)
                etCustomTime.setText("")
            }
        }

        etCustomTime.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val n = s?.toString()?.trim()?.toIntOrNull()
                if (n != null && n in 1..60) {
                    selectedMinutes = n
                    tvSelectedTime.text = "Đã chọn: $selectedMinutes phút"
                    presetMap.forEach { (id, _) -> view.findViewById<Button>(id).alpha = 0.5f }
                }
            }
        })

        view.findViewById<Button>(R.id.btnStart).setOnClickListener {
            val intention = etIntention.text.toString().trim()
            if (intention.isEmpty()) {
                etIntention.error = "Nhập mục đích đi bro!"
                return@setOnClickListener
            }
            // Prefix theo loại session để DataStore phân biệt được
            val intentionText = when {
                blockedUrl != null -> "⚠️ $intention"
                isEntertain -> "🎮 $intention"
                else -> "💼 $intention"
            }
            val domain = if (blockedUrl != null) extractDomain(blockedUrl) else ""

            // Rule: session thứ 3 liên tiếp của cùng app, hoặc cùng loại nội dung web
            // facebook/youtube/giải trí → cooldown 1h, bất kể mục đích nhập có thay đổi.
            val trackingKey = if (pkg == AppWatcherService.BROWSER_PKG) {
                val currentUrl = blockedUrl ?: AppWatcherService.instance?.getLastScannedUrl() ?: ""
                val type = AppWatcherService.instance?.getLastBrowserContentType()
                    ?: DataStore.classifyBrowserContent(currentUrl)
                type?.let { "browser:$it" }
            } else pkg
            val blocked = trackingKey != null && (AppWatcherService.instance
                ?.checkRepeatedIntentionAndMaybeBlock(trackingKey, intention, pkg) ?: false)
            if (blocked) {
                dismiss()
                return@setOnClickListener
            }

            dismiss()
            AppWatcherService.instance?.resetPopupState(keepUrl = blockedUrl ?: "")
            TimerService.startFor(context, pkg, appName, intentionText, selectedMinutes, domain)
            DebugLog.add("▶️ Session: '$intentionText' ${selectedMinutes}p domain='$domain'")
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dismiss()
            context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        try {
            windowManager.addView(view, overlayParams)
            DebugLog.add("🛑 Overlay shown: $appName")
        } catch (e: Exception) {
            DebugLog.add("❌ Overlay error: ${e.message}")
            overlayView = null
        }
    }

    // Hiện overlay cooldown: block 1 giờ, có nút "Chuyển sang web khác"
    fun showCooldown(remainingMinutes: Int) {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_cooldown, null)
        overlayView = view

        view.findViewById<TextView>(R.id.tvCooldownTime).text =
            "Còn lại: ${remainingMinutes} phút"

        // Nút chuyển sang web khác
        view.findViewById<Button>(R.id.btnSwitchUrl).setOnClickListener {
            val etUrl = view.findViewById<EditText>(R.id.etWorkUrl)
            val rawUrl = etUrl.text.toString().trim()
            if (rawUrl.isEmpty()) {
                etUrl.error = "Nhập URL muốn chuyển sang"
                return@setOnClickListener
            }

            // Format URL đúng
            val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
            val domain = extractDomain(rawUrl)

            DebugLog.add("🔄 Chuyển sang URL: $fullUrl (domain=$domain)")

            // Approve domain này trong cooldown để scanner không block
            CooldownState.approveUrl(domain)

            // Reset scanner state — giữ URL hiện tại để không re-trigger cooldown
            AppWatcherService.instance?.resetPopupState(keepUrl = domain)

            dismiss()

            // Mở URL trong Samsung Internet
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fullUrl)).apply {
                setPackage("com.sec.android.app.sbrowser")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(browserIntent)
                DebugLog.add("✅ Opened $fullUrl in Samsung Internet")
            } catch (e: Exception) {
                // Fallback: mở bất kỳ browser nào
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fullUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                DebugLog.add("✅ Opened $fullUrl in default browser")
            }
        }

        // Nút về màn hình chính
        view.findViewById<Button>(R.id.btnGoHome).setOnClickListener {
            dismiss()
            context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        // Nút dùng app hiện tại để làm việc (cho FB/YT)
        view.findViewById<Button>(R.id.btnUseForWork).setOnClickListener {
            // Reset cooldown state để scanner không block
            AppWatcherService.instance?.resetPopupState()
            dismiss()
            // Show popup nhập mục đích làm việc bình thường
            // AppWatcherService sẽ tự detect và show popup vì popupShownForPkg đã reset
            DebugLog.add("💼 User chọn dùng app để làm việc trong cooldown")
        }

        val etUrl = view.findViewById<EditText>(R.id.etWorkUrl)

        try {
            windowManager.addView(view, cooldownParams)
            DebugLog.add("🔒 Cooldown overlay shown ($remainingMinutes p)")
            // Request focus để bàn phím hoạt động khi tap vào EditText
            Handler(Looper.getMainLooper()).postDelayed({
                etUrl.setOnClickListener {
                    etUrl.requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(etUrl, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                }
            }, 200)
        } catch (e: Exception) {
            DebugLog.add("❌ Cooldown overlay error: ${e.message}")
            overlayView = null
        }
    }

    // Hiện overlay "hết giờ" đè lên app — không thể tương tác với app phía dưới
    fun showSessionEnded(appName: String, plannedMinutes: Int, actualMinutes: Int,
                         intention: String, isEntertain: Boolean) {
        dismiss()
        val ctx = context

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
        }

        inner.addView(TextView(ctx).apply {
            text = if (isEntertain) "⏰" else "✅"
            textSize = 48f; gravity = android.view.Gravity.CENTER
        })
        inner.addView(TextView(ctx).apply {
            text = if (isEntertain) "Hết giờ giải trí rồi bro!" else "Session kết thúc!"
            textSize = 20f; gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(8) }
        })

        // Stats box
        val statsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#F3E5F5"))
                cornerRadius = dpToPx(8).toFloat()
            }
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(16) }
        }
        val cleanIntention = intention.removePrefix("⚠️ ").removePrefix("🎮 ").removePrefix("💼 ").trim()
        statsBox.addView(TextView(ctx).apply {
            text = "📝 $cleanIntention"
            textSize = 13f; gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#6A1B9A"))
        })
        statsBox.addView(TextView(ctx).apply {
            text = "Dự kiến: ${plannedMinutes}p  •  Thực tế: ${actualMinutes}p"
            textSize = 12f; gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#7E57C2"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(4) }
        })
        inner.addView(statsBox)

        if (isEntertain) {
            inner.addView(TextView(ctx).apply {
                text = "Browser bị block 1 giờ tiếp theo.\nHãy tập trung làm việc nhé! 💪"
                textSize = 13f; gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#757575"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dpToPx(12) }
            })
        }

        val btnHome = Button(ctx).apply {
            text = "Về màn hình chính"
            setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(52)
            ).also { it.topMargin = dpToPx(20) }
            setOnClickListener {
                dismiss()
                ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        }
        inner.addView(btnHome)

        // Wrap inner trong card
        val card = android.widget.FrameLayout(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = dpToPx(16).toFloat()
            }
            addView(inner)
        }

        val root = LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#E6000000"))
            setPadding(dpToPx(24), 0, dpToPx(24), 0)
            addView(card)
        }
        overlayView = root

        // Tự động chuyển về màn hình chính ngay khi hết giờ
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })

        try {
            windowManager.addView(root, sessionEndedParams)
            DebugLog.add("⏰ Session ended overlay shown")
        } catch (e: Exception) {
            DebugLog.add("❌ SessionEnded overlay error: ${e.message}")
            overlayView = null
        }
    }

    private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    fun dismiss() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    val isShowing get() = overlayView != null

    private fun extractDomain(url: String): String {
        return url.lowercase()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.")
            .split("/")[0]
            .split("?")[0]
    }
}
