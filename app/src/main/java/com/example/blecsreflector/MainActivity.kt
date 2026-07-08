package com.example.blecsreflector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.blecsreflector.service.ReflectorService
import com.example.blecsreflector.ui.theme.BleCsReflectorTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var permissionsGranted by mutableStateOf(false)
    private var running by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            Log.i(TAG, "Permission result: $result")
            permissionsGranted = hasAllPermissions()
            Log.i(TAG, "permissionsGranted = $permissionsGranted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsGranted = hasAllPermissions()

        if (!permissionsGranted) {
            requestBlePermissions()
        }

        setContent {
            BleCsReflectorTheme {
                ReflectorScreen(
                    hasPermissions = permissionsGranted,
                    running = running,
                    onRequestPermissions = {
                        requestBlePermissions()
                    },
                    onStart = {
                        startReflectorService()
                    },
                    onStop = {
                        stopReflectorService()
                    }
                )
            }
        }
    }

    private fun startReflectorService() {
        Log.i(TAG, "Start Reflector requested")

        permissionsGranted = hasAllPermissions()

        if (!permissionsGranted) {
            Log.e(TAG, "Missing permissions")
            requestBlePermissions()
            return
        }

        val intent = Intent(this, ReflectorService::class.java).apply {
            action = ReflectorService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        running = true
    }

    private fun stopReflectorService() {
        Log.i(TAG, "Stop Reflector requested")

        val intent = Intent(this, ReflectorService::class.java).apply {
            action = ReflectorService.ACTION_STOP
        }

        startService(intent)
        running = false
    }

    private fun requestBlePermissions() {
        permissionLauncher.launch(
            requiredPermissions().toTypedArray()
        )
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.RANGING
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions
    }
}

@Composable
fun ReflectorScreen(
    hasPermissions: Boolean,
    running: Boolean,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("BLE CS Reflector")

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (hasPermissions) {
                    "Permissions: Granted"
                } else {
                    "Permissions: Missing"
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                enabled = !running,
                onClick = {
                    if (!hasPermissions) {
                        onRequestPermissions()
                    } else {
                        onStart()
                    }
                }
            ) {
                Text("Start Reflector")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                enabled = running,
                onClick = {
                    onStop()
                }
            ) {
                Text("Stop Reflector")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (running) {
                    "Status: Running"
                } else {
                    "Status: Stopped"
                }
            )
        }
    }
}