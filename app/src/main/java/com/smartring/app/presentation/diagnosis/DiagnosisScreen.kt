package com.smartring.app.presentation.diagnosis

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.util.DiagnosisFix
import com.smartring.app.util.DiagnosisItem
import com.smartring.app.util.DiagnosisSeverity
import com.smartring.app.util.diagnosisHeadline
import com.smartring.app.util.formatDayAndTime
import com.smartring.app.util.openSystemScreen

/**
 * "למה השעמור לא צלצל" — every reading the app can take, in the order these things
 * actually break, with the fix for each one a tap away.
 *
 * All of this existed already, scattered: the permission checks in Settings, the
 * next-registered-alarm row, the ring history, the technical log. What did not exist was
 * the one place that answers the question someone actually asks at 06:35. Nobody should
 * have to know that "exact alarm permission" is the thing to go looking for.
 *
 * Re-read on every resume, because the entire journey is a trip out to a system settings
 * screen and back, and a verdict still showing the old state would be worse than none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosisScreen(onBack: () -> Unit, vm: DiagnosisViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "חזור") } },
                title = { Text("למה השעמור לא צלצל?", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val blockers = state.items.count { it.severity == DiagnosisSeverity.BLOCKER }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = when {
                    blockers > 0 -> MaterialTheme.colorScheme.errorContainer
                    state.items.any { it.severity == DiagnosisSeverity.WARNING } ->
                        MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    diagnosisHeadline(state.items),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            state.items.forEach { DiagnosisRow(it) { fix -> openFix(context, fix) } }

            if (state.recentRings.isNotEmpty()) {
                Text("הצלצולים האחרונים", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .2f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        state.recentRings.forEach { log ->
                            ListItem(
                                headlineContent = {
                                    Text(log.alarmName.ifBlank { "שעמור" },
                                        fontWeight = FontWeight.Medium)
                                },
                                supportingContent = {
                                    // The action word matters more than the timestamp:
                                    // MISSED means it rang its full length untouched,
                                    // which is a very different story from STOPPED.
                                    Text("${actionLabel(log.action)} · ${formatDayAndTime(log.firedAt)}")
                                },
                            )
                        }
                    }
                }
            } else {
                // Deliberately does not mention the test ring: a rehearsal writes no
                // history row, by design, and promising one here would send the user
                // looking for a line that is never going to appear.
                Text(
                    "אין עדיין רישום צלצולים. אחרי הצלצול הראשון יופיעו כאן השורות האחרונות.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** FIRED / STOPPED / SNOOZED / MISSED, in words that say what happened. */
private fun actionLabel(action: String): String = when (action) {
    "FIRED"   -> "צלצל"
    "STOPPED" -> "נעצר ידנית"
    "SNOOZED" -> "נדחה בנודניק"
    "MISSED"  -> "צלצל עד הסוף ולא נעצר"
    else      -> action
}

@Composable
private fun DiagnosisRow(item: DiagnosisItem, onFix: (DiagnosisFix) -> Unit) {
    val (icon, tint) = when (item.severity) {
        DiagnosisSeverity.OK       -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.tertiary
        DiagnosisSeverity.WARNING  -> Icons.Rounded.Warning to MaterialTheme.colorScheme.secondary
        DiagnosisSeverity.BLOCKER  -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tint.copy(alpha = .3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(item.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.fix?.let { fix ->
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(
                        onClick = { onFix(fix) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) { Text("תקן", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

/**
 * Opens the system screen that fixes one finding.
 *
 * Routed through [openSystemScreen], which falls back to this app's own details page
 * rather than throwing: several of these screens simply do not exist on some OEM and
 * Android Go builds, and a diagnosis screen whose fix button crashes the app would be a
 * particularly poor joke.
 */
private fun openFix(context: android.content.Context, fix: DiagnosisFix) {
    val intent = when (fix) {
        DiagnosisFix.EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", context.packageName, null))
            else appDetails(context)
        DiagnosisFix.BATTERY ->
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        DiagnosisFix.NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        DiagnosisFix.FULL_SCREEN ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.fromParts("package", context.packageName, null))
            else appDetails(context)
        // No system screen raises the alarm volume; the sound settings page is the
        // closest thing, and the row's text already explains which slider to move.
        DiagnosisFix.VOLUME -> Intent(Settings.ACTION_SOUND_SETTINGS)
        DiagnosisFix.APP_DETAILS -> appDetails(context)
    }
    openSystemScreen(context, intent)
}

private fun appDetails(context: android.content.Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null))
