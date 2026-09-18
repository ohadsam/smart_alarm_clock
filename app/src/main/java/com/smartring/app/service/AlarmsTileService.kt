package com.smartring.app.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * A Quick Settings tile that switches every alarm off — and back on.
 *
 * The case is the one the "שליטה כללית" sheet already serves: a night away, a sick day, a
 * flight. What the tile adds is reach. That sheet is three taps deep inside an app the
 * user has to find first, and the moment somebody wants it is usually the moment they are
 * already lying down with the phone in one hand.
 *
 * **It toggles rather than only turning off.** An off-only tile would strand whoever used
 * it: they would be left holding a control that silenced every alarm and offered no way
 * back, exactly the trap the widget's own on/off button was added to close in v1.8.0. The
 * two directions map onto `disableAll()`/`enableAll()`, which the app already exposes side
 * by side — this is the same pair of operations, not a new power.
 */
@AndroidEntryPoint
class AlarmsTileService : TileService() {

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var appLogger: AppLogger

    // Its own scope rather than a global one: a tile is listened to only while the shade
    // is open, and work outliving that would be updating a Tile object the platform has
    // already taken back.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        // Read the live count rather than trusting the tile's own displayed state: the
        // shade can have been open, and the alarms changed from the app or a widget,
        // since the last refresh. Acting on a stale reading here would switch everything
        // *on* for someone who pressed a tile that said "on".
        scope.launch {
            val armed = withContext(Dispatchers.IO) { runCatching { repository.getActiveAlarms() }.getOrNull() }
                ?: return@launch
            if (armed.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    repository.disableAll()
                    scheduler.cancelAll(armed.map { it.id })
                }
                appLogger.log("Tile", "כל השעמורים כובו מאריח ההגדרות המהירות (${armed.size})")
            } else {
                val nowArmed = withContext(Dispatchers.IO) {
                    repository.enableAll()
                    repository.getActiveAlarms().also { scheduler.rescheduleAll(it) }
                }
                appLogger.log("Tile", "כל השעמורים הופעלו מאריח ההגדרות המהירות (${nowArmed.size})")
            }
            refresh()
        }
    }

    private fun refresh() = scope.launch {
        // qsTile is null when the platform is not currently bound to this tile — which is
        // most of the time, and is not an error state worth logging.
        val tile = qsTile ?: return@launch
        val count = withContext(Dispatchers.IO) {
            runCatching { repository.getActiveAlarms().size }.getOrDefault(0)
        }
        tile.state = if (count > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        // The label stays fixed and the subtitle carries the state, so the tile reads the
        // same way whichever state it is in. A label that changes between "כבה" and
        // "הפעל" makes the user re-read the tile every time to work out what pressing it
        // will do.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (count > 0) "$count פעילים" else "הכל כבוי"
        }
        tile.updateTile()
    }
}
