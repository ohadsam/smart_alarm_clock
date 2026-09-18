package com.smartring.app.presentation.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
import com.smartring.app.BuildConfig
import com.smartring.app.data.repository.AlarmDefaults
import com.smartring.app.domain.model.VibrationMode
import com.smartring.app.util.GENERIC_ALARM_NAME
import com.smartring.app.util.ForeignAlarms
import com.smartring.app.util.ReliabilityChecks
import com.smartring.app.util.formatDayAndTime
import com.smartring.app.util.formatDurationSeconds
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.smartring.app.util.openSystemScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenDiagnosis: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val d by vm.defaults.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "חזור") } },
                title = { Text("הגדרות", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsGroup("אמינות ברקע") {
                ReliabilitySection()
            }
            SettingsGroup("שפה") {
                RadioRow("עברית", "he", s.language) { vm.setLanguage("he") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                // Disabled deliberately, not hidden: picking English used to be
                // accepted and then change absolutely nothing on screen, because every
                // screen holds hardcoded Hebrew literals rather than reading
                // stringResource() — so values-en/strings.xml is unreachable from the
                // UI. Offering a setting that silently does nothing is worse than
                // saying it isn't ready. Re-enable this together with externalizing
                // the strings, not before.
                RadioRow("English", "en", s.language, enabled = false, supporting = "בקרוב")
            }
            SettingsGroup("עיצוב") {
                RadioRow("אוטומטי לפי המכשיר", "auto",  s.themeMode) { vm.setThemeMode("auto")  }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                RadioRow("כהה",                "dark",  s.themeMode) { vm.setThemeMode("dark")  }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                RadioRow("בהיר",               "light", s.themeMode) { vm.setThemeMode("light") }
            }
            // ── Defaults for a new alarm ──────────────────────────────────────────
            // Every one of these was a constant compiled into the edit screen's state,
            // so someone whose alarms are always 3 minutes long with vibration off had
            // to correct every new alarm by hand. Each row restores individually: a
            // single "restore all" would make fixing one mistake cost the other fourteen
            // deliberate choices.
            SettingsGroup("ברירת מחדל לשעמור חדש") {
                DefaultTimeRow(
                    hour = d.hour, minute = d.minute,
                    onChange = { h, m -> vm.updateDefaults { it.copy(hour = h, minute = m) } },
                    onReset = {
                        vm.updateDefaults {
                            it.copy(hour = AlarmDefaults.BUILT_IN.hour, minute = AlarmDefaults.BUILT_IN.minute)
                        }
                    },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultSwitchRow(
                    title = "ללא שם (\"$GENERIC_ALARM_NAME\")",
                    supporting = "שעמור חדש יקבל שם כללי אוטומטית במקום לדרוש שם",
                    checked = d.unnamed, isDefault = d.unnamed == AlarmDefaults.BUILT_IN.unnamed,
                    onChange = { v -> vm.updateDefaults { it.copy(unnamed = v) } },
                    onReset = { vm.updateDefaults { it.copy(unnamed = AlarmDefaults.BUILT_IN.unnamed) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultNumberRow(
                    title = "משך צלצול כולל", value = d.ringDurationSeconds, min = 5, max = 600,
                    display = ::formatDurationSeconds,
                    isDefault = d.ringDurationSeconds == AlarmDefaults.BUILT_IN.ringDurationSeconds,
                    onChange = { v -> vm.updateDefaults { it.copy(ringDurationSeconds = v) } },
                    onReset = { vm.updateDefaults { it.copy(ringDurationSeconds = AlarmDefaults.BUILT_IN.ringDurationSeconds) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultNumberRow(
                    title = "עוצמת צלצול", value = d.ringVolumePercent, min = 10, max = 100,
                    display = { "$it%" },
                    isDefault = d.ringVolumePercent == AlarmDefaults.BUILT_IN.ringVolumePercent,
                    onChange = { v -> vm.updateDefaults { it.copy(ringVolumePercent = v) } },
                    onReset = { vm.updateDefaults { it.copy(ringVolumePercent = AlarmDefaults.BUILT_IN.ringVolumePercent) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultSwitchRow(
                    title = "נודניק מופעל",
                    supporting = "האם שעמור חדש יאפשר נודניק",
                    checked = d.snoozeEnabled, isDefault = d.snoozeEnabled == AlarmDefaults.BUILT_IN.snoozeEnabled,
                    onChange = { v -> vm.updateDefaults { it.copy(snoozeEnabled = v) } },
                    onReset = { vm.updateDefaults { it.copy(snoozeEnabled = AlarmDefaults.BUILT_IN.snoozeEnabled) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultNumberRow(
                    title = "משך נודניק", value = d.snoozeMinutes, min = 1, max = 60,
                    display = { "$it דק׳" },
                    isDefault = d.snoozeMinutes == AlarmDefaults.BUILT_IN.snoozeMinutes,
                    onChange = { v -> vm.updateDefaults { it.copy(snoozeMinutes = v) } },
                    onReset = { vm.updateDefaults { it.copy(snoozeMinutes = AlarmDefaults.BUILT_IN.snoozeMinutes) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultNumberRow(
                    title = "מקסימום נודניקים", value = d.snoozeMaxCount, min = 1, max = 10,
                    display = { "$it" },
                    isDefault = d.snoozeMaxCount == AlarmDefaults.BUILT_IN.snoozeMaxCount,
                    onChange = { v -> vm.updateDefaults { it.copy(snoozeMaxCount = v) } },
                    onReset = { vm.updateDefaults { it.copy(snoozeMaxCount = AlarmDefaults.BUILT_IN.snoozeMaxCount) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultVibrationRow(
                    mode = d.vibrationMode,
                    isDefault = d.vibrationMode == AlarmDefaults.BUILT_IN.vibrationMode,
                    onChange = { v -> vm.updateDefaults { it.copy(vibrationMode = v) } },
                    onReset = { vm.updateDefaults { it.copy(vibrationMode = AlarmDefaults.BUILT_IN.vibrationMode) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultNumberRow(
                    title = "רטט לפני צלצול", value = d.vibrationOnlySeconds, min = 3, max = 120,
                    display = ::formatDurationSeconds,
                    isDefault = d.vibrationOnlySeconds == AlarmDefaults.BUILT_IN.vibrationOnlySeconds,
                    onChange = { v -> vm.updateDefaults { it.copy(vibrationOnlySeconds = v) } },
                    onReset = { vm.updateDefaults { it.copy(vibrationOnlySeconds = AlarmDefaults.BUILT_IN.vibrationOnlySeconds) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultSwitchRow(
                    title = "צלצול מתחזק",
                    supporting = "העוצמה תעלה בהדרגה במקום להתחיל מלאה",
                    checked = d.crescendoEnabled, isDefault = d.crescendoEnabled == AlarmDefaults.BUILT_IN.crescendoEnabled,
                    onChange = { v -> vm.updateDefaults { it.copy(crescendoEnabled = v) } },
                    onReset = { vm.updateDefaults { it.copy(crescendoEnabled = AlarmDefaults.BUILT_IN.crescendoEnabled) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultNumberRow(
                    title = "עוצמה התחלתית (מתחזק)", value = d.crescendoStartVolume, min = 5, max = 80,
                    display = { "$it%" },
                    isDefault = d.crescendoStartVolume == AlarmDefaults.BUILT_IN.crescendoStartVolume,
                    onChange = { v -> vm.updateDefaults { it.copy(crescendoStartVolume = v) } },
                    onReset = { vm.updateDefaults { it.copy(crescendoStartVolume = AlarmDefaults.BUILT_IN.crescendoStartVolume) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultNumberRow(
                    title = "כל כמה שניות עולה", value = d.crescendoStepSeconds, min = 5, max = 60,
                    display = ::formatDurationSeconds,
                    isDefault = d.crescendoStepSeconds == AlarmDefaults.BUILT_IN.crescendoStepSeconds,
                    onChange = { v -> vm.updateDefaults { it.copy(crescendoStepSeconds = v) } },
                    onReset = { vm.updateDefaults { it.copy(crescendoStepSeconds = AlarmDefaults.BUILT_IN.crescendoStepSeconds) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultNumberRow(
                    title = "עלייה בכל צעד", value = d.crescendoStepPercent, min = 5, max = 30,
                    display = { "$it%" },
                    isDefault = d.crescendoStepPercent == AlarmDefaults.BUILT_IN.crescendoStepPercent,
                    onChange = { v -> vm.updateDefaults { it.copy(crescendoStepPercent = v) } },
                    onReset = { vm.updateDefaults { it.copy(crescendoStepPercent = AlarmDefaults.BUILT_IN.crescendoStepPercent) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DefaultSwitchRow(
                    title = "מצב שבת",
                    supporting = "כפתורי עצירה ונודניק מושבתים בזמן הצלצול",
                    checked = d.isShabbatMode, isDefault = d.isShabbatMode == AlarmDefaults.BUILT_IN.isShabbatMode,
                    onChange = { v -> vm.updateDefaults { it.copy(isShabbatMode = v) } },
                    onReset = { vm.updateDefaults { it.copy(isShabbatMode = AlarmDefaults.BUILT_IN.isShabbatMode) } },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                var confirmResetAll by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text("שחזר את כל ברירות המחדל", fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("לא משנה שעמורים קיימים — רק את מה שיקרה בשעמור הבא") },
                    leadingContent = { Icon(Icons.Rounded.RestartAlt, null,
                        tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { confirmResetAll = true },
                )
                if (confirmResetAll) {
                    AlertDialog(
                        onDismissRequest = { confirmResetAll = false },
                        title = { Text("לשחזר את כל ברירות המחדל?") },
                        text = { Text("כל ההגדרות בקטע הזה יחזרו לערכים המקוריים. שעמורים שכבר נשמרו לא ישתנו.") },
                        confirmButton = {
                            TextButton({ vm.resetAllDefaults(); confirmResetAll = false }) {
                                Text("שחזר", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton({ confirmResetAll = false }) { Text("ביטול") } },
                    )
                }
            }
            SettingsGroup("שעמורים מאפליקציות אחרות") {
                ListItem(
                    headlineContent   = { Text("התרע על שעמור מאפליקציה אחרת", fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        // Says what it does and what it cannot do, in the same breath.
                        // Promising to "disable" the other alarm would be a promise no
                        // Android app can keep — see util/ForeignAlarms.kt — and a user
                        // who believed it would sleep through the alarm they meant to
                        // avoid. Which is the exact failure this setting exists to prevent.
                        Text(
                            "מציג התרעה במסך הראשי כאשר מוגדר שעמור באפליקציה אחרת " +
                            "(למשל שעון המערכת) שיצלצל לפני השעמור הקרוב של SmartRing — " +
                            "שימושי לשבת ולחג. אנדרואיד לא מאפשר לאפליקציה לכבות שעמור " +
                            "של אפליקציה אחרת, ולכן זו התרעה בלבד והכיבוי נעשה שם."
                        )
                    },
                    leadingContent    = { Icon(Icons.Rounded.NotificationsActive, null) },
                    trailingContent   = {
                        Switch(s.warnForeignAlarms, onCheckedChange = vm::setWarnForeignAlarms)
                    },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ForeignAlarmStatusRow()
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                SystemIndicatorStatusRow()
            }
            SettingsGroup("אבחון") {
                // First in this group, and phrased as the question rather than the
                // feature: someone whose alarm just failed is looking for an answer, not
                // for "diagnostics".
                ListItem(
                    headlineContent   = { Text("למה השעמור לא צלצל?", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("בדיקה מלאה של כל מה שיכול למנוע מהשעמור לצלצל, עם תיקון לכל ממצא") },
                    leadingContent    = { Icon(Icons.Rounded.HelpOutline, null) },
                    trailingContent   = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    modifier          = Modifier.clickable(onClick = onOpenDiagnosis),
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent   = { Text("לוגים", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("רישום פעולות רקע לצורכי בדיקה") },
                    leadingContent    = { Icon(Icons.Rounded.Description, null) },
                    // AutoMirrored: a "drills into another screen" chevron points in the
                    // reading direction. The hard-coded ChevronLeft here only looked right
                    // because this app happens to run RTL — it pointed backwards the
                    // moment anything rendered it LTR.
                    trailingContent   = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    modifier          = Modifier.clickable(onClick = onOpenLogs),
                )
            }
            SettingsGroup("אודות") {
                ListItem(
                    headlineContent   = { Text("SmartRing v${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Android 8+ · Kotlin + Compose") },
                    leadingContent    = { Icon(Icons.Rounded.Info, null) },
                )
            }
        }
    }
}

/**
 * Surfaces the 3 OS-level settings that most commonly stop an Android alarm clock
 * from firing reliably in the background: exact-alarm permission revoked, battery
 * optimization killing the process, or notifications blocked. None of these can be
 * granted programmatically — only requested via a system settings screen/dialog —
 * so each row re-checks its own status when the user returns to this screen.
 */
@Composable
private fun ReliabilitySection() {
    val context = LocalContext.current
    // False only when neither the specific settings screen nor this app's own details
    // page could be opened — rare, but the alternative is a button that looks broken.
    var opened by remember { mutableStateOf(true) }

    var notifGranted by remember { mutableStateOf(ReliabilityChecks.isNotificationsGranted(context)) }
    var exactGranted by remember { mutableStateOf(ReliabilityChecks.canScheduleExactAlarms(context)) }
    var batteryGranted by remember { mutableStateOf(ReliabilityChecks.isIgnoringBatteryOptimizations(context)) }
    var fullScreenGranted by remember { mutableStateOf(ReliabilityChecks.canUseFullScreenIntent(context)) }
    var volumeAudible by remember { mutableStateOf(ReliabilityChecks.isAlarmVolumeAudible(context)) }

    fun refresh() {
        notifGranted = ReliabilityChecks.isNotificationsGranted(context)
        exactGranted = ReliabilityChecks.canScheduleExactAlarms(context)
        batteryGranted = ReliabilityChecks.isIgnoringBatteryOptimizations(context)
        fullScreenGranted = ReliabilityChecks.canUseFullScreenIntent(context)
        volumeAudible = ReliabilityChecks.isAlarmVolumeAudible(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    if (!opened) {
        Text("לא נמצא מסך הגדרות מתאים במכשיר הזה. אפשר לשנות זאת ידנית בהגדרות המערכת של האפליקציה.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    ReliabilityRow(
        icon = Icons.Rounded.Notifications,
        title = "התראות",
        subtitle = "בלי התראות ייתכן שלא תראה שהשעמור מצלצל",
        granted = notifGranted,
        actionLabel = "אפשר",
        onAction = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    ReliabilityRow(
        icon = Icons.Rounded.Alarm,
        title = "שעמורים מדויקים",
        subtitle = "נדרש כדי שהשעמור יצלצל בדיוק בזמן שנקבע",
        granted = exactGranted,
        actionLabel = "אפשר",
        onAction = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                opened = openSystemScreen(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                })
            }
        },
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    ReliabilityRow(
        icon = Icons.Rounded.BatteryChargingFull,
        title = "אופטימיזציית סוללה",
        subtitle = "מערכת ההפעלה עלולה לעצור את האפליקציה ברקע ולמנוע צלצול אם זה לא כבוי",
        granted = batteryGranted,
        actionLabel = "כבה",
        onAction = {
            opened = openSystemScreen(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        },
    )
    // Android 14+ only: below that the permission is granted at install time and the
    // check always passes, so the row would just be permanent noise.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        ReliabilityRow(
            icon = Icons.Rounded.Fullscreen,
            title = "מסך מלא בזמן צלצול",
            subtitle = "בלי זה השעמור יופיע כהתראה בלבד במקום לפתוח את מסך הצלצול על מסך נעול",
            granted = fullScreenGranted,
            actionLabel = "אפשר",
            onAction = {
                opened = openSystemScreen(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                })
            },
        )
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    ReliabilityRow(
        // AutoMirrored: a speaker icon points in the reading direction, so the bare
        // Icons.Rounded.VolumeUp faces the wrong way in this RTL app (and the compiler
        // deprecates it for exactly that reason).
        icon = Icons.AutoMirrored.Rounded.VolumeUp,
        title = "עוצמת שעמור במכשיר",
        // Phrased as a condition, not a statement: this row keeps its subtitle when the
        // check passes, and "the volume is 0" next to a green check mark reads as a bug.
        subtitle = "אם עוצמת ערוץ השעמורים במכשיר היא 0, כל שעמור יהיה שקט — ללא קשר לעוצמה שהוגדרה באפליקציה",
        granted = volumeAudible,
        actionLabel = "פתח צליל",
        onAction = { opened = openSystemScreen(context, Intent(Settings.ACTION_SOUND_SETTINGS)) },
    )
}

@Composable
private fun ReliabilityRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, subtitle: String, granted: Boolean,
    actionLabel: String, onAction: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(icon, null, tint = if (granted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
        },
        headlineContent   = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            if (granted) Icon(Icons.Rounded.CheckCircle, "מאופשר", tint = MaterialTheme.colorScheme.tertiary)
            else TextButton(onAction) { Text(actionLabel, fontWeight = FontWeight.Bold) }
        },
    )
}



/**
 * What the app can see right now, whether or not the warning is switched on.
 *
 * The warning itself only appears on the main list, only while an alarm here is armed,
 * and only when the other app's alarm is the sooner of the two — so "is anything else
 * set?" was a question the app could answer and never did. Answering it here makes the
 * feature findable, and makes a silent banner distinguishable from a broken one.
 *
 * Re-read on resume, because the trip to the other app and back is the whole point.
 */
@Composable
private fun ForeignAlarmStatusRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var found by remember { mutableStateOf(ForeignAlarms.next(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) found = ForeignAlarms.next(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val alarm = found
    ListItem(
        headlineContent = { Text("מה מזוהה כרגע", fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(
                if (alarm == null)
                    "לא מזוהה שעמור מאפליקציה אחרת שיצלצל לפני השעמור הקרוב שלך. " +
                    "שים לב: אנדרואיד מדווח רק על השעמור הבא במכשיר, ולכן שעמור זר " +
                    "שמתוזמן אחרי שלך אינו נראה כאן."
                else
                    "\"${alarm.appLabel}\" — ${formatDayAndTime(alarm.triggerAtMillis)}",
            )
        },
        leadingContent = {
            Icon(
                if (alarm == null) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                null,
                tint = if (alarm == null) MaterialTheme.colorScheme.tertiary
                       else MaterialTheme.colorScheme.error,
            )
        },
    )
}


/**
 * Whether the system's next-alarm indicator is currently showing one of this app's
 * alarms.
 *
 * That indicator is not something an app draws; the OS shows it for any alarm registered
 * with `setAlarmClock`. So when it is missing there are exactly two explanations —
 * nothing is armed, or the exact-alarm permission was unavailable and the scheduler fell
 * back to an inexact alarm, which the OS does not advertise. Both are actionable and
 * neither was visible anywhere, which is why "there is no indication in the status bar"
 * had no answer inside the app.
 */
@Composable
private fun SystemIndicatorStatusRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var registered by remember { mutableStateOf(ForeignAlarms.nextRegistered(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) registered = ForeignAlarms.nextRegistered(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val r = registered
    val ours = r?.isOurs == true
    ListItem(
        headlineContent = { Text("אינדיקציה בשורת המצב", fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(
                when {
                    r == null -> "אין שעמור רשום במערכת, ולכן לא מופיע סמל שעמור בשורת המצב. " +
                        "ודא שיש שעמור פעיל — שעמור חד-פעמי נכבה אוטומטית לאחר שהוא מצלצל."
                    ours -> "רשום במערכת: השעמור שלך ל-${formatDayAndTime(r.triggerAtMillis)}. " +
                        "סמל השעמור בשורת המצב ובמסך הנעילה מגיע מכאן."
                    else -> "השעמור הבא הרשום במערכת שייך ל\"${r.appLabel}\", ולכן הסמל בשורת המצב " +
                        "מציג אותו ולא את השעמור שלך."
                },
            )
        },
        leadingContent = {
            Icon(
                if (ours) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                null,
                tint = if (ours) MaterialTheme.colorScheme.tertiary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

// ── Rows for the "default for a new alarm" section ──────────────────────────────
//
// Each carries its own restore button, shown only when the value differs from the
// shipped one. Always-visible restore buttons would put fifteen identical icons down the
// side of the screen and say nothing; this way the icon's presence *is* the indication
// that something was changed from the default.

@Composable
private fun RestoreButton(isDefault: Boolean, onReset: () -> Unit) {
    if (isDefault) return
    IconButton(onReset, Modifier.size(40.dp)) {
        Icon(Icons.Rounded.Restore, "שחזר לברירת המחדל", Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultTimeRow(hour: Int, minute: Int, onChange: (Int, Int) -> Unit, onReset: () -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val isDefault = hour == AlarmDefaults.BUILT_IN.hour && minute == AlarmDefaults.BUILT_IN.minute
    ListItem(
        headlineContent = { Text("שעה", fontWeight = FontWeight.Medium) },
        supportingContent = { Text("השעה שתופיע בשעמור חדש") },
        leadingContent = { Icon(Icons.Rounded.Schedule, null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RestoreButton(isDefault, onReset)
                Text("%02d:%02d".format(hour, minute),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable { showPicker = true },
    )
    if (showPicker) {
        val state = rememberTimePickerState(hour, minute, true)
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("שעת ברירת מחדל") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton({ onChange(state.hour, state.minute); showPicker = false }) { Text("אישור") }
            },
            dismissButton = { TextButton({ showPicker = false }) { Text("ביטול") } },
        )
    }
}

@Composable
private fun DefaultSwitchRow(
    title: String, supporting: String, checked: Boolean, isDefault: Boolean,
    onChange: (Boolean) -> Unit, onReset: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RestoreButton(isDefault, onReset)
                Switch(checked, onCheckedChange = onChange)
            }
        },
    )
}

/**
 * A numeric default, typed rather than dragged.
 *
 * A slider per row would make this section enormous and is the wrong control for a value
 * set once and then left alone. The dialog validates against the same bounds the edit
 * screen's slider uses, so a default can never be set to something the alarm editor
 * would refuse.
 */
@Composable
private fun DefaultNumberRow(
    title: String, value: Int, min: Int, max: Int, display: (Int) -> String,
    isDefault: Boolean, onChange: (Int) -> Unit, onReset: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RestoreButton(isDefault, onReset)
                Text(display(value), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable { showDialog = true },
    )
    if (showDialog) {
        var text by remember { mutableStateOf(value.toString()) }
        val parsed = text.toIntOrNull()
        val error = parsed == null || parsed < min || parsed > max
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter(Char::isDigit).take(4) },
                        singleLine = true,
                        isError = error,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (error) "הזן ערך בין $min ל-$max" else display(parsed!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton({ onChange(parsed!!); showDialog = false }, enabled = !error) { Text("אישור") }
            },
            dismissButton = { TextButton({ showDialog = false }) { Text("ביטול") } },
        )
    }
}

@Composable
private fun DefaultVibrationRow(
    mode: VibrationMode, isDefault: Boolean,
    onChange: (VibrationMode) -> Unit, onReset: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("רטט וצלצול", fontWeight = FontWeight.Medium) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RestoreButton(isDefault, onReset)
                Text(vibrationModeLabel(mode), style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable { showDialog = true },
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("רטט וצלצול") },
            text = {
                Column {
                    VibrationMode.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onChange(option); showDialog = false }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(option == mode, onClick = { onChange(option); showDialog = false })
                            Spacer(Modifier.width(8.dp))
                            Text(vibrationModeLabel(option))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showDialog = false }) { Text("סגור") } },
        )
    }
}

/** Shared so the Settings row and the edit screen cannot drift apart in wording. */
private fun vibrationModeLabel(mode: VibrationMode): String = when (mode) {
    VibrationMode.SOUND_ONLY           -> "צליל בלבד"
    VibrationMode.VIBRATION_ONLY       -> "רטט בלבד"
    VibrationMode.SOUND_AND_VIBRATION  -> "צליל ורטט"
    VibrationMode.VIBRATION_THEN_SOUND -> "רטט ואז צליל"
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text     = title.uppercase(),
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Surface(
            shape  = RoundedCornerShape(14.dp),
            color  = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    value: String,
    current: String,
    enabled: Boolean = true,
    supporting: String? = null,
    onClick: () -> Unit = {},
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ListItem(
        headlineContent  = { Text(label, fontWeight = FontWeight.Medium, color = contentColor) },
        supportingContent = supporting?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        trailingContent  = {
            RadioButton(selected = current == value, onClick = onClick.takeIf { enabled }, enabled = enabled)
        },
        modifier         = if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
