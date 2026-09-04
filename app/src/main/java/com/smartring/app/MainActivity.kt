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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        alarmTrigger = 0L to (intent?.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L) ?: -1L)
        setContent {
            val vm: SettingsViewModel = hiltViewModel()
            val settings by vm.state.collectAsState()
            val dark = when (settings.themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            SmartRingTheme(darkTheme = dark) {
                SmartRingNavGraph(alarmTrigger = alarmTrigger)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // launchMode="singleTop" means a new alarm firing while this Activity is
        // already on top arrives here instead of onCreate; without this override the
        // ring screen never showed for that alarm.
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L)
        if (id > 0L) alarmTrigger = (alarmTrigger.first + 1) to id
    }
}
