package com.aniob.core.tools

/**
 * Pure JVM device battery state representation.
 */
data class DevicePowerState(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = true,
    val isThermalThrottled: Boolean = false,
    val isNetworkAvailable: Boolean = true
)

object AniobBatteryHealth {

    const val CRITICAL_BATTERY_THRESHOLD = 20

    /**
     * Determines whether compute should be offloaded to cloud to save device battery & thermal budget.
     */
    fun shouldOffloadToCloud(powerState: DevicePowerState): Boolean {
        if (!powerState.isNetworkAvailable) return false // Cannot offload if offline
        return (!powerState.isCharging && powerState.batteryPercent < CRITICAL_BATTERY_THRESHOLD) ||
                powerState.isThermalThrottled
    }
}
