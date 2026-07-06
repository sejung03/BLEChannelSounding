package com.example.blecsreflector.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

class PermissionHelper(
    private val activity: Activity
) {

    companion object {

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    /**
     * 모든 권한이 허용되었는지 확인
     */
    fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(
                activity,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 권한 요청
     */
    fun requestPermissions(
        launcher: ActivityResultLauncher<Array<String>>
    ) {
        launcher.launch(REQUIRED_PERMISSIONS)
    }

    /**
     * 특정 권한 확인
     */
    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 권한 목록 반환
     */
    fun getMissingPermissions(): List<String> {
        return REQUIRED_PERMISSIONS.filter {
            !hasPermission(it)
        }
    }
}