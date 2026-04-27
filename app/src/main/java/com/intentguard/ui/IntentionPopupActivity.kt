package com.intentguard.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.intentguard.R
import com.intentguard.data.DataStore
import com.intentguard.service.TimerService

class IntentionPopupActivity : AppCompatActivity() {

    private var selectedMinutes = 20
    private lateinit var pkg: String
    private lateinit var appName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intention_popup)
        pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        appName = intent.getStringExtra(EXTRA_APP_NAME) ?: pkg
        val blockedUrl = intent.getStringExtra(EXTRA_BLOCKED_URL)
        setupUI(blockedUrl)
    }

    private fun setupUI(blockedUrl: String?) {
        val tvAppName = findViewById<TextView>(R.id.tvAppName)
        val tvBudgetRemaining = findViewById<TextView>(R.id.tvBudgetRemaining)
        val tvSelectedTime = findViewById<TextView>(R.id.tvSelectedTime)
        val etIntention = findViewById<EditText>(R.id.etIntention)
        val etCustomTime = findViewById<EditText>(R.id.etCustomTime)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnCancel = findViewById<Button>(R.id.btnCancel)
        val btn5 = findViewById<Button>(R.id.btn5)
        val btn10 = findViewById<Button>(R.id.btn10)
        val btn20 = findViewById<Button>(R.id.btn20)
        val btn30 = findViewById<Button>(R.id.btn30)
        val btn60 = findViewById<Button>(R.id.btn60)

        tvAppName.text = appName

        // Nếu là blocked URL, đổi màu header thành đỏ để cảnh báo
        if (blockedUrl != null) {
            tvAppName.setTextColor(Color.parseColor("#E53935"))
            etIntention.hint = "Tại sao bro cần xem nội dung này?"
        }

        // Budget remaining
        val budgetTotal = DataStore.getWeeklyBudgetMinutes(this)
        val usedThisWeek = DataStore.getThisWeekSessions(this).sumOf { it.actualMinutes }
        val remaining = maxOf(0, budgetTotal - usedThisWeek)
        findViewById<TextView>(R.id.tvBudgetRemaining).text = DataStore.formatMinutes(remaining)

        // Preset time buttons
        val presetButtons = listOf(btn5 to 5, btn10 to 10, btn20 to 20, btn30 to 30, btn60 to 60)

        fun updateSelection(mins: Int, fromCustom: Boolean = false) {
            selectedMinutes = mins.coerceIn(1, 60)
            tvSelectedTime.text = "Đã chọn: $selectedMinutes phút"
            presetButtons.forEach { (btn, m) ->
                btn.alpha = if (m == mins) 1f else 0.5f
            }
            if (!fromCustom) etCustomTime.setText("")
        }
        updateSelection(20)

        presetButtons.forEach { (btn, mins) ->
            btn.setOnClickListener { updateSelection(mins) }
        }

        // Custom time
        etCustomTime.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val input = s?.toString()?.trim()?.toIntOrNull()
                if (input != null && input in 1..60) {
                    selectedMinutes = input
                    tvSelectedTime.text = "Đã chọn: $selectedMinutes phút"
                    presetButtons.forEach { (btn, _) -> btn.alpha = 0.5f }
                }
            }
        })

        // Start
        btnStart.setOnClickListener {
            val intention = etIntention.text.toString().trim()
            if (intention.isEmpty()) {
                etIntention.error = "Nhập mục đích đi bro!"
                etIntention.requestFocus()
                return@setOnClickListener
            }
            val intentionText = if (blockedUrl != null) "⚠️ $intention" else intention
            TimerService.startFor(this, pkg, appName, intentionText, selectedMinutes)
            finish()
        }

        // Cancel → go home
        btnCancel.setOnClickListener {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            finish()
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_BLOCKED_URL = "extra_blocked_url"
    }
}
