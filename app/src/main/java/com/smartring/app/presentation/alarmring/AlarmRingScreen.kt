package com.smartring.app.presentation.alarmring
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.domain.model.*
import com.smartring.app.presentation.theme.*
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

@Composable
fun AlarmRingScreen(alarmId: Long, onDismiss: () -> Unit,
    vm: AlarmRingViewModel = hiltViewModel()) {
    LaunchedEffect(alarmId) { vm.loadAlarm(alarmId) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { delay(1_000); vm.tick() } }
    LaunchedEffect(s.isDismissed) { if (s.isDismissed) onDismiss() }

    // Back must not dismiss a ringing alarm. It never stopped the ringtone (that's
    // AlarmFiringService's job, and only Stop/Snooze tell it to) — it just navigated
    // away from the one screen with the buttons, leaving the alarm blaring with no
    // visible way to silence it. It also drove a hole straight through Shabbat mode,
    // whose entire premise is that no interaction is accepted while the alarm rings.
    // Disabled once the alarm has been dealt with, so the dismissal navigation that
    // follows isn't itself swallowed.
    BackHandler(enabled = !s.isDismissed) { /* deliberately consumed */ }

    // Add KeepScreenOn so alarm screen stays visible
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Null-check the local `alarm` (not the `s.alarm` property access, which is
    // backed by collectAsStateWithLifecycle()'s custom getter and doesn't smart-cast
    // reliably across repeated reads) so the rest of this function sees a real
    // non-null Alarm without needing ?. / !! everywhere.
    val alarm = s.alarm
    if (alarm == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(0.95f, 1.07f,
        infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "scale")

    // Live clock: recomputes on every tick so the display stays current
    val timeStr = remember(s.elapsedSeconds) {
        val cal = java.util.Calendar.getInstance()
        "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {

            Text(timeStr, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(alarm.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))

            // Vibration badge. Theme color *roles*, not the raw palette constants:
            // Green (a pale mint) and Gold (a pale amber) are legible as text on the
            // dark scheme's near-black surface and wash out completely on Light mode's
            // white one — the same contrast bug already fixed for the crescendo bar
            // and the reminder card below.
            val (vLabel, vColor) = when {
                alarm.vibrationMode == VibrationMode.VIBRATION_THEN_SOUND && !alarm.soundActiveAt(s.elapsedSeconds) ->
                    "רטט בלבד – עוד ${alarm.vibrationOnlySeconds - s.elapsedSeconds}שנ׳" to MaterialTheme.colorScheme.error
                alarm.vibrationMode == VibrationMode.SOUND_ONLY          -> "רק צלצול" to MaterialTheme.colorScheme.primary
                alarm.vibrationMode == VibrationMode.VIBRATION_ONLY      -> "רק רטט" to MaterialTheme.colorScheme.tertiary
                else -> "צלצול + רטט" to MaterialTheme.colorScheme.secondary
            }
            Surface(shape = RoundedCornerShape(999.dp), color = vColor.copy(.12f),
                border = BorderStroke(1.dp, vColor.copy(.35f))) {
                Text(vLabel, Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = vColor, fontWeight = FontWeight.SemiBold)
            }

            // Crescendo bar. Uses the theme's `tertiary` role (not the raw Green
            // constant) so it stays legible in Light mode too — Green (a light mint)
            // as literal text/icon color on a white surface fails contrast.
            //
            // Only drawn while sound is actually coming out: audibleVolumeAtSecond()
            // is null during vibration-only alarms and during both silent windows of
            // a "רטט→צלצול" alarm, where a bar reading "צלצול מתחזק 40%" over silence
            // is simply wrong. The percentage itself is the round's real volume, not
            // a hard-coded 100% base.
            val audibleVolume = alarm.audibleVolumeAtSecond(s.elapsedSeconds)
            if (alarm.crescendoEnabled && audibleVolume != null) {
                Spacer(Modifier.height(8.dp))
                val accent = MaterialTheme.colorScheme.tertiary
                // Which round is playing, shown only when there is more than one —
                // otherwise "סבב 1 מתוך 1" is noise on a screen meant to be read in
                // one glance while half awake.
                val roundLabel = alarm.ringAtSecond(s.elapsedSeconds)
                    ?.takeIf { alarm.effectiveRings.size > 1 }
                    ?.let { " · סבב ${it.index + 1}/${alarm.effectiveRings.size}" } ?: ""
                Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(.08f),
                    border = BorderStroke(1.dp, accent.copy(.2f)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, Modifier.size(14.dp), tint = accent)
                                Spacer(Modifier.width(6.dp))
                                Text("צלצול מתחזק$roundLabel", style = MaterialTheme.typography.labelMedium,
                                    color = accent, fontWeight = FontWeight.SemiBold)
                            }
                            Text("${audibleVolume}%", style = MaterialTheme.typography.labelMedium,
                                color = accent, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator({ audibleVolume / 100f }, Modifier.fillMaxWidth().height(6.dp),
                            color = accent, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }

            // Reminder text — same theme-aware accent as the crescendo bar above.
            alarm.reminderText?.takeIf { it.isNotBlank() }?.let { txt ->
                Spacer(Modifier.height(12.dp))
                val accent = MaterialTheme.colorScheme.tertiary
                Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(.1f),
                    border = BorderStroke(1.dp, accent.copy(.3f)), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.StickyNote2, null, Modifier.size(20.dp), tint = accent)
                        Spacer(Modifier.width(10.dp))
                        Text(txt, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }

            Spacer(Modifier.height(48.dp))

            // Shabbat mode: Stop/Snooze are disabled (grayed, non-clickable) so
            // nothing needs to be pressed — the alarm still auto-stops via
            // ringDurationSeconds (fireAlarm()'s autoStopJob), which isn't a
            // user-initiated action.
            val shabbat = !alarm.acceptsInteraction
            // Paired color roles, not a raw constant with hard-coded White on top. The
            // Red constant is a fairly light pink, so white text on it measured 3.14:1
            // — on the single most important control in the app, read half-awake in the
            // dark. error/onError is 6.24:1 in dark and 6.85:1 in light, and both are
            // pinned by ThemeContrastTest.
            val stopColor = if (shabbat) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.error
            val stopContentColor = if (shabbat) MaterialTheme.colorScheme.onSurfaceVariant
                                   else MaterialTheme.colorScheme.onError

            // STOP button
            Box(Modifier.size(160.dp).scale(if (shabbat) 1f else scale), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().background(stopColor.copy(.18f), CircleShape)
                    .border(2.dp, stopColor.copy(.4f), CircleShape))
                IconButton(vm::stop, Modifier.size(130.dp).clip(CircleShape).background(stopColor),
                    enabled = !shabbat) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Stop, null, Modifier.size(52.dp), tint = stopContentColor)
                        Text("עצור", color = stopContentColor, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            when {
                shabbat -> {
                    Surface(shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("מצב שבת פעיל — לא ניתן לעצור או לדחות. השעמור ייפסק אוטומטית.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                !alarm.snoozeEnabled -> { /* no snooze UI at all for this alarm */ }
                s.snoozeCount < alarm.snoozeMaxCount -> {
                    // colorScheme.secondary, not the raw Gold constant: as bare button
                    // text on Light mode's white background Gold is effectively
                    // invisible, which hid the only snooze control the screen has.
                    val snoozeColor = MaterialTheme.colorScheme.secondary
                    TextButton(vm::snooze) {
                        Icon(Icons.Rounded.Bedtime, null, Modifier.size(18.dp), tint = snoozeColor)
                        Spacer(Modifier.width(6.dp))
                        Text("נודניק – ${alarm.snoozeMinutes} דק' (${alarm.snoozeMaxCount - s.snoozeCount} נותרו)",
                            color = snoozeColor, fontWeight = FontWeight.SemiBold)
                    }
                }
                else -> {
                    Text("הגעת למגבלת הנודניק", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
