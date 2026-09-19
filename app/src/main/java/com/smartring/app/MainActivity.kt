package com.smartring.app
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartring.app.presentation.navigation.SmartRingNavGraph
import com.smartring.app.presentation.navigation.WidgetDestination
import com.smartring.app.presentation.settings.SettingsViewModel
import com.smartring.app.presentation.theme.SmartRingTheme
import com.smartring.app.receiver.AlarmReceiver
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // (nonce, alarmId): nonce bumps on every onNewIntent so the nav graph re-navigates
    // to the ring screen even when the same alarm id fires again (e.g. a recurring
    // alarm) while this exact same Activity instance is already on top.
    private var alarmTrigger by mutableStateOf(0L to -1L)

    // Same nonce trick, for the widget's deep links. Tapping the same widget row twice
    // must navigate twice — without the nonce the second tap is an identical value, the
    // state doesn't change, and nothing happens.
    // Explicit type: inferred, `0L to WidgetDestination.None` types the state as
    // Pair<Long, WidgetDestination.None> — the singleton's own type, not the interface —
    // so assigning an Edit to it later does not compile.
    private var widgetDestination: Pair<Long, WidgetDestination> by
        mutableStateOf(0L to WidgetDestination.None)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        alarmTrigger = 0L to (intent?.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L) ?: -1L)
        widgetDestination = 0L to widgetDestinationFrom(intent)
        setContent {
            val vm: SettingsViewModel = hiltViewModel()
            val settings by vm.state.collectAsState()
            val dark = when (settings.themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            SmartRingTheme(darkTheme = dark) {
                SmartRingNavGraph(
                    alarmTrigger = alarmTrigger,
                    widgetDestination = widgetDestination,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // launchMode="singleTop" means a new alarm firing while this Activity is
        // already on top arrives here instead of onCreate; without this override the
        // ring screen never showed for that alarm. A widget tap while the app is open
        // arrives the same way, and needs the same treatment.
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L)
        if (id > 0L) alarmTrigger = (alarmTrigger.first + 1) to id
        val dest = widgetDestinationFrom(intent)
        if (dest != WidgetDestination.None) {
            widgetDestination = (widgetDestination.first + 1) to dest
        }
    }

    /**
     * A ringing alarm outranks a widget tap.
     *
     * Both can be present at once — the widget's Intent could in principle be delivered
     * while an alarm is firing — and there is no contest about which the user needs to
     * see. The nav graph refuses to act on a widget destination while it is showing the
     * ring screen for the same reason.
     */
    private fun widgetDestinationFrom(intent: Intent?): WidgetDestination {
        if (intent == null) return WidgetDestination.None
        if (intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L) > 0L) return WidgetDestination.None
        val editId = intent.getLongExtra(EXTRA_WIDGET_EDIT_ALARM_ID, -1L)
        return when {
            editId > 0L -> WidgetDestination.Edit(editId)
            intent.getBooleanExtra(EXTRA_WIDGET_ADD_ALARM, false) -> WidgetDestination.Add
            else -> WidgetDestination.None
        }
    }

    companion object {
        /** Widget header "+": open the editor on a brand-new alarm. */
        const val EXTRA_WIDGET_ADD_ALARM = "smartring.widget.add_alarm"

        /** Widget row tap: open the editor on that alarm. */
        const val EXTRA_WIDGET_EDIT_ALARM_ID = "smartring.widget.edit_alarm_id"
    }
}
