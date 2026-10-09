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
            setOnClickListenerpackage com.example.autoanswer

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
            setOnClickListener
