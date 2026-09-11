package com.smartring.app.presentation.alarmring
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.service.AlarmFiringService
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlarmRingUiState(
    val alarm: Alarm?       = null,
    val elapsedSeconds: Int = 0,
    val snoozeCount: Int    = 0,
    val isDismissed: Boolean = false,
)

@HiltViewModel
class AlarmRingViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,          // ← injected for snooze reschedule
    private val appLogger: AppLogger,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(AlarmRingUiState())
    val state: StateFlow<AlarmRingUiState> = _state.asStateFlow()

    // The real moment AlarmFiringService started this ring (its "FIRED" alarm_logs
    // row — written on every fire, snooze re-fires included, so this is always the
    // most recent ring session's actual start), converted to the elapsedRealtime()
    // clock base at load time. Elapsed time is computed from this instead of either:
    //  - counting ticks from when the Compose screen happened to enter composition
    //    (a screen-local counter restarts from 0 on recreation — rotation, or
    //    reopening the screen well after it first appeared — and could drift
    //    arbitrarily far from AlarmFiringService's own ringDurationSeconds timer,
    //    exactly the "ring screen doesn't dismiss on time" symptom this exists to
    //    fix), or
    //  - repeatedly comparing against System.currentTimeMillis() (which jumps on a
    //    DST change, NTP resync, or manual clock adjustment — any of which would
    //    make elapsed time appear to leap forward mid-ring and trigger an instant
    //    false dismissal). elapsedRealtime() is monotonic and unaffected by wall
    //    clock changes after this one conversion; only this initial translation
    //    touches currentTimeMillis(), so a clock jump *during* the ring (the
    //    realistic window, given loadAlarm() resolves in milliseconds) can't affect
    //    subsequent ticks.
    // Null only if the FIRED row hasn't landed yet (falls back to the old
    // tick-counted behavior for that brief window).
    private var firedAtElapsedRealtime: Long? = null

    fun loadAlarm(id: Long) = viewModelScope.launch {
        // A handful of quick retries in case this races a concurrent write (e.g. the
        // same alarm's own reschedule happening right as this fires); without a
        // fallback, a permanently-null result left the screen showing its loading
        // spinner forever with no way out for the user.
        repeat(5) { attempt ->
            val alarm = repository.getAlarm(id)
            if (alarm != null) {
                // Only trust a FIRED timestamp taken *just now* (loadAlarm() always
                // runs within moments of the alarm actually firing) — if the write for
                // *this* firing failed (AlarmFiringService wraps it in runCatching so a
                // logging failure alone can't block the alarm from ringing), this would
                // otherwise silently fall back to a previous occurrence's much older
                // FIRED row, making elapsed time already exceed ringDurationSeconds on
                // the very first tick and instantly silence a ring that just started.
                val now = System.currentTimeMillis()
                firedAtElapsedRealtime = repository.lastFiredAt(id)
                    ?.takeIf { now - it < STALE_FIRED_AT_THRESHOLD_MILLIS }
                    ?.let { firedAtWallClock -> SystemClock.elapsedRealtime() - (now - firedAtWallClock) }
                _state.update { it.copy(alarm = alarm) }
                return@launch
            }
            if (attempt < 4) delay(200)
        }
        appLogger.log("AlarmRing", "שעמור #$id לא נמצא בעת פתיחת מסך הצלצול — נסגר אוטומטית")
        _state.update { it.copy(isDismissed = true) }
    }

    fun tick() {
        val prev = _state.value
        val firedAt = firedAtElapsedRealtime
        val elapsed = if (firedAt != null)
            ((SystemClock.elapsedRealtime() - firedAt) / 1000L).toInt().coerceAtLeast(0)
        else prev.elapsedSeconds + 1
        _state.update { it.copy(elapsedSeconds = elapsed) }
        val alarm = prev.alarm
        // AlarmFiringService has its own autoStopJob that stops the ringtone/vibration
        // after ringDurationSeconds (timed from inside fireAlarm(), a moment strictly
        // after this screen's firedAtElapsedRealtime anchor), and — on the normal, non-Shabbat
        // path where the alarm just runs to completion with nobody touching Stop —
        // is also what logs the "MISSED" history entry. A GRACE_SECONDS buffer here
        // lets that timer win the race and finish first in the common case; without
        // it, this screen's own timer (measured from slightly earlier) fired first on
        // essentially every unattended alarm, cancelling the service via
        // stopFiringService() before its autoStopJob's delay() ever resumed to write
        // that entry, so it silently never appeared in History. This check is purely
        // a safety net for when the service's own timer is late or never runs at all;
        // the redundant stopService() call is harmless (a second stop on an
        // already-stopped service is a no-op).
        if (alarm != null && !prev.isDismissed && elapsed >= alarm.ringDurationSeconds + GRACE_SECONDS) {
            stopFiringService()
            appLogger.log("AlarmRing", "מסך הצלצול נסגר אוטומטית (הגיע למשך הצלצול): \"${alarm.name}\" (#${alarm.id})")
            _state.update { it.copy(isDismissed = true) }
        }
    }

    private fun stopFiringService() = context.stopService(Intent(context, AlarmFiringService::class.java))

    fun stop() = viewModelScope.launch {
        // Shabbat-mode alarms accept no interaction; the UI already hides/disables
        // this button for them, but guard here too rather than trust the caller.
        if (_state.value.alarm?.acceptsInteraction == false) return@launch
        // The ring screen only reflects UI dismissal; without stopping the service
        // the ringtone/vibration kept playing in the background until ringDurationSeconds.
        stopFiringService()
        _state.value.alarm?.let {
            repository.log(it.id, it.name, System.currentTimeMillis(), "STOPPED")
            appLogger.log("AlarmRing", "נעצר ידנית: \"${it.name}\" (#${it.id})")
        }
        _state.update { it.copy(isDismissed = true) }
    }

    fun snooze() = viewModelScope.launch {
        val alarm = _state.value.alarm ?: return@launch
        if (!alarm.acceptsInteraction) return@launch
        // Snooze disabled for this alarm (button shouldn't be visible at all, but
        // guard defensively): degrade to a plain stop rather than doing nothing.
        if (!alarm.snoozeEnabled) { stop(); return@launch }
        // Derived from history, not the in-memory snoozeCount: this ViewModel (and
        // its counter) is recreated from scratch each time the ring screen reopens
        // for a new snooze wake-up, so an in-memory cap never actually triggered.
        val alreadySnoozed = repository.snoozeCountSinceLastFire(alarm.id)
        if (alreadySnoozed >= alarm.snoozeMaxCount) { stop(); return@launch }
        stopFiringService()
        scheduler.scheduleAt(alarm, System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L)
        repository.log(alarm.id, alarm.name, System.currentTimeMillis(), "SNOOZED")
        appLogger.log("AlarmRing", "נודניק ידני: \"${alarm.name}\" (#${alarm.id})")
        _state.update { it.copy(snoozeCount = it.snoozeCount + 1, isDismissed = true) }
    }

    private companion object {
        const val GRACE_SECONDS = 2
        // Comfortably below the shortest legitimate gap between two distinct firings
        // of the same alarm (the snooze-minutes slider's minimum is 1 minute — see
        // AlarmEditScreen's "משך נודניק" slider — daily/weekly recurrence is much
        // longer still), while generous enough to cover a slow service
        // startup/notification-post rather than a true additional occurrence.
        const val STALE_FIRED_AT_THRESHOLD_MILLIS = 45_000L
    }
}
