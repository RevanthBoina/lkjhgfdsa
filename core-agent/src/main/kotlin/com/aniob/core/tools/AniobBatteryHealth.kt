package com.aniob.core.tools

/**
 * Pure JVM device battery state representation.
 */
data class DevicePowerState(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = true,
    val isThermalThrottled: Boolean = false,
    val isNetworkAvailable: Boolean = true,
    val totalRamGb: Int = 8,
    val freeStorageMb: Long = 0L
)

object AniobBatteryHealth {

    const val CRITICAL_BATTERY_THRESHOLD = 15

    /**
     * Reflex 2: True when the battery is low (<15%) and the device is not charging.
     * This is a pure deterministic reflex that lets AutoRouter offload to cloud with zero LLM.
     */
    fun isLowBattery(powerState: DevicePowerState): Boolean =
        powerState.batteryPercent < CRITICAL_BATTERY_THRESHOLD && !powerState.isCharging

    /**
     * Determines whether compute should be offloaded to cloud to save device battery & thermal budget.
     */
    fun shouldOffloadToCloud(powerState: DevicePowerState): Boolean {
        if (!powerState.isNetworkAvailable) return false // Cannot offload if offline
        return isLowBattery(powerState) || powerState.isThermalThrottled
    }
}
