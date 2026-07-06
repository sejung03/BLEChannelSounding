package com.example.blecsreflector.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager

class CapabilityChecker(
    private val context: Context
) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    /**
     * BLE 지원 여부
     */
    fun supportsBle(): Boolean {
        return context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_BLUETOOTH_LE
        )
    }

    /**
     * Bluetooth 활성화 여부
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * BLE Advertising 지원 여부
     */
    fun supportsAdvertising(): Boolean {
        return bluetoothAdapter?.isMultipleAdvertisementSupported == true
    }

    /**
     * BLE Channel Sounding 지원 여부
     *
     * TODO(Android16):
     * BluetoothLeRanging API를 이용하여
     * 실제 Channel Sounding Capability를 확인하도록 변경
     */
    fun supportsChannelSounding(): Boolean {
        return supportsBle() &&
                isBluetoothEnabled() &&
                supportsAdvertising()
    }
}