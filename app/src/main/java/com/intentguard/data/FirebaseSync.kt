package com.intentguard.data

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object FirebaseSync {
    private const val TAG = "IGSync"
    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()

    suspend fun ensureSignedIn(): Boolean {
        return try {
            if (auth.currentUser == null) auth.signInAnonymously().await()
            true
        } catch (e: Exception) { Log.e(TAG, "SignIn failed: ${e.message}"); false }
    }

    fun uid(): String? = auth.currentUser?.uid

    suspend fun pushSession(session: Session): Boolean {
        val uid = uid() ?: return false
        return try {
            db.collection("intentguard_sessions").document(uid)
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
        val uid = uid() ?: return 0
        return try {
            val snap = db.collection("intentguard_sessions").document(uid)
                .collection("sessions")
                .orderBy("startTime", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(1000).get().await()
            val remote = snap.documents.mapNotNull { doc ->
                try {
                    Session(
                        id = doc.getString("id") ?: return@mapNotNull null,
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
            val localMap = DataStore.getSessions(context).associateBy { it.id }
            var count = 0
            remote.forEach { r ->
                val l = localMap[r.id]
                if (l == null || r.endTime > l.endTime) { DataStore.saveSession(context, r); count++ }
            }
            Log.d(TAG, "Pulled $count new sessions")
            count
        } catch (e: Exception) { Log.e(TAG, "Pull failed: ${e.message}"); 0 }
    }

    suspend fun pushSettings(context: Context): Boolean {
        val uid = uid() ?: return false
        return try {
            db.collection("intentguard_settings").document(uid)
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
        val uid = uid() ?: return false
        return try {
            val doc = db.collection("intentguard_settings").document(uid).get().await()
            if (doc.exists()) {
                doc.getLong("weeklyBudgetMinutes")?.toInt()?.let { DataStore.setWeeklyBudgetMinutes(context, it) }
                doc.getLong("weeklyReductionMinutes")?.toInt()?.let { DataStore.setWeeklyReductionMinutes(context, it) }
                doc.getLong("entertainTargetMinutes")?.toInt()?.let { DataStore.setEntertainTargetMinutes(context, it) }
            }
            true
        } catch (e: Exception) { false }
    }
}
