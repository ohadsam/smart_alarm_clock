package com.smartring.app.presentation.navigation
import androidx.compose.runtime.*
import androidx.navigation.*
import androidx.navigation.compose.*
import com.smartring.app.presentation.alarmedit.AlarmEditScreen
import com.smartring.app.presentation.alarmlist.AlarmListScreen
import com.smartring.app.presentation.alarmring.AlarmRingScreen
import com.smartring.app.presentation.history.HistoryScreen
import com.smartring.app.presentation.settings.SettingsScreen

sealed class Screen(val route: String) {
    object List     : Screen("list")
    object Edit     : Screen("edit/{alarmId}") { fun go(id: Long) = "edit/$id" }
    object Ring     : Screen("ring/{alarmId}") { fun go(id: Long) = "ring/$id" }
    object History  : Screen("history")
    object Settings : Screen("settings")
}

@Composable
fun SmartRingNavGraph(alarmTrigger: Pair<Long, Long> = 0L to -1L) {
    val nav = rememberNavController()
    val (initialNonce, initialAlarmId) = alarmTrigger
    val start = if (initialAlarmId > 0L) Screen.Ring.go(initialAlarmId) else Screen.List.route

    // The start destination already handles the very first alarm id; only re-navigate
    // when a *new* trigger (a higher nonce) arrives, e.g. via MainActivity.onNewIntent
    // while this nav graph is already showing.
    var lastHandledNonce by remember { mutableStateOf(initialNonce) }
    LaunchedEffect(alarmTrigger) {
        val (nonce, id) = alarmTrigger
        if (id > 0L && nonce != lastHandledNonce) {
            nav.navigate(Screen.Ring.go(id))
            lastHandledNonce = nonce
        }
    }

    NavHost(nav, start) {
        composable(Screen.List.route) {
            AlarmListScreen(
                onAddAlarm    = { nav.navigate(Screen.Edit.go(0L)) },
                onEditAlarm   = { nav.navigate(Screen.Edit.go(it)) },
                onOpenHistory = { nav.navigate(Screen.History.route) },
                onOpenSettings= { nav.navigate(Screen.Settings.route) },
            )
        }
        composable(Screen.Edit.route, listOf(navArgument("alarmId") { type = NavType.LongType })) {
            AlarmEditScreen(alarmId = it.arguments?.getLong("alarmId") ?: 0L, onBack = { nav.popBackStack() })
        }
        composable(Screen.Ring.route, listOf(navArgument("alarmId") { type = NavType.LongType })) {
            AlarmRingScreen(alarmId = it.arguments?.getLong("alarmId") ?: 0L, onDismiss = { nav.popBackStack() })
        }
        composable(Screen.History.route) {
            HistoryScreen(
                onBack = { nav.popBackStack() },
                onLoadAlarm = { name, hour, minute ->
                    // Navigate to new alarm pre-named from history
                    nav.navigate(Screen.Edit.go(0L))
                    // Note: pre-fill handled by AlarmEditViewModel via savedStateHandle
                    // For now, navigate to new alarm screen (improvement for v2)
                },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
