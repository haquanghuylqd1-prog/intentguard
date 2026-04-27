package com.intentguard.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.intentguard.R
import com.intentguard.data.DataStore
import com.intentguard.service.DebugLog
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        refreshPermissionStatus()
    }

    private fun setupUI() {
        // Permission buttons
        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnEnableOverlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // Budget edit
        findViewById<Button>(R.id.btnEditBudget).setOnClickListener {
            showNumberPickerDialog(
                "Budget tuần (phút)",
                DataStore.getWeeklyBudgetMinutes(this),
                30, 600, 15
            ) { minutes ->
                DataStore.setInitialWeeklyBudget(this, minutes)
                refreshStats()
            }
        }

        // Reduction edit
        findViewById<Button>(R.id.btnEditReduction).setOnClickListener {
            showNumberPickerDialog(
                "Giảm mỗi tuần (phút)",
                DataStore.getWeeklyReductionMinutes(this),
                0, 60, 5
            ) { minutes ->
                DataStore.setWeeklyReductionMinutes(this, minutes)
                refreshStats()
            }
        }

        // Debug log buttons
        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { refreshDebugLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            DebugLog.clear()
            refreshDebugLog()
        }
    }

    private fun refreshDebugLog() {
        val logs = DebugLog.logs
        val tv = findViewById<TextView>(R.id.tvDebugLog)
        if (logs.isEmpty()) {
            tv.text = "(chưa có log — mở Samsung Internet rồi quay lại bấm Refresh)"
        } else {
            tv.text = logs.take(30).joinToString("\n")
        }
    }

    private fun refreshStats() {
        val budget = DataStore.getWeeklyBudgetMinutes(this)
        val weekSessions = DataStore.getThisWeekSessions(this)
        val usedMinutes = weekSessions.sumOf { it.actualMinutes }
        val remaining = maxOf(0, budget - usedMinutes)
        val progress = if (budget > 0) (usedMinutes * 100 / budget).coerceAtMost(100) else 0

        findViewById<TextView>(R.id.tvUsedTime).text = DataStore.formatMinutes(usedMinutes)
        findViewById<TextView>(R.id.tvRemainingTime).text = DataStore.formatMinutes(remaining)
        findViewById<TextView>(R.id.tvSessionCount).text = weekSessions.size.toString()
        findViewById<TextView>(R.id.tvBudgetLabel).text = "Budget tuần: ${DataStore.formatMinutes(budget)}"
        findViewById<ProgressBar>(R.id.progressBudget).progress = progress
        findViewById<TextView>(R.id.tvCurrentBudget).text = DataStore.formatMinutes(budget)
        findViewById<TextView>(R.id.tvReduction).text = "${DataStore.getWeeklyReductionMinutes(this)}p"

        // Recent sessions list
        val llSessions = findViewById<LinearLayout>(R.id.llSessions)
        llSessions.removeAllViews()
        val recentSessions = DataStore.getSessions(this).take(10)
        if (recentSessions.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Chưa có session nào"
                textSize = 13f
                setTextColor(Color.parseColor("#9E9E9E"))
            }
            llSessions.addView(tv)
        } else {
            val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            recentSessions.forEach { session ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 8, 0, 8)
                }
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val appTv = TextView(this).apply {
                    text = "📱 ${session.appName}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#212121"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val timeTv = TextView(this).apply {
                    text = "${session.actualMinutes}p • ${sdf.format(Date(session.startTime))}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#757575"))
                }
                header.addView(appTv)
                header.addView(timeTv)

                val intentionTv = TextView(this).apply {
                    text = "→ ${session.intention}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#9C27B0"))
                    setPadding(0, 2, 0, 0)
                }

                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.topMargin = 8 }
                    setBackgroundColor(Color.parseColor("#E0E0E0"))
                }

                row.addView(header)
                row.addView(intentionTv)
                row.addView(divider)
                llSessions.addView(row)
            }
        }
    }

    private fun refreshPermissionStatus() {
        val accessibilityOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)

        val accessTv = findViewById<TextView>(R.id.tvAccessibilityStatus)
        val overlayTv = findViewById<TextView>(R.id.tvOverlayStatus)
        val btnAccess = findViewById<Button>(R.id.btnEnableAccessibility)
        val btnOverlay = findViewById<Button>(R.id.btnEnableOverlay)

        if (accessibilityOk) {
            accessTv.text = "✓"
            accessTv.setTextColor(Color.parseColor("#43A047"))
            btnAccess.text = "OK"
            btnAccess.isEnabled = false
        } else {
            accessTv.text = "✗"
            accessTv.setTextColor(Color.parseColor("#E53935"))
            btnAccess.text = "Bật"
            btnAccess.isEnabled = true
        }

        if (overlayOk) {
            overlayTv.text = "✓"
            overlayTv.setTextColor(Color.parseColor("#43A047"))
            btnOverlay.text = "OK"
            btnOverlay.isEnabled = false
        } else {
            overlayTv.text = "✗"
            overlayTv.setTextColor(Color.parseColor("#E53935"))
            btnOverlay.text = "Bật"
            btnOverlay.isEnabled = true
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun showNumberPickerDialog(
        title: String, current: Int, min: Int, max: Int, step: Int,
        onConfirm: (Int) -> Unit
    ) {
        val picker = NumberPicker(this).apply {
            val values = (min..max step step).toList()
            val displayValues = values.map {
                if (it >= 60) "${it / 60}h${if (it % 60 > 0) "${it % 60}p" else ""}" else "${it}p"
            }.toTypedArray()
            minValue = 0
            maxValue = displayValues.size - 1
            displayedValues = displayValues
            value = values.indexOfFirst { it >= current }.coerceAtLeast(0)
            wrapSelectorWheel = false
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(picker)
            .setPositiveButton("Xác nhận") { _, _ ->
                val values = (min..max step step).toList()
                onConfirm(values[picker.value])
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }
}
