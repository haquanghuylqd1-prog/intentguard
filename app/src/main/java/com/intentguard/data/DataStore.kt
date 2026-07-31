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
        AppConfig("com.android.chrome", "Chrome"),
        AppConfig("com.shopee.vn", "Shopee")
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── FB Reels Counter (đếm reels → cooldown sau reel thứ 4) ────────────────

    fun getReelsCount(context: Context): Int =
        prefs(context).getInt("fb_reels_count", 0)

    fun setReelsCount(context: Context, count: Int) {
        prefs(context).edit().putInt("fb_reels_count", count).apply()
    }

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
        "giải trí", "xem phim", "đọc truyện", "giải tri",
        "porn", "sex", "hentai", "⚠️", "reels", "shorts",
        "tiktok", "scroll", "lướt"
    )

    fun isEntertainSession(session: Session): Boolean {
        val intention = session.intention
        // Prefix emoji — chính xác nhất, ưu tiên tuyệt đối
        if (intention.startsWith("🎮") || intention.startsWith("⚠️")) return true
        if (intention.startsWith("💼")) return false
        // Session cũ không có prefix: check keywords
        val lower = intention.lowercase()
        return entertainKeywords.any { lower.contains(it) }
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

    // ── Continuous Usage Tracking (rule: >20p liên tục → cooldown 1h) ──────────
    // "key" = định danh 1 loại nội dung đang theo dõi:
    //   - app riêng (FB app, YouTube app, Shopee): key = packageName
    //   - browser: key = "browser:<contentType>" (vd "browser:facebook", "browser:youtube", "browser:entertain")
    // Nếu khoảng cách giữa lần dùng trước và lần dùng hiện tại < GAP_RESET_MS thì coi là
    // liên tục và cộng dồn thời lượng; nếu cách xa hơn thì coi là chuỗi mới, reset về 0.

    private const val KEY_CONTINUOUS_PREFIX = "continuous_"
    const val CONTINUOUS_LIMIT_MINUTES = 20
    const val CONTINUOUS_GAP_RESET_MS = 5 * 60_000L // > 5 phút không dùng → reset chuỗi

    data class ContinuousUsage(
        val key: String,
        val accumulatedMs: Long,   // tổng thời gian đã dùng liên tục trong chuỗi hiện tại
        val segmentStartMs: Long,  // thời điểm bắt đầu đoạn dùng hiện tại (chưa cộng vào accumulated)
        val lastUpdateMs: Long     // lần cập nhật gần nhất (để tính khoảng cách ngắt quãng)
    )

    private fun continuousKey(key: String) = KEY_CONTINUOUS_PREFIX + key

    fun getContinuousUsage(context: Context, key: String): ContinuousUsage? {
        val json = prefs(context).getString(continuousKey(key), null) ?: return null
        return try {
            val obj = JSONObject(json)
            ContinuousUsage(
                key = key,
                accumulatedMs = obj.getLong("accumulatedMs"),
                segmentStartMs = obj.getLong("segmentStartMs"),
                lastUpdateMs = obj.getLong("lastUpdateMs")
            )
        } catch (e: Exception) { null }
    }

    private fun saveContinuousUsage(context: Context, usage: ContinuousUsage) {
        val obj = JSONObject().apply {
            put("accumulatedMs", usage.accumulatedMs)
            put("segmentStartMs", usage.segmentStartMs)
            put("lastUpdateMs", usage.lastUpdateMs)
        }
        prefs(context).edit().putString(continuousKey(usage.key), obj.toString()).apply()
    }

    fun clearContinuousUsage(context: Context, key: String) {
        prefs(context).edit().remove(continuousKey(key)).apply()
    }

    /**
     * Gọi hàm này định kỳ (vd mỗi lần scan detect thấy đang xem nội dung thuộc [key]).
     * Trả về tổng số phút đã dùng liên tục SAU khi cập nhật lần gọi này.
     * Nếu khoảng cách tới lần gọi trước > CONTINUOUS_GAP_RESET_MS → coi là chuỗi mới (reset).
     */
    fun updateContinuousUsage(context: Context, key: String, nowMs: Long = System.currentTimeMillis()): Int {
        val existing = getContinuousUsage(context, key)
        val usage = if (existing == null || nowMs - existing.lastUpdateMs > CONTINUOUS_GAP_RESET_MS) {
            // Chuỗi mới: bắt đầu lại từ 0
            ContinuousUsage(key, accumulatedMs = 0L, segmentStartMs = nowMs, lastUpdateMs = nowMs)
        } else {
            // Vẫn trong chuỗi liên tục: cộng dồn thời gian từ lần update trước tới giờ
            val delta = nowMs - existing.lastUpdateMs
            existing.copy(accumulatedMs = existing.accumulatedMs + delta, lastUpdateMs = nowMs)
        }
        saveContinuousUsage(context, usage)
        return (usage.accumulatedMs / 60000L).toInt()
    }

    fun getContinuousMinutes(context: Context, key: String): Int {
        val usage = getContinuousUsage(context, key) ?: return 0
        val now = System.currentTimeMillis()
        if (now - usage.lastUpdateMs > CONTINUOUS_GAP_RESET_MS) return 0 // chuỗi đã hết hạn
        return (usage.accumulatedMs / 60000L).toInt()
    }

    // ── Repeated Intention Tracking (rule: 3 lần liên tiếp cùng mục đích → cooldown 1h) ──
    // "key" giống hệt key ở trên (packageName hoặc "browser:<contentType>").
    // Lưu: nội dung mục đích lần gần nhất + số lần lặp lại liên tiếp.

    private const val KEY_REPEAT_PREFIX = "repeat_intent_"
    const val REPEAT_LIMIT = 3 // 3 lần liên tiếp giống nhau → lần thứ 3 trigger cooldown

    private fun repeatKey(key: String) = KEY_REPEAT_PREFIX + key
    const val REPEAT_EXPIRY_MS = 6 * 3600_000L // quá 6 tiếng không lặp lại → coi như chuỗi cũ đã hết hạn

    data class RepeatState(val lastIntention: String, val count: Int, val lastAttemptMs: Long = 0L)

    fun getRepeatState(context: Context, key: String): RepeatState {
        val json = prefs(context).getString(repeatKey(key), null) ?: return RepeatState("", 0)
        return try {
            val obj = JSONObject(json)
            RepeatState(
                obj.getString("lastIntention"),
                obj.getInt("count"),
                obj.optLong("lastAttemptMs", 0L)
            )
        } catch (e: Exception) { RepeatState("", 0) }
    }

    private fun saveRepeatState(context: Context, key: String, state: RepeatState) {
        val obj = JSONObject().apply {
            put("lastIntention", state.lastIntention)
            put("count", state.count)
            put("lastAttemptMs", state.lastAttemptMs)
        }
        prefs(context).edit().putString(repeatKey(key), obj.toString()).apply()
    }

    fun clearRepeatState(context: Context, key: String) {
        prefs(context).edit().remove(repeatKey(key)).apply()
    }

    // Chuẩn hoá mục đích để so sánh (bỏ emoji prefix, khoảng trắng thừa, không phân biệt hoa/thường)
    fun normalizeIntention(intention: String): String {
        return intention
            .replace(Regex("^[\\p{So}\\p{Cn}\\u2600-\\u27BF]+\\s*"), "") // bỏ emoji prefix (💼/🎮/⚠️...)
            .trim()
            .lowercase()
    }

    /**
     * Gọi khi 1 session cho [key] BẮT ĐẦU với [intention].
     * Trả về số lần lặp lại liên tiếp SAU khi tính lần này (đã bao gồm lần hiện tại).
     * Nếu mục đích khác lần trước, hoặc đã quá REPEAT_EXPIRY_MS kể từ lần trước → reset về 1.
     * Nếu giống và còn trong hạn → tăng dần.
     */
    fun registerIntentionAttempt(
        context: Context, key: String, intention: String,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val normalized = normalizeIntention(intention)
        if (normalized.isEmpty()) {
            clearRepeatState(context, key)
            return 0
        }
        val existing = getRepeatState(context, key)
        val hasPriorState = existing.count > 0
        val expired = hasPriorState && nowMs - existing.lastAttemptMs > REPEAT_EXPIRY_MS
        val newCount = if (!expired && existing.lastIntention == normalized) existing.count + 1 else 1
        saveRepeatState(context, key, RepeatState(normalized, newCount, nowMs))
        return newCount
    }

    // ── Browser content-type classification (dùng làm key khi track browser) ───
    // Xác định "loại nội dung web" đang xem dựa trên URL, để phân biệt web Facebook /
    // web YouTube / web giải trí (truyện, phim, 18+) khi tính rule liên tục & lặp lại.

    fun classifyBrowserContent(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains("facebook.com") || lower.contains("fb.com") -> "facebook"
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "youtube"
            entertainKeywords.any { it.isNotBlank() && lower.contains(it) } -> "entertain"
            else -> null // web khác (không thuộc nhóm bị kiểm soát theo nội dung)
        }
    }
}
