package com.aniob.core.tools

/**
 * Companion device registry metadata for multi-device topology and dispatch.
 */
data class CompanionDevice(
    val deviceId: String,
    val modelName: String,
    val ipAddress: String,
    val port: Int = 8765,
    val token: String,
    val isOnline: Boolean = true,
    val batteryPercent: Int = 100,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

object DeviceRegistry {
    private val devices = mutableMapOf<String, CompanionDevice>()

    @Synchronized
    fun registerDevice(device: CompanionDevice) {
        devices[device.deviceId] = device
    }

    @Synchronized
    fun getDevice(deviceId: String): CompanionDevice? = devices[deviceId]

    @Synchronized
    fun getAllDevices(): List<CompanionDevice> = devices.values.toList()

    @Synchronized
    fun removeDevice(deviceId: String) {
        devices.remove(deviceId)
    }

    @Synchronized
    fun clear() {
        devices.clear()
    }
}
