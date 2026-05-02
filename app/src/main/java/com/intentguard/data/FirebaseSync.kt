package com.intentguard.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.intentguard.service.DebugLog
import kotlinx.coroutines.tasks.await


object FirebaseSync {
    private const val TAG = "IGSync"
    private val db get() = FirebaseFirestore.getInstance()

    // Dùng fixed user ID giống Speaking Coach — không cần Auth, không bao giờ mất data
    private const val FIXED_USER_ID = "quanghuy_intentguard"

    // Giả ensureSignedIn luôn true — không cần anonymous auth nữa
    suspend fun ensureSignedIn(): Boolean = true

    fun uid(): String = FIXED_USER_ID

    suspend fun pushSession(session: Session): Boolean {
        return try {
            db.collection("intentguard_sessions").document(FIXED_USER_ID)
                .collection("sessions").document(session.id)
                .set(mapOf(
                    "id" to session.id,
                    "appPackage" to session.appPackage,
                    "appName" to session.appName,
                    "intention" to session.intention,
                    "plannedMinutes" to session.plannedMinutes,
                    "startTime" to session.startTime,
                    "endTime" to session.endTime,
                    "actualMinutes" to session.actualMinutes,
                    "updatedAt" to System.currentTimeMillis()
                ), SetOptions.merge()).await()
            true
        } catch (e: Exception) { Log.e(TAG, "Push failed: ${e.message}"); false }
    }

    suspend fun pullSessions(context: Context): Int {
        return try {
            // Pull từ fixed ID + tất cả UID cũ từ anonymous auth
            val allUids = listOf(
                FIXED_USER_ID,
                "1NclyrABN8hxeGCgfRZiDRM2iox1",
                "8E3SJilF4UVZ73YnwxXBlFEeQr92",
                "UjbDPV6q2IUoPRTlxXU1Mufs1303",
                "awHGUsbBUph6Ma132Kw7bgike122",
                "zTbvSSENe4fpZai8TC2v1IZf9Eq1"
            )

            DebugLog.add("📦 Pulling from ${allUids.size} UIDs...")
            val allRemote = mutableListOf<Session>()

            for (uid in allUids) {
                try {
                    val snap = db.collection("intentguard_sessions")
                        .document(uid).collection("sessions")
                        .orderBy("startTime", com.google.firebase.firestore.Query.Direction.DESCENDING)
                        .limit(500).get().await()
                    DebugLog.add("📖 ${uid.take(12)}: ${snap.size()} sessions")
                    snap.documents.mapNotNullTo(allRemote) { doc ->
                        try {
                            Session(
                                id = doc.getString("id") ?: return@mapNotNullTo null,
                                appPackage = doc.getString("appPackage") ?: "",
                                appName = doc.getString("appName") ?: "",
                                intention = doc.getString("intention") ?: "",
                                plannedMinutes = (doc.getLong("plannedMinutes") ?: 0).toInt(),
                                startTime = doc.getLong("startTime") ?: 0L,
                                endTime = doc.getLong("endTime") ?: 0L,
                                actualMinutes = (doc.getLong("actualMinutes") ?: 0).toInt()
                            )
                        } catch (_: Exception) { null }
                    }
                } catch (e: Exception) {
                    DebugLog.add("❌ ${uid.take(12)}: ${e.message?.take(40)}")
                }
            }

            val localMap = DataStore.getSessions(context).associateBy { it.id }
            var count = 0
            allRemote.forEach { r ->
                val l = localMap[r.id]
                if (l == null || r.endTime > l.endTime) {
                    DataStore.saveSession(context, r)
                    count++
                }
            }
            DebugLog.add("✅ Merged $count new sessions (total: ${allRemote.size})")
            count
        } catch (e: Exception) {
            DebugLog.add("❌ pullSessions error: ${e.message?.take(80)}")
            0
        }
    }

    suspend fun pushSettings(context: Context): Boolean {
        return try {
            db.collection("intentguard_settings").document(FIXED_USER_ID)
                .set(mapOf(
                    "weeklyBudgetMinutes" to DataStore.getWeeklyBudgetMinutes(context),
                    "weeklyReductionMinutes" to DataStore.getWeeklyReductionMinutes(context),
                    "entertainTargetMinutes" to DataStore.getEntertainTargetMinutes(context),
                    "updatedAt" to System.currentTimeMillis()
                ), SetOptions.merge()).await()
            true
        } catch (e: Exception) { false }
    }

    suspend fun pullSettings(context: Context): Boolean {
        return try {
            val doc = db.collection("intentguard_settings").document(FIXED_USER_ID).get().await()
            if (doc.exists()) {
                doc.getLong("weeklyBudgetMinutes")?.toInt()?.let { DataStore.setWeeklyBudgetMinutes(context, it) }
                doc.getLong("weeklyReductionMinutes")?.toInt()?.let { DataStore.setWeeklyReductionMinutes(context, it) }
                doc.getLong("entertainTargetMinutes")?.toInt()?.let { DataStore.setEntertainTargetMinutes(context, it) }
            }
            true
        } catch (e: Exception) { false }
    }
}
