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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            Log.i(TAG, "Permission result: $result")
            permissionsGranted = result.values.all { it }
            Log.i(TAG, "permissionsGranted = $permissionsGranted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsGranted = hasAllPermissions()

        requestBlePermissions()

        setContent {
            BleCsReflectorTheme {
                ReflectorScreen(
                    hasPermissions = permissionsGranted,
                    onRequestPermissions = {
                        requestBlePermissions()
                    },
                    onStart = {
                        Log.i(TAG, "Start button clicked")

                        if (!hasAllPermissions()) {
                            Log.e(TAG, "Missing permissions")
                            requestBlePermissions()
                            return@ReflectorScreen
                        }

                        val intent = Intent(
                            this,
                            ReflectorService::class.java
                        ).apply {
                            action = ReflectorService.ACTION_START
                        }

                        startForegroundService(intent)
                    },
                    onStop = {
                        Log.i(TAG, "Stop button clicked")

                        val intent = Intent(
                            this,
                            ReflectorService::class.java
                        ).apply {
                            action = ReflectorService.ACTION_STOP
                        }

                        startService(intent)
                    }
                )
            }
        }
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
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    var running by remember { mutableStateOf(false) }

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
                onClick = {
                    if (!hasPermissions) {
                        onRequestPermissions()
                        return@Button
                    }

                    running = true
                    onStart()
                }
            ) {
                Text("Start Reflector")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    running = false
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