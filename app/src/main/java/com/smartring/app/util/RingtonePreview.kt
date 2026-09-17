package com.smartring.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

/**
 * Plays the ringtone a round is configured with, at the volume it is configured with, so
 * the user can hear what they chose before an alarm at 06:30 tells them.
 *
 * Deliberately a plain class rather than part of AlarmFiringService: a preview must never
 * touch the real firing path, must never hold a wake lock, and must be abandonable the
 * instant the screen goes away. It also stops itself, so a forgotten preview cannot go on
 * playing over the rest of the app.
 *
 * USAGE_ALARM, matching the real playback: the preview is worthless if it plays through a
 * different stream from the one the alarm will use, because the device's alarm volume is
 * exactly what the user is trying to judge. The consequence — a preview is as loud as an
 * alarm — is intended, and the UI says so.
 */
class RingtonePreviewPlayer {

    private var player: MediaPlayer? = null

    /** Whichever round is currently previewing, or null. Drives the play/stop icon. */
    var playingIndex: Int? = null
        private set

    /**
     * Starts (or restarts) the preview for [roundIndex].
     *
     * Returns false when nothing could be played — an unreadable custom ringtone, a
     * device with no default alarm sound — so the caller can say so instead of leaving a
     * button that looks broken. Every failure mode here is non-fatal by design: this is a
     * convenience, and it must not be able to take the edit screen down.
     */
    fun play(context: Context, roundIndex: Int, ringtoneUri: String, volumePercent: Int): Boolean {
        stop()
        val uri = resolveUri(context, ringtoneUri) ?: return false
        val started = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = false
                // The same 0..1 scaling the firing service applies, so what is heard here
                // is what will be heard then.
                val v = (volumePercent.coerceIn(0, 100)) / 100f
                setVolume(v, v)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }.getOrNull() ?: return false
        player = started
        playingIndex = roundIndex
        return true
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingIndex = null
    }

    /**
     * "default" is this app's own marker for "whatever the device's alarm sound is", and
     * falls back to the notification sound on a device that has no alarm default — which
     * is silent-by-default on some OEM builds, and a silent preview reads as a bug.
     */
    private fun resolveUri(context: Context, ringtoneUri: String): Uri? =
        if (ringtoneUri.isBlank() || ringtoneUri == "default") {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } else {
            runCatching { Uri.parse(ringtoneUri) }.getOrNull()
        }
}
