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

    private var isNear = false
    private var farSince = 0L          // מתי הטלפון הפך ל"רחוק"
    private var lastBurstAt = 0L       // מתי הייתה תנועה חזקה לאחרונה
    private var nearAt = 0L            // מתי הוצמד לאוזן
    private var maxMotionSinceNear = 0f

    // ----- ערכים שאפשר לכוונן -----
    private val minFarMs = 800L        // כמה זמן הטלפון צריך להיות רחוק לפני ההצמדה
    private val burstThreshold = 3.0f  // עוצמת תנועה (מ/ש^2) שנחשבת "הרמה"
    private val motionWindowMs = 2000L // התנועה חייבת לקרות עד כך זמן לפני ההצמדה
    private val settleMs = 300L        // זמן להתייצבות אחרי ההצמדה
    private val stillMax = 2.0f        // תנועה מקסימלית מותרת אחרי ההצמדה
    private val answerDelayMs = 900L   // כמה זמן להישאר צמוד ויציב לפני המענה
    // --------------------------------

    private val answerRunnable = Runnable { answerCall() }

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
        isNear = false
        farSince = 0L
        lastBurstAt = 0L
        nearAt = 0L
        maxMotionSinceNear = 0f

        val prox = proximitySensor ?: run {
            Log.w(TAG, "אין חיישן קרבה במכשיר")
            return
        }
        // החיישנים פועלים רק בזמן צלצול - חוסך סוללה
        sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)
        motionSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        registered = true
    }

    private fun onRingingEnded() {
        isRinging = false
        isNear = false
        farSince = 0L
        handler.removeCallbacks(answerRunnable)
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
                // בחיישן רגיל מפחיתים את כוח המשיכה
                val motion = if (motionIsLinear) magnitude else abs(magnitude - SensorManager.GRAVITY_EARTH)

                if (motion > burstThreshold) lastBurstAt = now

                // סופרים תנועה אחרי ההצמדה (אחרי זמן התייצבות קצר)
                if (isNear && nearAt != 0L && now - nearAt > settleMs) {
                    if (motion > maxMotionSinceNear) maxMotionSinceNear = motion
                }
            }

            Sensor.TYPE_PROXIMITY -> {
                val max = event.sensor.maximumRange
                val near = event.values[0] < max.coerceAtMost(5f)
                if (near == isNear) return
                isNear = near
                handler.removeCallbacks(answerRunnable)

                if (!near) {
                    // הטלפון התרחק
                    if (farSince == 0L) farSince = now
                    nearAt = 0L
                } else {
                    // הטלפון הוצמד - בודקים אם זו הרמה אמיתית לאוזן
                    val wasFarLongEnough = farSince != 0L && (now - farSince) >= minFarMs
                    val hadLiftMotion = lastBurstAt != 0L && (now - lastBurstAt) <= motionWindowMs
                    farSince = 0L

                    if (wasFarLongEnough && hadLiftMotion) {
                        nearAt = now
                        maxMotionSinceNear = 0f
                        handler.postDelayed(answerRunnable, answerDelayMs)
                    } else {
                        Log.i(TAG, "לא נענה: רחוק=$wasFarLongEnough תנועה=$hadLiftMotion")
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- מענה ----------

    private fun answerCall() {
        if (!isRinging || answered || !isNear) return

        // אם הטלפון המשיך לזוז אחרי ההצמדה (למשל הליכה עם טלפון בכיס) - לא עונים
        if (maxMotionSinceNear > stillMax) {
            Log.i(TAG, "לא נענה: תנועה אחרי ההצמדה = $maxMotionSinceNear")
            return
        }

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
            handler.removeCallbacks(answerRunnable)
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
