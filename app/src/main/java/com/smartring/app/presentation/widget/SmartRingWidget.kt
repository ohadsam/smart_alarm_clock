package com.smartring.app.presentation.widget
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.smartring.app.MainActivity
import com.smartring.app.R
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.UpcomingAlarm
import com.smartring.app.util.buildUpcomingAlarms
import com.smartring.app.util.formatCountdownUntil
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

// Tapping any widget opens the app to the alarm list; previously the widgets had no
// click action at all. Built from an explicit Intent (needs a Context) rather than
// the reified actionStartActivity<T>() helper, whose overload in this Glance version
// requires the Intent argument explicitly.
private fun openAppModifier(ctx: Context) =
    GlanceModifier.clickable(actionStartActivity(Intent(ctx, MainActivity::class.java)))

/** Thickness of the accent frame drawn around every widget. Must match the
 *  difference between widget_frame_border's and widget_frame_inner's corner radii,
 *  or the two curves stop being concentric. */
internal const val WIDGET_BORDER_DP = 2

/**
 * Below this much time remaining, the countdown is rendered as a live Chronometer
 * instead of a static string — see widget_countdown.xml for why. Above it, a value
 * that can be up to one refresh period stale is harmless ("בעוד 8 שע׳" does not
 * change meaningfully in 15 minutes) and not worth a permanently ticking view on
 * someone's home screen.
 */
internal const val LIVE_COUNTDOWN_WINDOW_MS = 60 * 60 * 1000L

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

/**
 * The accent-tinted rounded frame around every widget: an outer box painted with the
 * border drawable, padded by [WIDGET_BORDER_DP], containing the body drawable.
 *
 * The padding belongs on the *outer* box, not the inner one. It used to sit on the
 * inner box, which is a no-op for this purpose — a view's own padding insets its
 * children, not itself, so the inner background still filled the outer box edge to
 * edge and painted over the entire border. The frame was invisible on every device.
 */
@Composable
private fun WidgetFrame(ctx: Context, content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize()
        .background(ImageProvider(R.drawable.widget_frame_border))
        .padding(WIDGET_BORDER_DP.dp)
        .then(openAppModifier(ctx))) {
        Box(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_frame_inner))) {
            content()
        }
    }
}

/** A real android.widget.TextClock embedded via AndroidRemoteViews: it ticks on its
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

/** "How long until it rings" — live to the second inside the last hour, a plain
 *  Hebrew phrase before that. See [LIVE_COUNTDOWN_WINDOW_MS]. */
@Composable
private fun Countdown(ctx: Context, fireAt: Long, sizeSp: Float, color: Color) {
    val remaining = fireAt - System.currentTimeMillis()
    if (remaining in 0..LIVE_COUNTDOWN_WINDOW_MS) {
        AndroidRemoteViews(countdownRemoteViews(ctx, fireAt, sizeSp, color.toArgb()))
    } else {
        Text(formatCountdownUntil(fireAt),
            style = TextStyle(fontSize = sizeSp.sp, color = ColorProvider(color)), maxLines = 1)
    }
}

@EntryPoint @InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun alarmRepository(): AlarmRepository
    fun alarmScheduler(): AlarmScheduler
}

abstract class SmartRingBaseWidget : GlanceAppWidget() {
    /** Active alarms paired with their true next-fire epoch millis, soonest first.
     *  Uses AlarmScheduler.nextFireTime — the same recurrence/specific-date-aware
     *  calculation actually used to schedule alarms — rather than the DB's plain
     *  hour/minute ordering, so "the next alarm" (and its countdown) is always the
     *  real next one to ring, not just the earliest time-of-day in the list.
     *
     *  The selection and ordering rules themselves live in the pure
     *  [buildUpcomingAlarms], which is unit-tested; this only supplies the two
     *  lookups it needs. */
    /**
     * "אין שעמור פעיל", not "אין שעמור": the widgets only ever list alarms that are
     * enabled and unfrozen, so a user whose alarms have all rung and retired saw an
     * empty widget that appeared to have lost them. Naming the distinction is the whole
     * difference between "the widget is broken" and "nothing is armed".
     */
    protected suspend fun upcomingAlarms(ctx: Context): List<UpcomingAlarm> {
        val ep = EntryPointAccessors.fromApplication(ctx, WidgetEntryPoint::class.java)
        val scheduler = ep.alarmScheduler()
        return buildUpcomingAlarms(
            alarms     = ep.alarmRepository().getActiveAlarms(),
            snoozeAt   = scheduler::pendingSnoozeUntil,
            nextFireAt = { scheduler.nextFireTime(it) },
        )
    }
}

class SmartRingWidgetSmall : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val next = upcomingAlarms(ctx).firstOrNull()
        val p = paletteFor(ctx)
        provideContent {
            WidgetFrame(ctx) {
                Column(GlanceModifier.fillMaxSize().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveClock(ctx, 11f, p.textSecondary.toArgb())
                    Spacer(GlanceModifier.height(2.dp))
                    Text(next?.timeText ?: "--:--",
                        style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ColorProvider(p.textPrimary)))
                    if (next != null) Countdown(ctx, next.fireAt, 9f, p.accentBlue)
                    else Text("אין שעמור פעיל",
                        style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentBlue)), maxLines = 1)
                }
            }
        }
    }
}

class SmartRingWidgetMedium : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val alarms = upcomingAlarms(ctx); val next = alarms.firstOrNull()
        val p = paletteFor(ctx)
        provideContent {
            WidgetFrame(ctx) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ SMARTRING", style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentBlue)),
                            modifier = GlanceModifier.defaultWeight())
                        LiveClock(ctx, 11f, p.textSecondary.toArgb())
                        Spacer(GlanceModifier.width(8.dp))
                        Text("${alarms.size} פעילים", style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentGreen)))
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    Text(next?.timeText ?: "--:--",
                        style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = ColorProvider(p.textPrimary)))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(next?.alarm?.name ?: "אין שעמור פעיל",
                            style = TextStyle(fontSize = 11.sp, color = ColorProvider(p.textSecondary)),
                            modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                        next?.let { Countdown(ctx, it.fireAt, 10f, p.accentBlue) }
                    }
                }
            }
        }
    }
}

class SmartRingWidgetWide : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val alarms = upcomingAlarms(ctx); val next = alarms.firstOrNull()
        val p = paletteFor(ctx)
        provideContent {
            WidgetFrame(ctx) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ ${alarms.size} פעילים", style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentBlue)),
                            modifier = GlanceModifier.defaultWeight())
                        LiveClock(ctx, 10f, p.textSecondary.toArgb())
                    }
                    next?.let {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("הבא ", style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentGreen)))
                            Countdown(ctx, it.fireAt, 9f, p.accentGreen)
                        }
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    alarms.take(3).forEach { WidgetAlarmRow(it, p) }
                }
            }
        }
    }
}

class SmartRingWidgetLarge : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val alarms = upcomingAlarms(ctx); val next = alarms.firstOrNull()
        val p = paletteFor(ctx)
        provideContent {
            WidgetFrame(ctx) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ SMARTRING · ${alarms.size} פעילים", style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentBlue)),
                            modifier = GlanceModifier.defaultWeight())
                        LiveClock(ctx, 10f, p.textSecondary.toArgb())
                    }
                    next?.let {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("הבא ", style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentGreen)))
                            Countdown(ctx, it.fireAt, 9f, p.accentGreen)
                        }
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    alarms.take(4).forEach { WidgetAlarmRow(it, p) }
                }
            }
        }
    }
}

/** One row in the wide/large widgets. Shows the recurrence summary (the same one the
 *  alarm list card shows) beside the name: without it two 07:00 rows — one every
 *  weekday, one a single next-Tuesday reminder — were indistinguishable. */
@Composable
private fun WidgetAlarmRow(upcoming: UpcomingAlarm, palette: WidgetPalette) {
    val alarm = upcoming.alarm
    Row(GlanceModifier.fillMaxWidth().padding(horizontal=8.dp,vertical=5.dp)
        .background(ImageProvider(R.drawable.widget_row_bg)),
        verticalAlignment = Alignment.CenterVertically) {
        Text(upcoming.timeText, style = TextStyle(fontSize=16.sp, fontWeight=FontWeight.Bold, color=ColorProvider(palette.textPrimary)),
            modifier = GlanceModifier.padding(end=10.dp))
        Text(alarm.name, style = TextStyle(fontSize=11.sp, color=ColorProvider(palette.textSecondary)),
            modifier = GlanceModifier.defaultWeight(), maxLines=1)
        Text(if (upcoming.isSnoozed) "נודניק" else alarm.scheduleSummary(),
            style = TextStyle(fontSize=9.sp, color=ColorProvider(palette.accentBlue)), maxLines=1)
    }
    Spacer(GlanceModifier.height(4.dp))
}

class SmartRingWidgetSmallReceiver  : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetSmall()  }
class SmartRingWidgetMediumReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetMedium() }
class SmartRingWidgetWideReceiver   : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetWide()   }
class SmartRingWidgetLargeReceiver  : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetLarge()  }

/** Re-renders every widget instance of every size. Called immediately after any
 *  alarm mutation (via WidgetRefresher, from AlarmScheduler) and periodically
 *  (via WidgetRefreshWorker) so the countdown/next-alarm time don't go stale. */
suspend fun refreshAllWidgets(ctx: Context) {
    SmartRingWidgetSmall().updateAll(ctx)
    SmartRingWidgetMedium().updateAll(ctx)
    SmartRingWidgetWide().updateAll(ctx)
    SmartRingWidgetLarge().updateAll(ctx)
}
