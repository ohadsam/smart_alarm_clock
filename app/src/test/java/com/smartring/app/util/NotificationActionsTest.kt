package com.smartring.app.util

import com.smartring.app.domain.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rules behind the alarm notification's two buttons. Until they were pulled out
 * of the receivers they had no coverage at all — `goAsync()` needs a real pending
 * broadcast result and `@AndroidEntryPoint` needs the Hilt graph, so neither suite
 * could reach them — even though they carry two of the app's strongest promises:
 * Shabbat mode accepting *no* interaction anywhere, and the snooze cap being real.
 *
 * Both directions matter here. Refusing a tap the user is entitled to leaves a phone
 * blaring; honouring one it shouldn't breaks Shabbat mode from the lock screen, where
 * the ring screen's own guard never runs.
 */
class NotificationActionsTest {

    private val plain = Alarm(id = 1, name = "בוקר", snoozeEnabled = true, snoozeMinutes = 7, snoozeMaxCount = 3)
    private val shabbat = plain.copy(isShabbatMode = true)

    // ── Stop ─────────────────────────────────────────────────────────────────

    @Test
    fun `stop silences an ordinary alarm`() {
        assertEquals(StopDecision.STOP, stopDecision(plain))
    }

    @Test
    fun `stop is refused for a Shabbat alarm`() {
        // buildNotification() omits the button entirely for these, so reaching here at
        // all means a stale PendingIntent survived — which is precisely when the guard
        // has to hold.
        assertEquals(StopDecision.IGNORE, stopDecision(shabbat))
    }

    @Test
    fun `stop fails open when the alarm can't be read`() {
        // A database hiccup must not turn a tap on Stop into a no-op: an alarm nobody
        // can silence is a worse outcome than a Shabbat alarm silenced by mistake, and
        // the latter needs the row to say so in the first place.
        assertEquals(StopDecision.STOP, stopDecision(null))
    }

    // ── Snooze ───────────────────────────────────────────────────────────────

    @Test
    fun `snooze re-arms at the alarm's configured interval`() {
        assertEquals(SnoozeDecision.Snooze(7), snoozeDecision(plain, alreadySnoozed = 0))
    }

    @Test
    fun `snooze is refused for a Shabbat alarm, leaving it ringing`() {
        // Ignore, not SilenceOnly: stopping the service would itself be acting on a tap
        // this alarm accepts none of. It still ends on its own via ringDurationSeconds.
        assertEquals(SnoozeDecision.Ignore, snoozeDecision(shabbat, alreadySnoozed = 0))
    }

    @Test
    fun `snooze on an alarm that no longer exists just silences it`() {
        assertEquals(SnoozeDecision.SilenceOnly, snoozeDecision(null, alreadySnoozed = 0))
    }

    @Test
    fun `snooze on an alarm with snooze turned off degrades to a stop`() {
        // The button shouldn't be showing at all, but the user did press something —
        // leaving the alarm ringing would be the worst of the three options.
        assertEquals(SnoozeDecision.StopInstead("STOPPED"), snoozeDecision(plain.copy(snoozeEnabled = false), 0))
    }

    @Test
    fun `the last allowed snooze still snoozes`() {
        // Off-by-one in the other direction costs the user a snooze they configured.
        assertEquals(SnoozeDecision.Snooze(7), snoozeDecision(plain, alreadySnoozed = 2))
    }

    @Test
    fun `reaching the cap stops the alarm and records it as missed`() {
        // MISSED rather than STOPPED: "it ran out of snoozes" and "I turned it off" are
        // different stories in the history, and this is the one that means the alarm
        // was never actually dealt with.
        assertEquals(SnoozeDecision.StopInstead("MISSED"), snoozeDecision(plain, alreadySnoozed = 3))
    }

    @Test
    fun `passing the cap is still treated as reaching it`() {
        assertEquals(SnoozeDecision.StopInstead("MISSED"), snoozeDecision(plain, alreadySnoozed = 9))
    }

    @Test
    fun `a cap of one allows exactly one snooze`() {
        val once = plain.copy(snoozeMaxCount = 1)
        assertEquals(SnoozeDecision.Snooze(7), snoozeDecision(once, alreadySnoozed = 0))
        assertEquals(SnoozeDecision.StopInstead("MISSED"), snoozeDecision(once, alreadySnoozed = 1))
    }

    @Test
    fun `Shabbat mode outranks every other reason to act`() {
        // Whatever else is true of the alarm, a Shabbat one accepts nothing. Ordering
        // the checks the other way round would silence it via one of the degraded
        // paths, which is exactly the interaction the mode exists to prevent.
        assertEquals(SnoozeDecision.Ignore, snoozeDecision(shabbat.copy(snoozeEnabled = false), 0))
        assertEquals(SnoozeDecision.Ignore, snoozeDecision(shabbat, alreadySnoozed = 99))
    }
}
