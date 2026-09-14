package com.smartring.app.presentation.widget
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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

/**
 * The colors one widget renders with. Previously a single hard-coded near-black
 * palette, which made every widget a dark slab on a light home screen no matter what
 * the device was set to.
 *
 * Resolved from the night-mode configuration at render time rather than through a
 * Glance day/night ColorProvider, because the same values also drive the embedded
 * TextClock's RemoteViews color, which is a plain ARGB int and can't take a
 * ColorProvider — one source for both keeps the clock and the Glance content from
 * ending up on opposite sides of the theme. Home-screen widgets conventionally follow
 * the *system* theme rather than an in-app preference, and that is what this does;
 * the app's own auto/dark/light setting governs only the app's own screens.
 */
internal data class WidgetPalette(
    val frameBorder: Color,
    val background: Color,
    val accentBlue: Color,
    val accentGreen: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textSecondary: Color,
    val rowBackground: Color,
)

internal val DarkWidgetPalette = WidgetPalette(
    frameBorder   = Color(0x405B8DF6),
    background    = Color(0xEE13161E),
    accentBlue    = Color(0xFF5B8DF6),
    accentGreen   = Color(0xFF5BF6B0),
    textPrimary   = Color(0xFFFFFFFF),
    textMuted     = Color(0xFF6E7A96),
    textSecondary = Color(0xFF8A94AE),
    rowBackground = Color(0x1AFFFFFF),
)

// Deliberately not the dark palette's accents re-used on a pale ground: #5BF6B0 (a
// light mint) and #5B8DF6 as text on near-white both fail contrast. These are the
// same hues darkened, matching the app's own Light color scheme.
internal val LightWidgetPalette = WidgetPalette(
    frameBorder   = Color(0x403B5FD4),
    background    = Color(0xF2F7F8FC),
    accentBlue    = Color(0xFF3B5FD4),
    accentGreen   = Color(0xFF0E7A5A),
    textPrimary   = Color(0xFF0A0C10),
    textMuted     = Color(0xFF5A6478),
    textSecondary = Color(0xFF424C63),
    rowBackground = Color(0x14000000),
)

internal fun isNightMode(ctx: Context): Boolean =
    (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

internal fun paletteFor(ctx: Context): WidgetPalette =
    if (isNightMode(ctx)) DarkWidgetPalette else LightWidgetPalette

/** Thin, subtle accent-tinted border around every widget — a 1dp gap between an
 *  outer box painted in the border color and an inner box painted in the normal
 *  widget background, rather than relying on a Glance border() modifier (version
 *  support for one is inconsistent), so this works on any Glance release. */
@Composable
private fun WidgetFrame(ctx: Context, palette: WidgetPalette, content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize().background(palette.frameBorder).cornerRadius(20.dp)
        .then(openAppModifier(ctx))) {
        Box(GlanceModifier.fillMaxSize().padding(1.dp).background(palette.background).cornerRadius(19.dp)) {
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
            WidgetFrame(ctx, p) {
                Column(GlanceModifier.fillMaxSize().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveClock(ctx, 11f, p.textSecondary.toArgb())
                    Spacer(GlanceModifier.height(2.dp))
                    Text(next?.timeText ?: "--:--",
                        style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ColorProvider(p.textPrimary)))
                    Text(next?.let { formatCountdownUntil(it.fireAt) } ?: "אין שעמור",
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
            WidgetFrame(ctx, p) {
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
                        Text(next?.alarm?.name ?: "אין שעמור",
                            style = TextStyle(fontSize = 11.sp, color = ColorProvider(p.textMuted)),
                            modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                        next?.let {
                            Text(formatCountdownUntil(it.fireAt),
                                style = TextStyle(fontSize = 10.sp, color = ColorProvider(p.accentBlue)), maxLines = 1)
                        }
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
            WidgetFrame(ctx, p) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ ${alarms.size} פעילים", style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentBlue)),
                            modifier = GlanceModifier.defaultWeight())
                        LiveClock(ctx, 10f, p.textSecondary.toArgb())
                    }
                    next?.let {
                        Text("הבא ${formatCountdownUntil(it.fireAt)}",
                            style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentGreen)))
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
            WidgetFrame(ctx, p) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ SMARTRING · ${alarms.size} פעילים", style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentBlue)),
                            modifier = GlanceModifier.defaultWeight())
                        LiveClock(ctx, 10f, p.textSecondary.toArgb())
                    }
                    next?.let {
                        Text("הבא ${formatCountdownUntil(it.fireAt)}",
                            style = TextStyle(fontSize = 9.sp, color = ColorProvider(p.accentGreen)))
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
        .background(palette.rowBackground).cornerRadius(10.dp),
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
