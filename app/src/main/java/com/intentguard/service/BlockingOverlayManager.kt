package com.intentguard.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.intentguard.R
import com.intentguard.data.DataStore

/**
 * Shows a full-screen blocking overlay using WindowManager.
 * This works even when another app (Samsung Internet) is in foreground,
 * bypassing Android 12+ background activity start restrictions.
 */
class BlockingOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: android.view.View? = null
    private var selectedMinutes = 20

    fun show(appName: String, pkg: String, blockedUrl: String?) {
        if (overlayView != null) return // already showing

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.activity_intention_popup, null)
        overlayView = view

        // Setup UI
        view.findViewById<TextView>(R.id.tvAppName).apply {
            text = appName
            if (blockedUrl != null) setTextColor(android.graphics.Color.parseColor("#E53935"))
        }

        val budget = DataStore.getWeeklyBudgetMinutes(context)
        val used = DataStore.getThisWeekSessions(context).sumOf { it.actualMinutes }
        view.findViewById<TextView>(R.id.tvBudgetRemaining).text =
            DataStore.formatMinutes(maxOf(0, budget - used))

        val tvSelectedTime = view.findViewById<TextView>(R.id.tvSelectedTime)
        val etIntention = view.findViewById<EditText>(R.id.etIntention)
        val etCustomTime = view.findViewById<EditText>(R.id.etCustomTime)

        if (blockedUrl != null) {
            etIntention.hint = "Tại sao bro cần xem nội dung này?"
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

        // Start button
        view.findViewById<Button>(R.id.btnStart).setOnClickListener {
            val intention = etIntention.text.toString().trim()
            if (intention.isEmpty()) {
                etIntention.error = "Nhập mục đích đi bro!"
                return@setOnClickListener
            }
            val intentionText = if (blockedUrl != null) "⚠️ $intention" else intention
            dismiss()
            TimerService.startFor(context, pkg, appName, intentionText, selectedMinutes)
        }

        // Cancel → go home
        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dismiss()
            context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        // Add to window manager as full-screen overlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(view, params)
            DebugLog.add("🛑 Overlay shown for $appName")
        } catch (e: Exception) {
            DebugLog.add("❌ Overlay error: ${e.message}")
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
}
