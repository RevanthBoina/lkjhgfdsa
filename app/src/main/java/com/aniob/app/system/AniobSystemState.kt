package com.aniob.app.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.aniob.app.AniobApplication
import com.aniob.app.model.AniobModelDownloader
import com.aniob.app.service.AniobAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One reactive snapshot of every system gate the UI reads. Never a non-reactive local read. */
data class SystemState(
    val a11yConnected: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val networkAvailable: Boolean = true,
    val modelReady: Boolean = false,
    val checkedAt: Long = 0L
)

/**
 * Single reactive truth for permission/service state (UX-0 §1, kills U2).
 *
 * Sources push updates: a11y service lifecycle, connectivity callback, package changes and
 * Activity `onResume`. Every banner/card collects this flow — a manual Refresh button may
 * remain as a secondary affordance but is never the mechanism.
 */
class AniobSystemState(private val app: AniobApplication) {

    private val downloader = AniobModelDownloader(app)

    private val _state = MutableStateFlow(SystemState())
    val state: StateFlow<SystemState> = _state.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        registerConnectivityCallback()
        refresh()
    }

    /** Re-reads every gate. Cheap: called from onResume, service callbacks and package events. */
    fun refresh() {
        _state.update {
            SystemState(
                a11yConnected = AniobAccessibilityService.isServiceConnected,
                overlayGranted = canDrawOverlays(),
                notificationsEnabled = NotificationManagerCompat.from(app).areNotificationsEnabled(),
                batteryUnrestricted = isBatteryUnrestricted(),
                networkAvailable = isNetworkAvailable(),
                modelReady = isModelReady(),
                checkedAt = System.currentTimeMillis()
            )
        }
    }

    /** Push-based update from the accessibility service, avoiding a full re-probe. */
    fun onAccessibilityChanged(connected: Boolean) {
        _state.update { it.copy(a11yConnected = connected, checkedAt = System.currentTimeMillis()) }
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(app)

    private fun isBatteryUnrestricted(): Boolean {
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(app.packageName)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isModelReady(): Boolean {
        val id = downloader.getDefaultModelId() ?: return false
        if (!downloader.isModelInstalled(id)) return false
        return try {
            val engine = com.aniob.core.providers.AniobLocalLlmClient.getEngine()
            engine.isModelLoaded() && engine.capabilities().canDriveActions
        } catch (_: Throwable) {
            false
        }
    }

    private fun registerConnectivityCallback() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _state.update { it.copy(networkAvailable = true, checkedAt = System.currentTimeMillis()) }
            }

            override fun onLost(network: Network) {
                refresh()
            }
        }
        networkCallback = callback
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
        } catch (_: Exception) {
            // Some OEMs reject the request; onResume refresh keeps the state honest.
        }
    }

    fun stop() {
        val cm = connectivityManager ?: return
        val callback = networkCallback ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Already unregistered.
        }
        networkCallback = null
    }

    /** Deep link to the accessibility settings page, used by setup and recovery surfaces. */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }

    companion object {
        fun of(app: AniobApplication): AniobSystemState = app.systemState
    }
}
