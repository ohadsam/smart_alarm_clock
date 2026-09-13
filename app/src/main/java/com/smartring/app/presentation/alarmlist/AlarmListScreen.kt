package com.smartring.app.presentation.alarmlist
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.domain.model.Alarm
import com.smartring.app.presentation.theme.*
import com.smartring.app.presentation.whatsnew.WhatsNewDialog
import com.smartring.app.presentation.whatsnew.WhatsNewViewModel
import com.smartring.app.util.ReliabilityChecks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(onAddAlarm: ()->Unit, onEditAlarm: (Long)->Unit, onOpenHistory: ()->Unit, onOpenSettings: ()->Unit,
    vm: AlarmListViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showControls by remember { mutableStateOf(false) }

    val whatsNewVm: WhatsNewViewModel = hiltViewModel()
    val whatsNewState by whatsNewVm.state.collectAsStateWithLifecycle()
    WhatsNewDialog(whatsNewVm)
    // Don't compete with the What's New dialog (a system permission prompt popping up
    // at the same time as a Compose AlertDialog is jarring and one can eat the other's
    // input) — wait until it has genuinely resolved to "nothing to show" (`checked`,
    // not just the default-empty initial state) before this one gets a turn.
    if (whatsNewState.checked && whatsNewState.entriesToShow.isEmpty()) {
        ReliabilityGate(onOpenSettings)
    }

    Scaffold(
        topBar = {
            TopAppBar(title={Text("SmartRing",fontWeight=FontWeight.Black,fontSize=24.sp)},
                colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background),
                actions={
                    IconButton({showControls=true}){Icon(Icons.Rounded.Tune,"שליטה כללית")}
                    IconButton(onOpenHistory){Icon(Icons.Rounded.History,"היסטוריה")}
                    IconButton(onOpenSettings){Icon(Icons.Rounded.Settings,"הגדרות")}
                })
        },
        floatingActionButton={
            FloatingActionButton(onAddAlarm,shape=CircleShape,containerColor=MaterialTheme.colorScheme.primary){
                Icon(Icons.Rounded.Add,null,tint=White)
            }
        },
        containerColor=MaterialTheme.colorScheme.background,
    ) { pad ->
        if (state.isLoading) Box(Modifier.padding(pad).fillMaxSize(),Alignment.Center){CircularProgressIndicator()}
        else if (state.alarms.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(),Alignment.Center){
                Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Icon(Icons.Rounded.AlarmOff,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("אין שעמורים עדיין",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text("לחץ + להוספת שעמור",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.padding(pad),contentPadding=PaddingValues(16.dp,8.dp,16.dp,96.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(state.alarms,key={it.id}) { alarm ->
                    AlarmCardItem(alarm,{vm.toggle(alarm,it)},{onEditAlarm(alarm.id)},{vm.delete(alarm)})
                }
            }
        }
    }
    if (showControls) {
        val frozenCount = state.alarms.count { it.isFrozen }
        val activeCount = state.alarms.count { it.isActive }
        ModalBottomSheet({ showControls = false }) {
            Column(
                Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("שליטה כללית", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    "$activeCount פעילים · $frozenCount מוקפאים",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Button({ vm.enableAll(); showControls = false }, Modifier.fillMaxWidth()) {
                    Text("✅ הפעל הכל")
                }
                OutlinedButton(
                    onClick = {
                        if (frozenCount > 0) vm.unfreezeAll() else vm.freezeAll()
                        showControls = false
                    },
                    Modifier.fillMaxWidth(),
                ) {
                    Text(if (frozenCount > 0) "❄️ בטל הקפאה ($frozenCount)" else "❄️ הקפא הכל")
                }
                Button(
                    onClick = { vm.disableAll(); showControls = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("🔕 כבה הכל")
                }
            }
        }
    }}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AlarmCardItem(alarm: Alarm, onToggle:(Boolean)->Unit, onEdit:()->Unit, onDelete:()->Unit) {
    var showDel by remember { mutableStateOf(false) }
    val dotColor = if (alarm.isFrozen) Blue else if (alarm.isActive) Green else MaterialTheme.colorScheme.outline

    // Swipe (either direction) surfaces the same confirm dialog as long-press, rather
    // than deleting outright — a quicker, more discoverable gesture without an
    // accidental-delete risk. confirmValueChange always returns false so the card
    // snaps back to place once the dialog is shown; the dialog owns the real delete.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) showDel = true
            false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val alignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
                Alignment.CenterStart else Alignment.CenterEnd
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Icon(Icons.Rounded.Delete, "מחק", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
    Surface(Modifier.fillMaxWidth().combinedClickable(onClick=onEdit,onLongClick={showDel=true}),
        RoundedCornerShape(16.dp), color=MaterialTheme.colorScheme.surface,
        border=BorderStroke(1.5.dp,dotColor.copy(.3f))) {
        Row(Modifier.padding(16.dp,14.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)){
                Text(alarm.timeFormatted,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold,
                    color=if(alarm.isActive)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(alarm.name,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                // Which days this actually rings on. Without it two alarms at the same
                // time — one every weekday, one a single next-Tuesday reminder — looked
                // completely identical in the list.
                Text(alarm.scheduleSummary(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                alarm.reminderText?.takeIf{it.isNotBlank()}?.let{
                    Spacer(Modifier.height(2.dp))
                    Text("📝 $it",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary,maxLines=1)
                }
                if (alarm.isShabbatMode || !alarm.snoozeEnabled) {
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (alarm.isShabbatMode) MiniBadge("🕯 שבת", MaterialTheme.colorScheme.tertiary)
                        if (!alarm.snoozeEnabled) MiniBadge("ללא נודניק", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(horizontalAlignment=Alignment.End){
                Switch(alarm.isEnabled,onToggle,
                    modifier=Modifier.semantics{contentDescription=
                        "שעמור ${alarm.name.ifBlank{alarm.timeFormatted}} בשעה ${alarm.timeFormatted}, ${if(alarm.isEnabled)"פעיל" else "כבוי"}"})
                Text("לחץ לחיצה ארוכה למחיקה",
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f),
                    fontSize=9.sp)
            }
        }
    }
    }
    if (showDel) AlertDialog({showDel=false},title={Text("מחק שעמור")},text={Text("מחק את \"${alarm.name}\"?")},
        confirmButton={TextButton({showDel=false;onDelete()}){Text("מחק",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({showDel=false}){Text("ביטול")}})
}

/**
 * Proactively surfaces the reliability checks (Settings -> אמינות ברקע) on app
 * entry instead of only when the user happens to open Settings themselves —
 * notifications are requested directly (the only one of the three that can be
 * silently asked for without leaving the app); exact-alarm/battery-optimization
 * need a system settings screen, so this only nudges the user toward the existing
 * Settings screen (which has the real per-item fix buttons) rather than duplicating
 * that Intent-construction logic here.
 */
@Composable
private fun ReliabilityGate(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var alreadyChecked by rememberSaveable { mutableStateOf(false) }
    // rememberSaveable, matching alreadyChecked: a plain remember here reset to false
    // across a config change (e.g. rotation) while alreadyChecked survived it, so the
    // dialog silently vanished on rotation and — since alreadyChecked already being
    // true skips LaunchedEffect's re-check — never came back for the rest of the
    // session even though the underlying issue was never resolved.
    var showSettingsPrompt by rememberSaveable { mutableStateOf(false) }
    // Notifications denied still routes to the same Settings prompt (its "אמינות
    // ברקע" section has the real fix action for it), so a denied notification
    // permission isn't silently dropped just because it's the one check resolved
    // via a direct system dialog instead of the other two's Settings redirect.
    var missingNotif by rememberSaveable { mutableStateOf(false) }

    fun checkAllItems() {
        missingNotif = !ReliabilityChecks.isNotificationsGranted(context)
        val missingExact = !ReliabilityChecks.canScheduleExactAlarms(context)
        val missingBattery = !ReliabilityChecks.isIgnoringBatteryOptimizations(context)
        // Android 14+ can withhold the full-screen-intent permission, which silently
        // downgrades every alarm from "takes over the locked screen" to "heads-up
        // banner" — worth the same nudge as the other three.
        val missingFullScreen = !ReliabilityChecks.canUseFullScreenIntent(context)
        if (missingNotif || missingExact || missingBattery || missingFullScreen) showSettingsPrompt = true
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkAllItems()
    }

    LaunchedEffect(Unit) {
        if (alreadyChecked) return@LaunchedEffect
        alreadyChecked = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReliabilityChecks.isNotificationsGranted(context)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkAllItems()
        }
    }

    if (showSettingsPrompt) {
        AlertDialog(
            onDismissRequest = { showSettingsPrompt = false },
            title = { Text("הגדרות מומלצות לאמינות") },
            text = {
                Text(
                    "כדי שהשעמורים יצלצלו באמינות ברקע" +
                    (if (missingNotif) " ושתראה כשהם מצלצלים" else "") +
                    ", כדאי לאשר התראות, הרשאת שעמורים מדויקים, ולכבות אופטימיזציית סוללה לאפליקציה. אפשר לעשות זאת בהגדרות."
                )
            },
            confirmButton = { TextButton({ showSettingsPrompt = false; onOpenSettings() }) { Text("עבור להגדרות") } },
            dismissButton = { TextButton({ showSettingsPrompt = false }) { Text("אחר כך") } },
        )
    }
}

@Composable
private fun MiniBadge(text: String, color: Color) = Surface(
    shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.12f),
) {
    Text(text, Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = color, fontSize = 9.sp)
}
