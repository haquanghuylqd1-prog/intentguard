package com.intentguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import com.intentguard.R
import com.intentguard.data.DataStore
import com.intentguard.data.FirebaseSync
import com.intentguard.data.Session
import com.intentguard.ui.MainActivity
import kotlinx.coroutines.*
import java.util.UUID

class TimerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var overlayView: android.view.View? = null
    private var windowManager: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
            ACTION_STOP -> stopTimer()
        }
        return START_NOT_STICKY
    }

    private fun startTimer(pkg: String, appName: String, intention: String, plannedMinutes: Int) {
        currentPackage = pkg
        val sessionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        DebugLog.add("▶️ Timer start: '$intention' ${plannedMinutes}p | domain='${approvedDomain}'")

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
        currentIntention = intention

        startForeground(NOTIFICATION_ID, buildNotification(appName, plannedMinutes * 60))
        showOverlay(intention, plannedMinutes * 60)

        timerJob?.cancel()
        timerJob = scope.launch {
            var remaining = plannedMinutes * 60L
            while (remaining > 0) {
                delay(1000)
                remaining--
                updateNotification(appName, remaining.toInt())
                updateOverlay(intention, remaining.toInt())
            }
            onSessionEnded(pkg, appName, intention, plannedMinutes, sessionId, startTime)
        }
    }

    private fun showOverlay(intention: String, totalSeconds: Int) {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        removeOverlay()

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_timer, null)
        overlayView = view

        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        view.findViewById<TextView>(R.id.tvOverlayTime).text = "⏱ %02d:%02d".format(mins, secs)
        view.findViewById<TextView>(R.id.tvOverlayIntention).text = intention

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 8
            y = 120
        }

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            Log.e("IntentGuard", "Cannot show overlay: ${e.message}")
        }
    }

    private fun updateOverlay(intention: String, remainingSeconds: Int) {
        overlayView?.let { view ->
            val mins = remainingSeconds / 60
            val secs = remainingSeconds % 60
            view.findViewById<TextView>(R.id.tvOverlayTime).text = "⏱ %02d:%02d".format(mins, secs)
            view.findViewById<TextView>(R.id.tvOverlayIntention).text = intention
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            overlayView = null
        }
    }

    private fun onSessionEnded(
        pkg: String, appName: String, intention: String,
        plannedMinutes: Int, sessionId: String, startTime: Long
    ) {
        val actualMinutes = ((System.currentTimeMillis() - startTime) / 60000).toInt()
        val session = Session(
            id = sessionId, appPackage = pkg, appName = appName,
            intention = intention, plannedMinutes = plannedMinutes,
            startTime = startTime, endTime = System.currentTimeMillis(),
            actualMinutes = actualMinutes
        )
        DataStore.saveSession(this, session)

        // Push lên Firebase (background, không block)
        scope.launch(Dispatchers.IO) {
            try {
                if (FirebaseSync.ensureSignedIn()) FirebaseSync.pushSession(session)
            } catch (_: Exception) {}
        }

        currentPackage = ""
        currentSessionId = ""
        currentIntention = ""
        removeOverlay()

        val isEntertain = DataStore.isEntertainSession(session)

        // Nếu là session giải trí → bắt đầu cooldown 1 giờ cho đúng pkg đó
        if (isEntertain) {
            CooldownState.startCooldown(1, pkg)
        }

        AppWatcherService.instance?.resetPopupState()

        // Hiện overlay "hết giờ" thông qua AppWatcherService persistent instance
        AppWatcherService.instance?.showSessionEnded(
            appName, plannedMinutes, actualMinutes, intention, isEntertain
        )

        stopSelf()
    }

    private fun stopTimer() {
        timerJob?.cancel()
        val startTime = currentStartTime
        val sessionId = currentSessionId
        if (sessionId.isNotEmpty() && startTime > 0) {
            val actualMinutes = ((System.currentTimeMillis() - startTime) / 60000).toInt()
            val sessions = DataStore.getSessions(this)
            sessions.find { it.id == sessionId }?.let { existing ->
                DataStore.saveSession(this, existing.copy(
                    endTime = System.currentTimeMillis(),
                    actualMinutes = actualMinutes
                ))
            }
        }
        currentPackage = ""
        currentSessionId = ""
        currentIntention = ""
        removeOverlay()
        AppWatcherService.instance?.resetPopupState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(appName: String, remainingSeconds: Int): Notification {
        val mins = remainingSeconds / 60
        val secs = remainingSeconds % 60
        val mainIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TimerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("⏱ IntentGuard — $appName")
            .setContentText("Còn lại: %02d:%02d | $currentIntention".format(mins, secs))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(mainIntent)
            .addAction(Notification.Action.Builder(null, "Kết thúc", stopIntent).build())
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun updateNotification(appName: String, remainingSeconds: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(appName, remainingSeconds))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "IntentGuard Timer",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Đếm ngược thời gian sử dụng app"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
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

        var currentPackage = ""
        var currentSessionId = ""
        var currentStartTime = 0L
        var currentIntention = ""
        var approvedDomain = "" // Domain được approve để xem trong session giải trí

        fun isRunningFor(pkg: String) = currentPackage == pkg

        fun startFor(context: Context, pkg: String, appName: String, intention: String, minutes: Int,
                     domain: String = "") {
            approvedDomain = domain
            context.startForegroundService(Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_INTENTION, intention)
                putExtra(EXTRA_MINUTES, minutes)
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TimerService::class.java).apply { action = ACTION_STOP })
        }
    }
}
