package com.smartring.app.presentation.navigation
import android.net.Uri
import androidx.compose.runtime.*
import androidx.navigation.*
import androidx.navigation.compose.*
import com.smartring.app.presentation.alarmedit.AlarmEditScreen
import com.smartring.app.presentation.alarmlist.AlarmListScreen
import com.smartring.app.presentation.alarmring.AlarmRingScreen
import com.smartring.app.presentation.history.HistoryScreen
import com.smartring.app.presentation.logs.LogsScreen
import com.smartring.app.presentation.settings.SettingsScreen

sealed class Screen(val route: String) {
    object List     : Screen("list")
    // name/hour/minute are optional prefill values for a brand-new alarm (id=0),
    // used by History's "load again" action; a real edit (id>0) ignores them.
    object Edit     : Screen("edit/{alarmId}?name={name}&hour={hour}&minute={minute}") {
        fun go(id: Long, name: String? = null, hour: Int? = null, minute: Int? = null): String {
            val params = buildList {
                name?.let { add("name=${Uri.encode(it)}") }
                hour?.let { add("hour=$it") }
                minute?.let { add("minute=$it") }
            }
            return "edit/$id" + if (params.isEmpty()) "" else "?${params.joinToString("&")}"
        }
    }
    object Ring     : Screen("ring/{alarmId}") { fun go(id: Long) = "ring/$id" }
    object History  : Screen("history")
    object Settings : Screen("settings")
    object Logs     : Screen("logs")
}

@Composable
fun SmartRingNavGraph(alarmTrigger: Pair<Long, Long> = 0L to -1L) {
    val nav = rememberNavController()
    val (initialNonce, initialAlarmId) = alarmTrigger
    // remember{}, so the graph's start destination is fixed for the life of this
    // composable. NavHost rebuilds (and re-applies) its graph whenever the start
    // destination changes, which resets the back stack — so deriving it from a value
    // that changes meant an alarm firing while the app was open threw away wherever
    // the user was and then navigated to the ring screen twice: once from the rebuilt
    // graph, once from the LaunchedEffect below.
    val start = remember { if (initialAlarmId > 0L) Screen.Ring.go(initialAlarmId) else Screen.List.route }

    // The start destination already handles the very first alarm id; only re-navigate
    // when a *new* trigger (a higher nonce) arrives, e.g. via MainActivity.onNewIntent
    // while this nav graph is already showing.
    var lastHandledNonce by remember { mutableStateOf(initialNonce) }
    LaunchedEffect(alarmTrigger) {
        val (nonce, id) = alarmTrigger
        if (id > 0L && nonce != lastHandledNonce) {
            // Replace any ring screen already showing rather than stacking on it: a
            // second alarm firing while the first one's screen is up left the first
            // one underneath, so dismissing the second revealed a ring screen for an
            // alarm that had already stopped, with live Stop/Snooze buttons. popUpTo
            // is a no-op when there is no ring screen on the stack.
            nav.navigate(Screen.Ring.go(id)) {
                popUpTo(Screen.Ring.route) { inclusive = true }
                launchSingleTop = true
            }
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
        composable(Screen.Edit.route, listOf(
            navArgument("alarmId") { type = NavType.LongType },
            navArgument("name")   { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("hour")   { type = NavType.IntType; defaultValue = -1 },
            navArgument("minute") { type = NavType.IntType; defaultValue = -1 },
        )) {
            AlarmEditScreen(
                alarmId       = it.arguments?.getLong("alarmId") ?: 0L,
                prefillName   = it.arguments?.getString("name"),
                prefillHour   = it.arguments?.getInt("hour")?.takeIf { h -> h >= 0 },
                prefillMinute = it.arguments?.getInt("minute")?.takeIf { m -> m >= 0 },
                onBack        = { nav.popBackStack() },
            )
        }
        composable(Screen.Ring.route, listOf(navArgument("alarmId") { type = NavType.LongType })) {
            AlarmRingScreen(
                alarmId = it.arguments?.getLong("alarmId") ?: 0L,
                // When the alarm woke the app from cold, the ring screen *is* the start
                // destination and there is nothing behind it — popping it emptied the
                // back stack and left the user staring at a blank window after pressing
                // Stop. Fall through to the alarm list in that case instead.
                onDismiss = {
                    if (nav.previousBackStackEntry != null) nav.popBackStack()
                    else nav.navigate(Screen.List.route) {
                        popUpTo(Screen.Ring.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Screen.History.route) {
            HistoryScreen(
                onBack = { nav.popBackStack() },
                onLoadAlarm = { name, hour, minute ->
                    nav.navigate(Screen.Edit.go(0L, name, hour, minute))
                },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenLogs = { nav.navigate(Screen.Logs.route) },
            )
        }
        composable(Screen.Logs.route) {
            LogsScreen(onBack = { nav.popBackStack() })
        }
    }
}
