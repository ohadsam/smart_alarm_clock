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
import com.smartring.app.util.AlarmHandoffWakeLock
import com.smartring.app.util.AlarmNotifications
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import com.smartring.app.util.WidgetRefresher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class AlarmFiringService : Service() {
    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var appLogger: AppLogger
    @Inject lateinit var widgetRefresher: WidgetRefresher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var crescendoJob: Job? = null
    private var autoStopJob: Job? = null
    private var ringSequenceJob: Job? = null
    private var ringJob: Job? = null
    // Incremented on every fire. A fire whose generation is no longer the current one
    // has been superseded by a newer alarm and must not ring — but its *bookkeeping*
    // still has to finish. See onStartCommand.
    private var fireGeneration = 0
    // Set once per fire when no sound at all could be played and vibration was started
    // as a stand-in, so looping through the rounds doesn't restart it every round.
    private var silentFallbackVibrating = false

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

        // The CPU is not guaranteed to stay awake just because a foreground service is
        // running: with the screen off (the normal case for an alarm) the device can go
        // back to sleep between the delivery of the alarm broadcast and everything this
        // service schedules afterwards, which delays the ring sequence's delay()s and
        // the auto-stop timer by however long it stays asleep. Held for the service's
        // whole lifetime and released in onDestroy().
        acquireWakeLock()
        // The receiver's hand-off lock has done its job the moment this service holds
        // its own; releasing it here rather than leaving it to its timeout keeps the
        // CPU held for exactly as long as something is actually using it.
        AlarmHandoffWakeLock.release()

        // A second fire arriving while one is already ringing (two alarms set to the
        // same minute, or a snooze re-fire racing the previous ring's teardown) used to
        // start a second, parallel set of jobs on top of the first: the previous
        // MediaPlayer was still looping but no longer reachable through `player`, so
        // nothing ever released it and it kept playing until the process died, while
        // the *previous* alarm's autoStopJob went on to stopSelf() partway through the
        // new alarm. Tear the previous ring down first so only one is ever active.
        //
        // Only the *ring* is torn down, though. Cancelling the whole previous fire — as
        // this did — also killed its bookkeeping, and that bookkeeping is almost always
        // still in flight, because the very first thing it does is a suspending database
        // read. So two alarms set to the same minute meant the first one never logged,
        // never advanced its occurrence count and, worst of all, never armed its own next
        // occurrence: a daily alarm sharing a minute with another one simply stopped
        // after that day. The generation counter below lets the superseded fire finish
        // its bookkeeping and only skips the part that would make noise.
        val generation = ++fireGeneration
        stopAll()

        val scheduledFor = System.currentTimeMillis()
        scope.launch {
            val alarm = repository.getAlarm(id)
                ?: run { if (generation == fireGeneration) stopSelf(); return@launch }
            // Log FIRED before posting the notification, not after: the notification's
            // full-screen intent can launch the ring screen (AlarmRingViewModel reads
            // this same "FIRED" row's timestamp to anchor its auto-dismiss timer)
            // essentially immediately — logging afterward left a window where a
            // recurring alarm's ring screen could read *yesterday's* FIRED timestamp
            // (the row for today not committed yet) and see an already-elapsed
            // duration on its very first tick, instantly dismissing itself. Failures
            // other than cancellation are swallowed (a DB error here should cost a lost
            // history row, not a lost alarm — notify()/fireAlarm() below must still
            // run) but CancellationException is deliberately re-thrown: if the scope
            // was already cancelled (e.g. onDestroy() mid-suspension), the coroutine
            // must stop here too, not fall through to post a real alarm notification
            // for a service already being torn down.
            try {
                repository.log(id, alarm.name, scheduledFor, "FIRED")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogger.log("AlarmFiringService", "כתיבת יומן 'מצלצל' נכשלה: ${e.message}")
            }
            // A snooze re-fire is a continuation of the same occurrence, not a new
            // one: bumping occurrencesFired/rescheduling here too would advance a
            // COUNT-limited recurrence once per snooze instead of once per real day.
            //
            // Done *before* fireAlarm() rather than after it: fireAlarm() suspends for
            // vibrationOnlySeconds in VIBRATION_THEN_SOUND mode, and anything after it
            // is lost if the service is torn down first (the user pressing Stop during
            // those seconds, or the process being killed mid-ring) — which silently
            // left a recurring alarm with no next occurrence armed at all. Nothing
            // below needs the ring to have started.
            if (!isSnooze) {
                repository.incrementOccurrences(id)
                val advanced = alarm.copy(occurrencesFired = alarm.occurrencesFired + 1)
                // Only re-arm an alarm that genuinely has another occurrence coming.
                // Re-arming unconditionally (the previous behaviour) turned every
                // one-time alarm into a daily one, since nextFireTime()'s fallback for
                // an alarm with no declared schedule answers "same time tomorrow"
                // forever — the opposite of the one-time default a new alarm is
                // documented (and shown in the UI) to have.
                if (!advanced.isRecurrenceExpired() && scheduler.nextRecurringFireTime(advanced) != null) {
                    scheduler.schedule(advanced)
                } else {
                    repository.setEnabled(id, false)
                    appLogger.log("AlarmFiringService",
                        "\"${alarm.name}\" (#$id) היה חד-פעמי — כובה אוטומטית לאחר הצלצול")
                }
                // Both branches above write to the alarms table, which SmartRingApp's
                // observeAlarms() collector reacts to on its own, so neither needs to
                // refresh widgets here. A snooze re-fire doesn't touch that table at
                // all, so it still needs its own explicit refresh — otherwise the
                // widget keeps showing the now-elapsed snooze countdown until the next
                // periodic WidgetRefreshWorker run, up to 15 minutes later.
            } else widgetRefresher.refresh()

            // Superseded while the bookkeeping above was running: that work is done and
            // committed, but the alarm that arrived after this one owns the ring.
            if (generation != fireGeneration) return@launch

            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(alarm))
            appLogger.log("AlarmFiringService", "מצלצל: \"${alarm.name}\" (#$id)" +
                if (alarm.isShabbatMode) " [מצב שבת]" else "")
            // fireAlarm's own suspension points (the vibrate-first delay) have to be
            // cancellable by a later fire, so the ring runs as its own job that stopAll()
            // tears down — the bookkeeping coroutine above deliberately is not.
            ringJob = scope.launch { fireAlarm(alarm) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { stopAll(); releaseWakeLock(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                // Bounded so a wake lock can never outlive its usefulness and drain the
                // battery if some path manages to skip onDestroy(); the longest a ring
                // can legitimately last is the ring-duration slider's 600s maximum.
                runCatching { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
            }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private suspend fun fireAlarm(alarm: Alarm) {
        autoStopJob = scope.launch {
            delay(alarm.ringDurationSeconds * 1_000L)
            repository.log(alarm.id, alarm.name, System.currentTimeMillis(), "MISSED")
            appLogger.log("AlarmFiringService", "נעצר אוטומטית (הגיע למשך הצלצול): \"${alarm.name}\" (#${alarm.id})")
            stopSelf()
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
        // Alarm.effectiveRings, not a local copy of the same two rules: the ring
        // screen's crescendo readout walks this exact list via Alarm.ringAtSecond(),
        // and a second definition here is how the two would drift apart.
        val rings = alarm.effectiveRings
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

    /**
     * Prepares and starts one looping alarm player for [uri], or returns null if that
     * URI cannot be played at all.
     *
     * Every failure mode here used to be fatal to the whole alarm. `setDataSource()`
     * throws for a URI the app can't read — a track picked from the user's library
     * that was later deleted, an SD card that isn't mounted, or (the common one on
     * Android 13+) a MediaStore URI needing READ_MEDIA_AUDIO — and that exception
     * propagated out of playOneRing, out of the ring-sequence loop, and killed the
     * coroutine: no sound for the entire alarm, no second attempt, nothing in the log.
     * The onError path was barely better: it completed the wait and then sat out the
     * round's full duration in silence, once per loop, forever.
     */
    private suspend fun startPlayer(uri: Uri, volume: Float): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            val started = CompletableDeferred<Boolean>()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            // Keeps the CPU running while this plays with the screen off, on top of
            // the service's own wake lock — MediaPlayer releases it by itself when
            // playback stops, so it also covers the window between rings.
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(applicationContext, uri)
            mp.isLooping = true
            mp.setVolume(volume, volume)
            mp.setOnPreparedListener { it.start(); started.complete(true) }
            mp.setOnErrorListener { _, _, _ -> started.complete(false); true }
            // prepareAsync() rather than prepare(): this runs on the main dispatcher.
            mp.prepareAsync()
            if (started.await()) mp else { runCatching { mp.release() }; null }
        } catch (e: CancellationException) {
            // Cancelled mid-prepare (Stop pressed, a newer alarm taking over). Release
            // before unwinding or the native player is leaked, still holding a
            // USAGE_ALARM stream nothing can reach.
            runCatching { mp.release() }
            throw e
        } catch (e: Exception) {
            runCatching { mp.release() }
            appLogger.log("AlarmFiringService", "לא ניתן לנגן את הצליל $uri: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun playOneRing(alarm: Alarm, ring: AlarmRing, elapsedAtRingStart: Int) {
        val configured = if (ring.ringtoneUri != "default")
            runCatching { Uri.parse(ring.ringtoneUri) }.getOrNull() ?: DEFAULT_ALARM_URI
        else DEFAULT_ALARM_URI
        val base = ring.volumePercent / 100f
        // Continue the ramp from wherever cumulative elapsed time says it should be,
        // not always crescendoStartVolume: startAudioSequence() loops through
        // alarm.rings repeatedly until stopped, and playOneRing() runs once per loop
        // iteration — resetting to the floor volume every time (as if the crescendo
        // were only ever a few seconds long) made it look like crescendo "didn't
        // work" for anything but a single-ring, single-loop alarm.
        val start = if (alarm.crescendoEnabled)
            alarm.volumeAtSecond(ring.volumePercent, elapsedAtRingStart) / 100f
        else base

        // Fall back to the device's own alarm sound rather than ringing silently: the
        // chosen ringtone is the part most likely to have gone stale since the alarm
        // was set up, and "the alarm went off with the wrong sound" is a different
        // universe from "the alarm didn't go off".
        val mp: MediaPlayer? = startPlayer(configured, start)
            ?: if (configured != DEFAULT_ALARM_URI) {
                appLogger.log("AlarmFiringService",
                    "הצליל שנבחר ל-\"${alarm.name}\" (#${alarm.id}) לא ניתן לניגון — מנוגן צליל ברירת המחדל")
                startPlayer(DEFAULT_ALARM_URI, start)
            } else null
        if (mp == null) {
            // Nothing is playable — the device's alarm sound is set to silent, or audio
            // is unavailable entirely. Vibrate instead if this alarm wasn't already
            // going to, so the alarm still happens in *some* form.
            appLogger.log("AlarmFiringService",
                "אין צליל שניתן לנגן עבור \"${alarm.name}\" (#${alarm.id}) — מופעל רטט במקום")
            if (alarm.vibrationMode == VibrationMode.SOUND_ONLY && !silentFallbackVibrating) {
                silentFallbackVibrating = true
                startVibration()
            }
            // Still wait out the round so the sequence's timing (and the auto-stop) is
            // unchanged from a round that did play.
            delay(ring.durationSeconds * 1_000L)
            return
        }

        // Bound to a non-null val rather than relying on a smart cast: `mp` is read
        // inside the runCatching lambdas below, and a nullable local that a closure
        // captures is exactly the shape Kotlin refuses to smart-cast.
        val playing: MediaPlayer = mp
        player = playing
        // try/finally around everything after the player is live: without it, a
        // cancellation landing on the delay below (the user pressing Stop, the
        // auto-stop timer, a second alarm taking over) skipped the stop/release
        // entirely and left a looping, USAGE_ALARM MediaPlayer playing with no owner —
        // audible until the process itself died, and unstoppable from inside the app.
        try {
            if (alarm.crescendoEnabled) startCrescendo(alarm, playing, base, elapsedAtRingStart)
            delay(ring.durationSeconds * 1_000L)
        } finally {
            crescendoJob?.cancel()
            runCatching { playing.stop() }
            runCatching { playing.release() }
            if (player === playing) player = null
        }
    }

    /** Ramps [mp] specifically, rather than whatever `player` happens to point at when
     *  each tick runs — the ring sequence can move on to the next round (replacing
     *  `player`, releasing this one) between ticks, and setVolume() on a released
     *  MediaPlayer throws. */
    private fun startCrescendo(alarm: Alarm, mp: MediaPlayer, targetVol: Float, elapsedAtStart: Int) {
        var e = elapsedAtStart
        crescendoJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                e++
                val v = alarm.volumeAtSecond(Math.round(targetVol * 100), e) / 100f
                if (player !== mp) break
                runCatching { mp.setVolume(v, v) }.onFailure { return@launch }
                if (v >= targetVol) break
            }
        }
    }

    /**
     * The vibration is always declared as an *alarm* vibration, never an untagged one.
     *
     * `vibrate(VibrationEffect)` with no attributes is treated as USAGE_UNKNOWN, and
     * the platform suppresses unknown-usage vibrations whenever the device's
     * interruption policy says to — Do Not Disturb, and on many OEM builds plain
     * silent mode. So an alarm set to "רטט" or "רטט→צלצול" simply did not vibrate on a
     * phone left in DND overnight, which is exactly the night someone is relying on
     * it. USAGE_ALARM is exempt from that suppression, matching the USAGE_ALARM
     * AudioAttributes the MediaPlayer already uses for the sound half.
     */
    private fun startVibration() {
        val pattern = longArrayOf(0, 600, 900)
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        } else {
            // API 26–32: the VibrationAttributes overload doesn't exist yet, but the
            // AudioAttributes one carries the same USAGE_ALARM meaning.
            @Suppress("DEPRECATION")
            v.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }

    private fun stopAll() {
        // ringJob, not the whole fire coroutine: a superseded fire must still finish
        // logging and re-arming itself (see onStartCommand's generation counter), and
        // only the noise-making half gets cancelled.
        ringJob?.cancel()
        silentFallbackVibrating = false
        autoStopJob?.cancel(); crescendoJob?.cancel(); ringSequenceJob?.cancel()
        // Separate runCatching per call: if stop() throws (e.g. player still in the
        // Initialized/Prepared-but-not-started state), release() must still run or
        // the native MediaPlayer leaks.
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        vibrator?.cancel()
    }

    private fun createChannel() = AlarmNotifications.ensureChannel(this)

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
        val builder = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(alarm.name)
            .setContentText(
                if (alarm.isShabbatMode) "מצב שבת — הצלצול ייפסק אוטומטית"
                else alarm.reminderText ?: alarm.timeFormatted)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true).setFullScreenIntent(openPi, true)
        // Shabbat mode: no actionable buttons anywhere, including the notification —
        // the whole point is that nothing can be pressed. It still auto-stops via
        // ringDurationSeconds (fireAlarm()'s autoStopJob), which isn't a user action.
        if (alarm.acceptsInteraction) {
            builder.addAction(R.drawable.ic_stop, "עצור", pi(StopAlarmReceiver::class.java, alarm.id, alarm.id.toInt()))
            if (alarm.snoozeEnabled) {
                builder.addAction(R.drawable.ic_snooze, "נודניק", pi(SnoozeAlarmReceiver::class.java, alarm.id, (alarm.id+10000).toInt()))
            }
        }
        return builder.build()
    }

    companion object {
        /** Defined (and silenced — this service plays the alarm's own sound and
         *  vibration itself) in [AlarmNotifications]; shared with the receiver's
         *  fallback notification. */
        const val CH = AlarmNotifications.CHANNEL_ID
        const val NOTIF_ID = 1001
        /** The device's configured alarm sound; the fallback whenever a ring's own
         *  chosen ringtone can't be played. */
        private val DEFAULT_ALARM_URI: Uri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        private const val WAKE_LOCK_TAG = "SmartRing:alarm"
        // The ring-duration slider's maximum (600s) plus room for the pre-ring
        // vibration window and teardown.
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 15 * 60 * 1000L
    }
}
