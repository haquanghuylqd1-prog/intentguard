package com.intentguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.intentguard.R
import com.intentguard.service.TimerService

class SessionEndedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_ended)

        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "App"
        val planned = intent.getIntExtra(EXTRA_PLANNED, 0)
        val actual = intent.getIntExtra(EXTRA_ACTUAL, 0)
        val intention = intent.getStringExtra(EXTRA_INTENTION) ?: ""

        findViewById<TextView>(R.id.tvAppName).text = "Session $appName đã kết thúc"
        findViewById<TextView>(R.id.tvPlanned).text = "$planned phút"
        findViewById<TextView>(R.id.tvActual).text = "$actual phút"
        findViewById<TextView>(R.id.tvIntention).text = "\"$intention\""

        // Go home
        findViewById<Button>(R.id.btnDone).setOnClickListener {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            finish()
        }

        // Extend 10 more minutes - starts a new mini session
        findViewById<Button>(R.id.btnContinue).setOnClickListener {
            TimerService.startFor(this, "", appName, "$intention (+10p gia hạn)", 10)
            finish()
        }
    }

    companion object {
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_PLANNED = "extra_planned"
        const val EXTRA_ACTUAL = "extra_actual"
        const val EXTRA_INTENTION = "extra_intention"
    }
}
