package com.smartring.app.presentation.alarmring
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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

            // Vibration badge
            val (vLabel, vColor) = when {
                alarm.vibrationMode == VibrationMode.VIBRATION_THEN_SOUND && s.elapsedSeconds < alarm.vibrationOnlySeconds ->
                    "רטט בלבד – עוד ${alarm.vibrationOnlySeconds - s.elapsedSeconds}שנ׳" to Red
                alarm.vibrationMode == VibrationMode.SOUND_ONLY          -> "רק צלצול" to Blue
                alarm.vibrationMode == VibrationMode.VIBRATION_ONLY      -> "רק רטט" to Green
                else -> "צלצול + רטט" to Gold
            }
            Surface(RoundedCornerShape(999.dp), color = vColor.copy(.12f),
                border = BorderStroke(1.dp, vColor.copy(.35f))) {
                Text(vLabel, Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = vColor, fontWeight = FontWeight.SemiBold)
            }

            // Crescendo bar. Uses the theme's `tertiary` role (not the raw Green
            // constant) so it stays legible in Light mode too — Green (a light mint)
            // as literal text/icon color on a white surface fails contrast.
            if (alarm.crescendoEnabled) {
                Spacer(Modifier.height(8.dp))
                val vol = alarm.volumeAtSecond(100, s.elapsedSeconds)
                val accent = MaterialTheme.colorScheme.tertiary
                Surface(RoundedCornerShape(12.dp), color = accent.copy(.08f),
                    border = BorderStroke(1.dp, accent.copy(.2f)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.TrendingUp, null, Modifier.size(14.dp), tint = accent)
                                Spacer(Modifier.width(6.dp))
                                Text("צלצול מתחזק", style = MaterialTheme.typography.labelMedium,
                                    color = accent, fontWeight = FontWeight.SemiBold)
                            }
                            Text("${vol}%", style = MaterialTheme.typography.labelMedium,
                                color = accent, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator({ vol / 100f }, Modifier.fillMaxWidth().height(6.dp),
                            color = accent, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }

            // Reminder text — same theme-aware accent as the crescendo bar above.
            alarm.reminderText?.takeIf { it.isNotBlank() }?.let { txt ->
                Spacer(Modifier.height(12.dp))
                val accent = MaterialTheme.colorScheme.tertiary
                Surface(RoundedCornerShape(16.dp), color = accent.copy(.1f),
                    border = BorderStroke(1.dp, accent.copy(.3f)), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.StickyNote2, null, Modifier.size(20.dp), tint = accent)
                        Spacer(Modifier.width(10.dp))
                        Text(txt, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }

            Spacer(Modifier.height(48.dp))

            // STOP button
            Box(Modifier.size(160.dp).scale(scale), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().background(Red.copy(.18f), CircleShape)
                    .border(2.dp, Red.copy(.4f), CircleShape))
                IconButton(vm::stop, Modifier.size(130.dp).clip(CircleShape).background(Red)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Stop, null, Modifier.size(52.dp), tint = White)
                        Text("עצור", color = White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            if (s.snoozeCount < alarm.snoozeMaxCount) {
                TextButton(vm::snooze) {
                    Icon(Icons.Rounded.Bedtime, null, Modifier.size(18.dp), tint = Gold)
                    Spacer(Modifier.width(6.dp))
                    Text("נודניק – ${alarm.snoozeMinutes} דק' (${alarm.snoozeMaxCount - s.snoozeCount} נותרו)",
                        color = Gold, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("הגעת למגבלת הנודניק", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
