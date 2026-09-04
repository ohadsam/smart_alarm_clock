package com.smartring.app
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incomingAlarmId = intent?.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L) ?: -1L
        setContent {
            val vm: SettingsViewModel = hiltViewModel()
            val settings by vm.state.collectAsState()
            val dark = when (settings.themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            SmartRingTheme(darkTheme = dark) {
                SmartRingNavGraph(startAlarmId = incomingAlarmId)
            }
        }
    }
}
