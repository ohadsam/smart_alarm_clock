package com.smartring.app.presentation.widget
import android.content.Context
import android.content.Intent
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
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.AlarmScheduler
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

private val FrameBorder = Color(0x405B8DF6)
private val WidgetBg    = Color(0xEE13161E)
private val AccentBlue  = Color(0xFF5B8DF6)
private val AccentGreen = Color(0xFF5BF6B0)
private val TextWhite   = Color.White
private val TextMuted   = Color(0xFF6E7A96)
private val TextMuted2  = Color(0xFF8A94AE)

/** Thin, subtle accent-tinted border around every widget — a 1dp gap between an
 *  outer box painted in the border color and an inner box painted in the normal
 *  widget background, rather than relying on a Glance border() modifier (version
 *  support for one is inconsistent), so this works on any Glance release. */
@Composable
private fun WidgetFrame(ctx: Context, content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize().background(FrameBorder).cornerRadius(20.dp).then(openAppModifier(ctx))) {
        Box(GlanceModifier.fillMaxSize().padding(1.dp).background(WidgetBg).cornerRadius(19.dp)) {
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
     *  real next one to ring, not just the earliest time-of-day in the list. */
    protected suspend fun upcomingAlarms(ctx: Context): List<Pair<Alarm, Long>> {
        val ep = EntryPointAccessors.fromApplication(ctx, WidgetEntryPoint::class.java)
        val scheduler = ep.alarmScheduler()
        return ep.alarmRepository().getActiveAlarms()
            .mapNotNull { a ->
                // A still-armed snooze counts even once the regular recurrence has
                // expired (this could be a COUNT-limited alarm's very last, snoozed
                // occurrence) — only fall back to the regular schedule otherwise.
                val t = scheduler.pendingSnoozeUntil(a)
                    ?: if (a.isRecurrenceExpired()) null else scheduler.nextFireTime(a)
                t?.let { a to it }
            }
            .sortedBy { it.second }
    }
}

class SmartRingWidgetSmall : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val next = upcomingAlarms(ctx).firstOrNull()
        provideContent {
            WidgetFrame(ctx) {
                Column(GlanceModifier.fillMaxSize().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveClock(ctx, 11f, TextMuted2.toArgb())
                    Spacer(GlanceModifier.height(2.dp))
                    Text(next?.first?.timeFormatted ?: "--:--",
                        style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ColorProvider(TextWhite)))
                    Text(next?.let { formatCountdownUntil(it.second) } ?: "אין שעמור",
                        style = TextStyle(fontSize = 9.sp, color = ColorProvider(AccentBlue)), maxLines = 1)
                }
            }
        }
    }
}

class SmartRingWidgetMedium : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val alarms = upcomingAlarms(ctx); val next = alarms.firstOrNull()
        provideContent {
            WidgetFrame(ctx) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ SMARTRING", style = TextStyle(fontSize = 9.sp, color = ColorProvider(AccentBlue)),
                            modifier = GlanceModifier.defaultWeight())
                        LiveClock(ctx, 11f, TextMuted2.toArgb())
                        Spacer(GlanceModifier.width(8.dp))
                        Text("${alarms.size} פעילים", style = TextStyle(fontSize = 9.sp, color = ColorProvider(AccentGreen)))
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    Text(next?.first?.timeFormatted ?: "--:--",
                        style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = ColorProvider(TextWhite)))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(next?.first?.name ?: "אין שעמור",
                            style = TextStyle(fontSize = 11.sp, color = ColorProvider(TextMuted)),
                            modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                        next?.let {
                            Text(formatCountdownUntil(it.second),
                                style = TextStyle(fontSize = 10.sp, color = ColorProvider(AccentBlue)), maxLines = 1)
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
        provideContent {
            WidgetFrame(ctx) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ ${alarms.size} פעילים", style = TextStyle(fontSize = 9.sp, color = ColorProvider(AccentBlue)),
                            modifier = GlanceModifier.defaultWeight())
                        LiveClock(ctx, 10f, TextMuted2.toArgb())
                    }
                    next?.let {
                        Text("הבא ${formatCountdownUntil(it.second)}",
                            style = TextStyle(fontSize = 9.sp, color = ColorProvider(AccentGreen)))
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    alarms.take(3).forEach { WidgetAlarmRow(it.first) }
                }
            }
        }
    }
}

class SmartRingWidgetLarge : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val alarms = upcomingAlarms(ctx); val next = alarms.firstOrNull()
        provideContent {
            WidgetFrame(ctx) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ SMARTRING · ${alarms.size} פעילים", style = TextStyle(fontSize = 9.sp, color = ColorProvider(AccentBlue)),
                            modifier = GlanceModifier.defaultWeight())
                        LiveClock(ctx, 10f, TextMuted2.toArgb())
                    }
                    next?.let {
                        Text("הבא ${formatCountdownUntil(it.second)}",
                            style = TextStyle(fontSize = 9.sp, color = ColorProvider(AccentGreen)))
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    alarms.take(4).forEach { WidgetAlarmRow(it.first) }
                }
            }
        }
    }
}

@Composable
private fun WidgetAlarmRow(alarm: Alarm) {
    Row(GlanceModifier.fillMaxWidth().padding(horizontal=8.dp,vertical=5.dp)
        .background(Color(0x1AFFFFFF)).cornerRadius(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(alarm.timeFormatted, style = TextStyle(fontSize=16.sp, fontWeight=FontWeight.Bold, color=ColorProvider(TextWhite)),
            modifier = GlanceModifier.padding(end=10.dp))
        Text(alarm.name, style = TextStyle(fontSize=11.sp, color=ColorProvider(TextMuted2)),
            modifier = GlanceModifier.defaultWeight(), maxLines=1)
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
