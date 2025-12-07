package com.kidsmonitor

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kidsmonitor.camera.CameraFacing
import com.kidsmonitor.databinding.ActivityMainBinding
import com.kidsmonitor.network.NetworkUtils
import com.kidsmonitor.receivers.MyDeviceAdminReceiver
import com.kidsmonitor.services.MonitorService
import com.kidsmonitor.utils.MonitorActions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isMicOn = false

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            if (checkPermissions()) {
                startMonitorService()
            } else {
                requestPermissions()
            }
        }

        binding.btnStop.setOnClickListener {
            stopMonitorService()
        }

        binding.btnFront.setOnClickListener {
            switchCamera(CameraFacing.FRONT)
        }

        binding.btnBack.setOnClickListener {
            switchCamera(CameraFacing.BACK)
        }

        binding.btnMic.setOnClickListener {
            isMicOn = !isMicOn
            val intent = Intent(this, MonitorService::class.java)
            if (isMicOn) {
                binding.btnMic.text = "Mic: ON"
                intent.action = MonitorActions.ACTION_AUDIO_ON
            } else {
                binding.btnMic.text = "Mic: OFF"
                intent.action = MonitorActions.ACTION_AUDIO_OFF
            }
            startService(intent)
        }

        binding.btnAdmin.setOnClickListener {
            enableDeviceAdmin()
        }
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorActions.ACTION_START_MONITORING
        }
        ContextCompat.startForegroundService(this, intent)
        val ip = NetworkUtils.getLocalIpAddress(this)
        binding.tvStatus.text = "Monitoring: http://$ip:8081 (video), ws://$ip:8082 (audio)"
    }

    private fun stopMonitorService() {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorActions.ACTION_STOP_MONITORING
        }
        startService(intent)
        binding.tvStatus.text = "Status: Not running"
    }

    private fun switchCamera(facing: Int) {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorActions.ACTION_SWITCH_CAMERA
            putExtra(MonitorActions.EXTRA_FACING, facing)
        }
        startService(intent)
    }

    private fun enableDeviceAdmin() {
        val componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allow Kids Monitor admin so it cannot be easily uninstalled by your child.")
        }
        startActivity(intent)
    }

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            requiredPermissions,
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startMonitorService()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }
}
