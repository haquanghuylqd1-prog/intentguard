package com.intentguard.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class Session(
    val id: String,
    val appPackage: String,
    val appName: String,
    val intention: String,
    val plannedMinutes: Int,
    val startTime: Long,
    val endTime: Long = 0L,
    val actualMinutes: Int = 0
)

data class AppConfig(
    val packageName: String,
    val appName: String,
    val isEnabled: Boolean = true
)

object DataStore {
    private const val PREF_NAME = "intentguard_data"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_WATCHED_APPS = "watched_apps"
    private const val KEY_WEEKLY_BUDGET_MINUTES = "weekly_budget_minutes"
    private const val KEY_WEEK_START = "week_start"
    private const val KEY_INITIAL_WEEKLY_BUDGET = "initial_weekly_budget"
    private const val KEY_WEEKLY_REDUCTION_MINUTES = "weekly_reduction_minutes"

    // Default watched apps
    val DEFAULT_APPS = listOf(
        AppConfig("com.facebook.katana", "Facebook"),
        AppConfig("com.facebook.lite", "Facebook Lite"),
        AppConfig("com.google.android.youtube", "YouTube"),
        AppConfig("com.sec.android.app.sbrowser", "Samsung Internet"),
        AppConfig("com.android.chrome", "Chrome")
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Sessions ──────────────────────────────────────────────────────────────

    fun getSessions(context: Context): List<Session> {
        val json = prefs(context).getString(KEY_SESSIONS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<Session>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                Session(
                    id = obj.getString("id"),
                    appPackage = obj.getString("appPackage"),
                    appName = obj.getString("appName"),
                    intention = obj.getString("intention"),
                    plannedMinutes = obj.getInt("plannedMinutes"),
                    startTime = obj.getLong("startTime"),
                    endTime = obj.optLong("endTime", 0L),
                    actualMinutes = obj.optInt("actualMinutes", 0)
                )
            )
        }
        return list.sortedByDescending { it.startTime }
    }

    fun saveSession(context: Context, session: Session) {
        val sessions = getSessions(context).toMutableList()
        val existing = sessions.indexOfFirst { it.id == session.id }
        if (existing >= 0) sessions[existing] = session else sessions.add(0, session)
        // Keep last 200 sessions only
        val trimmed = sessions.take(200)
        val arr = JSONArray()
        trimmed.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("appPackage", s.appPackage)
                put("appName", s.appName)
                put("intention", s.intention)
                put("plannedMinutes", s.plannedMinutes)
                put("startTime", s.startTime)
                put("endTime", s.endTime)
                put("actualMinutes", s.actualMinutes)
            })
        }
        prefs(context).edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    fun getTodaySessions(context: Context): List<Session> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        return getSessions(context).filter { it.startTime >= startOfDay && it.endTime > 0 }
    }

    fun getThisWeekSessions(context: Context): List<Session> {
        val weekStart = getWeekStartTime(context)
        return getSessions(context).filter { it.startTime >= weekStart && it.endTime > 0 }
    }

    fun getThisWeekEntertainmentMinutes(context: Context): Int {
        return getThisWeekSessions(context)
            .filter { it.intention.contains("giải trí", ignoreCase = true) ||
                      it.intention.contains("entertainment", ignoreCase = true) ||
                      it.intention.contains("xem", ignoreCase = true) ||
                      it.intention.contains("đọc truyện", ignoreCase = true) }
            .sumOf { it.actualMinutes }
    }

    // ── Watched Apps ──────────────────────────────────────────────────────────

    fun getWatchedApps(context: Context): List<AppConfig> {
        val json = prefs(context).getString(KEY_WATCHED_APPS, null)
        if (json == null) {
            saveWatchedApps(context, DEFAULT_APPS)
            return DEFAULT_APPS
        }
        val arr = JSONArray(json)
        val list = mutableListOf<AppConfig>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(AppConfig(
                packageName = obj.getString("packageName"),
                appName = obj.getString("appName"),
                isEnabled = obj.optBoolean("isEnabled", true)
            ))
        }
        return list
    }

    fun saveWatchedApps(context: Context, apps: List<AppConfig>) {
        val arr = JSONArray()
        apps.forEach { app ->
            arr.put(JSONObject().apply {
                put("packageName", app.packageName)
                put("appName", app.appName)
                put("isEnabled", app.isEnabled)
            })
        }
        prefs(context).edit().putString(KEY_WATCHED_APPS, arr.toString()).apply()
    }

    fun isWatchedApp(context: Context, packageName: String): Boolean {
        return getWatchedApps(context).any { it.packageName == packageName && it.isEnabled }
    }

    fun getAppName(context: Context, packageName: String): String {
        return getWatchedApps(context).find { it.packageName == packageName }?.appName ?: packageName
    }

    // ── Budget ────────────────────────────────────────────────────────────────

    fun getWeeklyBudgetMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_WEEKLY_BUDGET_MINUTES, 120) // default 2h
    }

    fun setWeeklyBudgetMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_WEEKLY_BUDGET_MINUTES, minutes).apply()
    }

    fun getInitialWeeklyBudget(context: Context): Int {
        return prefs(context).getInt(KEY_INITIAL_WEEKLY_BUDGET, 120)
    }

    fun setInitialWeeklyBudget(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INITIAL_WEEKLY_BUDGET, minutes).apply()
        prefs(context).edit().putInt(KEY_WEEKLY_BUDGET_MINUTES, minutes).apply()
    }

    fun getWeeklyReductionMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_WEEKLY_REDUCTION_MINUTES, 15) // default giảm 15 phút/tuần
    }

    fun setWeeklyReductionMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_WEEKLY_REDUCTION_MINUTES, minutes).apply()
    }

    fun getWeekStartTime(context: Context): Long {
        val saved = prefs(context).getLong(KEY_WEEK_START, 0L)
        if (saved == 0L) {
            val start = currentWeekStart()
            prefs(context).edit().putLong(KEY_WEEK_START, start).apply()
            return start
        }
        return saved
    }

    fun checkAndRotateWeek(context: Context) {
        val weekStart = getWeekStartTime(context)
        val now = System.currentTimeMillis()
        val oneWeekMs = 7L * 24 * 60 * 60 * 1000
        if (now - weekStart >= oneWeekMs) {
            // New week: reduce budget
            val current = getWeeklyBudgetMinutes(context)
            val reduction = getWeeklyReductionMinutes(context)
            val newBudget = maxOf(30, current - reduction) // minimum 30 phút/tuần
            setWeeklyBudgetMinutes(context, newBudget)
            prefs(context).edit().putLong(KEY_WEEK_START, currentWeekStart()).apply()
        }
    }

    private fun currentWeekStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun formatMinutes(minutes: Int): String {
        return if (minutes >= 60) {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "${h}h" else "${h}h${m}p"
        } else {
            "${minutes}p"
        }
    }
}

    // ── Entertain target ──────────────────────────────────────────────────────
    private const val KEY_ENTERTAIN_TARGET = "entertain_target_minutes"

    fun getEntertainTargetMinutes(context: Context): Int =
        prefs(context).getInt(KEY_ENTERTAIN_TARGET, 30) // default 30p/ngày

    fun setEntertainTargetMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_ENTERTAIN_TARGET, minutes).apply()
    }

    // ── Session classification ────────────────────────────────────────────────
    private val entertainKeywords = listOf(
        "truyen", "truyện", "manga", "manhwa", "comic",
        "giải trí", "xem phim", "đọc truyện",
        "porn", "sex", "hentai", "⚠️"
    )

    fun isEntertainSession(session: Session): Boolean {
        val lower = session.intention.lowercase()
        return entertainKeywords.any { lower.contains(it) } ||
               session.appPackage in listOf("com.facebook.katana","com.facebook.lite","com.google.android.youtube")
    }

    // ── Day stats ─────────────────────────────────────────────────────────────
    fun getSessionsForDay(context: Context, dayStartMs: Long): List<Session> {
        val dayEnd = dayStartMs + 86_400_000L
        return getSessions(context).filter { it.endTime in dayStartMs until dayEnd && it.endTime > 0 }
    }

    fun getEntertainMinutesForDay(context: Context, dayStartMs: Long): Int =
        getSessionsForDay(context, dayStartMs)
            .filter { isEntertainSession(it) }
            .sumOf { it.actualMinutes }

    // Get start of a specific day (midnight)
    fun dayStartMs(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month, day, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
