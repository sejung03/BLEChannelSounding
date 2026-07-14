package com.example.blecsreflector.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.example.blecsreflector.CsSupport
import com.example.blecsreflector.R
import com.example.blecsreflector.RangingSessionState
import com.example.blecsreflector.ReflectorStateStore
import com.example.blecsreflector.ReflectorUiState
import com.example.blecsreflector.bluetooth.ReflectorGattContract
import com.example.blecsreflector.bluetooth.ReflectorService

class MainActivity : AppCompatActivity() {
    private lateinit var views: MainViews
    private val stateObserver: (ReflectorUiState) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        views = MainViews(this)

        views.androidValue.text = getString(
            R.string.android_version_format,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT
        )
        views.serviceUuidValue.text = ReflectorGattContract.SERVICE_UUID_TEXT
        views.rxUuidValue.text = ReflectorGattContract.RX_UUID_TEXT
        views.txUuidValue.text = ReflectorGattContract.TX_UUID_TEXT

        views.toggleServiceButton.setOnClickListener {
            if (ReflectorStateStore.snapshot().serviceRunning) {
                ReflectorService.stop(this)
            } else {
                startWithPermissions()
            }
        }
        views.enableBluetoothButton.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (_: SecurityException) {
                    requestPermissions(
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                        REQUEST_PERMISSIONS
                    )
                }
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_PERMISSIONS
                )
            }
        }
        views.clearLogButton.setOnClickListener {
            ReflectorStateStore.clearLogs()
        }
    }

    override fun onStart() {
        super.onStart()
        ReflectorStateStore.observe(stateObserver)
        refreshBluetoothState()
    }

    override fun onStop() {
        ReflectorStateStore.removeObserver(stateObserver)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (essentialPermissions().all(::isGranted)) {
            ReflectorService.start(this)
        } else {
            ReflectorStateStore.log("Required Nearby devices permissions were denied")
        }
    }

    private fun startWithPermissions() {
        val missing = requestedPermissions().filterNot(::isGranted)
        if (missing.isEmpty()) {
            ReflectorService.start(this)
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun essentialPermissions(): List<String> = listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.RANGING
    )

    private fun requestedPermissions(): List<String> =
        essentialPermissions() + Manifest.permission.POST_NOTIFICATIONS

    private fun isGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshBluetoothState() {
        val enabled = if (isGranted(Manifest.permission.BLUETOOTH_CONNECT)) {
            getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
        } else {
            false
        }
        ReflectorStateStore.update { it.copy(bluetoothEnabled = enabled) }
    }

    private fun render(state: ReflectorUiState) {
        val presentation = statusPresentation(state)
        views.statusTitle.text = presentation.title
        views.statusSubtitle.text = presentation.subtitle
        views.statusIndicator.backgroundTintList = ColorStateList.valueOf(getColor(presentation.color))

        views.toggleServiceButton.text = getString(
            if (state.serviceRunning) R.string.stop_reflector else R.string.start_reflector
        )
        views.enableBluetoothButton.visibility =
            if (state.serviceRunning && !state.bluetoothEnabled) View.VISIBLE else View.GONE

        views.bluetoothValue.text = getString(
            if (state.bluetoothEnabled) R.string.value_on else R.string.value_off
        )
        views.bluetoothValue.setTextColor(
            getColor(if (state.bluetoothEnabled) R.color.reflector_success else R.color.reflector_error)
        )

        views.csValue.text = when (state.csSupport) {
            CsSupport.CHECKING -> "Checking"
            CsSupport.AVAILABLE -> "Available"
            CsSupport.DISABLED -> "Disabled"
            CsSupport.UNSUPPORTED -> "Unsupported"
            CsSupport.REQUIRES_ANDROID_16 -> "Requires Android 16"
            CsSupport.ERROR -> "Query failed"
        }
        views.csValue.setTextColor(
            getColor(
                when (state.csSupport) {
                    CsSupport.AVAILABLE -> R.color.reflector_success
                    CsSupport.CHECKING -> R.color.reflector_secondary
                    CsSupport.DISABLED -> R.color.reflector_warning
                    else -> R.color.reflector_error
                }
            )
        )
        views.securityValue.text = if (state.csSecurityLevels.isEmpty()) {
            getString(R.string.value_unknown)
        } else {
            state.csSecurityLevels.sorted().joinToString { "L$it" }
        }

        views.deviceValue.text = when {
            state.connectedDeviceAddress == null -> getString(R.string.value_not_connected)
            state.connectedDeviceName.isNullOrBlank() -> state.connectedDeviceAddress
            else -> "${state.connectedDeviceName}\n${state.connectedDeviceAddress}"
        }
        views.bondValue.text = when (state.bondState) {
            BluetoothDevice.BOND_BONDED -> "Bonded"
            BluetoothDevice.BOND_BONDING -> "Bonding"
            BluetoothDevice.BOND_NONE -> "Not bonded"
            else -> getString(R.string.value_unknown)
        }
        views.bondValue.setTextColor(
            getColor(
                when (state.bondState) {
                    BluetoothDevice.BOND_BONDED -> R.color.reflector_success
                    BluetoothDevice.BOND_BONDING -> R.color.reflector_warning
                    else -> R.color.reflector_text
                }
            )
        )
        views.subscriptionValue.text = getString(
            if (state.indicationsEnabled) R.string.value_yes else R.string.value_no
        )
        views.sessionValue.text = sessionLabel(state.sessionState)
        views.mtuValue.text = state.mtu.toString()
        views.logValue.text = if (state.logs.isEmpty()) {
            "No events"
        } else {
            state.logs.joinToString("\n")
        }
    }

    private fun statusPresentation(state: ReflectorUiState): StatusPresentation {
        return when {
            !state.serviceRunning -> StatusPresentation(
                "Stopped",
                "Reflector service is idle",
                R.color.reflector_neutral
            )
            !state.bluetoothEnabled -> StatusPresentation(
                "Bluetooth off",
                "Reflector service is waiting",
                R.color.reflector_error
            )
            state.csSupport == CsSupport.UNSUPPORTED -> StatusPresentation(
                "BLE CS unsupported",
                "The current Android device reports no CS capability",
                R.color.reflector_error
            )
            state.csSupport == CsSupport.DISABLED -> StatusPresentation(
                "BLE CS unavailable",
                "Channel Sounding is disabled by the system",
                R.color.reflector_warning
            )
            state.sessionState == RangingSessionState.ACTIVE -> StatusPresentation(
                "Channel sounding active",
                "Nordic initiator controls the measurement session",
                R.color.reflector_success
            )
            state.sessionState == RangingSessionState.OPEN -> StatusPresentation(
                "Reflector ready",
                "OOB responder session is open",
                R.color.reflector_success
            )
            state.connectedDeviceAddress == null && state.advertising -> StatusPresentation(
                "Advertising",
                "Waiting for Nordic initiator",
                R.color.reflector_secondary
            )
            state.bondState == BluetoothDevice.BOND_BONDING -> StatusPresentation(
                "Pairing",
                "Securing the Nordic link",
                R.color.reflector_warning
            )
            state.connectedDeviceAddress != null && !state.indicationsEnabled -> StatusPresentation(
                "Nordic connected",
                "Waiting for OOB indication subscription",
                R.color.reflector_secondary
            )
            state.sessionState == RangingSessionState.OPENING -> StatusPresentation(
                "Opening responder",
                "Android Ranging is negotiating OOB transport",
                R.color.reflector_warning
            )
            state.sessionState == RangingSessionState.FAILED -> StatusPresentation(
                "Responder failed",
                "See the event log for the platform error",
                R.color.reflector_error
            )
            else -> StatusPresentation(
                "Starting",
                "Preparing BLE advertising and GATT",
                R.color.reflector_warning
            )
        }
    }

    private fun sessionLabel(state: RangingSessionState): String = when (state) {
        RangingSessionState.IDLE -> "Idle"
        RangingSessionState.WAITING_FOR_BOND -> "Waiting for bond"
        RangingSessionState.WAITING_FOR_INDICATIONS -> "Waiting for indications"
        RangingSessionState.OPENING -> "Opening"
        RangingSessionState.OPEN -> "Ready"
        RangingSessionState.ACTIVE -> "CS active"
        RangingSessionState.FAILED -> "Failed"
        RangingSessionState.CLOSED -> "Closed"
    }

    private data class StatusPresentation(
        val title: String,
        val subtitle: String,
        val color: Int
    )

    private class MainViews(activity: MainActivity) {
        val statusIndicator: View = activity.findViewById(R.id.statusIndicator)
        val statusTitle: TextView = activity.findViewById(R.id.statusTitle)
        val statusSubtitle: TextView = activity.findViewById(R.id.statusSubtitle)
        val toggleServiceButton: MaterialButton = activity.findViewById(R.id.toggleServiceButton)
        val enableBluetoothButton: MaterialButton = activity.findViewById(R.id.enableBluetoothButton)
        val clearLogButton: MaterialButton = activity.findViewById(R.id.clearLogButton)
        val androidValue: TextView = activity.findViewById(R.id.androidValue)
        val bluetoothValue: TextView = activity.findViewById(R.id.bluetoothValue)
        val csValue: TextView = activity.findViewById(R.id.csValue)
        val securityValue: TextView = activity.findViewById(R.id.securityValue)
        val deviceValue: TextView = activity.findViewById(R.id.deviceValue)
        val bondValue: TextView = activity.findViewById(R.id.bondValue)
        val subscriptionValue: TextView = activity.findViewById(R.id.subscriptionValue)
        val sessionValue: TextView = activity.findViewById(R.id.sessionValue)
        val mtuValue: TextView = activity.findViewById(R.id.mtuValue)
        val serviceUuidValue: TextView = activity.findViewById(R.id.serviceUuidValue)
        val rxUuidValue: TextView = activity.findViewById(R.id.rxUuidValue)
        val txUuidValue: TextView = activity.findViewById(R.id.txUuidValue)
        val logValue: TextView = activity.findViewById(R.id.logValue)
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }
}
