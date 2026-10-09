package com.example.autoanswer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val callPerms = result[Manifest.permission.ANSWER_PHONE_CALLS] == true &&
                    result[Manifest.permission.READ_PHONE_STATE] == true
            if (callPerms || hasCallPermissions()) {
                startWatcher()
            } else {
                Toast.makeText(this, "נדרשות הרשאות טלפון כדי לפעול", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val info = TextView(this).apply {
            text = "מענה אוטומטי לשיחות כשהטלפון מוצמד לאוזן.\n" +
                    "אחרי ההתקנה: אשרו הרשאות, והגדירו סוללה ל'ללא הגבלה'."
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val startBtn = Button(this).apply {
            text = "הפעל שירות"
            setOnClickListener { requestPermissionsAndStart() }
        }

        val stopBtn = Button(this).apply {
            text = "עצור שירות"
            setOnClickListener { stopService(Intent(this@MainActivity, CallWatcherService::class.java)) }
        }

        val batteryBtn = Button(this).apply {
            text = "פתח הגדרות אפליקציה (סוללה)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }

        layout.addView(info)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        layout.addView(batteryBtn)
        setContentView(layout)
    }

    private fun hasCallPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startWatcher() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startWatcher() {
        ContextCompat.startForegroundService(this, Intent(this, CallWatcherService::class.java))
        Toast.makeText(this, "השירות פועל", Toast.LENGTH_SHORT).show()
    }
}
