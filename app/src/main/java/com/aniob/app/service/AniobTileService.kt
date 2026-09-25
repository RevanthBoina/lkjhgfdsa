package com.aniob.app.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.aniob.app.MainActivity
import com.aniob.app.R
import com.aniob.app.background.AniobForegroundService

/**
 * Quick Settings tile (UX-6 §2).
 *
 * Idle → "Ask Aniob" (tap opens the app). Running → "Stop task" (tap stops immediately; stop is
 * safe by design, so no undo). While the keyguard is showing, tapping just opens the app instead
 * of acting on a locked device.
 */
@RequiresApi(Build.VERSION_CODES.N)
class AniobTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext as? com.aniob.app.AniobApplication
        val running = app?.taskOwner?.isActive() == true || AniobAccessibilityService.isTaskActive
        if (isLocked || !running) {
            // Locked devices only get the safe "open the app" action.
            openApp()
            return
        }
        app?.taskOwner?.stop()
        AniobForegroundService.requestStop()
        updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val app = applicationContext as? com.aniob.app.AniobApplication
        val running = app?.taskOwner?.isActive() == true || AniobAccessibilityService.isTaskActive
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (running) R.string.tile_running else R.string.tile_idle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "Tap to stop" else "Tap to open"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_aniob)
        tile.updateTile()
    }
}
