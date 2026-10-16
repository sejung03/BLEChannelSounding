package com.example.blecsranger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import com.example.blecsranger.model.AttemptLogRecord
import com.example.blecsranger.model.BoardConfig
import com.example.blecsranger.model.BoardMeasurementRecord
import com.example.blecsranger.model.CycleMeasurementRecord
import com.example.blecsranger.ranging.AndroidBleCsMeasurementSource
import com.example.blecsranger.ranging.CycleRangingController
import com.example.blecsranger.ranging.GattConnectionKeeper
import com.example.blecsranger.ranging.SimulatedMeasurementSource
import com.example.blecsranger.sensing.ImuSampler
import com.example.blecsranger.storage.BoardPreferences
import com.example.blecsranger.storage.CsvMeasurementLogger
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), CycleRangingController.Listener, GattConnectionKeeper.Listener {
    private data class BoardViews(
        val container: View,
        val name: TextView,
        val status: TextView,
        val mac: EditText,
        val distance: TextView,
    )

    private lateinit var boardViews: List<BoardViews>
    private lateinit var simulationSwitch: Switch
    private lateinit var dynamicExperimentSwitch: Switch
    private lateinit var supportStatus: TextView
    private lateinit var cycleTime: TextView
    private lateinit var cycleCount: TextView
    private lateinit var phaseValue: TextView
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var preferences: BoardPreferences
    private lateinit var connectionKeeper: GattConnectionKeeper
    private lateinit var csvLogger: CsvMeasurementLogger
    private lateinit var imuSampler: ImuSampler

    private var controller: CycleRangingController? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private val logs = ArrayDeque<String>()
    private val imuCsvErrorReported = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        preferences = BoardPreferences(this)
        connectionKeeper = GattConnectionKeeper(this, this)
        csvLogger = CsvMeasurementLogger(this)
        imuSampler = ImuSampler(
            context = this,
            onSample = { sample ->
                csvLogger.appendImuSample(sample) { error ->
                    if (error != null && imuCsvErrorReported.compareAndSet(false, true)) {
                        runOnUiThread { appendLog("IMU CSV 실패: ${error.message}") }
                    }
                }
            },
            onError = { error ->
                if (imuCsvErrorReported.compareAndSet(false, true)) {
                    runOnUiThread { appendLog("IMU 로깅 실패: ${error.message}") }
                }
            },
        )
        populateBoards(preferences.load())
        updateHardwareStatus()
        wireActions()
        appendLog("앱 준비 완료 · 기본은 시뮬레이션 모드입니다")
    }

    private fun bindViews() {
        val containers = listOf(
            findViewById<View>(R.id.boardContainerA),
            findViewById<View>(R.id.boardContainerB),
            findViewById<View>(R.id.boardContainerC),
            findViewById<View>(R.id.boardContainerD),
        )
        boardViews = containers.map { container ->
            BoardViews(
                container = container,
                name = container.findViewById(R.id.boardName),
                status = container.findViewById(R.id.connectionStatus),
                mac = container.findViewById(R.id.macAddress),
                distance = container.findViewById(R.id.distanceValue),
            )
        }
        simulationSwitch = findViewById(R.id.simulationSwitch)
        dynamicExperimentSwitch = findViewById(R.id.dynamicExperimentSwitch)
        supportStatus = findViewById(R.id.supportStatus)
        cycleTime = findViewById(R.id.cycleTime)
        cycleCount = findViewById(R.id.cycleCount)
        phaseValue = findViewById(R.id.phaseValue)
        logView = findViewById(R.id.logView)
        connectButton = findViewById(R.id.connectButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
    }

    private fun populateBoards(boards: List<BoardConfig>) {
        boards.forEach { board ->
            val views = boardViews[board.index]
            views.name.text = "Board ${board.label}"
            views.mac.setText(board.macAddress)
            views.status.text = if (simulationSwitch.isChecked) "SIM" else "미연결"
        }
    }

    private fun wireActions() {
        simulationSwitch.setOnCheckedChangeListener { _, simulated ->
            stopMeasurement()
            connectionKeeper.closeAll()
            connectButton.isEnabled = !simulated
            boardViews.forEachIndexed { index, views ->
                views.status.text = if (simulated) "SIM" else "미연결"
                views.status.setTextColor(getColor(if (simulated) R.color.accent else R.color.text_secondary))
                views.container.setBackgroundResource(R.drawable.rounded_surface)
            }
            appendLog(if (simulated) "시뮬레이션 모드" else "실제 BLE CS 모드")
        }
        connectButton.isEnabled = !simulationSwitch.isChecked
        connectButton.setOnClickListener {
            withValidBoards { boards ->
                ensurePermissionsThen {
                    preferences.save(boards)
                    connectionKeeper.connectAll(boards)
                    appendLog("A/B/C/D GATT 연결 시작")
                }
            }
        }
        startButton.setOnClickListener {
            withValidBoards { boards ->
                preferences.save(boards)
                if (simulationSwitch.isChecked) {
                    startMeasurement(boards, simulated = true)
                } else {
                    ensurePermissionsThen {
                        if (!hasChannelSoundingFeature()) {
                            showMessage("이 스마트폰은 BLE Channel Sounding 기능을 보고하지 않습니다")
                        } else if (!connectionKeeper.allConnected(boards)) {
                            showMessage("먼저 4개 보드를 모두 연결하고 페어링을 완료해 주세요")
                        } else {
                            startMeasurement(boards, simulated = false)
                        }
                    }
                }
            }
        }
        stopButton.setOnClickListener { stopMeasurement() }
    }

    private fun startMeasurement(boards: List<BoardConfig>, simulated: Boolean) {
        controller?.close()
        imuSampler.stop()
        val missingSensors = imuSampler.missingSensorNames()
        if (missingSensors.isNotEmpty()) {
            showMessage("IMU 로깅에 필요한 센서가 없습니다: ${missingSensors.joinToString()}")
            return
        }
        val experimentCondition = if (dynamicExperimentSwitch.isChecked) "DYNAMIC" else "STATIC"
        val csvSession = runCatching { csvLogger.startSession(simulated, experimentCondition) }
            .onFailure { showMessage("CSV 파일 생성 실패: ${it.message}") }
            .getOrNull() ?: return
        imuCsvErrorReported.set(false)
        val imuStarted = runCatching { imuSampler.start() }
            .onFailure {
                csvLogger.endSession()
                showMessage("IMU 로깅 시작 실패: ${it.message}")
            }
            .isSuccess
        if (!imuStarted) return
        val source = if (simulated) SimulatedMeasurementSource() else AndroidBleCsMeasurementSource(this)
        controller = CycleRangingController(source, this).also { it.start(boards) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        appendLog("$experimentCondition 반복 측정 시작 · 세션 준비 중")
        appendLog("Cycle+IMU CSV (50Hz): ${csvSession.relativePath}/${csvSession.cycleSummaryFileName}")
        appendLog("Attempt CSV: ${csvSession.relativePath}/${csvSession.attemptLogFileName}")
    }

    private fun stopMeasurement() {
        controller?.close()
        controller = null
        imuSampler.stop()
        csvLogger.endSession()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runOnUiThread {
            onRunningChanged(false)
            phaseValue.text = "정지"
            boardViews.forEach { it.container.setBackgroundResource(R.drawable.rounded_surface) }
        }
    }

    private fun withValidBoards(action: (List<BoardConfig>) -> Unit) {
        val boards = mutableListOf<BoardConfig>()
        for ((index, views) in boardViews.withIndex()) {
            val mac = views.mac.text.toString().trim().uppercase(Locale.US)
            if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
                showMessage("Board ${('A'.code + index).toChar()} MAC 주소를 확인해 주세요")
                views.mac.requestFocus()
                return
            }
            boards += BoardConfig(index, ('A'.code + index).toChar().toString(), mac)
        }
        action(boards)
    }

    private fun ensurePermissionsThen(action: () -> Unit) {
        val missing = REQUIRED_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingPermissionAction = action
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (granted) action?.invoke() else showMessage("실제 측정에는 Nearby devices/RANGING 권한이 필요합니다")
    }

    private fun updateHardwareStatus() {
        val manager = getSystemService(BluetoothManager::class.java)
        val bluetoothOn = manager.adapter?.isEnabled == true
        val csSupported = hasChannelSoundingFeature()
        supportStatus.text = when {
            !bluetoothOn -> "Bluetooth가 꺼져 있습니다 · 시뮬레이션만 사용 가능"
            csSupported -> "BLE Channel Sounding 하드웨어 지원 확인됨 · Android ${android.os.Build.VERSION.RELEASE}"
            else -> "BLE Channel Sounding 하드웨어 미지원 · 시뮬레이션으로 UI/루프 테스트 가능"
        }
        supportStatus.setTextColor(getColor(if (csSupported && bluetoothOn) R.color.accent else R.color.warning))
    }

    private fun hasChannelSoundingFeature(): Boolean =
        packageManager.hasSystemFeature(FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING)

    override fun onRunningChanged(running: Boolean) = runOnUiThread {
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        simulationSwitch.isEnabled = !running
        dynamicExperimentSwitch.isEnabled = !running
        connectButton.isEnabled = !running && !simulationSwitch.isChecked
        boardViews.forEach { views ->
            views.mac.isEnabled = !running
        }
    }

    override fun onSessionReady(detail: String) = runOnUiThread {
        appendLog(detail)
    }

    override fun onSessionError(message: String) = runOnUiThread {
        imuSampler.stop()
        csvLogger.endSession()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        phaseValue.text = "세션 오류"
        showMessage(message)
    }

    override fun onBoardStarted(board: BoardConfig) = runOnUiThread {
        phaseValue.text = "${board.label} CS 측정 중"
        boardViews.forEachIndexed { index, views ->
            views.container.setBackgroundResource(
                if (index == board.index) R.drawable.rounded_surface_active else R.drawable.rounded_surface,
            )
        }
    }

    override fun onBoardResult(board: BoardConfig, distanceMeters: Double, elapsedMs: Double) = runOnUiThread {
        boardViews[board.index].distance.text = String.format(Locale.US, "%.3f m", distanceMeters)
        appendLog("${board.label}: %.3fm (%.1fms)".format(Locale.US, distanceMeters, elapsedMs))
    }

    override fun onBoardError(board: BoardConfig, message: String, elapsedMs: Double) = runOnUiThread {
        boardViews[board.index].distance.text = "ERR"
        appendLog("${board.label}: $message (%.1fms)".format(Locale.US, elapsedMs))
    }

    override fun onAttemptEvent(record: AttemptLogRecord) {
        csvLogger.appendAttempt(record) { error ->
            if (error != null) {
                runOnUiThread { appendLog("Attempt CSV 실패: ${error.message}") }
            }
        }
    }

    override fun onBoardMeasurement(cycleNumber: Long, record: BoardMeasurementRecord) {
        runCatching {
            csvLogger.appendBoardMeasurement(cycleNumber, record) { csvError ->
                runOnUiThread {
                    if (csvError == null) {
                        appendLog("Cycle #$cycleNumber ${record.board.label} CSV 저장 완료")
                    } else {
                        appendLog("Board ${record.board.label} CSV 실패: ${csvError.message}")
                    }
                }
            }
        }.onFailure { error ->
            runOnUiThread { appendLog("Board ${record.board.label} CSV 실패: ${error.message}") }
        }
    }

    override fun onCycleCompleted(record: CycleMeasurementRecord) {
        runOnUiThread {
            cycleTime.text = String.format(Locale.US, "%.1f ms", record.elapsedMs)
            cycleCount.text = record.cycleNumber.toString()
            appendLog("Cycle #${record.cycleNumber} 완료: %.1fms".format(Locale.US, record.elapsedMs))
        }
    }

    override fun onConnectionState(boardIndex: Int, state: GattConnectionKeeper.State, detail: String) = runOnUiThread {
        val views = boardViews[boardIndex]
        views.status.text = detail
        views.status.setTextColor(
            getColor(
                when (state) {
                    GattConnectionKeeper.State.CONNECTED -> R.color.accent
                    GattConnectionKeeper.State.CONNECTING -> R.color.warning
                    GattConnectionKeeper.State.DISCONNECTED -> R.color.danger
                },
            ),
        )
        appendLog("${('A'.code + boardIndex).toChar()} GATT: $detail")
    }

    private fun appendLog(message: String) {
        if (logs.size >= MAX_LOG_LINES) logs.removeFirst()
        logs.addLast(message)
        logView.text = logs.joinToString("\n")
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        appendLog(message)
    }

    override fun onDestroy() {
        controller?.close()
        imuSampler.close()
        csvLogger.close()
        connectionKeeper.destroy()
        super.onDestroy()
    }

    companion object {
        private const val FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING =
            "android.hardware.bluetooth_le.channel_sounding"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val MAX_LOG_LINES = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RANGING,
        )
    }
}
