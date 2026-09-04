package com.smartring.app.service
import android.app.*
import android.content.Intent
import android.media.*
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import com.smartring.app.R
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.*
import com.smartring.app.receiver.AlarmReceiver
import com.smartring.app.util.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class AlarmFiringService : Service() {
    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var crescendoJob: Job? = null
    private var autoStopJob: Job? = null
    private var ringSequenceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Vibrator::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L) ?: -1L
        if (id < 0) { stopSelf(); return START_NOT_STICKY }
        val isSnooze = intent?.getBooleanExtra(AlarmReceiver.EXTRA_IS_SNOOZE, false) ?: false

        // Start foreground immediately (within 5-second ANR window)
        startForeground(NOTIF_ID, buildPlaceholderNotification())

        val scheduledFor = System.currentTimeMillis()
        scope.launch {
            val alarm = repository.getAlarm(id) ?: run { stopSelf(); return@launch }
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(alarm))
            repository.log(id, alarm.name, scheduledFor, "FIRED")
            // A snooze re-fire is a continuation of the same occurrence, not a new
            // one: bumping occurrencesFired/rescheduling here too would advance a
            // COUNT-limited recurrence once per snooze instead of once per real day.
            if (!isSnooze) repository.incrementOccurrences(id)
            fireAlarm(alarm)
            if (!isSnooze) scheduler.schedule(alarm.copy(occurrencesFired = alarm.occurrencesFired + 1))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { stopAll(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null

    private suspend fun fireAlarm(alarm: Alarm) {
        autoStopJob = scope.launch {
            delay(alarm.ringDurationSeconds * 1_000L)
            repository.log(alarm.id, alarm.name, System.currentTimeMillis(), "MISSED"); stopSelf()
        }
        when (alarm.vibrationMode) {
            VibrationMode.SOUND_ONLY          -> startAudioSequence(alarm, 0)
            VibrationMode.VIBRATION_ONLY      -> startVibration()
            VibrationMode.SOUND_AND_VIBRATION -> { startVibration(); startAudioSequence(alarm, 0) }
            VibrationMode.VIBRATION_THEN_SOUND -> {
                startVibration()
                delay(alarm.vibrationOnlySeconds * 1_000L)
                startAudioSequence(alarm, alarm.vibrationOnlySeconds)
            }
        }
    }

    /**
     * Plays through alarm.rings in order (each with its own duration/volume/ringtone,
     * separated by delayAfterSeconds), looping the whole sequence until autoStopJob
     * or the user ends it. Previously only rings.firstOrNull() ever played on an
     * infinite loop, so a configured multi-round alarm never advanced past round 1.
     */
    private fun startAudioSequence(alarm: Alarm, elapsedAtStart: Int) {
        val rings = alarm.rings.ifEmpty { listOf(AlarmRing(volumePercent = 100)) }
            .sortedBy { it.orderIndex }
        ringSequenceJob = scope.launch {
            var elapsed = elapsedAtStart
            while (isActive) {
                for (ring in rings) {
                    if (!isActive) break
                    playOneRing(alarm, ring, elapsed)
                    elapsed += ring.durationSeconds
                    if (ring.delayAfterSeconds > 0) {
                        delay(ring.delayAfterSeconds * 1_000L)
                        elapsed += ring.delayAfterSeconds
                    }
                }
            }
        }
    }

    private suspend fun playOneRing(alarm: Alarm, ring: AlarmRing, elapsedAtRingStart: Int) {
        val uri = if (ring.ringtoneUri != "default") Uri.parse(ring.ringtoneUri)
                  else android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        val base = ring.volumePercent / 100f
        val start = if (alarm.crescendoEnabled) alarm.crescendoStartVolume / 100f else base

        val mp = MediaPlayer()
        player = mp
        val prepared = CompletableDeferred<Unit>()
        mp.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(applicationContext, uri)
            isLooping = true
            setVolume(start, start)
            // Use prepareAsync() to avoid blocking the main thread
            setOnPreparedListener { it.start(); prepared.complete(Unit) }
            setOnErrorListener { _, _, _ ->
                // Fallback: fall through silently for this ring rather than getting
                // stuck waiting on `prepared` forever; the next ring (or vibration)
                // still runs.
                prepared.complete(Unit)
                true
            }
            prepareAsync()
        }
        prepared.await()

        if (alarm.crescendoEnabled) startCrescendo(alarm, base, elapsedAtRingStart)

        delay(ring.durationSeconds * 1_000L)

        crescendoJob?.cancel()
        runCatching { mp.stop() }
        runCatching { mp.release() }
        if (player === mp) player = null
    }

    private fun startCrescendo(alarm: Alarm, targetVol: Float, elapsedAtStart: Int) {
        var e = elapsedAtStart
        crescendoJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                e++
                val v = alarm.volumeAtSecond(Math.round(targetVol * 100), e) / 100f
                player?.setVolume(v, v)
                if (v >= targetVol) break
            }
        }
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 600, 900)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        else @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
    }

    private fun stopAll() {
        autoStopJob?.cancel(); crescendoJob?.cancel(); ringSequenceJob?.cancel()
        // Separate runCatching per call: if stop() throws (e.g. player still in the
        // Initialized/Prepared-but-not-started state), release() must still run or
        // the native MediaPlayer leaks.
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        vibrator?.cancel()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "SmartRing Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
    }

    private fun buildPlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("SmartRing")
            .setContentText("שעמור מתחיל…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .build()

    private fun buildNotification(alarm: Alarm): Notification {
        fun pi(cls: Class<*>, extra: Long, reqCode: Int) = PendingIntent.getBroadcast(this, reqCode,
            Intent(this, cls).putExtra(AlarmReceiver.EXTRA_ALARM_ID, extra),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, alarm.id.toInt(),
            packageManager.getLaunchIntentForPackage(packageName)
                ?.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id) ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(alarm.name)
            .setContentText(alarm.reminderText ?: alarm.timeFormatted)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true).setFullScreenIntent(openPi, true)
            .addAction(R.drawable.ic_stop, "עצור", pi(StopAlarmReceiver::class.java, alarm.id, alarm.id.toInt()))
            .addAction(R.drawable.ic_snooze, "נודניק", pi(SnoozeAlarmReceiver::class.java, alarm.id, (alarm.id+10000).toInt()))
            .build()
    }

    companion object { const val CH = "smartring_alarm_channel"; const val NOTIF_ID = 1001 }
}
