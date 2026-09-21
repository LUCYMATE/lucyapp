package com.soltini.app.services

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.soltini.app.overlay.OverlayService
import com.soltini.app.settings.AppSettings

/**
 * BankingModeTileService — Quick Settings Tile for 1-tap toggling of Banking Mode.
 * Allows user to quickly pause/resume Soltini overlays before opening banking apps.
 */
@RequiresApi(Build.VERSION_CODES.N)
class BankingModeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val appSettings = AppSettings(this)
        val newPausedState = !appSettings.isBankingModePaused
        appSettings.isBankingModePaused = newPausedState

        val overlay = OverlayService.getInstance()
        if (overlay != null) {
            overlay.setHiddenForBanking(newPausedState)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val appSettings = AppSettings(this)
        val isPaused = appSettings.isBankingModePaused || (OverlayService.getInstance()?.isHiddenForBanking() == true)

        if (isPaused) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "LUCY: Paused"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Banking Mode ON"
            }
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "LUCY: Active"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Overlay Active"
            }
        }
        tile.updateTile()
    }
}
