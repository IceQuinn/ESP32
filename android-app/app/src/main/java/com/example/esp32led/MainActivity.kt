package com.example.esp32led

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), BleController.Listener {
    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
        private const val REQUEST_ENABLE_BLUETOOTH = 1002
    }

    private lateinit var bleController: BleController
    private lateinit var connectionStatusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var ledStatusText: TextView
    private lateinit var scanButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var onButton: Button
    private lateinit var offButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionStatusText = findViewById(R.id.connectionStatusText)
        deviceText = findViewById(R.id.deviceText)
        ledStatusText = findViewById(R.id.ledStatusText)
        scanButton = findViewById(R.id.scanButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        onButton = findViewById(R.id.onButton)
        offButton = findViewById(R.id.offButton)

        bleController = BleController(this, this)

        scanButton.setOnClickListener { requestPermissionsAndScan() }
        disconnectButton.setOnClickListener {
            bleController.disconnect()
            setControlsEnabled(false)
        }
        onButton.setOnClickListener { bleController.setLed(true) }
        offButton.setOnClickListener { bleController.setLed(false) }
    }

    private fun requestPermissionsAndScan() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            onError("这台手机不支持 BLE")
            return
        }

        val missing = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
        } else {
            ensureBluetoothAndScan()
        }
    }

    private fun ensureBluetoothAndScan() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter

        if (!adapter.isEnabled) {
            startActivityForResult(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                REQUEST_ENABLE_BLUETOOTH
            )
            return
        }

        setControlsEnabled(false)
        bleController.startScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            ) {
                ensureBluetoothAndScan()
            } else {
                onError("需要“附近设备”权限才能连接 ESP32")
            }
        }
    }

    @Deprecated("Activity result API retained to avoid external dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                bleController.startScan()
            } else {
                onError("需要打开蓝牙才能继续")
            }
        }
    }

    override fun onStatusChanged(status: String) {
        connectionStatusText.text = status
        if (status == "连接已断开") {
            setControlsEnabled(false)
            ledStatusText.text = getString(R.string.led_unknown)
        }
    }

    override fun onDeviceFound(name: String, address: String) {
        deviceText.text = "$name\n$address"
    }

    override fun onReady() {
        setControlsEnabled(true)
    }

    override fun onLedStateChanged(isOn: Boolean) {
        ledStatusText.text = if (isOn) "已开启" else "已关闭"
    }

    override fun onError(message: String) {
        connectionStatusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        onButton.isEnabled = enabled
        offButton.isEnabled = enabled
        disconnectButton.isEnabled = enabled
        scanButton.isEnabled = !enabled
    }

    override fun onDestroy() {
        bleController.close()
        super.onDestroy()
    }
}

