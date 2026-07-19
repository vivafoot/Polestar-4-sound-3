package com.squirrel.polestarsound

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * 사운드 엔진 + GPS 속도 추적을 소유하는 포그라운드 서비스.
 *
 * MainActivity(화면)가 백그라운드로 가거나 화면이 꺼져도(운전 중 거치대/주머니 상태)
 * 안드로이드가 프로세스를 그대로 얼려버리지 않도록, 알림을 띄운 "포그라운드 서비스" 형태로
 * GPS 콜백과 오디오 렌더링 스레드를 계속 살려둔다.
 */
class SoundService : Service() {

    companion object {
        const val CHANNEL_ID = "polestar_sound_channel"
        const val NOTIFICATION_ID = 42
        const val ACTION_START = "com.squirrel.polestarsound.action.START"
        const val ACTION_STOP = "com.squirrel.polestarsound.action.STOP"
    }

    interface UpdateListener {
        fun onSpeedUpdate(speedKmh: Float, rpm: Int)
        fun onLocationStale() // GPS 신호가 안 잡힐 때 UI에 표시하기 위한 콜백
    }

    inner class LocalBinder : Binder() {
        fun getService(): SoundService = this@SoundService
    }

    private val binder = LocalBinder()
    private val engine = SoundEngine()
    private var listener: UpdateListener? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastSpeedKmh = 0f
    private var lastFixElapsedMs = 0L
    private var lastLocation: Location? = null

    private val idleRpm = 800f
    private val maxRpm = 6500f
    private val maxSpeedForMapping = 180f // km/h

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            lastFixElapsedMs = android.os.SystemClock.elapsedRealtime()

            // 일부 단말/상황에서는 hasSpeed()가 false로 오는 경우가 있어
            // (특히 GPS 위성 신호가 약하거나 막 켜졌을 때) 위치 이동거리/시간차로 보정 계산한다.
            val speedKmh: Float = when {
                loc.hasSpeed() && loc.speed > 0.3f -> loc.speed * 3.6f
                else -> {
                    val prev = lastLocation
                    if (prev != null) {
                        val dtSec = (loc.time - prev.time) / 1000f
                        if (dtSec > 0.05f) {
                            val distM = prev.distanceTo(loc)
                            (distM / dtSec) * 3.6f
                        } else lastSpeedKmh
                    } else 0f
                }
            }
            lastLocation = loc

            val accel = speedKmh - lastSpeedKmh
            lastSpeedKmh = speedKmh

            val speedRatio = (speedKmh / maxSpeedForMapping).coerceIn(0f, 1f)
            val estimatedRpm = idleRpm + speedRatio * (maxRpm - idleRpm)
            val throttleEstimate = (0.15f + accel.coerceIn(0f, 5f) / 5f * 0.85f).coerceIn(0f, 1f)

            engine.rpm = estimatedRpm
            engine.throttle = throttleEstimate

            listener?.onSpeedUpdate(speedKmh, estimatedRpm.toInt())
            updateNotification(speedKmh.toInt(), estimatedRpm.toInt())
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startAsForeground()
        }
        // START_STICKY: 시스템이 메모리 확보를 위해 서비스를 죽이더라도 재생성 시도
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification(0, engine.rpm.toInt())
        startForeground(NOTIFICATION_ID, notification)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "PolestarSound:EngineWakeLock"
            )
        }
        wakeLock?.let { if (!it.isHeld) it.acquire(6 * 60 * 60 * 1000L /* 최대 6시간 안전장치 */) }

        engine.start()
        requestLocationUpdates()
    }

    fun setListener(l: UpdateListener?) {
        listener = l
    }

    fun setMode(mode: SoundMode) {
        engine.mode = mode
    }

    fun getMode(): SoundMode = engine.mode

    fun setVolume(v: Float) {
        engine.masterVolume = v
    }

    private fun requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 권한이 없으면 위치는 못 받지만, 아이들 사운드는 계속 재생된다.
            return
        }
        // 500ms 간격, 최우선 정확도(GPS) 요청. 이동거리 제한 없이 계속 갱신.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
            .setMinUpdateIntervalMillis(300L)
            .setWaitForAccurateLocation(false)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "가상 사운드 실행 중", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GPS 속도 추적과 엔진음 합성이 백그라운드에서도 계속되도록 유지합니다."
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(speedKmh: Int, rpm: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("가상 사운드 재생 중")
            .setContentText("속도 ${speedKmh}km/h · 추정 RPM ${rpm}")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            val contentPendingIntent = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(contentPendingIntent)
        }
        return builder.build()
    }

    private fun updateNotification(speedKmh: Int, rpm: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(speedKmh, rpm))
    }

    fun isGpsStale(): Boolean {
        if (lastFixElapsedMs == 0L) return true
        return android.os.SystemClock.elapsedRealtime() - lastFixElapsedMs > 4000L
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
