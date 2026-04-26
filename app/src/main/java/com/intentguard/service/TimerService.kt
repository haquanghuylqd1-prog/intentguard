package com.intentguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.intentguard.data.DataStore
import com.intentguard.data.Session
import com.intentguard.ui.MainActivity
import com.intentguard.ui.SessionEndedActivity
import kotlinx.coroutines.*
import java.util.UUID

class TimerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return START_NOT_STICKY
                val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: pkg
                val intention = intent.getStringExtra(EXTRA_INTENTION) ?: ""
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 15)
                startTimer(pkg, appName, intention, minutes)
            }
            ACTION_STOP -> {
                stopTimer()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTimer(pkg: String, appName: String, intention: String, plannedMinutes: Int) {
        currentPackage = pkg
        val sessionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        // Save session start
        val session = Session(
            id = sessionId,
            appPackage = pkg,
            appName = appName,
            intention = intention,
            plannedMinutes = plannedMinutes,
            startTime = startTime
        )
        DataStore.saveSession(this, session)
        currentSessionId = sessionId
        currentStartTime = startTime

        val totalSeconds = plannedMinutes * 60L
        startForeground(NOTIFICATION_ID, buildNotification(appName, plannedMinutes, plannedMinutes * 60))

        timerJob?.cancel()
        timerJob = scope.launch {
            var remaining = totalSeconds
            while (remaining > 0) {
                delay(1000)
                remaining--
                val mins = (remaining / 60).toInt()
                val secs = (remaining % 60).toInt()
                updateNotification(appName, plannedMinutes, remaining.toInt())
            }
            // Time's up!
            onSessionEnded(pkg, appName, intention, plannedMinutes, sessionId, startTime)
        }

        Log.d("IntentGuard", "Timer started: $appName for $plannedMinutes min")
    }

    private fun onSessionEnded(
        pkg: String, appName: String, intention: String,
        plannedMinutes: Int, sessionId: String, startTime: Long
    ) {
        val actualMinutes = ((System.currentTimeMillis() - startTime) / 60000).toInt()

        // Save completed session
        val session = Session(
            id = sessionId,
            appPackage = pkg,
            appName = appName,
            intention = intention,
            plannedMinutes = plannedMinutes,
            startTime = startTime,
            endTime = System.currentTimeMillis(),
            actualMinutes = actualMinutes
        )
        DataStore.saveSession(this, session)

        currentPackage = ""
        currentSessionId = ""

        // Reset popup state so next open shows popup again
        AppWatcherService.instance?.resetPopupState()

        // Show session ended screen
        val intent = Intent(this, SessionEndedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(SessionEndedActivity.EXTRA_APP_NAME, appName)
            putExtra(SessionEndedActivity.EXTRA_PLANNED, plannedMinutes)
            putExtra(SessionEndedActivity.EXTRA_ACTUAL, actualMinutes)
            putExtra(SessionEndedActivity.EXTRA_INTENTION, intention)
        }
        startActivity(intent)

        stopSelf()
    }

    private fun stopTimer() {
        timerJob?.cancel()
        val startTime = currentStartTime
        val sessionId = currentSessionId
        val pkg = currentPackage

        if (sessionId.isNotEmpty() && startTime > 0) {
            val actualMinutes = ((System.currentTimeMillis() - startTime) / 60000).toInt()
            val sessions = DataStore.getSessions(this)
            val existing = sessions.find { it.id == sessionId }
            if (existing != null) {
                DataStore.saveSession(this, existing.copy(
                    endTime = System.currentTimeMillis(),
                    actualMinutes = actualMinutes
                ))
            }
        }

        currentPackage = ""
        currentSessionId = ""
        AppWatcherService.instance?.resetPopupState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(appName: String, plannedMinutes: Int, remainingSeconds: Int): Notification {
        val mins = remainingSeconds / 60
        val secs = remainingSeconds % 60
        val timeStr = String.format("%02d:%02d", mins, secs)

        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TimerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("⏱ IntentGuard — $appName")
            .setContentText("Còn lại: $timeStr / ${plannedMinutes}p")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(mainIntent)
            .addAction(Notification.Action.Builder(
                null, "Kết thúc sớm", stopIntent
            ).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(appName: String, plannedMinutes: Int, remainingSeconds: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(appName, plannedMinutes, remainingSeconds))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "IntentGuard Timer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Đếm ngược thời gian sử dụng app"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ACTION_START = "com.intentguard.START_TIMER"
        const val ACTION_STOP = "com.intentguard.STOP_TIMER"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_INTENTION = "intention"
        const val EXTRA_MINUTES = "minutes"
        const val CHANNEL_ID = "intentguard_timer"
        const val NOTIFICATION_ID = 1001

        private var currentPackage = ""
        private var currentSessionId = ""
        private var currentStartTime = 0L

        fun isRunningFor(pkg: String) = currentPackage == pkg

        fun startFor(context: Context, pkg: String, appName: String, intention: String, minutes: Int) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_INTENTION, intention)
                putExtra(EXTRA_MINUTES, minutes)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TimerService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
