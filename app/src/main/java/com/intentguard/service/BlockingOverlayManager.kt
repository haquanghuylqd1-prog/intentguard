package com.intentguard.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.intentguard.R
import com.intentguard.data.DataStore

class BlockingOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: android.view.View? = null
    private var selectedMinutes = 20

    private val overlayParams get() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.CENTER }

    // Hiện popup nhập mục đích + thời gian (lần đầu vào browser hoặc bị chặn)
    fun show(appName: String, pkg: String, blockedUrl: String?) {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.activity_intention_popup, null)
        overlayView = view

        view.findViewById<TextView>(R.id.tvAppName).apply {
            text = appName
            if (blockedUrl != null) setTextColor(Color.parseColor("#E53935"))
        }

        val budget = DataStore.getWeeklyBudgetMinutes(context)
        val used = DataStore.getThisWeekSessions(context).sumOf { it.actualMinutes }
        view.findViewById<TextView>(R.id.tvBudgetRemaining).text =
            DataStore.formatMinutes(maxOf(0, budget - used))

        val tvSelectedTime = view.findViewById<TextView>(R.id.tvSelectedTime)
        val etIntention = view.findViewById<EditText>(R.id.etIntention)
        val etCustomTime = view.findViewById<EditText>(R.id.etCustomTime)

        if (blockedUrl != null) etIntention.hint = "Tại sao bro cần xem nội dung này?"

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
            val intentionText = if (blockedUrl != null) "⚠️ $intention" else intention
            val domain = if (blockedUrl != null) extractDomain(blockedUrl) else ""
            dismiss()
            // Set lastScannedUrl = blocked URL để scanner không re-trigger ngay
            AppWatcherService.instance?.resetPopupState(keepUrl = blockedUrl ?: "")
            TimerService.startFor(context, pkg, appName, intentionText, selectedMinutes, domain)
            DebugLog.add("▶️ Session started: '$intentionText' ${selectedMinutes}p domain='$domain'")
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
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) {
                etUrl.error = "Nhập URL muốn chuyển sang"
                return@setOnClickListener
            }
            // Approve URL này để scanner không block
            CooldownState.approveUrl(extractDomain(url))
            dismiss()
            AppWatcherService.instance?.resetPopupState()
            DebugLog.add("🔄 Chuyển sang URL: $url")
        }

        // Nút về màn hình chính
        view.findViewById<Button>(R.id.btnGoHome).setOnClickListener {
            dismiss()
            context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        try {
            windowManager.addView(view, overlayParams)
            DebugLog.add("🔒 Cooldown overlay shown ($remainingMinutes p)")
        } catch (e: Exception) {
            DebugLog.add("❌ Cooldown overlay error: ${e.message}")
            overlayView = null
        }
    }

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
