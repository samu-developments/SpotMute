package com.developments.samu.muteforspotify.service

import android.annotation.TargetApi
import android.content.Intent
import android.net.Uri
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat


@TargetApi(24)
class MuteTileService : TileService() {

    private val LOG_TAG: String = MuteTileService::class.java.simpleName

    private val loggerServiceIntentForeground by lazy { Intent(LoggerService.ACTION_START_FOREGROUND, Uri.EMPTY, this, LoggerService::class.java) }

    override fun onStartListening() {
        toggleTile(LoggerService.isServiceRunning())
        super.onStartListening()
    }
    
    override fun onClick() {
        // tile can be quite unresponsive, a click can be registered several seconds after happening.
        if (qsTile?.state == Tile.STATE_INACTIVE) {
            ContextCompat.startForegroundService(this, loggerServiceIntentForeground)
            toggleTile(on=true)
        } else {
            this.stopService(loggerServiceIntentForeground)
            toggleTile(on=false)
        }
        super.onClick()
    }

    private fun toggleTile(on: Boolean) {
        qsTile?.let {
            it.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }
}


