package io.github.dovecoteescapee.byedpi.services

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import io.github.dovecoteescapee.byedpi.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/** Separate Telegram toggle: no VPN mode changes or VPN permission prompt. */
@RequiresApi(Build.VERSION_CODES.N)
class TelegramQuickTileService : TileService() {
    private var listening: CoroutineScope? = null
    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                combine(TelegramWsProxyService.running, TelegramWsProxyService.starting) { ready, busy ->
                    if (busy) Tile.STATE_UNAVAILABLE else if (ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                }.collect { state -> qsTile?.apply { this.state = state; updateTile() } }
            }
        }
    }
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            if (TelegramWsProxyService.starting.value) return@unlockAndRun
            try {
                if (TelegramWsProxyService.running.value) TelegramWsProxyService.stop(this)
                else TelegramWsProxyService.start(this)
            } catch (_: RuntimeException) {
                Toast.makeText(this, R.string.telegram_ws_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }
    override fun onDestroy() {
        listening?.cancel()
        super.onDestroy()
    }
}
