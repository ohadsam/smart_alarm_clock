package com.smartring.app.presentation.diagnosis

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.AlarmLog
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.DiagnosisInputs
import com.smartring.app.util.DiagnosisItem
import com.smartring.app.util.ForeignAlarms
import com.smartring.app.util.ReliabilityChecks
import com.smartring.app.util.diagnoseRinging
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosisUiState(
    val items: List<DiagnosisItem> = emptyList(),
    val recentRings: List<AlarmLog> = emptyList(),
)

/**
 * Takes every reading the diagnosis needs and hands them to the pure [diagnoseRinging].
 *
 * The split is deliberate: the readings are Android, the verdicts are not, and the
 * verdicts are the part that must be right. Keeping them in `util` means the wording, the
 * severities and the ordering are all covered by plain JVM tests — a diagnosis screen that
 * says "all fine" while an alarm is un-armed is worse than no screen, because it stops a
 * user who would otherwise have kept looking.
 */
@HiltViewModel
class DiagnosisViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosisUiState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val alarms = repository.getAllAlarms()
        // effectiveNextFireTime, not nextFireTime: a snoozed alarm's real next ring is the
        // snooze deadline, and reporting its configured time here would contradict the
        // countdown the widgets and the list are showing.
        val armed = alarms.filter { it.isActive }
            .mapNotNull { scheduler.effectiveNextFireTime(it) }
        val registered = ForeignAlarms.nextRegistered(ctx)

        val items = diagnoseRinging(
            DiagnosisInputs(
                hasAnyAlarm = alarms.isNotEmpty(),
                hasArmedAlarm = armed.isNotEmpty(),
                nextArmedAtMillis = armed.minOrNull(),
                systemNextAlarmIsOurs = registered?.isOurs == true,
                systemHasNextAlarm = registered != null,
                canScheduleExactAlarms = ReliabilityChecks.canScheduleExactAlarms(ctx),
                ignoringBatteryOptimizations = ReliabilityChecks.isIgnoringBatteryOptimizations(ctx),
                notificationsGranted = ReliabilityChecks.isNotificationsGranted(ctx),
                canUseFullScreenIntent = ReliabilityChecks.canUseFullScreenIntent(ctx),
                alarmVolumeAudible = ReliabilityChecks.isAlarmVolumeAudible(ctx),
            ),
        )

        // The last handful only. This screen answers "what happened recently", and a full
        // history already has its own screen; pasting it here would bury the verdicts
        // that are the point.
        val recent = runCatching { repository.observeLogs().first().take(5) }.getOrDefault(emptyList())

        _state.update { it.copy(items = items, recentRings = recent) }
    }
}
