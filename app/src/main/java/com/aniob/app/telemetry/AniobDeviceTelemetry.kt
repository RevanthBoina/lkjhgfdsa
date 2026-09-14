package com.aniob.app.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Context.*
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.aniob.core.tools.DevicePowerState

/**
 * Reads real Android hardware telemetry:
 * - BatteryManager for battery percentage and charging state
 * - PowerManager for thermal throttling status
 * - ConnectivityManager for active network connectivity
 * - ActivityManager for total memory (RAM in GB)
 */
object AniobDeviceTelemetry {

    fun getRealState(context: Context): DevicePowerState {
        return try {
            val bm = context.getSystemService(BATTERY_SERVICE) as? BatteryManager
            val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
            val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bm?.isCharging ?: true
            } else {
                true
            }

            val pm = context.getSystemService(POWER_SERVICE) as? PowerManager
            val isThermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >= PowerManager.THERMAL_STATUS_MODERATE
            } else {
                false
            }

            val cm = context.getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm?.activeNetwork != null
            } else {
                @Suppress("DEPRECATION")
                cm?.activeNetworkInfo?.isConnected == true
            }

            val am = context.getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mem)
            val totalRamGb = if (mem.totalMem > 0) {
                ((mem.totalMem + (1024L * 1024L * 512L)) / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(1)
            } else 8

            // Internal files-dir free space (used by the model downloader before fetching a 0.3GB embed model)
            val freeStorageMb = try {
                context.filesDir?.freeSpace?.div(1024L * 1024L) ?: 0L
            } catch (e: Exception) {
                0L
            }

            DevicePowerState(
                batteryPercent = batteryPct,
                isCharging = isCharging,
                isThermalThrottled = isThermal,
                isNetworkAvailable = isNetwork,
                totalRamGb = totalRamGb,
                freeStorageMb = freeStorageMb
            )
        } catch (e: Exception) {
            DevicePowerState(
                batteryPercent = 85,
                isCharging = true,
                isThermalThrottled = false,
                isNetworkAvailable = true,
                totalRamGb = 8
            )
        }
    }
}
