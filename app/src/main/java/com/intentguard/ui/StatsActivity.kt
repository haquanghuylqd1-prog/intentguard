package com.intentguard.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.intentguard.R
import com.intentguard.data.DataStore
import com.intentguard.data.FirebaseSync
import kotlinx.coroutines.launch
import java.util.*

class StatsActivity : AppCompatActivity() {

    private var displayYear = 0
    private var displayMonth = 0  // 0-based (Calendar.JANUARY = 0)
    private var entertainTargetMinutes = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        val now = Calendar.getInstance()
        displayYear = now.get(Calendar.YEAR)
        displayMonth = now.get(Calendar.MONTH)
        entertainTargetMinutes = DataStore.getEntertainTargetMinutes(this)

        setupHeader()
        setupTargetInput()
        renderCalendar()
        renderWeekSummary()
        syncFromFirebase()
    }

    private fun setupHeader() {
        updateMonthLabel()
        findViewById<ImageButton>(R.id.btnPrevMonth).setOnClickListener {
            if (displayMonth == 0) { displayMonth = 11; displayYear-- }
            else displayMonth--
            updateMonthLabel()
            renderCalendar()
            renderWeekSummary()
        }
        findViewById<ImageButton>(R.id.btnNextMonth).setOnClickListener {
            if (displayMonth == 11) { displayMonth = 0; displayYear++ }
            else displayMonth++
            updateMonthLabel()
            renderCalendar()
            renderWeekSummary()
        }
    }

    private fun updateMonthLabel() {
        val monthNames = arrayOf("Tháng 1","Tháng 2","Tháng 3","Tháng 4","Tháng 5","Tháng 6",
            "Tháng 7","Tháng 8","Tháng 9","Tháng 10","Tháng 11","Tháng 12")
        findViewById<TextView>(R.id.tvMonthYear).text = "${monthNames[displayMonth]} $displayYear"
    }

    private fun setupTargetInput() {
        val etTarget = findViewById<EditText>(R.id.etEntertainTarget)
        etTarget.setText(entertainTargetMinutes.toString())
        findViewById<Button>(R.id.btnSaveTarget).setOnClickListener {
            val v = etTarget.text.toString().trim().toIntOrNull()
            if (v != null && v > 0) {
                entertainTargetMinutes = v
                DataStore.setEntertainTargetMinutes(this, v)
                renderCalendar()
                renderWeekSummary()
                Toast.makeText(this, "Đã lưu target: ${v}p/ngày", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch { FirebaseSync.pushSettings(this@StatsActivity) }
            }
        }
    }

    private fun renderCalendar() {
        val grid = findViewById<GridLayout>(R.id.calendarGrid)
        grid.removeAllViews()
        grid.columnCount = 7

        // Header: T2 T3 T4 T5 T6 T7 CN
        val dayHeaders = arrayOf("T2","T3","T4","T5","T6","T7","CN")
        dayHeaders.forEach { label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(if (label == "CN") Color.parseColor("#EF5350") else Color.parseColor("#90CAF9"))
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = dpToPx(36)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            }
            grid.addView(tv)
        }

        // Calendar cells
        val cal = Calendar.getInstance()
        cal.set(displayYear, displayMonth, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun, 2=Mon...
        val offset = if (firstDayOfWeek == Calendar.SUNDAY) 6 else firstDayOfWeek - 2
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val today = Calendar.getInstance()
        val isCurrentMonth = today.get(Calendar.YEAR) == displayYear && today.get(Calendar.MONTH) == displayMonth
        val todayDay = today.get(Calendar.DAY_OF_MONTH)

        // Empty cells for offset
        repeat(offset) {
            val empty = View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = dpToPx(56)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            }
            grid.addView(empty)
        }

        // Ngày bắt đầu track: 27/4/2026
        val trackingStartCal = Calendar.getInstance()
        trackingStartCal.set(2026, Calendar.APRIL, 27, 0, 0, 0)
        trackingStartCal.set(Calendar.MILLISECOND, 0)
        val trackingStartMs = trackingStartCal.timeInMillis

        // Day cells
        for (day in 1..daysInMonth) {
            val dayStart = DataStore.dayStartMs(displayYear, displayMonth, day)
            val entertainMins = DataStore.getEntertainMinutesForDay(this, dayStart)
            val isFuture = if (isCurrentMonth) day > todayDay else
                (displayYear > today.get(Calendar.YEAR) ||
                 (displayYear == today.get(Calendar.YEAR) && displayMonth > today.get(Calendar.MONTH)))
            // Ngày trước tracking start → hiện số ngày nhưng không hiện tick
            val isBeforeTracking = dayStart < trackingStartMs
            val isToday = isCurrentMonth && day == todayDay

            val cell = makeDayCell(day, entertainMins, isFuture || isBeforeTracking, isToday)
            grid.addView(cell)
        }
    }

    private fun makeDayCell(
        day: Int, entertainMins: Int, isFuture: Boolean, isToday: Boolean
    ): LinearLayout {
        val target = entertainTargetMinutes
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0; height = dpToPx(64)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(2, 2, 2, 2)
            }
        }

        // Background
        if (isToday) {
            cell.setBackgroundColor(Color.parseColor("#1A90CAF9"))
            cell.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1A90CAF9"))
                cornerRadius = dpToPx(8).toFloat()
            }
        }

        // Day number
        val tvDay = TextView(this).apply {
            text = day.toString()
            textSize = 13f
            setTextColor(if (isToday) Color.parseColor("#90CAF9") else Color.parseColor("#B0BEC5"))
            gravity = Gravity.CENTER
        }
        cell.addView(tvDay)

        if (isFuture) {
            cell.addView(spacer(4))
            return cell
        }

        // Status icon + background based on entertainment time vs target
        cell.addView(spacer(2))

        val (symbol, mainColor, bgColor, showPlus) = when {
            entertainMins == 0 -> DayStatus("✓", "#1E88E5", "#0D1E88E5", true)   // 🔵+ no entertain
            entertainMins < target / 3 -> DayStatus("✓", "#1E88E5", null, false)  // 🔵 very low
            entertainMins < (target * 2) / 3 -> DayStatus("✓", "#43A047", null, true)  // 🟢+
            entertainMins <= target -> DayStatus("✓", "#43A047", null, false)          // 🟢
            else -> DayStatus("✗", "#E53935", "#1AE53935", false)                      // 🔴✗
        }

        if (bgColor != null) {
            cell.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = dpToPx(8).toFloat()
            }
        }

        // Icon row
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val tvIcon = TextView(this).apply {
            text = symbol
            textSize = 16f
            setTextColor(Color.parseColor(mainColor))
            gravity = Gravity.CENTER
        }
        iconRow.addView(tvIcon)

        if (showPlus) {
            val tvPlus = TextView(this).apply {
                text = "+"
                textSize = 10f
                setTextColor(Color.parseColor(mainColor))
                gravity = Gravity.TOP
            }
            iconRow.addView(tvPlus)
        }
        cell.addView(iconRow)

        // Minutes label (if has entertain time)
        if (entertainMins > 0) {
            val tvMins = TextView(this).apply {
                text = if (entertainMins >= 60) "${entertainMins/60}h${entertainMins%60}p" else "${entertainMins}p"
                textSize = 9f
                setTextColor(Color.parseColor(mainColor))
                gravity = Gravity.CENTER
            }
            cell.addView(tvMins)
        }

        return cell
    }

    private data class DayStatus(
        val symbol: String,
        val mainColor: String,
        val bgColor: String?,
        val showPlus: Boolean
    )

    private fun renderWeekSummary() {
        val llWeeks = findViewById<LinearLayout>(R.id.llWeekSummary)
        llWeeks.removeAllViews()

        // Tính các tuần trong tháng
        val cal = Calendar.getInstance()
        cal.set(displayYear, displayMonth, 1)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val today = Calendar.getInstance()

        // Group days by week
        var currentWeekStart = 1
        val weeks = mutableListOf<Pair<Int,Int>>() // start day, end day of month

        for (day in 1..daysInMonth) {
            cal.set(displayYear, displayMonth, day)
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SUNDAY || day == daysInMonth) {
                weeks.add(Pair(currentWeekStart, day))
                currentWeekStart = day + 1
            }
        }

        weeks.forEachIndexed { idx, (startDay, endDay) ->
            var totalEntertain = 0
            var workDays = 0
            var hasData = false

            for (day in startDay..endDay) {
                val isCurrentMonth = today.get(Calendar.YEAR) == displayYear && today.get(Calendar.MONTH) == displayMonth
                val isFuture = if (isCurrentMonth) day > today.get(Calendar.DAY_OF_MONTH)
                    else (displayYear > today.get(Calendar.YEAR) || (displayYear == today.get(Calendar.YEAR) && displayMonth > today.get(Calendar.MONTH)))
                if (isFuture) continue

                val dayStart = DataStore.dayStartMs(displayYear, displayMonth, day)
                val eMins = DataStore.getEntertainMinutesForDay(this, dayStart)
                totalEntertain += eMins
                if (eMins == 0) workDays++
                hasData = true
            }

            if (!hasData) return@forEachIndexed

            val weekDays = endDay - startDay + 1
            val targetTotal = entertainTargetMinutes * weekDays
            val row = makeWeekRow(idx + 1, startDay, endDay, totalEntertain, targetTotal, workDays, weekDays)
            llWeeks.addView(row)
        }
    }

    private fun makeWeekRow(
        weekNum: Int, startDay: Int, endDay: Int,
        entertainMins: Int, targetMins: Int, workDays: Int, totalDays: Int
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            setBackgroundColor(Color.parseColor(
                if (entertainMins > targetMins) "#1AE53935" else "#0D43A047"
            ))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dpToPx(4), 0, 0)
            layoutParams = lp
        }

        // Week label
        val tvWeek = TextView(this).apply {
            text = "Tuần $weekNum\n$startDay-${endDay}/${displayMonth+1}"
            textSize = 12f
            setTextColor(Color.parseColor("#90CAF9"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        }
        row.addView(tvWeek)

        // Entertain time
        val entertainStr = if (entertainMins >= 60) "${entertainMins/60}h${entertainMins%60}p" else "${entertainMins}p"
        val targetStr = if (targetMins >= 60) "${targetMins/60}h${targetMins%60}p" else "${targetMins}p"
        val tvEntertain = TextView(this).apply {
            text = "Giải trí: $entertainStr\nTarget: $targetStr"
            textSize = 12f
            setTextColor(if (entertainMins > targetMins) Color.parseColor("#EF5350") else Color.parseColor("#81C784"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        row.addView(tvEntertain)

        // Work days
        val tvWork = TextView(this).apply {
            text = "Sạch:\n$workDays/$totalDays ngày"
            textSize = 12f
            setTextColor(Color.parseColor("#90CAF9"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tvWork)

        return row
    }

    private fun syncFromFirebase() {
        val tvSync = TextView(this).apply {
            text = "⏳ Đang sync data từ Firebase..."
            textSize = 12f
            setTextColor(Color.parseColor("#90CAF9"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        findViewById<LinearLayout>(R.id.llWeekSummary).addView(tvSync)

        lifecycleScope.launch {
            try {
                val signedIn = FirebaseSync.ensureSignedIn()
                if (signedIn) {
                    val uid = FirebaseSync.uid() ?: "null"
                    android.util.Log.d("IntentGuard", "Firebase UID: $uid")

                    FirebaseSync.pullSettings(this@StatsActivity)
                    entertainTargetMinutes = DataStore.getEntertainTargetMinutes(this@StatsActivity)

                    val pulled = FirebaseSync.pullSessions(this@StatsActivity)
                    android.util.Log.d("IntentGuard", "Pulled sessions: $pulled")

                    tvSync.text = "✅ Đã sync $pulled sessions từ Firebase"
                    renderCalendar()
                    renderWeekSummary()
                } else {
                    tvSync.text = "❌ Firebase sign-in failed"
                }
            } catch (e: Exception) {
                tvSync.text = "❌ Sync error: ${e.message}"
                android.util.Log.e("IntentGuard", "Sync error: ${e.message}")
            }
        }
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(heightDp))
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
