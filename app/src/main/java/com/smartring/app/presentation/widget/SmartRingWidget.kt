package com.smartring.app.presentation.widget
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint @InstallIn(SingletonComponent::class)
interface WidgetEntryPoint { fun alarmRepository(): AlarmRepository }

abstract class SmartRingBaseWidget : GlanceAppWidget() {
    protected suspend fun activeAlarms(ctx: Context): List<Alarm> =
        EntryPointAccessors.fromApplication(ctx, WidgetEntryPoint::class.java)
            .alarmRepository().getActiveAlarms()
}

class SmartRingWidgetSmall : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val next = activeAlarms(ctx).firstOrNull()
        provideContent {
            Box(GlanceModifier.fillMaxSize().background(Color(0xEE13161E)).cornerRadius(20.dp)) {
                Column(GlanceModifier.fillMaxSize().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⏰", TextStyle(fontSize = 18.sp))
                    Text(next?.timeFormatted ?: "--:--",
                        TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ColorProvider(Color.White)))
                    Text(next?.name ?: "אין שעמור",
                        TextStyle(fontSize = 10.sp, color = ColorProvider(Color(0xFF6E7A96))), maxLines = 1)
                }
            }
        }
    }
}

class SmartRingWidgetMedium : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val alarms = activeAlarms(ctx); val next = alarms.firstOrNull()
        provideContent {
            Box(GlanceModifier.fillMaxSize().background(Color(0xEE13161E)).cornerRadius(20.dp)) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Row(GlanceModifier.fillMaxWidth()) {
                        Text("⏰ SMARTRING", TextStyle(fontSize = 9.sp, color = ColorProvider(Color(0xFF5B8DF6))),
                            modifier = GlanceModifier.defaultWeight())
                        Text("${alarms.size} פעילים", TextStyle(fontSize = 9.sp, color = ColorProvider(Color(0xFF5BF6B0))))
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    Text(next?.timeFormatted ?: "--:--",
                        TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = ColorProvider(Color.White)))
                    Text(next?.name ?: "אין שעמור",
                        TextStyle(fontSize = 11.sp, color = ColorProvider(Color(0xFF6E7A96))), maxLines = 1)
                }
            }
        }
    }
}

class SmartRingWidgetWide : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val alarms = activeAlarms(ctx).take(3)
        provideContent {
            Box(GlanceModifier.fillMaxSize().background(Color(0xEE13161E)).cornerRadius(20.dp)) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Text("⏰ ${alarms.size} פעילים", TextStyle(fontSize = 9.sp, color = ColorProvider(Color(0xFF5B8DF6))))
                    Spacer(GlanceModifier.height(8.dp))
                    alarms.forEach { WidgetAlarmRow(it) }
                }
            }
        }
    }
}

class SmartRingWidgetLarge : SmartRingBaseWidget() {
    override suspend fun provideGlance(ctx: Context, id: GlanceId) {
        val alarms = activeAlarms(ctx).take(4)
        provideContent {
            Box(GlanceModifier.fillMaxSize().background(Color(0xEE13161E)).cornerRadius(20.dp)) {
                Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                    Text("⏰ SMARTRING · ${alarms.size} פעילים", TextStyle(fontSize = 9.sp, color = ColorProvider(Color(0xFF5B8DF6))))
                    Spacer(GlanceModifier.height(8.dp))
                    alarms.forEach { WidgetAlarmRow(it) }
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
        Text(alarm.timeFormatted, TextStyle(fontSize=16.sp, fontWeight=FontWeight.Bold, color=ColorProvider(Color.White)),
            modifier = GlanceModifier.padding(end=10.dp))
        Text(alarm.name, TextStyle(fontSize=11.sp, color=ColorProvider(Color(0xFF8A94AE))),
            modifier = GlanceModifier.defaultWeight(), maxLines=1)
    }
    Spacer(GlanceModifier.height(4.dp))
}

class SmartRingWidgetSmallReceiver  : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetSmall()  }
class SmartRingWidgetMediumReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetMedium() }
class SmartRingWidgetWideReceiver   : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetWide()   }
class SmartRingWidgetLargeReceiver  : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmartRingWidgetLarge()  }
