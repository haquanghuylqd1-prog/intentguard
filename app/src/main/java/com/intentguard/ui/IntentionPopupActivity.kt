package com.intentguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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

        setupUI()
    }

    private fun setupUI() {
        val tvAppName = findViewById<TextView>(R.id.tvAppName)
        val tvBudgetRemaining = findViewById<TextView>(R.id.tvBudgetRemaining)
        val tvSelectedTime = findViewById<TextView>(R.id.tvSelectedTime)
        val etIntention = findViewById<EditText>(R.id.etIntention)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnCancel = findViewById<Button>(R.id.btnCancel)
        val btn10 = findViewById<Button>(R.id.btn10)
        val btn20 = findViewById<Button>(R.id.btn20)
        val btn30 = findViewById<Button>(R.id.btn30)
        val btn60 = findViewById<Button>(R.id.btn60)

        tvAppName.text = appName

        // Show remaining weekly budget
        val budgetTotal = DataStore.getWeeklyBudgetMinutes(this)
        val usedThisWeek = DataStore.getThisWeekSessions(this).sumOf { it.actualMinutes }
        val remaining = maxOf(0, budgetTotal - usedThisWeek)
        tvBudgetRemaining.text = DataStore.formatMinutes(remaining)

        // Time selection
        val timeButtons = listOf(btn10 to 10, btn20 to 20, btn30 to 30, btn60 to 60)
        fun updateSelection(mins: Int) {
            selectedMinutes = mins
            tvSelectedTime.text = "Đã chọn: $mins phút"
            timeButtons.forEach { (btn, m) ->
                btn.isSelected = (m == mins)
                btn.alpha = if (m == mins) 1f else 0.5f
            }
        }
        updateSelection(20) // default 20p

        timeButtons.forEach { (btn, mins) ->
            btn.setOnClickListener { updateSelection(mins) }
        }

        // Start session
        btnStart.setOnClickListener {
            val intention = etIntention.text.toString().trim()
            if (intention.isEmpty()) {
                etIntention.error = "Nhập mục đích đi bro!"
                return@setOnClickListener
            }

            TimerService.startFor(this, pkg, appName, intention, selectedMinutes)
            finish()
        }

        // Cancel - go back (don't open the app)
        btnCancel.setOnClickListener {
            // Press home to avoid landing on the watched app
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            finish()
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_APP_NAME = "extra_app_name"
    }
}
