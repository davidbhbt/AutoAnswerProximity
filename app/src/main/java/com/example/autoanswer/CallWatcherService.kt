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
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class CallWatcherService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isRinging = false
    private var listening = false
    private var answered = false

    // האם החיישן דיווח "רחוק" בזמן הצלצול (כלומר הטלפון לא היה בכיס)
    private var sawFar = false

    private val answerDelayMs = 700L
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
        sawFar = false

        val sensor = proximitySensor ?: run {
            Log.w(TAG, "אין חיישן קרבה במכשיר")
            return
        }
        listening = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun onRingingEnded() {
        isRinging = false
        sawFar = false
        handler.removeCallbacks(answerRunnable)
        if (listening) {
            sensorManager.unregisterListener(this)
            listening = false
        }
    }

    // ---------- חיישן קרבה ----------

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRinging || answered) return
        val max = event.sensor.maximumRange
        val near = event.values[0] < max.coerceAtMost(5f)

        handler.removeCallbacks(answerRunnable)
        if (!near) {
            // הטלפון רחוק (מחוץ לכיס / ביד) - מתחילים "לחמש" את המענה
            sawFar = true
        } else if (sawFar) {
            // עבר מרחוק לקרוב = הוצמד לאוזן
            handler.postDelayed(answerRunnable, answerDelayMs)
        }
        // אם הוא קרוב מההתחלה (בכיס) - לא עושים כלום
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- מענה ----------

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
