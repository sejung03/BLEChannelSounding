package com.example.blecsreflector.ranging

import android.content.Context
import android.ranging.RangingCapabilities
import android.ranging.RangingManager
import com.example.blecsreflector.CsSupport
import com.example.blecsreflector.ReflectorStateStore

class CsCapabilityMonitor(private val context: Context) {
    private val manager = context.getSystemService(RangingManager::class.java)
    private val executor = context.mainExecutor
    private var registered = false

    private val callback = object : RangingManager.RangingCapabilitiesCallback {
        override fun onRangingCapabilities(capabilities: RangingCapabilities) {
            val availability = capabilities.technologyAvailability[RangingManager.BLE_CS]
                ?: RangingCapabilities.NOT_SUPPORTED
            val support = when (availability) {
                RangingCapabilities.ENABLED -> CsSupport.AVAILABLE
                RangingCapabilities.NOT_SUPPORTED -> CsSupport.UNSUPPORTED
                RangingCapabilities.DISABLED_REGULATORY,
                RangingCapabilities.DISABLED_USER,
                RangingCapabilities.DISABLED_USER_RESTRICTIONS -> CsSupport.DISABLED
                else -> CsSupport.ERROR
            }
            val levels = capabilities.csCapabilities?.supportedSecurityLevels?.toSet().orEmpty()

            ReflectorStateStore.update {
                it.copy(csSupport = support, csSecurityLevels = levels)
            }
            ReflectorStateStore.log("BLE CS capability: $support, security=${levels.sorted()}")
        }
    }

    fun start() {
        if (registered) return
        try {
            manager.registerCapabilitiesCallback(executor, callback)
            registered = true
        } catch (error: RuntimeException) {
            ReflectorStateStore.update { it.copy(csSupport = CsSupport.ERROR) }
            ReflectorStateStore.log("Capability query failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!registered) return
        try {
            manager.unregisterCapabilitiesCallback(callback)
        } catch (_: RuntimeException) {
            // The ranging service may already have been torn down.
        }
        registered = false
    }
}
