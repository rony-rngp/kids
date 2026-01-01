package com.kidsmonitor

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kidsmonitor.databinding.ActivityMainBinding
import com.kidsmonitor.receivers.MyDeviceAdminReceiver
import com.kidsmonitor.services.MonitorService
import com.kidsmonitor.utils.MonitorActions
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var componentName: ComponentName
    private var isServiceRunning = false
    private var myDeviceId: String = ""

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.CAMERA, 
            Manifest.permission.RECORD_AUDIO, 
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA, 
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MonitorService.ACTION_STATUS_UPDATE) {
                isServiceRunning = intent.getBooleanExtra(MonitorService.EXTRA_IS_RUNNING, false)
                val progress = intent.getIntExtra(MonitorService.EXTRA_PROGRESS, 0)
                updateStatus(isServiceRunning, progress)
            }
        }
    }

    private val autoStartPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val sharedPrefs = getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
        with(sharedPrefs.edit()) {
            putBoolean("auto_start_enabled", true)
            apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init()

        // Get ID (Logic matching MonitorService)
        val sharedPrefs = getSharedPreferences("MKLMonitorPrefs", Context.MODE_PRIVATE)
        val existingId = sharedPrefs.getString("deviceId", null)
        if (existingId == null || existingId.length > 6) {
            myDeviceId = (100000..999999).random().toString()
            sharedPrefs.edit().putString("deviceId", myDeviceId).apply()
        } else {
            myDeviceId = existingId
        }
        binding.tvMyDeviceId.text = myDeviceId
        
        // Load Device Name
        val savedName = sharedPrefs.getString("deviceName", "My Phone")
        binding.etDeviceName.setText(savedName)
        
        // Save Name on Change
        binding.etDeviceName.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                sharedPrefs.edit().putString("deviceName", s.toString()).apply()
                // Notify service to update name
                val intent = Intent(this@MainActivity, MonitorService::class.java).apply {
                    action = MonitorActions.ACTION_UPDATE_CONFIG
                }
                startService(intent)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnAdmin.setOnClickListener { enableDeviceAdmin() }
        binding.btnAutoStart.setOnClickListener { requestAutoStartPermission() }

        // Auto-enable monitoring flag
        getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("monitoring_enabled", true)
            .apply()
            
        if (checkPermissions()) {
            startMonitorService()
        }
    }

    private fun init() {
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        val filter = IntentFilter(MonitorService.ACTION_STATUS_UPDATE)
        registerReceiver(serviceStatusReceiver, filter)
        
        // Ensure service is running if we have permissions
        if (checkPermissions() && !isServiceRunning) {
            startMonitorService()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(serviceStatusReceiver)
    }

    private fun updateUi() {
        if (isDeviceAdminEnabled()) {
            binding.btnAdmin.visibility = View.GONE
            requestIgnoreBatteryOptimizations()
            if (isFirstLaunch()) requestAutoStartPermission()

            if (isServiceRunning) {
                binding.tvStatus.text = "Status: Server Running"
                binding.tvStatus.visibility = View.VISIBLE
                binding.progressBar.visibility = View.VISIBLE
            } else {
                binding.tvStatus.text = "Status: Starting..."
                binding.tvStatus.visibility = View.VISIBLE
                binding.progressBar.visibility = View.INVISIBLE
            }
        } else {
            binding.btnAdmin.visibility = View.VISIBLE
            binding.tvStatus.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            if (!checkPermissions()) requestPermissions()
        }
        
        val sharedPrefs = getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
        binding.btnAutoStart.visibility = if (!sharedPrefs.getBoolean("auto_start_enabled", false)) View.VISIBLE else View.GONE
    }

    private fun startMonitorService() {
        if (checkPermissions()) {
            val intent = Intent(this, MonitorService::class.java).apply {
                action = MonitorActions.ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            requestPermissions()
        }
    }

    // stopMonitorService removed as it is no longer user-accessible

    private fun isDeviceAdminEnabled() = devicePolicyManager.isAdminActive(componentName)

    private fun enableDeviceAdmin() {
        if (checkPermissions()) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allow MKL admin so it cannot be easily uninstalled.")
            }
            startActivity(intent)
        } else {
            requestPermissions()
        }
    }

    private fun checkPermissions() = requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }
    }

    private fun requestAutoStartPermission() {
        // ... (Keep existing logic or simplify)
        val sharedPrefs = getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
        try {
            val intent = Intent()
            val manufacturer = android.os.Build.MANUFACTURER
            if ("xiaomi".equals(manufacturer, true)) intent.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            else if ("oppo".equals(manufacturer, true)) intent.component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            else if ("vivo".equals(manufacturer, true)) intent.component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            
            if (intent.resolveActivity(packageManager) != null) autoStartPermissionLauncher.launch(intent)
            else sharedPrefs.edit().putBoolean("auto_start_enabled", true).apply()
        } catch (e: Exception) {}
        sharedPrefs.edit().putBoolean("first_launch", false).apply()
    }

    private fun isFirstLaunch() = getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE).getBoolean("first_launch", true)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            // Permissions granted, auto start
            startMonitorService()
        }
    }

    private fun updateStatus(isRunning: Boolean, progress: Int) {
        if (isRunning) {
            binding.tvStatus.text = "Status: Server Running"
            binding.progressBar.progress = progress
            binding.progressBar.visibility = View.VISIBLE
        } else {
            binding.tvStatus.text = "Status: Not running"
            binding.progressBar.visibility = View.INVISIBLE
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }
}