package com.squirrel.polestarsound

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SoundService.UpdateListener {

    private var soundService: SoundService? = null
    private var bound = false
    private var pendingPowerOn = false

    private val staleCheckHandler = Handler(Looper.getMainLooper())
    private val staleCheckRunnable = object : Runnable {
        override fun run() {
            val svc = soundService
            if (svc != null && powerToggleRef?.isChecked == true) {
                if (svc.isGpsStale()) {
                    speedTextRef?.text = getString(R.string.gps_waiting)
                }
            }
            staleCheckHandler.postDelayed(this, 2000L)
        }
    }

    private var powerToggleRef: ToggleButton? = null
    private var speedTextRef: TextView? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SoundService.LocalBinder
            soundService = binder.getService()
            soundService?.setListener(this@MainActivity)
            applyCurrentModeAndVolume()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            soundService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        speedTextRef = findViewById(R.id.speedText)
        powerToggleRef = findViewById(R.id.powerToggle)

        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioEngine -> SoundMode.ENGINE_V6
                R.id.radioSpaceship -> SoundMode.SPACESHIP
                else -> SoundMode.OFF
            }
            soundService?.setMode(mode)
        }

        val volumeSeek = findViewById<SeekBar>(R.id.volumeSeek)
        volumeSeek.progress = 60
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                soundService?.setVolume(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        powerToggleRef?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                pendingPowerOn = true
                ensurePermissionsThenStart()
            } else {
                stopSoundService()
            }
        }
    }

    // ---------------- 권한 & 배터리 최적화 ----------------

    private fun ensurePermissionsThenStart() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
            return
        }

        maybeRequestBatteryOptimizationExemption()
        startSoundService()
    }

    private fun maybeRequestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val pkg = packageName
        if (!pm.isIgnoringBatteryOptimizations(pkg)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$pkg")
                }
                startActivity(intent)
            } catch (_: Exception) {
                // 일부 제조사(One UI 포함)는 이 인텐트를 막아둘 수 있음 -> 무시하고 진행
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val fineGranted = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION).let {
                it == -1 || (it >= 0 && grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED)
            }
            if (fineGranted && pendingPowerOn) {
                maybeRequestBatteryOptimizationExemption()
                startSoundService()
            } else if (!fineGranted) {
                speedTextRef?.text = getString(R.string.permission_needed)
                powerToggleRef?.isChecked = false
            }
        }
    }

    // ---------------- 서비스 시작/중지/바인딩 ----------------

    private fun startSoundService() {
        val startIntent = Intent(this, SoundService::class.java).apply {
            action = SoundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }
        bindService(Intent(this, SoundService::class.java), connection, Context.BIND_AUTO_CREATE)
        staleCheckHandler.postDelayed(staleCheckRunnable, 2000L)
    }

    private fun stopSoundService() {
        staleCheckHandler.removeCallbacks(staleCheckRunnable)
        if (bound) {
            soundService?.setListener(null)
            unbindService(connection)
            bound = false
        }
        val stopIntent = Intent(this, SoundService::class.java).apply {
            action = SoundService.ACTION_STOP
        }
        startService(stopIntent)
        speedTextRef?.text = getString(R.string.speed_placeholder)
    }

    private fun applyCurrentModeAndVolume() {
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)
        val mode = when (modeGroup.checkedRadioButtonId) {
            R.id.radioEngine -> SoundMode.ENGINE_V6
            R.id.radioSpaceship -> SoundMode.SPACESHIP
            else -> SoundMode.OFF
        }
        soundService?.setMode(mode)
        val volumeSeek = findViewById<SeekBar>(R.id.volumeSeek)
        soundService?.setVolume(volumeSeek.progress / 100f)
    }

    // ---------------- SoundService.UpdateListener ----------------

    override fun onSpeedUpdate(speedKmh: Float, rpm: Int) {
        runOnUiThread {
            speedTextRef?.text = getString(R.string.speed_format, speedKmh.toInt(), rpm)
        }
    }

    override fun onLocationStale() {
        runOnUiThread {
            speedTextRef?.text = getString(R.string.gps_waiting)
        }
    }

    override fun onStart() {
        super.onStart()
        if (bound) return
        if (powerToggleRef?.isChecked == true) {
            bindService(Intent(this, SoundService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        // 화면이 안 보이더라도 서비스는 포그라운드로 계속 돌아야 하므로 바인딩만 해제하고
        // 서비스 자체(startForegroundService)는 멈추지 않는다.
        if (bound) {
            soundService?.setListener(null)
            unbindService(connection)
            bound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        staleCheckHandler.removeCallbacks(staleCheckRunnable)
    }
}
