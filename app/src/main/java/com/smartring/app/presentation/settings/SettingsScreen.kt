package com.smartring.app.presentation.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.BuildConfig
import com.smartring.app.util.ReliabilityChecks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLogs: () -> Unit = {}, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
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
                RadioRow("עברית",   "he", s.language)  { vm.setLanguage("he") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                RadioRow("English", "en", s.language)  { vm.setLanguage("en") }
            }
            SettingsGroup("עיצוב") {
                RadioRow("אוטומטי לפי המכשיר", "auto",  s.themeMode) { vm.setThemeMode("auto")  }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                RadioRow("כהה",                "dark",  s.themeMode) { vm.setThemeMode("dark")  }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                RadioRow("בהיר",               "light", s.themeMode) { vm.setThemeMode("light") }
            }
            SettingsGroup("אבחון") {
                ListItem(
                    headlineContent   = { Text("לוגים", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("רישום פעולות רקע לצורכי בדיקה") },
                    leadingContent    = { Icon(Icons.Rounded.Description, null) },
                    trailingContent   = { Icon(Icons.Rounded.ChevronLeft, null) },
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

    var notifGranted by remember { mutableStateOf(isNotificationsGranted(context)) }
    var exactGranted by remember { mutableStateOf(ReliabilityChecks.canScheduleExactAlarms(context)) }
    var batteryGranted by remember { mutableStateOf(ReliabilityChecks.isIgnoringBatteryOptimizations(context)) }

    fun refresh() {
        notifGranted = isNotificationsGranted(context)
        exactGranted = ReliabilityChecks.canScheduleExactAlarms(context)
        batteryGranted = ReliabilityChecks.isIgnoringBatteryOptimizations(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

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
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
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
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        },
    )
}

private fun isNotificationsGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    else true

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
private fun RadioRow(label: String, value: String, current: String, onClick: () -> Unit) {
    ListItem(
        headlineContent  = { Text(label, fontWeight = FontWeight.Medium) },
        trailingContent  = { RadioButton(selected = current == value, onClick = onClick) },
        modifier         = Modifier.clickable(onClick = onClick),
    )
}
