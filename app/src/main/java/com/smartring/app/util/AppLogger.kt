package com.smartring.app.util
import com.smartring.app.data.repository.AlarmRepository
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fire-and-forget technical/diagnostic logging (scheduling decisions, boot
 * rescheduling, background work, receiver/service activity) — separate from
 * AlarmRepository.log(), which writes the user-facing ring history shown in
 * HistoryScreen. Viewable/copyable/downloadable from Settings -> Logs, and
 * auto-trimmed to the last 3 days by LogCleanupWorker.
 */
@Singleton
class AppLogger @Inject constructor(
    private val repository: AlarmRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun log(tag: String, message: String) {
        scope.launch { runCatching { repository.insertAppLog(tag, message) } }
    }
}
