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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.domain.model.Alarm
import com.smartring.app.presentation.theme.*
import com.smartring.app.presentation.intro.IntroDialog
import com.smartring.app.presentation.intro.IntroViewModel
import com.smartring.app.presentation.whatsnew.WhatsNewDialog
import com.smartring.app.presentation.whatsnew.WhatsNewViewModel
import com.smartring.app.presentation.settings.SettingsViewModel
import androidx.activity.compose.BackHandler
import com.smartring.app.util.AlarmSection
import com.smartring.app.util.QuickPreset
import com.smartring.app.util.QuickPresetKind
import com.smartring.app.util.presetLabel
import com.smartring.app.util.ForeignAlarm
import com.smartring.app.util.SEARCH_VISIBLE_FROM
import com.smartring.app.util.buildAlarmGroups
import com.smartring.app.util.filterAlarms
import com.smartring.app.util.ForeignAlarms
import com.smartring.app.util.ReliabilityChecks
import com.smartring.app.util.foreignAlarmWarning
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(onAddAlarm: ()->Unit, onEditAlarm: (Long)->Unit,
    onDuplicateAlarm: (Long)->Unit, onOpenHistory: ()->Unit, onOpenSettings: ()->Unit,
    vm: AlarmListViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val selectedIds by vm.selectedIds.collectAsStateWithLifecycle()
    val undoable by vm.undoableDelete.collectAsStateWithLifecycle()
    var showControls by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val quickPresets by vm.quickPresets.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    // "היום" is a claim about the current date, so it has to be re-evaluated when the
    // screen comes back rather than fixed at whatever moment it was first composed. A
    // phone left on this screen overnight would otherwise still be filing tomorrow's
    // alarms under "היום" — the one heading someone reads before going to sleep.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) now = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val groups = remember(state.rows, query, now) {
        buildAlarmGroups(filterAlarms(state.rows, query), now)
    }

    // Undo, rather than a confirmation dialog before every delete. A dialog interrupts the
    // hundreds of deletes that were intended and still cannot rescue the one mis-tap,
    // because by then it has been confirmed. Dismissing the snackbar (or letting it time
    // out) is what makes the delete final.
    LaunchedEffect(undoable) {
        val deleted = undoable ?: return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            message = "\"${deleted.name}\" נמחק",
            actionLabel = "בטל",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete() else vm.clearUndo()
    }

    // Only meaningful while this app actually has something armed: the warning says the
    // other alarm rings *before ours*, and with every alarm here disabled there is no
    // "ours" for it to come before.
    val foreignAlarm = rememberForeignAlarm(enabled = state.alarms.any { it.isActive })

    val whatsNewVm: WhatsNewViewModel = hiltViewModel()
    val whatsNewState by whatsNewVm.state.collectAsStateWithLifecycle()
    WhatsNewDialog(whatsNewVm)
    // Three things want the screen on launch, so they queue rather than race. What's New
    // goes first (an upgrade has something to report), the intro second (only ever on a
    // fresh install, where What's New is silent by design), and the reliability prompt
    // last — a system permission dialog on top of a Compose AlertDialog is jarring and one
    // can eat the other's input. Each waits for the one before it to resolve to "nothing
    // to show" (`checked`, not just a default-empty initial state).
    val introVm: IntroViewModel = hiltViewModel()
    val introState by introVm.state.collectAsStateWithLifecycle()
    val whatsNewSettled = whatsNewState.checked && whatsNewState.entriesToShow.isEmpty()
    if (whatsNewSettled && introState.show) {
        IntroDialog(onDismiss = introVm::dismiss)
    }
    if (whatsNewSettled && introState.checked && !introState.show) {
        ReliabilityGate(onOpenSettings)
    }

    val inSelection = selectedIds.isNotEmpty()
    var confirmBulkDelete by remember { mutableStateOf(false) }
    // Leaving selection mode with the system back gesture, which is what anyone will try
    // first — otherwise the only way out is deselecting every row one at a time.
    BackHandler(enabled = inSelection) { vm.clearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            if (inSelection) {
                // A separate bar rather than extra icons on the normal one: selection is a
                // mode, and a bar that changes what its buttons mean without looking
                // different is how people delete the wrong thing.
                TopAppBar(
                    navigationIcon = {
                        IconButton({ vm.clearSelection() }) { Icon(Icons.Rounded.Close, "בטל בחירה") }
                    },
                    title = { Text("${selectedIds.size} נבחרו", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    actions = {
                        IconButton({ vm.setSelectedEnabled(true) }) {
                            Icon(Icons.Rounded.AlarmOn, "הפעל את הנבחרים")
                        }
                        IconButton({ vm.setSelectedEnabled(false) }) {
                            Icon(Icons.Rounded.AlarmOff, "כבה את הנבחרים")
                        }
                        IconButton({ confirmBulkDelete = true }) {
                            Icon(Icons.Rounded.DeleteOutline, "מחק את הנבחרים",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            } else {
                TopAppBar(title={Text("SmartRing",fontWeight=FontWeight.Black,fontSize=24.sp)},
                    colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background),
                    actions={
                        IconButton({showControls=true}){Icon(Icons.Rounded.Tune,"שליטה כללית")}
                        IconButton(onOpenHistory){Icon(Icons.Rounded.History,"היסטוריה")}
                        IconButton(onOpenSettings){Icon(Icons.Rounded.Settings,"הגדרות")}
                    })
            }
        },
        floatingActionButton={
            // onPrimary, not a hard-coded White: primary is a light blue in the dark
            // scheme, where white on it is 3.19:1.
            FloatingActionButton(onAddAlarm,shape=CircleShape,containerColor=MaterialTheme.colorScheme.primary){
                Icon(Icons.Rounded.Add,"הוסף שעמור",tint=MaterialTheme.colorScheme.onPrimary)
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
                // Above the alarms, because it is about to ring before all of them.
                // Added conditionally rather than letting the banner render nothing: an
                // empty item still takes a slot, and this list is spacedBy(10.dp), so an
                // invisible banner would leave a visible gap at the top.
                if (foreignAlarm != null) item { ForeignAlarmBanner(foreignAlarm) }
                // Hidden during selection: a shortcut that creates a new alarm while the
                // user is picking existing ones to delete is noise at best.
                if (!inSelection && quickPresets.isNotEmpty()) {
                    item { QuickCreateRow(quickPresets, vm::createFromPreset, onOpenSettings) }
                }
                // Only past the point where scrolling costs more than typing. Below it a
                // field that costs a tap and a keyboard to filter rows already visible on
                // one screen is a control that makes the screen worse.
                //
                // Kept during selection, unlike the quick-create row above it: narrowing
                // to "all the gym ones" is exactly how somebody picks a set to switch off,
                // and hiding the field while its filter stayed applied would leave the
                // list mysteriously short with nothing on screen explaining why.
                if (state.alarms.size >= SEARCH_VISIBLE_FROM) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            placeholder = { Text("חפש לפי שם") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = if (query.isNotEmpty()) {
                                { IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "נקה חיפוש") } }
                            } else null,
                        )
                    }
                }
                // Only reachable through search — with no query every alarm lands in some
                // group — but gated on the query anyway rather than on that reasoning
                // holding forever, since the alternative renders `אין שעמור בשם ""`.
                if (groups.isEmpty() && query.isNotBlank()) {
                    item {
                        Text("אין שעמור בשם \"${query.trim()}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                groups.forEach { group ->
                    // Headings only earn their space once there is more than one group:
                    // a lone "היום" above the whole list labels nothing the list does not
                    // already say.
                    if (groups.size > 1) {
                        item(key = "header-${group.section}") {
                            Text(group.title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (group.section == AlarmSection.OFF)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary)
                        }
                    }
                    items(group.rows, key = { it.alarm.id }) { row ->
                        val alarm = row.alarm
                        AlarmCardItem(
                            alarm = alarm,
                            onToggle = {vm.toggle(alarm,it)},
                            onEdit = {
                                // In selection mode a tap selects instead of navigating —
                                // otherwise selecting a third alarm means opening the second.
                                if (inSelection) vm.toggleSelection(alarm.id) else onEditAlarm(alarm.id)
                            },
                            onDuplicate = {onDuplicateAlarm(alarm.id)},
                            onScheduleNextDay = {vm.scheduleForNextDay(alarm)},
                            onDelete = {vm.delete(alarm)},
                            onLongPress = { vm.toggleSelection(alarm.id) },
                            selected = alarm.id in selectedIds,
                            inSelectionMode = inSelection,
                        )
                    }
                }
            }
        }
    }
    if (confirmBulkDelete) {
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("למחוק ${selectedIds.size} שעמורים?") },
            // Asked, unlike a single delete. Undo holds one alarm because one alarm fits
            // in a held value and in a sentence; promising to restore an arbitrary set and
            // getting it half-right would be worse than asking.
            text = { Text("הפעולה הזו אינה ניתנת לביטול.") },
            confirmButton = {
                TextButton({ vm.deleteSelected(); confirmBulkDelete = false }) {
                    Text("מחק", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ confirmBulkDelete = false }) { Text("ביטול") } },
        )
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
private fun AlarmCardItem(
    alarm: Alarm,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onScheduleNextDay: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit,
    selected: Boolean,
    inSelectionMode: Boolean,
) {
    // No confirm-before-delete state any more: deleting is immediate and undoable from
    // the snackbar. Keeping both would be the worst of each — an interruption that still
    // cannot rescue a mis-tap, followed by an undo offer for something already confirmed.
    // Theme color roles rather than the raw Blue/Green constants: those are tuned for
    // the dark scheme's near-black card, and Green (a pale mint) as a 8dp dot and a
    // card border on Light mode's white surface is all but invisible.
    val dotColor = when {
        alarm.isFrozen -> MaterialTheme.colorScheme.primary
        alarm.isActive -> MaterialTheme.colorScheme.tertiary
        else           -> MaterialTheme.colorScheme.outline
    }

    // Swipe (either direction) deletes, with the snackbar's undo as the safety net rather
    // than a dialog. confirmValueChange still returns false so the card snaps back rather
    // than animating away: the row disappears when the list flow drops it, which is the
    // one moment the delete is actually real, and animating out first would show the card
    // gone for an instant even if the write failed.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onDelete()
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
    // Long-press enters selection instead of deleting. The old gesture deleted, which is
    // an odd thing for a long-press to do and left no room for multi-select — and delete
    // now has its own button on every card, so nothing is lost.
    Surface(Modifier.fillMaxWidth().combinedClickable(onClick=onEdit,onLongClick=onLongPress),
        RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else dotColor.copy(.3f),
        )) {
        Row(Modifier.padding(16.dp,14.dp),verticalAlignment=Alignment.CenterVertically){
            if (inSelectionMode) {
                Icon(
                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    if (selected) "נבחר" else "לא נבחר",
                    Modifier.size(20.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)){
                // Struck through once the alarm has rung and retired. A finished one-time
                // alarm used to look exactly like a paused repeating one, so the list gave
                // no way to tell "this already happened" from "this is switched off" —
                // and the answer changes what the user should do about it.
                val finishedDecoration = if (alarm.hasFinished) TextDecoration.LineThrough else null
                Text(alarm.timeFormatted,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold,
                    textDecoration = finishedDecoration,
                    color=if(alarm.isActive)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(alarm.name,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = finishedDecoration, maxLines=1)
                // Which days this actually rings on. Without it two alarms at the same
                // time — one every weekday, one a single next-Tuesday reminder — looked
                // completely identical in the list.
                Text(alarm.scheduleSummary(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                alarm.reminderText?.takeIf{it.isNotBlank()}?.let{
                    Spacer(Modifier.height(2.dp))
                    Text("📝 $it",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary,maxLines=1)
                }
                // Labelled buttons, on the card, for the two things a finished alarm is
                // actually for. Both already existed — re-enable via the switch, copy via
                // the duplicate icon — but neither said what it was *for* on an alarm that
                // has run its course, which is when the user needs them.
                if (alarm.hasFinished) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (alarm.isOneOffDated) {
                            FilledTonalButton(
                                onClick = onScheduleNextDay,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Rounded.Event, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("תזמן ליום הבא", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        TextButton(
                            onClick = onDuplicate,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("צור חדש מזה", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (alarm.hasFinished || alarm.isFrozen || alarm.isShabbatMode || !alarm.snoozeEnabled) {
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Says what to do about it, not just what happened: the fix is one
                        // toggle away and nothing previously pointed at it.
                        if (alarm.hasFinished)
                            MiniBadge("✔ צלצל והסתיים", MaterialTheme.colorScheme.secondary)
                        if (alarm.isOneOffDated && !alarm.hasFinished)
                            MiniBadge("מזדמן — פעם אחת", MaterialTheme.colorScheme.secondary)
                        // A frozen alarm keeps isEnabled = true, so its switch stays
                        // visibly ON while the alarm will not ring at all — the only
                        // hint was the color of an 8dp dot. Say it in words.
                        if (alarm.isFrozen) MiniBadge("❄️ מוקפא — לא יצלצל", MaterialTheme.colorScheme.primary)
                        if (alarm.isShabbatMode) MiniBadge("🕯 שבת", MaterialTheme.colorScheme.tertiary)
                        if (!alarm.snoozeEnabled) MiniBadge("ללא נודניק", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // Per-row controls stand down during selection: a switch and a delete button
            // inside a row whose tap now means "select" is three different meanings
            // competing in one place.
            if (!inSelectionMode) Column(horizontalAlignment=Alignment.End){
                Switch(alarm.isEnabled,onToggle,
                    // "מוקפא" is called out separately from the on/off state: a frozen
                    // alarm reads as "פעיל" to the switch but never rings, and a screen
                    // reader user has no dot color or badge to fall back on.
                    modifier=Modifier.semantics{contentDescription=
                        "שעמור ${alarm.name.ifBlank{alarm.timeFormatted}} בשעה ${alarm.timeFormatted}, " +
                        when { alarm.isFrozen -> "מוקפא"; alarm.isEnabled -> "פעיל"; else -> "כבוי" }})
                // Explicit buttons rather than only the swipe and long-press gestures.
                // Those still work, but a gesture nobody discovers is not an affordance,
                // and duplicating had no gesture at all.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onDuplicate, Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.ContentCopy, "שכפל את ${alarm.name}",
                            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onDelete, Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, "מחק את ${alarm.name}",
                            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    }

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

/**
 * Re-reads the device's next foreign alarm on every resume, or null when there is none.
 *
 * Opt-in (Settings → "שעמורים מאפליקציות אחרות", off by default) because it is a niche
 * need — the Shabbat and holiday case, where someone's weekday alarm from the stock Clock
 * would go off regardless of what is set here — and an app that comments on other apps'
 * alarms uninvited is being nosy.
 *
 * ON_RESUME rather than once: the entire user journey is a trip to the other app and
 * back, and a banner still naming an alarm the user has just cancelled would be worse
 * than no banner at all.
 */

/**
 * One-tap ad-hoc alarms for the two cases that need no thought.
 *
 * The shortest route to an alarm was: tap +, land on a form of fifteen controls, set a
 * time, name it, save. For "wake me in eight hours" that is a great deal of screen for a
 * decision already made. Everything except the time comes from the user's configured
 * defaults, so a quick alarm rings the way their alarms ring.
 *
 * Both create an *ad-hoc* alarm — one that rings once and then offers "schedule for the
 * next day" on its card — rather than something that quietly repeats tomorrow.
 */
@Composable
private fun QuickCreateRow(
    presets: List<QuickPreset>,
    onCreate: (QuickPreset) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("יצירה מהירה", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            // The way to the editor for these chips, from the one place someone is
            // looking at them and thinking "not that one". Buried in Settings it would be
            // a feature nobody finds.
            TextButton(onOpenSettings, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("ערוך", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(2.dp))
        // Scrollable rather than wrapped: the chips are user-configured now, so the row's
        // width is not something this layout can predict at all, and a chip clipped off
        // the edge is a control nobody can reach.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset ->
                AssistChip(
                    onClick = { onCreate(preset) },
                    label = { Text(presetLabel(preset)) },
                    leadingIcon = {
                        Icon(
                            if (preset.kind == QuickPresetKind.RELATIVE) Icons.Rounded.Timer
                            else Icons.Rounded.Event,
                            null, Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberForeignAlarm(
    enabled: Boolean,
    settingsVm: SettingsViewModel = hiltViewModel(),
): ForeignAlarm? {
    val settings by settingsVm.state.collectAsStateWithLifecycle()
    val on = enabled && settings.warnForeignAlarms

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var found by remember { mutableStateOf<ForeignAlarm?>(null) }

    DisposableEffect(lifecycleOwner, on) {
        if (!on) {
            // Clear rather than leave the last reading behind: switching the setting off
            // has to remove the banner immediately, not at the next resume.
            found = null
            onDispose { }
        } else {
            found = ForeignAlarms.next(context)
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) found = ForeignAlarms.next(context)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    // The alarm is filtered through the same pure check that produces the text, so a
    // stale registration (one already in the past) never reaches the banner.
    return found?.takeIf { foreignAlarmWarning(it) != null }
}

/**
 * The banner itself.
 *
 * The wording never promises to switch the other alarm off, because this app cannot: an
 * alarm belongs to the UID that created it and Android has no API for reaching across.
 * See [ForeignAlarms]. The one action offered is to open the other app, where the user
 * can turn it off themselves.
 */
@Composable
private fun ForeignAlarmBanner(alarm: ForeignAlarm) {
    val context = LocalContext.current
    val text = foreignAlarmWarning(alarm) ?: return

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("שעמור מאפליקציה אחרת",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
            val launch = remember(alarm.packageName) {
                // Null for an app with no launcher activity — nothing to open, so the
                // button is not offered rather than offered and doing nothing.
                context.packageManager.getLaunchIntentForPackage(alarm.packageName)
            }
            if (launch != null) {
                Spacer(Modifier.height(4.dp))
                TextButton({ runCatching { context.startActivity(launch) } }) {
                    Text("פתח את ${alarm.appLabel}")
                }
            }
        }
    }
}

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
    // Same reason as the delete hint above: labelSmall, not labelSmall shrunk to 9sp.
    // These badges carry "מוקפא" and "נודניק" — state the user needs to be able to read
    // at a glance, on the screen they spend the most time on.
    Text(text, Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = color)
}
