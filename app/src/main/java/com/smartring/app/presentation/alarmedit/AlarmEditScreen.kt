package com.smartring.app.presentation.alarmedit

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.domain.model.*
import com.smartring.app.presentation.theme.*
import com.smartring.app.util.formatDurationSeconds
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarmId: Long    = 0L,
    prefillName: String? = null,
    prefillHour: Int? = null,
    prefillMinute: Int? = null,
    onBack: () -> Unit,
    vm: AlarmEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(alarmId, prefillName, prefillHour, prefillMinute) {
        if (alarmId > 0L) vm.loadAlarm(alarmId)
        else if (prefillName != null || prefillHour != null) vm.prefill(prefillName, prefillHour, prefillMinute)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.isSaved) { if (s.isSaved) onBack() }

    // Dirty-state check: confirm before discarding unsaved changes. Shared by the
    // system back gesture/button (BackHandler) and the toolbar's back arrow below —
    // the latter used to call onBack() directly, bypassing this check entirely.
    var showDiscardDialog by remember { mutableStateOf(false) }
    val onBackPressed = { if (vm.isDirty) showDiscardDialog = true else onBack() }
    BackHandler(enabled = vm.isDirty) { showDiscardDialog = true }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title            = { Text("לבטל שינויים?") },
            text             = { Text("יש שינויים שלא נשמרו. לצאת בלי לשמור?") },
            confirmButton    = { TextButton(onClick = { showDiscardDialog = false; onBack() }) {
                Text("צא בלי לשמור", color = MaterialTheme.colorScheme.error) } },
            dismissButton    = { TextButton({ showDiscardDialog = false }) { Text("המשך עריכה") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBackPressed) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "חזור") } },
                title          = { Text(if (alarmId > 0) "עריכת שעמור" else "שעמור חדש", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    TextButton(onClick = vm::save, enabled = !s.isSaving) {
                        if (s.isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("שמור", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val listState = rememberLazyListState()
        // The name field is item index 0; without this, tapping Save while scrolled
        // past it just silently fails from the user's point of view — the red error
        // text appears off-screen at the top with nothing visible to explain why
        // saving didn't work. A one-shot event (not s.nameError itself) so a second
        // Save tap while still blank scrolls again too, not just the first failure.
        LaunchedEffect(Unit) {
            vm.scrollToNameRequests.collect { listState.animateScrollToItem(0) }
        }
        LazyColumn(
            state                 = listState,
            contentPadding        = PaddingValues(start=16.dp, end=16.dp, top=pad.calculateTopPadding()+8.dp, bottom=80.dp),
            verticalArrangement   = Arrangement.spacedBy(12.dp),
        ) {
            // ── Name ──────────────────────────────────────────────
            item {
                OutlinedTextField(
                    value         = s.name,
                    onValueChange = vm::setName,
                    modifier      = Modifier.fillMaxWidth(),
                    label         = { Text("שם השעמור") },
                    placeholder   = { Text("למשל: קום לעבודה") },
                    leadingIcon   = { Icon(Icons.Rounded.Label, null) },
                    isError       = s.nameError,
                    supportingText = if (s.nameError) {{ Text("נדרש שם") }} else null,
                    singleLine    = true,
                    shape         = RoundedCornerShape(14.dp),
                )
            }

            // ── Time picker ───────────────────────────────────────
            item {
                var showPicker by remember { mutableStateOf(false) }
                EditCard {
                    Box(Modifier.fillMaxWidth(), Alignment.Center) {
                        Text("%02d:%02d".format(s.hour, s.minute),
                            style    = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.clickable { showPicker = true })
                    }
                    Text("לחץ לשינוי שעה", Modifier.align(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Next fire hint — or, when there is no next fire at all, an
                    // explicit warning in its place. The hint simply disappearing (all
                    // this used to do) gave no clue that the alarm as configured will
                    // never ring: a date/time already in the past, or every specific
                    // date passed, saved and sat in the list looking completely normal.
                    if (s.neverFires) {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("לא נקבע מועד צלצול עתידי — השעמור לא יצלצל",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    s.nextFireHint?.let { hint ->
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Schedule, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(hint, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (showPicker) {
                    val tState = rememberTimePickerState(s.hour, s.minute, true)
                    AlertDialog(
                        onDismissRequest = { showPicker = false },
                        title            = { Text("בחר שעה") },
                        text             = { TimePicker(tState) },
                        confirmButton    = {
                            TextButton({ vm.setTime(tState.hour, tState.minute); showPicker = false }) { Text("אישור") }
                        },
                        dismissButton    = { TextButton({ showPicker = false }) { Text("ביטול") } },
                    )
                }
            }

            // ── Specific DateTime ─────────────────────────────────
            item { SectionLabel("סוג שעמור") }
            item {
                EditCard {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column {
                            FieldLabel("תאריך ושעה ספציפיים",
                                info = "השעמור יצלצל פעם אחת בלבד, בתאריך ובשעה שתבחר, במקום לפי ימים קבועים.")
                            Text("הצלצול יהיה פעם אחת בלבד בתאריך שתבחר",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(s.specificDateTime != null, onCheckedChange = { enabled ->
                            if (enabled) {
                                // Default: tomorrow at alarm time
                                val cal = Calendar.getInstance().apply {
                                    add(Calendar.DAY_OF_YEAR, 1)
                                    set(Calendar.HOUR_OF_DAY, s.hour)
                                    set(Calendar.MINUTE, s.minute)
                                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                                }
                                vm.setSpecificDateTime(cal.timeInMillis)
                            } else {
                                vm.setSpecificDateTime(null)
                            }
                        })
                    }
                    if (s.specificDateTime != null) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))
                        DateTimePickerInline(
                            epochMillis = s.specificDateTime!!,
                            onChanged   = vm::setSpecificDateTime,
                        )
                    }
                }
            }

            // ── Schedule (days + recurrence) ──────────────────────
            if (s.specificDateTime == null) {
                item { SectionLabel("ימי חזרה") }
                item {
                    EditCard {
                        val days = listOf("א","ב","ג","ד","ה","ו","ש")
                        // Each of the 7 circles gets an equal share of the available width
                        // (via weight(1f), not a fixed 48.dp) so the row always fits exactly —
                        // 7 fixed 48.dp circles (336.dp) overflow the card's content width on
                        // most phone screens, clipping the last one ("ש", Saturday) off-screen
                        // in this RTL layout.
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            days.forEachIndexed { i, d ->
                                val sel = (s.repeatDaysBitmask shr i) and 1 == 1
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clickable { vm.toggleDay(i) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier.fillMaxSize(0.85f)
                                            .background(
                                                if (sel) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(d, fontWeight = FontWeight.Bold,
                                            color = if (sel) White else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                // Frequency
                item {
                    EditCard {
                        FieldLabel("תדירות",
                            info = "כל כמה זמן השעמור חוזר: כל שבוע, כל שבועיים, פעם בחודש, או ללא חזרה כלל.")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                RepeatFrequency.WEEKLY    to "שבועי",
                                RepeatFrequency.BIWEEKLY  to "דו-שבועי",
                                RepeatFrequency.MONTHLY   to "חודשי",
                                RepeatFrequency.NONE      to "ללא",
                            ).forEach { (f, l) ->
                                // No chip shows selected while no weekday is picked yet
                                // (repeatDaysBitmask == 0) — the stored repeatFrequency
                                // default is WEEKLY, but showing it pre-selected here
                                // made a brand-new, still one-time alarm look like a
                                // recurring "weekly" pattern had already been chosen.
                                val selected = s.repeatDaysBitmask != 0 && s.repeatFrequency == f
                                FilterChip(selected, { vm.setRepeatFrequency(f) }, { Text(l, fontSize = 11.sp) })
                            }
                        }
                    }
                }

                // Recurrence End
                if (s.repeatDaysBitmask != 0 && s.repeatFrequency != RepeatFrequency.NONE) {
                    item { SectionLabel("סיום חזרתיות") }
                    item { RecurrenceEndSection(s, vm) }
                }

                // Specific extra dates
                item { SectionLabel("תאריכים ספציפיים נוספים") }
                item { SpecificDatesSection(s.specificDates, vm::addDate, vm::removeDate) }
            }

            // ── Reminder ──────────────────────────────────────────
            item { SectionLabel("תזכורת") }
            item {
                OutlinedTextField(
                    value         = s.reminderText,
                    onValueChange = vm::setReminderText,
                    modifier      = Modifier.fillMaxWidth(),
                    label         = { Text("טקסט תזכורת (אופציונלי)") },
                    leadingIcon   = { Icon(Icons.Rounded.StickyNote2, null) },
                    maxLines      = 2,
                    shape         = RoundedCornerShape(14.dp),
                )
            }

            // ── Ring ──────────────────────────────────────────────
            item { SectionLabel("צלצול") }
            item {
                EditCard {
                    LabeledSlider("משך צלצול", s.ringDurationSeconds, "שנ׳", 5f, 600f, 118, MaterialTheme.colorScheme.primary,
                        info = "כמה זמן השעמור ימשיך לצלצול לפני שהוא נעצר אוטומטית, אם לא תעצור אותו ידנית.",
                        formatter = ::formatDurationSeconds, onChange = vm::setRingDuration)
                }
            }

            // ── Ring rounds (up to 10, each with its own sound/volume/duration) ────
            item { SectionLabel("סבבי צלצול") }
            item { RingsSection(s.rings, vm::updateRing, vm::addRing, vm::removeRing) }

            // ── Vibration ─────────────────────────────────────────
            item { SectionLabel("רטט וצלצול") }
            item {
                EditCard {
                    FieldLabel("סוג צלצול",
                        info = "בחר אם השעמור יצלצל בקול, ירטוט, שניהם יחד, או ירטוט לפני שיתחיל לצלצול.")
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
                        listOf(
                            VibrationMode.SOUND_ONLY          to "צלצול",
                            VibrationMode.VIBRATION_ONLY      to "רטט",
                            VibrationMode.SOUND_AND_VIBRATION to "שניהם",
                            VibrationMode.VIBRATION_THEN_SOUND to "רטט→צלצול",
                        ).forEach { (m, l) ->
                            FilterChip(s.vibrationMode == m, { vm.setVibrationMode(m) },
                                { Text(l, fontSize = 10.sp) }, Modifier.weight(1f))
                        }
                    }
                    if (s.vibrationMode == VibrationMode.VIBRATION_THEN_SOUND) {
                        Spacer(Modifier.height(8.dp))
                        LabeledSlider("רטט לפני צלצול", s.vibrationOnlySeconds, "שנ׳", 3f, 120f, 38, MaterialTheme.colorScheme.error,
                            info = "כמה זמן לרטוט לפני שהצליל מתחיל להתנגן.",
                            formatter = ::formatDurationSeconds, onChange = vm::setVibrationOnlySeconds)
                    }
                }
            }

            // ── Crescendo ─────────────────────────────────────────
            item { SectionLabel("צלצול מתחזק") }
            item {
                EditCard {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.TrendingUp, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            FieldLabel("צלצול מתחזק",
                                info = "העוצמה תעלה בהדרגה מנמוכה לגבוהה, במקום לצלצל בעוצמה מלאה מיד.")
                        }
                        Switch(s.crescendoEnabled, vm::setCrescendoEnabled)
                    }
                    if (s.crescendoEnabled) {
                        Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                        LabeledSlider("עוצמה התחלתית", s.crescendoStartVolume, "%", 5f, 80f, 14, MaterialTheme.colorScheme.tertiary,
                            info = "עוצמת הקול בתחילת הצלצול, לפני שהיא מתחילה לעלות.",
                            onChange = vm::setCrescendoStartVolume)
                        Spacer(Modifier.height(6.dp))
                        LabeledSlider("כל כמה שניות עולה", s.crescendoStepSeconds, "שנ׳", 5f, 60f, 10, MaterialTheme.colorScheme.primary,
                            info = "כל כמה שניות עוצמת הקול תעלה לשלב הבא.",
                            formatter = ::formatDurationSeconds, onChange = vm::setCrescendoStepSeconds)
                        Spacer(Modifier.height(6.dp))
                        LabeledSlider("עלייה בכל צעד", s.crescendoStepPercent, "%", 5f, 30f, 4, MaterialTheme.colorScheme.secondary,
                            info = "כמה אחוזים עוצמת הקול עולה בכל שלב.",
                            onChange = vm::setCrescendoStepPercent)
                    }
                }
            }

            // ── Snooze ────────────────────────────────────────────
            item { SectionLabel("נודניק") }
            item {
                EditCard {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        FieldLabel("אפשר נודניק לשעמור זה",
                            info = "כאשר כבוי, לא תוצג אפשרות נודניק כלל עבור השעמור הזה — לא במסך הצלצול ולא בהתראה.")
                        Switch(s.snoozeEnabled, vm::setSnoozeEnabled)
                    }
                    if (s.snoozeEnabled) {
                        Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                        LabeledSlider("משך נודניק", s.snoozeMinutes, "דק׳", 1f, 60f, 58, MaterialTheme.colorScheme.secondary,
                            info = "כמה זמן השעמור יידחה כאשר לוחצים על נודניק.",
                            onChange = vm::setSnoozeMinutes)
                        Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            FieldLabel("מקסימום נודניקים",
                                info = "כמה פעמים ניתן ללחוץ על נודניק לפני שהאפשרות נעלמת.")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ if (s.snoozeMaxCount > 1) vm.setSnoozeMaxCount(s.snoozeMaxCount - 1) }, Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Remove, null)
                                }
                                Text("${s.snoozeMaxCount}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                IconButton({ if (s.snoozeMaxCount < 10) vm.setSnoozeMaxCount(s.snoozeMaxCount + 1) }, Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Add, null)
                                }
                            }
                        }
                    }
                }
            }

            // ── Shabbat mode ────────────────────────────────────────
            item { SectionLabel("מצב שבת") }
            item {
                EditCard {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            FieldLabel("שעמור שבת",
                                info = "כאשר מופעל, כפתורי העצירה והנודניק יהיו מושבתים לגמרי בזמן שהשעמור מצלצל — " +
                                    "לא ניתן יהיה ללחוץ עליהם, גם לא מההתראה. השעמור עדיין ייפסק אוטומטית לפי \"משך צלצול\" שהגדרת.")
                            Text("כפתורי עצירה/נודניק יהיו מושבתים בזמן הצפצוף",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(s.isShabbatMode, vm::setShabbatMode)
                    }
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerInline(epochMillis: Long, onChanged: (Long) -> Unit) {
    val cal  = remember(epochMillis) { Calendar.getInstance().apply { timeInMillis = epochMillis } }
    val fmt  = remember { SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.getDefault()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(fmt.format(cal.time), fontSize = 11.sp, maxLines = 1)
        }
        OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(.6f),
            shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)))
        }
    }

    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = epochMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton({
                    dpState.selectedDateMillis?.let { date ->
                        // DatePicker reports the picked day as UTC midnight; read the
                        // year/month/day from a UTC calendar, then apply them (plus the
                        // chosen hour/minute) on a device-local calendar. Building the
                        // local calendar straight from the UTC millis would shift the
                        // date by a day for negative-UTC-offset timezones.
                        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = date
                        }
                        val newCal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                            set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                            set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY))
                            set(Calendar.MINUTE, cal.get(Calendar.MINUTE))
                            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }
                        onChanged(newCal.timeInMillis)
                    }
                    showDatePicker = false
                }) { Text("אישור") }
            },
            dismissButton = { TextButton({ showDatePicker = false }) { Text("ביטול") } },
        ) { DatePicker(dpState) }
    }

    if (showTimePicker) {
        val tState = rememberTimePickerState(
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("בחר שעה") },
            text  = { TimePicker(tState) },
            confirmButton = {
                TextButton({
                    val newCal = Calendar.getInstance().apply {
                        timeInMillis = epochMillis
                        set(Calendar.HOUR_OF_DAY, tState.hour)
                        set(Calendar.MINUTE, tState.minute)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    onChanged(newCal.timeInMillis)
                    showTimePicker = false
                }) { Text("אישור") }
            },
            dismissButton = { TextButton({ showTimePicker = false }) { Text("ביטול") } },
        )
    }
}

/**
 * Editor for the up-to-10 ring "rounds" (AlarmRing), each with its own sound, volume,
 * duration and post-round delay — AlarmScheduler/AlarmFiringService already support
 * sequencing through this list, but until now there was no UI to configure more than
 * the single default round.
 */
@Composable
private fun RingsSection(
    rings: List<AlarmRing>,
    onUpdate: (Int, AlarmRing) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    val context = LocalContext.current
    var pickingIndex by remember { mutableStateOf(-1) }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (pickingIndex in rings.indices) {
            onUpdate(pickingIndex, rings[pickingIndex].copy(ringtoneUri = uri?.toString() ?: "default"))
        }
        pickingIndex = -1
    }

    EditCard {
        rings.forEachIndexed { i, ring ->
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("סבב ${i + 1}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                if (rings.size > 1) {
                    IconButton({ onRemove(i) }, Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, "הסר סבב ${i + 1}", Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    pickingIndex = i
                    ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                        if (ring.ringtoneUri != "default")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(ring.ringtoneUri))
                    })
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Rounded.MusicNote, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(ringtoneDisplayName(context, ring.ringtoneUri), maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            LabeledSlider("משך", ring.durationSeconds, "שנ׳", 5f, 300f, 58, MaterialTheme.colorScheme.primary,
                info = "כמה זמן הסבב הזה מנגן לפני שעובר לסבב הבא.",
                formatter = ::formatDurationSeconds) {
                onUpdate(i, ring.copy(durationSeconds = it))
            }
            Spacer(Modifier.height(6.dp))
            LabeledSlider("עוצמה", ring.volumePercent, "%", 10f, 100f, 17, MaterialTheme.colorScheme.tertiary,
                info = "עוצמת הקול של הסבב הזה, כאחוז מהעוצמה המקסימלית.") {
                onUpdate(i, ring.copy(volumePercent = it))
            }
            Spacer(Modifier.height(6.dp))
            LabeledSlider("השהיה אחרי סבב זה", ring.delayAfterSeconds, "שנ׳", 0f, 600f, 59, MaterialTheme.colorScheme.secondary,
                info = "כמה זמן להמתין בשקט אחרי שהסבב הזה מסתיים, לפני שהסבב הבא (או החזרה לסבב הראשון) מתחיל.",
                formatter = ::formatDurationSeconds) {
                onUpdate(i, ring.copy(delayAfterSeconds = it))
            }
            if (i < rings.size - 1) HorizontalDivider(Modifier.padding(vertical = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onAdd, enabled = rings.size < 10,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (rings.size < 10) "הוסף סבב צלצול (${rings.size}/10)" else "הגעת למקסימום סבבים (10)")
        }
    }
}

private fun ringtoneDisplayName(context: android.content.Context, uriString: String): String {
    if (uriString == "default") return "צלצול ברירת מחדל"
    return runCatching {
        RingtoneManager.getRingtone(context, Uri.parse(uriString))?.getTitle(context)
    }.getOrNull() ?: "צלצול נבחר"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurrenceEndSection(s: AlarmEditUiState, vm: AlarmEditViewModel) {
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    EditCard {
        FieldLabel("מתי מסתיימת החזרתיות?",
            info = "בחר מתי השעמור מפסיק לחזור: לעולם לא, אחרי מספר פעמים מסוים, או עד תאריך מסוים.")
        Spacer(Modifier.height(10.dp))

        // End type selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.recurrenceEndType == RecurrenceEndType.FOREVER,
                { vm.setRecurrenceEndType(RecurrenceEndType.FOREVER) }, { Text("תמיד") })
            FilterChip(s.recurrenceEndType == RecurrenceEndType.COUNT,
                { vm.setRecurrenceEndType(RecurrenceEndType.COUNT) }, { Text("מספר פעמים") })
            FilterChip(s.recurrenceEndType == RecurrenceEndType.UNTIL,
                { vm.setRecurrenceEndType(RecurrenceEndType.UNTIL) }, { Text("עד תאריך") })
        }

        // COUNT input
        if (s.recurrenceEndType == RecurrenceEndType.COUNT) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("מספר חזרות", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (s.recurrenceCount > 1) vm.setRecurrenceCount(s.recurrenceCount - 1) },
                        Modifier.size(36.dp)) { Icon(Icons.Rounded.Remove, null) }
                    Text("${s.recurrenceCount}",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    IconButton({ if (s.recurrenceCount < 100) vm.setRecurrenceCount(s.recurrenceCount + 1) },
                        Modifier.size(36.dp)) { Icon(Icons.Rounded.Add, null) }
                }
            }
        }

        // UNTIL date picker
        if (s.recurrenceEndType == RecurrenceEndType.UNTIL) {
            Spacer(Modifier.height(10.dp))
            var showDp by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { showDp = true }, Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Rounded.CalendarToday, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.recurrenceUntilDate?.let { fmt.format(Date(it)) } ?: "בחר תאריך סיום")
            }
            if (showDp) {
                val dpState = rememberDatePickerState(
                    initialSelectedDateMillis = s.recurrenceUntilDate ?: System.currentTimeMillis())
                DatePickerDialog(
                    onDismissRequest = { showDp = false },
                    confirmButton = {
                        TextButton({
                            vm.setRecurrenceUntilDate(dpState.selectedDateMillis)
                            showDp = false
                        }) { Text("אישור") }
                    },
                    dismissButton = { TextButton({ showDp = false }) { Text("ביטול") } },
                ) { DatePicker(dpState) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpecificDatesSection(
    dates: List<AlarmDate>,
    onAdd: (Long, String?) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    var showDp by remember { mutableStateOf(false) }
    var labelInput by remember { mutableStateOf("") }

    EditCard {
        if (dates.isEmpty()) {
            Text("אין תאריכים ספציפיים", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            dates.forEachIndexed { i, d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CalendarToday, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(fmt.format(Date(d.date)), fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                        d.label?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton({ onRemove(i) }, Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
                if (i < dates.size - 1) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = { showDp = true }, Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("הוסף תאריך")
        }
    }

    if (showDp) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDp = false; labelInput = "" },
            confirmButton = {
                TextButton({
                    dpState.selectedDateMillis?.let { onAdd(it, labelInput.takeIf { l -> l.isNotBlank() }) }
                    showDp = false; labelInput = ""
                }) { Text("הוסף") }
            },
            dismissButton = { TextButton({ showDp = false; labelInput = "" }) { Text("ביטול") } },
        ) {
            Column {
                DatePicker(dpState)
                OutlinedTextField(
                    value         = labelInput,
                    onValueChange = { labelInput = it },
                    label         = { Text("תיאור (אופציונלי)") },
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    singleLine    = true,
                )
            }
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────

@Composable
fun SectionLabel(text: String) = Text(text.uppercase(),
    style      = MaterialTheme.typography.labelSmall,
    color      = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = 1.5.sp,
    modifier   = Modifier.padding(top = 4.dp))

@Composable
fun EditCard(content: @Composable ColumnScope.() -> Unit) = Surface(
    shape    = RoundedCornerShape(16.dp),
    color    = MaterialTheme.colorScheme.surface,
    border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(.2f)),
    modifier = Modifier.fillMaxWidth(),
) { Column(Modifier.padding(16.dp), content = content) }

/**
 * A value pill that opens a small numeric-entry dialog when tapped — the exact-value
 * counterpart to dragging a slider. Shared by every duration/percent/count field so
 * typing "90" is always available alongside drag-to-adjust.
 */
@Composable
fun EditableValueBadge(
    value: Int, unit: String, color: androidx.compose.ui.graphics.Color,
    min: Int, max: Int, onChange: (Int) -> Unit,
    displayText: String = "$value $unit",
) {
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        onClick = { showDialog = true },
        shape = RoundedCornerShape(999.dp), color = color.copy(.12f),
        border = BorderStroke(1.dp, color.copy(.3f)),
    ) {
        Text(displayText, Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.ExtraBold)
    }
    if (showDialog) {
        var text by remember { mutableStateOf(value.toString()) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("הזן ערך") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    suffix = { Text(unit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("טווח: $min–$max") },
                )
            },
            confirmButton = {
                TextButton({
                    text.toIntOrNull()?.let { onChange(it.coerceIn(min, max)) }
                    showDialog = false
                }) { Text("אישור") }
            },
            dismissButton = { TextButton({ showDialog = false }) { Text("ביטול") } },
        )
    }
}

/** A field's label text with an optional (i) button opening a short explanation. */
@Composable
fun FieldLabel(text: String, info: String? = null, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        if (info != null) {
            var show by remember { mutableStateOf(false) }
            IconButton({ show = true }, Modifier.size(22.dp)) {
                Icon(Icons.Rounded.Info, "מידע על $text", Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (show) {
                AlertDialog(
                    onDismissRequest = { show = false },
                    title = { Text(text) },
                    text  = { Text(info) },
                    confirmButton = { TextButton({ show = false }) { Text("הבנתי") } },
                )
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String, value: Int, unit: String, min: Float, max: Float,
    steps: Int, color: androidx.compose.ui.graphics.Color,
    info: String? = null,
    formatter: (Int) -> String = { "$it $unit" },
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        FieldLabel(label, info)
        EditableValueBadge(value, unit, color, min.toInt(), max.toInt(), onChange, displayText = formatter(value))
    }
    // Math.round, not toInt(): toInt() truncates, so any step position that doesn't
    // land exactly on an integer (most of them, for ranges that don't divide evenly by
    // the step count) resolved one unit *below* the value the slider was showing.
    Slider(value.toFloat(), { onChange(Math.round(it)) }, valueRange = min..max, steps = steps,
        colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
}
