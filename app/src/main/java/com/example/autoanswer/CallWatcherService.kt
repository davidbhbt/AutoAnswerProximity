package com.example.autoanswer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt

class CallWatcherService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var motionSensor: Sensor? = null
    private var motionIsLinear = false
    private val handler = Handler(Looper.getMainLooper())

    private var isRinging = false
    private var registered = false
    private var answered = false

    private var gotFirstProx = false
    private var isNear = false
    private var farSince = 0L       // מתי הטלפון הפך ל"רחוק"
    private var nearAt = 0L         // מתי הוצמד לאוזן לאחרונה
    private var lastMotionAt = 0L   // מתי הייתה תנועה לאחרונה
    private var armedUntil = 0L     // עד מתי "הרמה לאוזן" תקפה

    // ----- ערכים שאפשר לכוונן -----
    private val minFarMs = 800L          // כמה זמן הטלפון צריך להיות רחוק לפני ההצמדה
    private val motionThreshold = 2.0f   // עוצמת תנועה שנחשבת "תזוזה" (מ/ש^2)
    private val liftWindowMs = 3000L     // התנועה חייבת להיות עד כך זמן לפני ההצמדה
    private val armWindowMs = 5000L      // כמה זמן ההרמה נשארת תקפה אחרי ההצמדה
    private val nearHoldMs = 500L        // כמה זמן הטלפון צריך להישאר צמוד לפני המענה
    private val calmMs = 350L            // כמה זמן הטלפון צריך להיות יציב לפני המענה
    // --------------------------------

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRinging || answered) return
            tryAnswer()
            handler.postDelayed(this, 150L)
        }
    }

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> onRingingStarted()
                TelephonyManager.EXTRA_STATE_OFFHOOK,
                TelephonyManager.EXTRA_STATE_IDLE -> onRingingEnded()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linear != null) {
            motionSensor = linear
            motionIsLinear = true
        } else {
            motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            motionIsLinear = false
        }

        startAsForeground()
        registerReceiver(
            phoneStateReceiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        onRingingEnded()
        try { unregisterReceiver(phoneStateReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------- מצב צלצול ----------

    private fun onRingingStarted() {
        if (isRinging) return
        isRinging = true
        answered = false
        gotFirstProx = false
        isNear = false
        farSince = 0L
        nearAt = 0L
        lastMotionAt = 0L
        armedUntil = 0L

        val prox = proximitySensor ?: run {
            Log.w(TAG, "אין חיישן קרבה במכשיר")
            return
        }
        sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)
        motionSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        registered = true
        handler.postDelayed(checkRunnable, 150L)
    }

    private fun onRingingEnded() {
        isRinging = false
        isNear = false
        farSince = 0L
        armedUntil = 0L
        handler.removeCallbacks(checkRunnable)
        if (registered) {
            sensorManager.unregisterListener(this)
            registered = false
        }
    }

    // ---------- חיישנים ----------

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRinging || answered) return
        val now = SystemClock.elapsedRealtime()

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                val motion = if (motionIsLinear) magnitude else abs(magnitude - SensorManager.GRAVITY_EARTH)
                if (motion > motionThreshold) lastMotionAt = now
            }

            Sensor.TYPE_PROXIMITY -> {
                val max = event.sensor.maximumRange
                val near = event.values[0] < max.coerceAtMost(5f)

                // הקריאה הראשונה היא המצב ההתחלתי בזמן תחילת הצלצול
                if (!gotFirstProx) {
                    gotFirstProx = true
                    isNear = near
                    if (near) {
                        nearAt = now          // כנראה בכיס - לא נחשב הצמדה תקפה
                    } else {
                        farSince = now - minFarMs   // כבר מחוץ לכיס - נחשב רחוק מספיק
                    }
                    return
                }

                if (near == isNear) return
                isNear = near

                if (!near) {
                    farSince = now
                } else {
                    nearAt = now
                    val farLongEnough = farSince != 0L && (now - farSince) >= minFarMs
                    val hadLiftMotion = lastMotionAt != 0L && (now - lastMotionAt) <= liftWindowMs
                    if (farLongEnough && hadLiftMotion) {
                        armedUntil = now + armWindowMs
                    } else {
                        Log.i(TAG, "הצמדה לא תקפה: רחוק=$farLongEnough תנועה=$hadLiftMotion")
                    }
                    farSince = 0L
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- מענה ----------

    private fun tryAnswer() {
        val now = SystemClock.elapsedRealtime()
        if (!isNear || now > armedUntil) return          // לא הורם לאוזן
        if (now - nearAt < nearHoldMs) return            // עוד לא הוחזק מספיק
        if (now - lastMotionAt < calmMs) return          // עדיין זז, מחכים שיתייצב
        answerCall()
    }

    private fun answerCall() {
        if (!isRinging || answered) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "חסרה הרשאת ANSWER_PHONE_CALLS")
            return
        }
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("MissingPermission")
            telecom.acceptRingingCall()
            answered = true
            handler.removeCallbacks(checkRunnable)
            Log.i(TAG, "השיחה נענתה")
        } catch (e: SecurityException) {
            Log.e(TAG, "נכשל במענה", e)
        }
    }

    // ---------- התראה של Foreground Service ----------

    private fun startAsForeground() {
        val channelId = "call_watcher"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "מענה אוטומטי", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("מענה אוטומטי פעיל")
            .setContentText("עונה לשיחות כשהטלפון צמוד לאוזן")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        private const val TAG = "CallWatcherService"
    }
}
