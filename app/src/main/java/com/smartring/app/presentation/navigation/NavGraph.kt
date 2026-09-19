package com.smartring.app.presentation.navigation
import android.net.Uri
import androidx.compose.runtime.*
import androidx.navigation.*
import androidx.navigation.compose.*
import com.smartring.app.presentation.alarmedit.AlarmEditScreen
import com.smartring.app.presentation.alarmlist.AlarmListScreen
import com.smartring.app.presentation.alarmring.AlarmRingScreen
import com.smartring.app.presentation.diagnosis.DiagnosisScreen
import com.smartring.app.presentation.history.HistoryScreen
import com.smartring.app.presentation.logs.LogsScreen
import com.smartring.app.presentation.settings.SettingsScreen

sealed class Screen(val route: String) {
    object List     : Screen("list")
    // name/hour/minute are optional prefill values for a brand-new alarm (id=0),
    // used by History's "load again" action; a real edit (id>0) ignores them.
    object Edit     : Screen("edit/{alarmId}?name={name}&hour={hour}&minute={minute}&copyOf={copyOf}") {
        fun go(id: Long, name: String? = null, hour: Int? = null, minute: Int? = null): String {
            val params = buildList {
                name?.let { add("name=${Uri.encode(it)}") }
                hour?.let { add("hour=$it") }
                minute?.let { add("minute=$it") }
            }
            return "edit/$id" + if (params.isEmpty()) "" else "?${params.joinToString("&")}"
        }

        /**
         * Duplicating opens the editor on a *new, unsaved* alarm copied from [sourceId],
         * rather than writing a copy straight to the database.
         *
         * That is what makes duplicating a finished one-time alarm behave: the copy
         * arrives with the original's past date, and save() refuses a date in the past —
         * so the user is required to pick a future one before the copy can exist at all.
         * Writing the copy directly would have produced a second alarm that could never
         * ring, which is the bug this release is mostly about.
         */
        fun duplicate(sourceId: Long) = "edit/0?copyOf=$sourceId"
    }
    object Ring     : Screen("ring/{alarmId}") { fun go(id: Long) = "ring/$id" }
    object History  : Screen("history")
    object Settings : Screen("settings")
    object Logs     : Screen("logs")
    object Diagnosis: Screen("diagnosis")
}

/**
 * Where a widget tap wants to land.
 *
 * The widget used to have exactly one destination for every part of it — the alarm list —
 * which is one answer to three different questions. A row means "this alarm", the header
 * "+" means "a new one", and the title still means "everything".
 */
sealed interface WidgetDestination {
    object None : WidgetDestination
    object Add : WidgetDestination
    data class Edit(val alarmId: Long) : WidgetDestination
}

@Composable
fun SmartRingNavGraph(
    alarmTrigger: Pair<Long, Long> = 0L to -1L,
    widgetDestination: Pair<Long, WidgetDestination> = 0L to WidgetDestination.None,
) {
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

    // Widget deep links. Nonce-gated exactly like the alarm trigger above, so tapping the
    // same row twice navigates twice; without it the second tap carries an identical
    // value, the state never changes, and nothing happens.
    //
    // Never while an alarm is ringing: the widget's Intent could arrive at the same
    // moment, and nothing the widget offers outranks a ringing alarm. Navigating away
    // would also strand the ring screen's Stop button.
    var lastWidgetNonce by remember { mutableStateOf(-1L) }
    LaunchedEffect(widgetDestination, initialAlarmId) {
        val (nonce, dest) = widgetDestination
        if (dest == WidgetDestination.None || nonce == lastWidgetNonce) return@LaunchedEffect
        if (initialAlarmId > 0L) return@LaunchedEffect
        lastWidgetNonce = nonce
        val route = when (dest) {
            is WidgetDestination.Edit -> Screen.Edit.go(dest.alarmId)
            WidgetDestination.Add     -> Screen.Edit.go(0L)
            WidgetDestination.None    -> return@LaunchedEffect
        }
        // launchSingleTop so a repeated tap on the same row re-uses the editor already
        // open for it rather than stacking a second copy behind it.
        nav.navigate(route) { launchSingleTop = true }
    }

    NavHost(nav, start) {
        composable(Screen.List.route) {
            AlarmListScreen(
                onAddAlarm    = { nav.navigate(Screen.Edit.go(0L)) },
                onEditAlarm   = { nav.navigate(Screen.Edit.go(it)) },
                onDuplicateAlarm = { nav.navigate(Screen.Edit.duplicate(it)) },
                onOpenHistory = { nav.navigate(Screen.History.route) },
                onOpenSettings= { nav.navigate(Screen.Settings.route) },
            )
        }
        composable(Screen.Edit.route, listOf(
            navArgument("alarmId") { type = NavType.LongType },
            navArgument("name")   { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("hour")   { type = NavType.IntType; defaultValue = -1 },
            navArgument("minute") { type = NavType.IntType; defaultValue = -1 },
            navArgument("copyOf") { type = NavType.LongType; defaultValue = 0L },
        )) {
            AlarmEditScreen(
                alarmId       = it.arguments?.getLong("alarmId") ?: 0L,
                prefillName   = it.arguments?.getString("name"),
                prefillHour   = it.arguments?.getInt("hour")?.takeIf { h -> h >= 0 },
                prefillMinute = it.arguments?.getInt("minute")?.takeIf { m -> m >= 0 },
                copyOfAlarmId = it.arguments?.getLong("copyOf")?.takeIf { c -> c > 0L } ?: 0L,
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
                onOpenDiagnosis = { nav.navigate(Screen.Diagnosis.route) },
            )
        }
        composable(Screen.Logs.route) {
            LogsScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.Diagnosis.route) {
            DiagnosisScreen(onBack = { nav.popBackStack() })
        }
    }
}
