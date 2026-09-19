package com.smartring.app.presentation.widget
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.ColorFilter
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.smartring.app.MainActivity
import com.smartring.app.R
import com.smartring.app.data.repository.AlarmDefaultsRepository
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.data.repository.QuickPresetsRepository
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import com.smartring.app.util.QuickPreset
import com.smartring.app.util.buildQuickAlarm
import com.smartring.app.util.formatDayAndTime
import com.smartring.app.util.presetLabel
import com.smartring.app.util.presetsForWidget
import com.smartring.app.util.quickPresetFireAt
import com.smartring.app.util.WidgetAlarmEntry
import com.smartring.app.util.buildWidgetRows
import com.smartring.app.util.nextOccasionalDate
import com.smartring.app.util.widgetDayLabel
import java.util.Calendar
import kotlinx.coroutines.flow.first
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

// ── Deep links ───────────────────────────────────────────────────────────────
//
// Tapping the widget used to always open the alarm list, wherever it was pressed. That
// is one destination for three different intentions: "show me everything", "let me edit
// this one", "make a new one". Each now goes where it says it goes.

private fun openListIntent(ctx: Context) = Intent(ctx, MainActivity::class.java)

private fun openAddIntent(ctx: Context) =
    Intent(ctx, MainActivity::class.java)
        .setData(Uri.parse("smartring://widget/add"))
        .putExtra(MainActivity.EXTRA_WIDGET_ADD_ALARM, true)

/**
 * A distinct `data` URI per alarm, not just a distinct extra.
 *
 * Extras are not part of an Intent's identity for PendingIntent matching (`filterEquals`
 * ignores them), so several rows whose intents differed only by an extra would collapse
 * into a single PendingIntent and every row would open whichever alarm was registered
 * first. The URI is what keeps them distinct — the same reason each alarm's AlarmManager
 * registration needs its own request code.
 */
private fun openEditIntent(ctx: Context, alarmId: Long) =
    Intent(ctx, MainActivity::class.java)
        .setData(Uri.parse("smartring://widget/alarm/$alarmId"))
        .putExtra(MainActivity.EXTRA_WIDGET_EDIT_ALARM_ID, alarmId)

/** Thickness of the accent frame drawn around every widget. Must match the
 *  difference between widget_frame_border's and widget_frame_inner's corner radii,
 *  or the two curves stop being concentric. */
internal const val WIDGET_BORDER_DP = 2

/**
 * The colors one widget renders with. Previously a single hard-coded near-black
 * palette, which made every widget a dark slab on a light home screen no matter what
 * the device was set to.
 *
 * Resolved from the night-mode configuration at render time rather than through a
 * Glance day/night ColorProvider, because the same values also drive the embedded
 * TextClock's and Chronometer's RemoteViews color, which is a plain ARGB int and
 * can't take a ColorProvider — one source for both keeps the clock and the Glance
 * content from ending up on opposite sides of the theme. Home-screen widgets
 * conventionally follow the *system* theme rather than an in-app preference, and
 * that is what this does; the app's own auto/dark/light setting governs only the
 * app's own screens.
 *
 * [background], [frameBorder] and [rowBackground] are also declared as XML colors
 * (values/colors.xml + values-night), because the rounded shapes that use them have
 * to be drawables to round below API 31. WidgetPaletteTest asserts the two
 * definitions are identical so they cannot drift apart.
 *
 * Every foreground here clears WCAG AA (4.5:1) against *both* [background] and
 * [rowBackground]; WidgetContrastTest pins that. The widget's type is 9-11sp, which
 * is squarely "normal text" for contrast purposes, and it is read half-awake in the
 * dark.
 */
internal data class WidgetPalette(
    val frameBorder: Color,
    val background: Color,
    val accentBlue: Color,
    val accentGreen: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val rowBackground: Color,
)

internal val DarkWidgetPalette = WidgetPalette(
    frameBorder   = Color(0x805B8DF6),
    background    = Color(0xFF13161E),
    accentBlue    = Color(0xFF7FA6F8),
    accentGreen   = Color(0xFF5BF6B0),
    textPrimary   = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF9BA4BC),
    rowBackground = Color(0x1AFFFFFF),
)

// Deliberately not the dark palette's accents re-used on a pale ground: #5BF6B0 (a
// light mint) and #5B8DF6 as text on near-white both fail contrast. These are the
// same hues darkened, matching the app's own Light color scheme.
internal val LightWidgetPalette = WidgetPalette(
    frameBorder   = Color(0x803B5FD4),
    background    = Color(0xFFF7F8FC),
    accentBlue    = Color(0xFF2F4FB8),
    accentGreen   = Color(0xFF0B6047),
    textPrimary   = Color(0xFF0A0C10),
    textSecondary = Color(0xFF424C63),
    rowBackground = Color(0x14000000),
)

internal fun isNightMode(ctx: Context): Boolean =
    (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

internal fun paletteFor(ctx: Context): WidgetPalette =
    if (isNightMode(ctx)) DarkWidgetPalette else LightWidgetPalette

/** Test tags, so a render test names one node instead of matching on Hebrew prose that
 *  an ordinary copy edit would silently break. */
internal object WidgetTags {
    const val EMPTY = "widget-empty"
    const val NEXT_TIME = "widget-next-time"
    const val ROWS = "widget-rows"
    const val ADD = "widget-add"
    fun row(id: Long) = "widget-row-$id"
    fun toggle(id: Long) = "widget-toggle-$id"

    /** The row's own day label. Tagged because the hero line prints the same day text,
     *  so matching on the words alone is ambiguous by construction. */
    fun day(id: Long) = "widget-day-$id"

    const val PANEL = "widget-panel"
    const val PANEL_TOGGLE = "widget-panel-toggle"
    const val BULK_ENABLE = "widget-bulk-enable"
    const val BULK_FREEZE = "widget-bulk-freeze"
    fun preset(id: Long) = "widget-preset-$id"
}

/**
 * The accent-tinted rounded frame around every widget: an outer box painted with the
 * border drawable, padded by [WIDGET_BORDER_DP], containing the body drawable.
 *
 * The padding belongs on the *outer* box, not the inner one. It used to sit on the
 * inner box, which is a no-op for this purpose — a view's own padding insets its
 * children, not itself, so the inner background still filled the outer box edge to
 * edge and painted over the entire border. The frame was invisible on every device.
 *
 * The frame is no longer itself the "open the app" target. It used to be, which made the
 * whole widget one big button and meant every row sat inside a click region that opened
 * the generic list — so a row could not sensibly open *its own* alarm. Each body now
 * places its own targets.
 */
@Composable
private fun WidgetFrame(content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize()
        .background(ImageProvider(R.drawable.widget_frame_border))
        .padding(WIDGET_BORDER_DP.dp)) {
        Box(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_frame_inner))) {
            content()
        }
    }
}

/** A real android.widget.TextClock, embedded via AndroidRemoteViews: it ticks on its
 *  own inside the widget host process, so the displayed time stays live without the
 *  app ever waking up to re-render the widget — unlike a Glance Text() built from a
 *  value computed once at provideGlance() time. */
private fun clockRemoteViews(ctx: Context, sizeSp: Float, colorArgb: Int): RemoteViews =
    RemoteViews(ctx.packageName, R.layout.widget_clock).apply {
        setTextViewTextSize(R.id.widget_clock, TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(R.id.widget_clock, colorArgb)
    }

@Composable
private fun LiveClock(ctx: Context, sizeSp: Float, colorArgb: Int) {
    AndroidRemoteViews(clockRemoteViews(ctx, sizeSp, colorArgb))
}

/** Chronometer's base is on the elapsed-realtime clock, not the wall clock, so the
 *  wall-clock target has to be re-expressed as "now, plus however long is left". */
private fun countdownRemoteViews(ctx: Context, fireAt: Long, sizeSp: Float, colorArgb: Int): RemoteViews =
    RemoteViews(ctx.packageName, R.layout.widget_countdown).apply {
        setTextViewTextSize(R.id.widget_countdown, TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(R.id.widget_countdown, colorArgb)
        setChronometerCountDown(R.id.widget_countdown, true)
        setChronometer(
            R.id.widget_countdown,
            SystemClock.elapsedRealtime() + (fireAt - System.currentTimeMillis()),
            "בעוד %s",
            true,
        )
    }

/**
 * "How long until it rings" — always a live Chronometer now, at every distance.
 *
 * This used to fall back to a static string for anything more than an hour out, on the
 * reasoning that "בעוד 8 שע׳" does not change meaningfully in fifteen minutes and a
 * permanently ticking view was not worth it on someone's home screen. That reasoning
 * assumed something untrue: that a re-render would arrive. Nothing guarantees one. Both
 * refresh paths — the descriptor's `updatePeriodMillis` and the 15-minute
 * `WidgetRefreshWorker` — are deferred in Doze, which is the state a phone is in all
 * night. A widget rendered at 22:00 for an alarm at 07:00 therefore went on saying
 * "בעוד 9 שע׳" at 06:55. That is the "widgets don't sync" complaint in one sentence.
 *
 * A count-down Chronometer cannot go stale: it ticks inside the host process against the
 * elapsed-realtime clock and needs no wake-up from this app at all. It costs one view.
 * Being always right is worth more than the view.
 */
@Composable
private fun Countdown(ctx: Context, fireAt: Long, sizeSp: Float, color: Color) {
    AndroidRemoteViews(countdownRemoteViews(ctx, fireAt, sizeSp, color.toArgb()))
}

@EntryPoint @InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun alarmRepository(): AlarmRepository
    fun alarmScheduler(): AlarmScheduler
    fun alarmDefaultsRepository(): AlarmDefaultsRepository
    fun quickPresetsRepository(): QuickPresetsRepository
    fun appLogger(): AppLogger
}

/**
 * Everything a widget renders from, gathered once in `provideGlance`.
 *
 * Passing a value object into the composables rather than letting them reach for the
 * repository is what makes the widgets testable at all: every body below is an ordinary
 * Glance composable over plain data, so `runGlanceAppWidgetUnitTest` can render one and
 * assert what is on screen. Until this batch nothing had ever rendered a widget in CI —
 * the tests checked the manifest descriptors and the pure row-ordering function — so a
 * `provideGlance` that threw was invisible to all four jobs.
 */
internal data class WidgetUiState(
    val rows: List<WidgetAlarmEntry>,
    val nowMillis: Long,
    /** The shortcut chips this surface should offer, already filtered and capped. */
    val presets: List<QuickPreset> = emptyList(),
    /** Whether the quick-actions panel is open, per widget instance. */
    val panelOpen: Boolean = false,
) {
    val next: WidgetAlarmEntry? get() = rows.firstOrNull()?.takeIf { it.isArmed }
    val armedCount: Int get() = rows.count { it.isArmed }
    val hasAnyAlarms: Boolean get() = rows.isNotEmpty()
    val anyFrozen: Boolean get() = rows.any { it.alarm.isFrozen }
}

abstract class SmartRingBaseWidget : GlanceAppWidget() {

    /**
     * Render per actual size rather than once at the smallest one.
     *
     * The default is `SizeMode.Single`, which renders a single layout sized from the
     * provider's *minimum* dimensions and never renders again when the user resizes. All
     * four descriptors advertise `resizeMode="horizontal|vertical"`, so the widget invited
     * a resize and then ignored it: stretching one left the original content floating in
     * the middle of the new area. `Exact` asks the host for a render per real size.
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    /**
     * Every alarm, armed first — the list the widgets render.
     *
     * Reads all alarms rather than only the active ones, which is what makes a per-row
     * toggle possible at all: a widget that hides disabled alarms can switch one off and
     * then offers no way to switch it back on.
     */
    // internal, not protected: WidgetUiState is internal, and a protected member may not
    // expose an internal type. The four subclasses live in this module, so internal
    // reaches every caller that actually exists.
    internal suspend fun widgetState(ctx: Context): WidgetUiState {
        val ep = EntryPointAccessors.fromApplication(ctx, WidgetEntryPoint::class.java)
        val scheduler = ep.alarmScheduler()
        val quick = ep.quickPresetsRepository().config.first()
        return WidgetUiState(
            rows = buildWidgetRows(
                alarms     = ep.alarmRepository().getAllAlarms(),
                snoozeAt   = scheduler::pendingSnoozeUntil,
                nextFireAt = { scheduler.nextFireTime(it) },
            ),
            nowMillis = System.currentTimeMillis(),
            presets = presetsForWidget(quick.presets, quick.limits),
        )
    }
}

class SmartRingWidgetSmall : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val state = widgetState(ctx)
        val p = paletteFor(ctx)
        provideContent { WidgetFrame { SmallBody(ctx, state, p) } }
    }
}

class SmartRingWidgetMedium : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val state = widgetState(ctx)
        val p = paletteFor(ctx)
        provideContent { WidgetFrame { MediumBody(ctx, state, p) } }
    }
}

class SmartRingWidgetWide : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val state = widgetState(ctx)
        val p = paletteFor(ctx)
        // currentState() has to be read inside provideContent — it is composition-scoped,
        // and it is per widget instance, which is the point: one widget's open panel must
        // not open every other widget's.
        provideContent {
            val open = currentState<Preferences>()[PANEL_OPEN_KEY] ?: false
            WidgetFrame { ListBody(ctx, state.copy(panelOpen = open), p) }
        }
    }
}

class SmartRingWidgetLarge : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val state = widgetState(ctx)
        val p = paletteFor(ctx)
        provideContent {
            val open = currentState<Preferences>()[PANEL_OPEN_KEY] ?: false
            WidgetFrame { ListBody(ctx, state.copy(panelOpen = open), p) }
        }
    }
}

/** Per-widget-instance flag for the quick-actions panel. */
internal val PANEL_OPEN_KEY = booleanPreferencesKey("smartring_widget_panel_open")

// ── Bodies ───────────────────────────────────────────────────────────────────

/** 2x2: one question answered — when is the next one, and how long have I got. */
@Composable
internal fun SmallBody(ctx: Context, state: WidgetUiState, p: WidgetPalette) {
    val next = state.next
    Column(
        GlanceModifier.fillMaxSize().padding(10.dp)
            .clickable(actionStartActivity(openListIntent(ctx))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LiveClock(ctx, 11f, p.textSecondary.toArgb())
        val fireAt = next?.fireAt
        if (next != null && fireAt != null) {
            Text(
                next.timeText,
                style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = ColorProvider(p.textPrimary)),
                modifier = GlanceModifier.semantics { testTag = WidgetTags.NEXT_TIME },
            )
            Text(
                widgetDayLabel(fireAt, state.nowMillis),
                style = TextStyle(fontSize = 10.sp, color = ColorProvider(p.textSecondary)),
                maxLines = 1,
            )
            Countdown(ctx, fireAt, 9f, p.accentBlue)
        } else {
            WidgetEmptyState(ctx, p, state.hasAnyAlarms, compact = true)
        }
    }
}

/** 4x2: the next alarm with room for its name and the day it lands on. */
@Composable
internal fun MediumBody(ctx: Context, state: WidgetUiState, p: WidgetPalette) {
    val next = state.next
    val fireAt = next?.fireAt
    Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
        WidgetHeader(ctx, state, p)
        if (next != null && fireAt != null) {
            Row(GlanceModifier.fillMaxWidth()
                .clickable(actionStartActivity(openEditIntent(ctx, next.alarm.id))),
                verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        next.timeText,
                        style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            color = ColorProvider(p.textPrimary)),
                        modifier = GlanceModifier.semantics { testTag = WidgetTags.NEXT_TIME },
                    )
                    Text(next.alarm.name,
                        style = TextStyle(fontSize = 11.sp, color = ColorProvider(p.textSecondary)),
                        maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(widgetDayLabel(fireAt, state.nowMillis),
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = ColorProvider(p.accentGreen)),
                        maxLines = 1)
                    Countdown(ctx, fireAt, 10f, p.accentBlue)
                }
            }
        } else {
            WidgetEmptyState(ctx, p, state.hasAnyAlarms)
        }
    }
}

/**
 * 4x3 and 4x4: the whole list, scrollable.
 *
 * The two used to differ only in `take(3)` versus `take(4)` — a hard cap with nothing on
 * screen to say more existed, so a user with six alarms saw four and no reason to think
 * otherwise. That is not a smaller widget showing less; it is a widget misreporting what
 * is set up, and it is most of "the widgets don't show my alarms".
 *
 * A `LazyColumn` is the right primitive for a variable-length list in a widget: it is
 * backed by a RemoteViews collection, so it scrolls in the host process and carries no
 * fixed child budget the way a `Column` of N children does. The previous layout also
 * emitted a `Spacer` after every row, so each alarm cost two children of the same
 * `Column` as the header, the hero line and their own spacer — the largest widget put
 * eleven children in one container. Nothing is capped now: the widget shows as much as it
 * is tall enough for and the rest is a scroll away.
 */
@Composable
internal fun ListBody(ctx: Context, state: WidgetUiState, p: WidgetPalette) {
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        WidgetHeader(ctx, state, p, showPanelToggle = true)
        val next = state.next
        val fireAt = next?.fireAt
        // The hero line stands down while the panel is open: the panel is a mode, and a
        // few cells of home screen cannot show both without showing neither properly.
        if (!state.panelOpen && next != null && fireAt != null) {
            Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("הבא ",
                    style = TextStyle(fontSize = 10.sp, color = ColorProvider(p.accentGreen)))
                Text("${next.timeText} · ${widgetDayLabel(fireAt, state.nowMillis)}",
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = ColorProvider(p.textPrimary)),
                    modifier = GlanceModifier.defaultWeight()
                        .semantics { testTag = WidgetTags.NEXT_TIME },
                    maxLines = 1)
                Countdown(ctx, fireAt, 10f, p.accentBlue)
            }
        }
        when {
            state.panelOpen -> QuickActionsPanel(state, p)
            state.rows.isEmpty() -> WidgetEmptyState(ctx, p, hasAnyAlarms = false)
            else -> LazyColumn(GlanceModifier.fillMaxSize().semantics { testTag = WidgetTags.ROWS }) {
                items(state.rows, itemId = { it.alarm.id }) { entry ->
                    WidgetAlarmRow(ctx, entry, p, state.nowMillis)
                }
            }
        }
    }
}

/**
 * The widget's quick-actions panel.
 *
 * Replaces the alarm list rather than sitting above it, because a widget is a few cells:
 * a panel that pushed the list down would leave one row of each and be useless as both.
 * It is a mode, and the same button that opens it closes it.
 *
 * What is in it, and why those:
 * - **The shortcut chips**, configured in Settings exactly like the app's — the point of
 *   the feature. Creating an alarm from a home screen without opening anything is the
 *   thing a widget is uniquely good at.
 * - **כבה הכל / הפעל הכל** and **הקפא הכל / בטל הקפאה**, which are the four global
 *   operations the app already exposes behind "שליטה כללית". They are what someone
 *   reaches for on a night away or a sick day, and that sheet is three taps deep inside
 *   an app you have to find first.
 *
 * Deliberately *not* here: "snooze the next alarm" and "skip the next occurrence". Both
 * read as obvious quick actions and neither has an honest implementation today — a snooze
 * belongs to a ring that is happening, and skipping one occurrence has no representation
 * in the data model (`occurrencesFired` counts rings that happened, not ones waved off).
 * Inventing either here would mean a button that half-works on a surface with no room to
 * explain itself.
 */
@Composable
private fun QuickActionsPanel(state: WidgetUiState, p: WidgetPalette) {
    LazyColumn(GlanceModifier.fillMaxSize().semantics { testTag = WidgetTags.PANEL }) {
        items(state.presets, itemId = { it.id }) { preset ->
            PanelRow(
                label = presetLabel(preset, state.nowMillis),
                iconRes = R.drawable.ic_widget_add,
                tint = p.accentGreen,
                tag = WidgetTags.preset(preset.id),
                palette = p,
                action = actionRunCallback<CreateQuickAlarmAction>(
                    actionParametersOf(CreateQuickAlarmAction.presetIdKey to preset.id),
                ),
            )
        }
        item {
            PanelRow(
                label = if (state.armedCount > 0) "כבה את כל השעמורים" else "הפעל את כל השעמורים",
                iconRes = if (state.armedCount > 0) R.drawable.ic_widget_alarm_off
                          else R.drawable.ic_widget_alarm_on,
                tint = p.accentBlue,
                tag = WidgetTags.BULK_ENABLE,
                palette = p,
                action = actionRunCallback<BulkAlarmAction>(
                    actionParametersOf(
                        BulkAlarmAction.opKey to
                            if (state.armedCount > 0) BulkAlarmAction.OP_DISABLE_ALL
                            else BulkAlarmAction.OP_ENABLE_ALL,
                    ),
                ),
            )
        }
        item {
            PanelRow(
                // Freezing is not the same as switching off, and the widget should not
                // pretend otherwise: a frozen alarm keeps its schedule and simply does
                // not ring, which is what "away for a few days" wants.
                label = if (state.anyFrozen) "בטל הקפאה" else "הקפא את כל השעמורים",
                iconRes = R.drawable.ic_widget_snooze,
                tint = p.textSecondary,
                tag = WidgetTags.BULK_FREEZE,
                palette = p,
                action = actionRunCallback<BulkAlarmAction>(
                    actionParametersOf(
                        BulkAlarmAction.opKey to
                            if (state.anyFrozen) BulkAlarmAction.OP_UNFREEZE_ALL
                            else BulkAlarmAction.OP_FREEZE_ALL,
                    ),
                ),
            )
        }
    }
}

/** One tappable row in the panel: an icon, a label, and the whole row as the target. */
@Composable
private fun PanelRow(
    label: String,
    iconRes: Int,
    tint: Color,
    tag: String,
    palette: WidgetPalette,
    action: androidx.glance.action.Action,
) {
    Row(
        GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp)
            .background(ImageProvider(R.drawable.widget_row_bg))
            // The whole row, not just the icon: there is no competing target inside a
            // panel row, so the largest tap area is simply the right one.
            .clickable(action),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            modifier = GlanceModifier.size(18.dp),
            colorFilter = ColorFilter.tint(ColorProvider(tint)),
        )
        Spacer(GlanceModifier.width(8.dp))
        // The tag belongs on the Text, not on the Row that contains it: an assertion
        // about text reads the tagged node's *own* text and does not descend into its
        // children, so a tag one level up matches a node with no text at all.
        Text(label,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = ColorProvider(palette.textPrimary)),
            modifier = GlanceModifier.semantics { testTag = tag },
            maxLines = 1)
    }
}

/**
 * The strip along the top of every widget but the smallest.
 *
 * Carries the three things true of the widget as a whole rather than of any single row:
 * how many alarms are armed, what time it is now, and a way to add one.
 */
@Composable
private fun WidgetHeader(
    ctx: Context,
    state: WidgetUiState,
    p: WidgetPalette,
    showPanelToggle: Boolean = false,
) {
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(
            // The count, not the brand name. Someone glancing at their home screen wants
            // to know whether anything is set; they already know which app this is.
            if (state.armedCount > 0) "⏰ ${state.armedCount} פעילים" else "⏰ אין פעילים",
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium,
                color = ColorProvider(if (state.armedCount > 0) p.accentBlue else p.textSecondary)),
            modifier = GlanceModifier.defaultWeight()
                .clickable(actionStartActivity(openListIntent(ctx))),
            maxLines = 1,
        )
        LiveClock(ctx, 10f, p.textSecondary.toArgb())
        if (showPanelToggle) {
            Image(
                provider = ImageProvider(
                    if (state.panelOpen) R.drawable.ic_widget_close else R.drawable.ic_widget_bolt,
                ),
                contentDescription = if (state.panelOpen) "סגור פעולות מהירות" else "פעולות מהירות",
                modifier = GlanceModifier.size(26.dp).padding(start = 6.dp)
                    .semantics { testTag = WidgetTags.PANEL_TOGGLE }
                    .clickable(actionRunCallback<ToggleQuickPanelAction>()),
                colorFilter = ColorFilter.tint(
                    ColorProvider(if (state.panelOpen) p.accentGreen else p.textSecondary)),
            )
        }
        Image(
            provider = ImageProvider(R.drawable.ic_widget_add),
            contentDescription = "הוסף שעמור",
            modifier = GlanceModifier.size(26.dp).padding(start = 6.dp)
                .semantics { testTag = WidgetTags.ADD }
                .clickable(actionStartActivity(openAddIntent(ctx))),
            colorFilter = ColorFilter.tint(ColorProvider(p.accentBlue)),
        )
    }
}

/**
 * Switches one alarm on or off straight from a widget.
 *
 * The widget is where people look at their alarms without opening anything, so "turn
 * tonight's alarm off" should not require launching the app, finding the row and coming
 * back. One toggle covers all three of pause, cancel and re-arm: an alarm that is off has
 * no ring coming, and one that is on has its next occurrence armed.
 *
 * ## The one non-obvious behaviour, and why
 *
 * Switching an *ad-hoc* alarm back on when its date has already passed re-dates it to the
 * next day rather than merely setting `isEnabled = true`. Enabling it as-is would store a
 * perfectly correct "on" flag on an alarm whose only occurrence is in the past — so
 * `schedule()` cancels it and nothing rings. That exact state is what v1.7.0 and v1.7.1
 * were mostly about, and a widget button is the last place it should be reachable from,
 * because there is no screen there to explain it. The row re-renders with the new date
 * immediately, so the change is visible rather than silent.
 */
class ToggleWidgetAlarmAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[alarmIdKey] ?: return
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val repository = ep.alarmRepository()
        val scheduler = ep.alarmScheduler()
        val alarm = repository.getAlarm(id) ?: return

        if (alarm.isEnabled) {
            repository.setEnabled(id, false)
            scheduler.cancel(id)
        } else if (alarm.isOneOffDated &&
            (alarm.specificDateTime ?: 0L) <= System.currentTimeMillis()
        ) {
            val next = nextOccasionalDate(alarm.specificDateTime, alarm.hour, alarm.minute)
            val cal = Calendar.getInstance().apply { timeInMillis = next }
            val revived = alarm.copy(
                specificDateTime = next,
                hour = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE),
                isEnabled = true,
                occurrencesFired = 0,
            )
            repository.saveAlarm(revived)
            scheduler.schedule(revived)
        } else {
            // Clearing the counter matters here too: an alarm switched off by the firing
            // service has one occurrence recorded, and a COUNT-limited recurrence would
            // otherwise be re-enabled straight into isRecurrenceExpired().
            val revived = alarm.copy(isEnabled = true, occurrencesFired = 0)
            repository.saveAlarm(revived)
            scheduler.schedule(revived)
        }
        refreshAllWidgets(context)
    }

    companion object {
        val alarmIdKey = ActionParameters.Key<Long>("smartring_widget_alarm_id")
    }
}

/**
 * The "nothing is coming up" state.
 *
 * A greyed-out crossed alarm icon plus a line of text, rather than text alone: an empty
 * widget showing only words reads as easily as one that failed to load as one correctly
 * reporting an empty schedule. Tinted from the widget's own muted colour so it is right
 * in both themes.
 *
 * It is a button now. The previous version stated the situation and offered nothing to do
 * about it, which on a surface whose whole job is one tap is a dead end.
 */
@Composable
private fun WidgetEmptyState(
    ctx: Context,
    palette: WidgetPalette,
    hasAnyAlarms: Boolean,
    compact: Boolean = false,
) {
    Column(
        GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)
            .semantics { testTag = WidgetTags.EMPTY }
            // "No alarm armed" is fixed in the app by switching one back on; "no alarms at
            // all" is fixed by creating one. Two situations, two landings.
            .clickable(actionStartActivity(
                if (hasAnyAlarms) openListIntent(ctx) else openAddIntent(ctx))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_alarm_off),
            contentDescription = null,
            modifier = GlanceModifier.size(if (compact) 20.dp else 26.dp),
            colorFilter = ColorFilter.tint(ColorProvider(palette.textSecondary)),
        )
        Text(
            // Two different situations, two different sentences. "Nothing armed" when
            // alarms exist but are all off is a fixable state; "nothing set up" is not
            // the same message and should not borrow its wording.
            if (hasAnyAlarms) "אין שעמור פעיל" else "אין שעמורים",
            style = TextStyle(
                fontSize = if (compact) 9.sp else 11.sp,
                color = ColorProvider(palette.textSecondary),
            ),
            maxLines = 1,
        )
        Text(
            if (hasAnyAlarms) "הקש להפעלה" else "הקש להוספה",
            style = TextStyle(fontSize = if (compact) 8.sp else 10.sp,
                color = ColorProvider(palette.accentBlue)),
            maxLines = 1,
        )
    }
}

/**
 * One row in the wide/large widgets.
 *
 * Two targets, each going somewhere different and each big enough to hit: the row opens
 * *that* alarm's editor — it used to sit inside the frame's click region and open the
 * generic list, so every row in the widget led to the same place — and the icon toggles
 * it. Glance has no Switch, so the icon carries the state.
 *
 * The line under the name is the day it next rings ("מחר", "יום ה׳") rather than the
 * recurrence summary it used to show. The pattern ("ימי חול") is a different fact from
 * the one someone is asking at a glance, and two rows both reading 07:00 were otherwise
 * indistinguishable.
 */
@Composable
private fun WidgetAlarmRow(
    ctx: Context,
    entry: WidgetAlarmEntry,
    palette: WidgetPalette,
    nowMillis: Long,
) {
    val alarm = entry.alarm
    Row(GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
        .background(ImageProvider(R.drawable.widget_row_bg))
        .semantics { testTag = WidgetTags.row(alarm.id) },
        verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()
            .clickable(actionStartActivity(openEditIntent(ctx, alarm.id)))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.timeText,
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        color = ColorProvider(
                            if (entry.isArmed) palette.textPrimary else palette.textSecondary)),
                    modifier = GlanceModifier.padding(end = 8.dp))
                Text(alarm.name,
                    style = TextStyle(fontSize = 11.sp, color = ColorProvider(palette.textSecondary)),
                    maxLines = 1)
            }
            val fireAt = entry.fireAt
            Text(
                // An idle row says so in words. Dimming alone is not enough to tell
                // "switched off" apart from "nothing scheduled" on a home screen.
                //
                // Keyed off the nullable directly rather than off isArmed with a `!!`:
                // the two say the same thing today (isArmed *is* fireAt != null), but a
                // widget that throws is the failure this release is about, and a null
                // check costs nothing to keep honest.
                when {
                    fireAt == null -> "כבוי — הקש להפעלה"
                    entry.isSnoozed -> "נודניק · ${widgetDayLabel(fireAt, nowMillis)}"
                    else -> widgetDayLabel(fireAt, nowMillis)
                },
                style = TextStyle(
                    fontSize = 9.sp,
                    color = ColorProvider(
                        if (entry.isArmed) palette.accentBlue else palette.textSecondary),
                ),
                modifier = GlanceModifier.semantics { testTag = WidgetTags.day(alarm.id) },
                maxLines = 1,
            )
        }
        // Runs ToggleWidgetAlarmAction for this alarm id and re-renders every widget, so
        // the row shows the new state immediately.
        Image(
            provider = ImageProvider(
                if (entry.isArmed) R.drawable.ic_widget_alarm_on else R.drawable.ic_widget_alarm_off,
            ),
            contentDescription = if (entry.isArmed) "כבה את ${alarm.name}" else "הפעל את ${alarm.name}",
            modifier = GlanceModifier.size(34.dp).padding(start = 6.dp)
                .semantics { testTag = WidgetTags.toggle(alarm.id) }
                .clickable(
                    actionRunCallback<ToggleWidgetAlarmAction>(
                        actionParametersOf(ToggleWidgetAlarmAction.alarmIdKey to alarm.id),
                    ),
                ),
            colorFilter = ColorFilter.tint(
                ColorProvider(if (entry.isArmed) palette.accentGreen else palette.textSecondary),
            ),
        )
    }
}

/**
 * Opens and closes the quick-actions panel, for this widget instance only.
 *
 * The flag lives in the widget's own Glance state rather than in a repository, because it
 * is a property of *this placement* and nothing else: two widgets on two home screens
 * should not open in lockstep, and the state must not survive as a stored preference that
 * outlives the widget being removed.
 */
class ToggleQuickPanelAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[PANEL_OPEN_KEY] = !(prefs[PANEL_OPEN_KEY] ?: false)
        }
        refreshAllWidgets(context)
    }
}

/**
 * Creates the alarm one shortcut chip stands for, straight from the home screen.
 *
 * Goes through [buildQuickAlarm] — the same function the app's own chips use — so an alarm
 * created here is byte-for-byte the one created there. Two copies of "what a quick alarm
 * is" is exactly how the two surfaces start disagreeing about the user's defaults.
 *
 * The panel closes itself afterwards. Leaving it open would hide the very row that just
 * appeared, so the tap would look like it did nothing.
 */
class CreateQuickAlarmAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val presetId = parameters[presetIdKey] ?: return
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val config = ep.quickPresetsRepository().config.first()
        val preset = config.presets.firstOrNull { it.id == presetId } ?: return
        val at = quickPresetFireAt(preset)
        val alarm = buildQuickAlarm(at, ep.alarmDefaultsRepository().defaults.first())
        val id = ep.alarmRepository().saveAlarm(alarm)
        ep.alarmScheduler().schedule(alarm.copy(id = id))
        ep.appLogger().log("Widget", "שעמור מהיר נוצר מהווידג'ט ל-${formatDayAndTime(at)}")
        updateAppWidgetState(context, glanceId) { prefs -> prefs[PANEL_OPEN_KEY] = false }
        refreshAllWidgets(context)
    }

    companion object {
        val presetIdKey = ActionParameters.Key<Long>("smartring_widget_preset_id")
    }
}

/**
 * The four global operations, from the panel.
 *
 * Each one is the same call the app's own "שליטה כללית" sheet makes, not a widget-specific
 * reimplementation — including the ordering that matters: `disableAll` reads the active
 * set *before* the write that clears it, or it cancels nothing.
 */
class BulkAlarmAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val op = parameters[opKey] ?: return
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val repository = ep.alarmRepository()
        val scheduler = ep.alarmScheduler()
        when (op) {
            OP_DISABLE_ALL -> {
                val armed = repository.getActiveAlarms()
                repository.disableAll()
                scheduler.cancelAll(armed.map { it.id })
            }
            OP_ENABLE_ALL -> {
                repository.enableAll()
                scheduler.rescheduleAll(repository.getActiveAlarms(), refreshWidgets = false)
            }
            // Freezing keeps the schedule and stops the ringing, which is a different
            // thing from switching off — an alarm comes back from a freeze exactly as it
            // was, with no re-dating and no counter to clear.
            OP_FREEZE_ALL -> {
                val armed = repository.getActiveAlarms()
                repository.freezeAll()
                scheduler.cancelAll(armed.map { it.id })
            }
            OP_UNFREEZE_ALL -> {
                repository.unfreezeAll()
                scheduler.rescheduleAll(repository.getActiveAlarms(), refreshWidgets = false)
            }
        }
        ep.appLogger().log("Widget", "פעולה קבוצתית מהווידג'ט: $op")
        updateAppWidgetState(context, glanceId) { prefs -> prefs[PANEL_OPEN_KEY] = false }
        refreshAllWidgets(context)
    }

    companion object {
        val opKey = ActionParameters.Key<String>("smartring_widget_bulk_op")
        const val OP_DISABLE_ALL = "disable_all"
        const val OP_ENABLE_ALL = "enable_all"
        const val OP_FREEZE_ALL = "freeze_all"
        const val OP_UNFREEZE_ALL = "unfreeze_all"
    }
}

class SmartRingWidgetSmallReceiver  : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetSmall()  }
class SmartRingWidgetMediumReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetMedium() }
class SmartRingWidgetWideReceiver   : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetWide()   }
class SmartRingWidgetLargeReceiver  : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetLarge()  }

/** Re-renders every widget instance of every size. Called immediately after any
 *  alarm mutation (via WidgetRefresher, from AlarmScheduler) and periodically
 *  (via WidgetRefreshWorker) so the next-alarm row doesn't go stale. */
suspend fun refreshAllWidgets(ctx: Context) {
    SmartRingWidgetSmall().updateAll(ctx)
    SmartRingWidgetMedium().updateAll(ctx)
    SmartRingWidgetWide().updateAll(ctx)
    SmartRingWidgetLarge().updateAll(ctx)
}
