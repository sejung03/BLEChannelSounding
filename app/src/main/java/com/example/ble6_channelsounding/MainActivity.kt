package com.example.ble6_channelsounding

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ble6_channelsounding.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(),
    BlePeerCoordinator.Listener,
    ChannelSoundingController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleCoordinator: BlePeerCoordinator
    private var csController: ChannelSoundingController? = null
    private lateinit var csvLogger: CsCsvLogger

    private enum class UiRole { INITIATOR, REFLECTOR }

    private var uiRole = UiRole.INITIATOR

    private val foundDevices = linkedMapOf<String, BluetoothDevice>()
    private val deviceLabels = mutableListOf<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var selectedAddress: String? = null
    private var controlReady = false

    /* Reflector가 완전히 준비된 뒤 Initiator를 약간 늦게 시작하여 역할 반전 안정성 향상 */
    private val csStartHandler = Handler(Looper.getMainLooper())

    private val logLines = ArrayDeque<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys

        if (denied.isEmpty()) {
            log("모든 권한 허용 완료")
            initChannelSounding()
        } else {
            log("권한 거부: $denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        csvLogger = CsCsvLogger(applicationContext)

        if (Build.VERSION.SDK_INT < 36) {
            binding.capabilityText.text = "Android 16 / API 36 이상 필요"
            disableAllActions()
            return
        }

        bleCoordinator = BlePeerCoordinator(this, this)
        bleCoordinator.register()

        deviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice,
            deviceLabels
        )

        binding.deviceList.adapter = deviceAdapter

        binding.deviceList.setOnItemClickListener { _, _, position, _ ->
            val address = foundDevices.keys.elementAtOrNull(position)
                ?: return@setOnItemClickListener

            selectedAddress = address
            foundDevices[address]?.let(bleCoordinator::selectDevice)
            binding.selectedDeviceText.text = "선택 장치: $address"
        }

        binding.applyRoleButton.setOnClickListener {
            applySelectedRole()
        }

        binding.scanButton.setOnClickListener {
            foundDevices.clear()
            deviceLabels.clear()
            deviceAdapter.notifyDataSetChanged()

            selectedAddress = null
            controlReady = false
            binding.startCsButton.isEnabled = false

            bleCoordinator.startScan()
        }

        binding.pairButton.setOnClickListener {
            controlReady = false
            binding.startCsButton.isEnabled = false
            bleCoordinator.connectAndPairSelected()
        }

        binding.startCsButton.setOnClickListener {
            if (!controlReady) {
                log("먼저 연결·페어링·GATT 준비를 완료하세요.")
                return@setOnClickListener
            }

            binding.startCsButton.isEnabled = false
            startCsvSession(UiRole.INITIATOR, selectedAddress)
            bleCoordinator.requestStartFromReflector()
        }

        binding.advertiseButton.setOnClickListener {
            bleCoordinator.startReflectorAdvertising()
        }

        binding.stopButton.setOnClickListener {
            stopEverything()
        }

        requestPermissionsIfNeeded()
        applySelectedRole()
    }

    @RequiresApi(36)
    private fun initChannelSounding() {
        if (csController == null) {
            csController = ChannelSoundingController(this, this)
        }

        csController?.checkCapability()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.RANGING
        )

        val missing = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 36) {
                initChannelSounding()
            }
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun applySelectedRole() {
        stopEverything(keepLog = true)

        uiRole = if (binding.reflectorRadio.isChecked) {
            UiRole.REFLECTOR
        } else {
            UiRole.INITIATOR
        }

        val initiator = uiRole == UiRole.INITIATOR

        binding.initiatorPanel.visibility =
            if (initiator) View.VISIBLE else View.GONE

        binding.reflectorPanel.visibility =
            if (initiator) View.GONE else View.VISIBLE

        binding.distanceText.text = "--.-- m"

        binding.rawDistanceText.text = if (initiator) {
            "Raw: - / samples: 0"
        } else {
            "Initiator가 전달하는 거리값 대기 중"
        }

        controlReady = false
        binding.startCsButton.isEnabled = false

        log("역할 적용: $uiRole")
    }

    private fun stopEverything(keepLog: Boolean = false) {
        if (!keepLog) {
            log("전체 세션 중지", "APP")
        }
        csStartHandler.removeCallbacksAndMessages(null)
        csController?.stop()

        if (::bleCoordinator.isInitialized) {
            bleCoordinator.stopAll()
        }

        controlReady = false

        if (::binding.isInitialized) {
            binding.startCsButton.isEnabled = false
        }

        if (::csvLogger.isInitialized) csvLogger.close()
    }

    override fun onDestroy() {
        stopEverything(keepLog = true)

        if (::bleCoordinator.isInitialized) {
            bleCoordinator.unregister()
        }

        super.onDestroy()
    }

    override fun onLog(message: String) {
        log(message, "BLE")
    }

    @SuppressLint("MissingPermission")
    override fun onScanDevice(
        device: BluetoothDevice,
        name: String,
        rssi: Int
    ) {
        val address = device.address.uppercase(Locale.US)

        if (!foundDevices.containsKey(address)) {
            foundDevices[address] = device
            deviceLabels.add("$name\n$address   RSSI=$rssi dBm")

            runOnUiThread {
                deviceAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onSelectedPeerConnected(address: String) {
        runOnUiThread {
            if (uiRole == UiRole.REFLECTOR) {
                binding.reflectorPeerText.text =
                    "연결된 Initiator: $address"
            } else {
                binding.selectedDeviceText.text =
                    "연결된 Reflector: $address"
            }
        }
    }

    override fun onBondState(address: String, state: Int) {
        val stateText = when (state) {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            else -> "NONE"
        }

        log("Bond state $address: $stateText")
    }

    override fun onControlChannelReady(address: String) {
        controlReady = true

        runOnUiThread {
            binding.startCsButton.isEnabled = true
            binding.selectedDeviceText.text =
                "페어링 및 제어·거리 채널 준비 완료: $address"
        }

        log("GATT 제어 및 거리 채널 준비 완료")
    }

    override fun onStartReflectorRequested(initiatorAddress: String) {
        if (uiRole != UiRole.REFLECTOR) return

        startCsvSession(UiRole.REFLECTOR, initiatorAddress)
        log("Initiator로부터 START 수신: $initiatorAddress")

        runOnUiThread {
            val controller = csController

            if (controller == null) {
                val message = "ChannelSoundingController가 초기화되지 않았습니다."
                log(message)
                bleCoordinator.notifyReflectorFailure(message)
                return@runOnUiThread
            }

            binding.distanceText.text = "--.-- m"
            binding.rawDistanceText.text = "Initiator 거리값 수신 대기 중"

            bleCoordinator.notifyReflectorPreparing()
            controller.startReflector(
                initiatorAddress.uppercase(Locale.US)
            )
        }
    }

    override fun onStopReflectorRequested() {
        log("Initiator로부터 STOP 수신")

        runOnUiThread {
            csController?.stop()
            bleCoordinator.notifyReflectorStopped()
            binding.rawDistanceText.text = "거리 공유 중지됨"
        }
    }

    override fun onReflectorReady(reflectorAddress: String) {
        if (uiRole != UiRole.INITIATOR) return

        val normalizedAddress = reflectorAddress.uppercase(Locale.US)

        log("Reflector READY 수신 → 800 ms 후 Initiator CS 시작")

        csStartHandler.removeCallbacksAndMessages(null)
        csStartHandler.postDelayed({
            if (uiRole != UiRole.INITIATOR || !controlReady) {
                log("역할 또는 GATT 상태 변경으로 Initiator 시작 취소")
                restoreStartButton()
                return@postDelayed
            }

            val controller = csController
            if (controller == null) {
                log("CS 오류: ChannelSoundingController가 초기화되지 않았습니다.")
                restoreStartButton()
                return@postDelayed
            }

            controller.startInitiator(normalizedAddress)
        }, 800L)
    }

    override fun onReflectorError(message: String) {
        log("BLE 오류: $message")
        csvLogger.close()
        restoreStartButton()
    }

    override fun onRemoteDistance(
        rawMeters: Double,
        smoothedMeters: Double,
        sampleCount: Int
    ) {
        if (uiRole != UiRole.REFLECTOR) return

        csvLogger.distance("REMOTE", rawMeters, smoothedMeters, sampleCount)

        runOnUiThread {
            binding.distanceText.text = String.format(
                Locale.US,
                "%.2f m",
                smoothedMeters
            )

            binding.rawDistanceText.text = String.format(
                Locale.US,
                "Initiator relay / Raw: %.3f m / 5-sample mean: %.3f m / samples: %d",
                rawMeters,
                smoothedMeters,
                sampleCount
            )
        }
    }

    override fun onDisconnected() {
        controlReady = false
        csStartHandler.removeCallbacksAndMessages(null)

        runOnUiThread {
            binding.startCsButton.isEnabled = false
        }

        log("Peer 연결 해제")
        csvLogger.close()
    }

    override fun onCapability(supported: Boolean, detail: String) {
        runOnUiThread {
            binding.capabilityText.text = "CS capability: $detail"
        }

        log("Capability: $supported / $detail")
    }

    override fun onSessionOpened(role: ChannelSoundingController.Role) {
        log("RangingSession opened: $role")

        if (role == ChannelSoundingController.Role.REFLECTOR) {
            bleCoordinator.notifyReflectorReady()
        }
    }

    override fun onRangingStarted(role: ChannelSoundingController.Role) {
        log("CS started: $role")
    }

    override fun onDistance(
        rawMeters: Double,
        smoothedMeters: Double,
        sampleCount: Int
    ) {
        if (uiRole != UiRole.INITIATOR) return

        csvLogger.distance("LOCAL", rawMeters, smoothedMeters, sampleCount)

        runOnUiThread {
            binding.distanceText.text = String.format(
                Locale.US,
                "%.2f m",
                smoothedMeters
            )

            binding.rawDistanceText.text = String.format(
                Locale.US,
                "Raw: %.3f m / 5-sample mean: %.3f m / samples: %d",
                rawMeters,
                smoothedMeters,
                sampleCount
            )
        }

        /* Initiator가 받은 값을 Reflector GATT server로 전달 */
        bleCoordinator.sendDistanceToReflector(
            rawMeters = rawMeters,
            smoothedMeters = smoothedMeters,
            sampleCount = sampleCount
        )
    }

    override fun onRangingStopped(role: ChannelSoundingController.Role) {
        log("CS stopped: $role")
        restoreStartButton()
    }

    override fun onError(message: String) {
        log("CS 오류: $message")

        if (uiRole == UiRole.REFLECTOR) {
            bleCoordinator.notifyReflectorFailure(message)
        } else {
            restoreStartButton()
        }
    }

    override fun onClosed(reason: Int) {
        log("RangingSession closed: reason=$reason")
        csvLogger.close()
        restoreStartButton()
    }

    private fun restoreStartButton() {
        if (uiRole != UiRole.INITIATOR || !controlReady) return

        runOnUiThread {
            binding.startCsButton.isEnabled = true
        }
    }

    private fun startCsvSession(role: UiRole, peerAddress: String?) {
        val uri = csvLogger.start(role.name, peerAddress)
        if (uri == null) {
            log("CSV 파일 생성 실패: 다운로드/PhoneCS 폴더를 확인하세요.", "STORAGE")
        } else {
            log("CSV 저장 시작: 다운로드/PhoneCS", "STORAGE")
        }
    }

    private fun log(message: String, source: String = "CS") {
        val time = SimpleDateFormat(
            "HH:mm:ss.SSS",
            Locale.US
        ).format(Date())

        val line = "$time  $message"

        if (::csvLogger.isInitialized) {
            csvLogger.event(source, message)
        }

        while (logLines.size >= 100) {
            logLines.removeFirst()
        }

        logLines.addLast(line)

        if (::binding.isInitialized) {
            runOnUiThread {
                binding.statusText.text = logLines.joinToString("\n")
            }
        }
    }

    private fun disableAllActions() {
        binding.applyRoleButton.isEnabled = false
        binding.scanButton.isEnabled = false
        binding.pairButton.isEnabled = false
        binding.startCsButton.isEnabled = false
        binding.advertiseButton.isEnabled = false
        binding.stopButton.isEnabled = false
    }
}
