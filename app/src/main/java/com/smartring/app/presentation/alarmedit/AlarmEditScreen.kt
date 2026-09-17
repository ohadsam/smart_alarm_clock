package com.smartring.app.presentation.alarmedit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.domain.model.*
import com.smartring.app.presentation.theme.*
import com.smartring.app.util.GENERIC_ALARM_NAME
import com.smartring.app.util.RingtonePreviewPlayer
import com.smartring.app.util.formatDurationSeconds
import com.smartring.app.util.durationPartsOf
import com.smartring.app.util.durationPreview
import com.smartring.app.util.durationRangeError
import com.smartring.app.util.durationRangeHint
import com.smartring.app.util.normalizeDurationParts
import com.smartring.app.util.ringSetupWarnings
import com.smartring.app.util.formatPickedDay
import com.smartring.app.util.localInstantOnPickedDay
import com.smartring.app.util.localInstantToPickedDay
import java.text.SimpleDateFormat
import java.util.*


/** Test tags for [AlarmEditScreen]. Declared beside the screen so renaming one has to
 *  pass through the same file as the UI it identifies. */
object AlarmEditTags {
    const val NAME_FIELD       = "alarm_edit_name_field"
    const val FORM_LIST        = "alarm_edit_form_list"
    const val DURATION_MINUTES = "duration_input_minutes"
    const val DURATION_SECONDS = "duration_input_seconds"
    /**
     * The ring-duration badge. Tagged because its *text* is not unique: several sliders
     * on this screen legitimately read "1 דק׳" at their defaults (ring duration, snooze
     * length, per-round duration), so matching by text finds three nodes and fails.
     */
    const val RING_DURATION_BADGE = "ring_duration_badge"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarmId: Long    = 0L,
    prefillName: String? = null,
    prefillHour: Int? = null,
    prefillMinute: Int? = null,
    /** Non-zero when this screen is a *copy* of that alarm, not an edit of it. */
    copyOfAlarmId: Long = 0L,
    onBack: () -> Unit,
    vm: AlarmEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(alarmId, prefillName, prefillHour, prefillMinute, copyOfAlarmId) {
        when {
            alarmId > 0L       -> vm.loadAlarm(alarmId)
            copyOfAlarmId > 0L -> vm.loadAsCopy(copyOfAlarmId)
            // Every new alarm — prefilled from History or not — starts from the user's
            // configured defaults rather than the constants compiled into the state class.
            else               -> vm.startNew(prefillName, prefillHour, prefillMinute)
        }
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
                title          = {
                    Text(
                        when {
                            alarmId > 0       -> "עריכת שעמור"
                            copyOfAlarmId > 0 -> "שכפול שעמור"
                            else              -> "שעמור חדש"
                        },
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
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
            // Tagged so a UI test can scroll to a field deterministically: a LazyColumn
            // only composes what is on screen, so anything below the fold does not exist
            // to look for until something has scrolled to it.
            modifier              = Modifier.testTag(AlarmEditTags.FORM_LIST),
            state                 = listState,
            contentPadding        = PaddingValues(start=16.dp, end=16.dp, top=pad.calculateTopPadding()+8.dp, bottom=80.dp),
            verticalArrangement   = Arrangement.spacedBy(12.dp),
        ) {
            // A save that threw. Shown at the very top, where the user is looking after
            // tapping Save — previously the button simply went back to being tappable
            // with nothing to say the alarm had not been written at all.
            if (s.saveError) {
                item {
                    Surface(shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(8.dp))
                            Text("שמירת השעמור נכשלה. נסה שוב — הפרטים נשמרו במסך. " +
                                "אם זה חוזר, אפשר לראות את הסיבה בהגדרות ← לוגים.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // ── Off / on ──────────────────────────────────────────
            // First thing on the screen when the alarm is off, because until v1.6.13 an
            // alarm that had already rung was switched off automatically, could not be
            // switched back on from here, and saving wrote the off state straight back.
            // The alarm saved and never rang, with nothing anywhere to say why.
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (s.isEnabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)
                            else MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    if (s.isEnabled) "השעמור פעיל" else "השעמור כבוי",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (s.isEnabled) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    if (s.isEnabled) "יצלצל במועד שנקבע למטה."
                                    else if (s.loadedDisabled)
                                        "שעמור חד-פעמי נכבה אוטומטית לאחר שהוא מצלצל. " +
                                        "הפעל אותו כאן כדי שיצלצל שוב — אחרת השמירה לא תועיל."
                                    else "כבוי — לא יצלצל, גם לא אחרי שמירה.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (s.isEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            Switch(s.isEnabled, onCheckedChange = vm::setEnabled)
                        }
                    }
                }
            }

            // ── Name ──────────────────────────────────────────────
            item {
                OutlinedTextField(
                    value         = s.name,
                    onValueChange = vm::setName,
                    // Tagged for the end-to-end UI test: this screen has several text
                    // fields, and matching this one by its label would be matching on a
                    // user-visible Hebrew string that a copy change breaks silently.
                    modifier      = Modifier.fillMaxWidth().testTag(AlarmEditTags.NAME_FIELD),
                    label         = { Text("שם השעמור") },
                    placeholder   = { Text("למשל: קום לעבודה") },
                    leadingIcon   = { Icon(Icons.AutoMirrored.Rounded.Label, null) },
                    isError       = s.nameError,
                    supportingText = if (s.nameError) {{ Text("נדרש שם") }} else null,
                    singleLine    = true,
                    shape         = RoundedCornerShape(14.dp),
                    enabled       = !s.unnamed,
                )
            }

            // ── "no name needed" ──────────────────────────────────
            // A name is required (an unnamed alarm is unidentifiable in the list, the
            // widgets and the notification), but "קום לעבודה" is not worth typing for a
            // one-off reminder. This gives the required name without the typing, and its
            // starting position is configurable in Settings.
            item {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    FieldLabel("ללא שם — קרא לו \"$GENERIC_ALARM_NAME\"",
                        info = "מסמן את השעמור בשם כללי במקום לדרוש ממך לחשוב על אחד. " +
                            "אפשר לקבוע בהגדרות אם המתג הזה יתחיל דלוק או כבוי.",
                        modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Switch(s.unnamed, onCheckedChange = vm::setUnnamed)
                }
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
                    if (s.pastDateError) {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("התאריך שנבחר עבר — בחר תאריך ושעה עתידיים כדי לשמור",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold)
                        }
                    }
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
                        // weight(1f) so the trailing control keeps its own space. Without it this
                        // text takes its full intrinsic width and the switch is drawn over the end
                        // of it — visible on a stock phone, not only at a large font scale.
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
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
                                        // onPrimary on the selected circle: white on the
                                        // dark scheme's light-blue primary is 3.19:1.
                                        Text(d, fontWeight = FontWeight.Bold,
                                            color = if (sel) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant)
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
                    leadingIcon   = { Icon(Icons.AutoMirrored.Rounded.StickyNote2, null) },
                    maxLines      = 2,
                    shape         = RoundedCornerShape(14.dp),
                )
            }

            // ── Ring ──────────────────────────────────────────────
            item { SectionLabel("צלצול") }
            item {
                EditCard {
                    LabeledSlider("משך צלצול כולל", s.ringDurationSeconds, "שנ׳", 5f, 600f, 118, MaterialTheme.colorScheme.primary,
                        info = "הזמן הכולל שהשעמור מצלצל עד שהוא נעצר מעצמו, אם לא תעצור אותו קודם.\n\n" +
                            "זה לא אותו דבר כמו \"משך הסבב\" למטה: הסבבים מתנגנים בתוך הזמן הזה, בזה אחר זה, " +
                            "והרשימה חוזרת מהתחלה עד שהזמן הכולל נגמר. לדוגמה: משך כולל 2 דקות עם סבב אחד " +
                            "באורך 30 שניות — הסבב יתנגן ארבע פעמים.",
                        formatter = ::formatDurationSeconds, durationInput = true,
                        badgeTestTag = AlarmEditTags.RING_DURATION_BADGE,
                        onChange = vm::setRingDuration)
                }
            }

            // ── Ring rounds (up to 10, each with its own sound/volume/duration) ────
            item { SectionLabel("סבבי צלצול") }
            // Spelling out the relationship between the two durations, in the place where
            // the confusion actually happens. "משך צלצול כולל" above is the envelope; a
            // round's own length is how long its sound plays inside that envelope.
            item {
                Text(
                    "כל סבב מנגן את הצליל שלו למשך הזמן שנקבע לו, אחריו ההשהיה שלו, ואז הסבב הבא. " +
                    "כשהרשימה נגמרת היא חוזרת מהתחלה — עד שנגמר \"משך צלצול כולל\" שהגדרת למעלה.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                            formatter = ::formatDurationSeconds, durationInput = true, onChange = vm::setVibrationOnlySeconds)
                    }
                }
            }

            // ── Crescendo ─────────────────────────────────────────
            item { SectionLabel("צלצול מתחזק") }
            item {
                EditCard {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, Modifier.size(18.dp),
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
                            formatter = ::formatDurationSeconds, durationInput = true, onChange = vm::setCrescendoStepSeconds)
                        Spacer(Modifier.height(6.dp))
                        LabeledSlider("עלייה בכל צעד", s.crescendoStepPercent, "%", 5f, 30f, 4, MaterialTheme.colorScheme.secondary,
                            info = "כמה אחוזים עוצמת הקול עולה בכל שלב.",
                            onChange = vm::setCrescendoStepPercent)
                    }
                }
            }

            // Configurations the sliders allow but that won't behave as the screen
            // implies — a vibrate-first window longer than the whole ring (the sound
            // then never plays at all), or a crescendo that can't climb or can't
            // finish. These fail silently at 06:30 the next morning otherwise; the
            // rules themselves live in the unit-tested ringSetupWarnings().
            val ringWarnings = ringSetupWarnings(
                vibrationMode        = s.vibrationMode,
                vibrationOnlySeconds = s.vibrationOnlySeconds,
                ringDurationSeconds  = s.ringDurationSeconds,
                rings                = s.rings,
                crescendoEnabled     = s.crescendoEnabled,
                crescendoStartVolume = s.crescendoStartVolume,
                crescendoStepSeconds = s.crescendoStepSeconds,
                crescendoStepPercent = s.crescendoStepPercent,
            )
            if (ringWarnings.isNotEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            ringWarnings.forEachIndexed { i, warning ->
                                if (i > 0) Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.width(8.dp))
                                    Text(warning, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
            }

            // ── Snooze ────────────────────────────────────────────
            item { SectionLabel("נודניק") }
            item {
                EditCard {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        FieldLabel("אפשר נודניק לשעמור זה",
                            info = "כאשר כבוי, לא תוצג אפשרות נודניק כלל עבור השעמור הזה — לא במסך הצלצול ולא בהתראה.",
                            modifier = Modifier.weight(1f).padding(end = 12.dp))
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
                                info = "כמה פעמים ניתן ללחוץ על נודניק לפני שהאפשרות נעלמת.",
                                modifier = Modifier.weight(1f).padding(end = 8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ if (s.snoozeMaxCount > 1) vm.setSnoozeMaxCount(s.snoozeMaxCount - 1) }, Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Remove, "הפחת מקסימום נודניקים")
                                }
                                Text("${s.snoozeMaxCount}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                IconButton({ if (s.snoozeMaxCount < 10) vm.setSnoozeMaxCount(s.snoozeMaxCount + 1) }, Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Add, "הוסף מקסימום נודניקים")
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

            // ── Save, again, at the bottom ────────────────────────
            // The form is long enough that almost all of it is below the fold, so the
            // only Save button sat off-screen behind a scroll back to the top. A second
            // one at the end means the action is wherever the user finishes.
            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = vm::save,
                    enabled = !s.isSaving,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (s.isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    else Text("שמור שעמור", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
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

    // Stacked, not side by side. As a 1f/0.6f split these two never fit: the date reads
    // "יום חמישי, 17/09/2026" and was ellipsized down to "יום חמישי," — the part that
    // actually identifies the day was the part thrown away — while the time button was
    // narrow enough that "07:00" wrapped mid-value onto two lines ("07:0" / "0"). Any
    // horizontal split has that problem somewhere, because the weekday name's length is
    // language- and locale-dependent and the font scale is the user's to choose. Full
    // width each is the layout that cannot be squeezed, and it gives both a bigger tap
    // target as well.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                fmt.format(cal.time),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                // softWrap = false is the direct fix for the two-line "07:0 / 0": a time
                // is one token and must never be broken across lines, whatever the width.
                softWrap = false,
                maxLines = 1,
            )
        }
    }

    if (showDatePicker) {
        // The stored value is a local instant; the picker reads
        // initialSelectedDateMillis as UTC, so hand it the picked-day form.
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = localInstantToPickedDay(epochMillis))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton({
                    dpState.selectedDateMillis?.let { pickedDay ->
                        onChanged(localInstantOnPickedDay(
                            pickedDay, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)))
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

    // One player for the whole section: starting a second preview stops the first, which
    // is the only sane behaviour when each round has its own button.
    val preview = remember { RingtonePreviewPlayer() }
    var previewing by remember { mutableStateOf<Int?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    // Stopped when the screen leaves composition. Without this, navigating back mid-play
    // leaves an alarm-volume sound playing over the rest of the app with no way to stop
    // it short of killing the process.
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // The untyped getParcelableExtra() is deprecated from API 33 because it can
        // hand back an object of the wrong type without complaining; the typed overload
        // it was replaced by only exists there, hence the version split.
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        else
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (pickingIndex in rings.indices) {
            onUpdate(pickingIndex, rings[pickingIndex].copy(ringtoneUri = uri?.toString() ?: "default"))
        }
        pickingIndex = -1
    }

    // The picker itself needs no permission — but the URI it hands back does, later,
    // when AlarmFiringService opens it. A track from the user's own library lives in
    // MediaStore, and reading it on Android 13+ requires READ_MEDIA_AUDIO (below that,
    // READ_EXTERNAL_STORAGE). Both were declared in the manifest and never once
    // requested, so the grant never existed: the alarm fell back to the default sound
    // (and, before this release, fell silent altogether) at 06:30 with no hint why.
    // Asked for at the moment the user reaches for a custom sound, which is the only
    // point where the request makes sense to them.
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    // A device with no ringtone picker (stripped AOSP/Go builds) makes launch() throw
    // ActivityNotFoundException straight out of the click handler. The round keeps its
    // current sound and the user is told, rather than the app closing.
    var pickerMissing by remember { mutableStateOf(false) }

    fun openPicker(index: Int) {
        pickingIndex = index
        val launched = runCatching {
            ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                val current = rings.getOrNull(index)?.ringtoneUri
                if (current != null && current != "default")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
            })
        }.isSuccess
        if (!launched) { pickingIndex = -1; pickerMissing = true }
    }

    // Opens the picker whatever the user answers: a denied permission only limits
    // which sounds will play later, and refusing to show the picker over it would be
    // worse than the degraded case it protects against.
    var pendingPickIndex by remember { mutableStateOf(-1) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (pendingPickIndex >= 0) { openPicker(pendingPickIndex); pendingPickIndex = -1 } }

    fun pickRingtone(index: Int) {
        val granted = ContextCompat.checkSelfPermission(context, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) openPicker(index)
        else { pendingPickIndex = index; audioPermissionLauncher.launch(audioPermission) }
    }

    EditCard {
        if (pickerMissing) {
            Text("לא נמצא בורר צלצולים במכשיר הזה — הסבב ימשיך עם הצליל הנוכחי.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        rings.forEachIndexed { i, ring ->
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("סבב ${i + 1}", Modifier.weight(1f), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                if (rings.size > 1) {
                    // Destructive, and was a 28dp target — the easiest button on the
                    // screen to miss and the worst one to hit by accident.
                    IconButton({ onRemove(i) }, Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Close, "הסר סבב ${i + 1}", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickRingtone(i) },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ringtoneDisplayName(context, ring.ringtoneUri), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                // Plays this round's sound at this round's volume, through the alarm
                // stream — so what is heard here is what will be heard at 06:30, device
                // alarm volume included. Judging a choice any other way is guesswork.
                FilledTonalIconButton(
                    onClick = {
                        if (previewing == i) {
                            preview.stop(); previewing = null
                        } else {
                            previewFailed = !preview.play(context, i, ring.ringtoneUri, ring.volumePercent)
                            previewing = if (previewFailed) null else i
                        }
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (previewing == i) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        if (previewing == i) "עצור השמעה" else "השמע את הצליל של סבב ${i + 1}",
                    )
                }
            }
            if (previewFailed && previewing == null) {
                Spacer(Modifier.height(4.dp))
                Text("לא ניתן להשמיע את הצליל הזה. בחר צליל אחר.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            LabeledSlider("משך הסבב", ring.durationSeconds, "שנ׳", 5f, 300f, 58, MaterialTheme.colorScheme.primary,
                info = "כמה זמן הסבב הזה מנגן לפני שעובר לסבב הבא.",
                formatter = ::formatDurationSeconds, durationInput = true) {
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
                formatter = ::formatDurationSeconds, durationInput = true) {
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
                Text("מספר חזרות", Modifier.weight(1f).padding(end = 8.dp), fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (s.recurrenceCount > 1) vm.setRecurrenceCount(s.recurrenceCount - 1) },
                        Modifier.size(36.dp)) { Icon(Icons.Rounded.Remove, "הפחת מספר חזרות") }
                    Text("${s.recurrenceCount}",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    IconButton({ if (s.recurrenceCount < 100) vm.setRecurrenceCount(s.recurrenceCount + 1) },
                        Modifier.size(36.dp)) { Icon(Icons.Rounded.Add, "הוסף מספר חזרות") }
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
                // recurrenceUntilDate is a local end-of-day instant, so a local
                // formatter is right here — unlike the picked-day values above.
                Text(s.recurrenceUntilDate?.let { fmt.format(Date(it)) } ?: "בחר תאריך סיום")
            }
            if (showDp) {
                val dpState = rememberDatePickerState(
                    // Converted back to picked-day form: reopening the picker on the raw
                    // 23:59:59.999 local instant landed a day late for negative offsets.
                    initialSelectedDateMillis = localInstantToPickedDay(
                        s.recurrenceUntilDate ?: System.currentTimeMillis()))
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
                        // formatPickedDay, not a local formatter: d.date is midnight
                        // UTC of the chosen day, which a device-local formatter renders
                        // as the *previous* day for any negative UTC offset.
                        Text(formatPickedDay(d.date), fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                        d.label?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton({ onRemove(i) }, Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, "הסר את ${formatPickedDay(d.date)}", Modifier.size(16.dp),
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
    modifier: Modifier = Modifier,
    displayText: String = "$value $unit",
    /**
     * Whether [value] is a number of seconds, and should therefore be typed as minutes +
     * seconds rather than as a single seconds box. Passed explicitly rather than inferred
     * from [unit]: keying behaviour off a display string means a copy edit silently
     * changes which dialog opens.
     */
    durationInput: Boolean = false,
) {
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        onClick = { showDialog = true },
        modifier = modifier,
        shape = RoundedCornerShape(999.dp), color = color.copy(.12f),
        border = BorderStroke(1.dp, color.copy(.3f)),
    ) {
        Text(displayText, Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.ExtraBold)
    }
    if (showDialog && durationInput) {
        DurationInputDialog(
            initialSeconds = value, minSeconds = min, maxSeconds = max,
            onDismiss = { showDialog = false },
            onConfirm = { onChange(it); showDialog = false },
        )
    } else if (showDialog) {
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


/**
 * Types a duration as minutes + seconds instead of as one seconds box.
 *
 * The seconds-only box it replaces was technically complete and practically unusable:
 * "450" gives no sense of whether the alarm will ring for seven minutes or twelve, and
 * the values here go to 600. Three things make this one workable —
 *
 *  - it normalises as you go, so 120 in the seconds field becomes 2 minutes 0 seconds
 *    (carried on focus loss and again on confirm, not on every keystroke: rewriting
 *    mid-typing would fight anyone typing "1", "2", "0");
 *  - it previews the result in words under the fields, next to the raw total, so the
 *    friendly form and the number that actually gets stored can be checked against each
 *    other before committing;
 *  - it refuses to confirm an out-of-range total and says which bound was missed,
 *    rather than silently clamping to it.
 */
@Composable
private fun DurationInputDialog(
    initialSeconds: Int,
    minSeconds: Int,
    maxSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initial = remember(initialSeconds) { durationPartsOf(initialSeconds) }
    var minText by remember { mutableStateOf(initial.minutes.toString()) }
    var secText by remember { mutableStateOf(initial.seconds.toString()) }

    val parts = normalizeDurationParts(minText.toIntOrNull() ?: 0, secText.toIntOrNull() ?: 0)
    val error = durationRangeError(parts.totalSeconds, minSeconds, maxSeconds)

    fun carryOverflow() {
        minText = parts.minutes.toString()
        secText = parts.seconds.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הזן משך זמן") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = it.filter(Char::isDigit).take(4) },
                        // Tagged because a dialog is its own window: a test matching
                        // "any editable field" would also find the name field on the
                        // screen behind this one.
                        modifier = Modifier.weight(1f).testTag(AlarmEditTags.DURATION_MINUTES),
                        singleLine = true,
                        label = { Text("דקות") },
                        isError = error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = secText,
                        onValueChange = { secText = it.filter(Char::isDigit).take(4) },
                        // Carrying on focus loss is what makes "120 שניות" turn into
                        // "2 דקות" by itself, which is the whole point of the pair of
                        // fields — without it the seconds box just holds an odd number.
                        modifier = Modifier.weight(1f)
                            .testTag(AlarmEditTags.DURATION_SECONDS)
                            .onFocusChanged { if (!it.isFocused) carryOverflow() },
                        singleLine = true,
                        label = { Text("שניות") },
                        isError = error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (error == null) MaterialTheme.colorScheme.primary.copy(.10f)
                            else MaterialTheme.colorScheme.error.copy(.10f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            durationPreview(parts.totalSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (error == null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            error ?: durationRangeHint(minSeconds, maxSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { carryOverflow(); onConfirm(parts.totalSeconds) },
                enabled = error == null,
            ) { Text("אישור") }
        },
        dismissButton = { TextButton(onDismiss) { Text("ביטול") } },
    )
}

/** A field's label text with an optional (i) button opening a short explanation. */
@Composable
fun FieldLabel(text: String, info: String? = null, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // weight(1f, fill = false): the text yields space to the (i) button when the row
        // is tight, but does not stretch to fill the row when it is not — so a short
        // label still sits right next to its own info button rather than a gap away.
        Text(text, Modifier.weight(1f, fill = false),
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        if (info != null) {
            var show by remember { mutableStateOf(false) }
            // 32dp, not 22dp. Material's minimum touch target is 48dp and Compose's
            // IconButton defaults to it — an explicit Modifier.size() opts out, and 22dp
            // is small enough to be genuinely hard to hit. 32dp is the compromise this
            // layout can absorb: these sit inline beside every field label, so a full
            // 48dp would set the row height of the entire screen. The glyph stays small.
            IconButton({ show = true }, Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Info, "מידע על $text", Modifier.size(16.dp),
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
    durationInput: Boolean = false,
    badgeTestTag: String? = null,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        // weight(1f) on the label, not on nothing: without it the label takes its full
        // intrinsic width and the badge is pushed past the edge of the card. Labels here
        // run to "השהיה אחרי סבב זה" and badges to "10 דק׳ 30 שנ׳", so the pair overflows
        // a phone-width card at the default font scale, never mind a larger one.
        FieldLabel(label, info, Modifier.weight(1f).padding(end = 8.dp))
        EditableValueBadge(value, unit, color, min.toInt(), max.toInt(), onChange,
            modifier = if (badgeTestTag != null) Modifier.testTag(badgeTestTag) else Modifier,
            displayText = formatter(value), durationInput = durationInput)
    }
    // Math.round, not toInt(): toInt() truncates, so any step position that doesn't
    // land exactly on an integer (most of them, for ranges that don't divide evenly by
    // the step count) resolved one unit *below* the value the slider was showing.
    Slider(value.toFloat(), { onChange(Math.round(it)) }, valueRange = min..max, steps = steps,
        colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
}
