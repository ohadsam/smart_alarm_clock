package com.smartring.app.presentation.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.domain.model.AlarmLog
import com.smartring.app.presentation.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onLoadAlarm: ((name: String, hour: Int?, minute: Int?) -> Unit)? = null,
    vm: HistoryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "חזור") } },
                title          = { Text("היסטוריה", fontWeight = FontWeight.ExtraBold) },
                actions        = {
                    if (state.logs.isNotEmpty()) {
                        IconButton({ showDeleteAllDialog = true }) {
                            Icon(Icons.Rounded.DeleteSweep, "מחק הכל", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        when {
            state.isLoading -> Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.logs.isEmpty() -> Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.History, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("אין היסטוריה עדיין", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text("השעמורים שיצלצלו יופיעו כאן",
                        style = MaterialTheme.typography.bodyMedium,
                        color  = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> LazyColumn(
                Modifier.padding(pad),
                contentPadding        = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp),
            ) {
                items(state.logs, key = { it.id }) { log ->
                    HistoryLogCard(log, onDelete = { vm.deleteLog(log.id) }, onLoad = onLoadAlarm)
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title            = { Text("מחק את כל ההיסטוריה") },
            text             = { Text("פעולה זו תמחק את כל הרשומות לצמיתות ולא ניתן לשחזר.") },
            confirmButton    = {
                TextButton(onClick = { vm.deleteAll(); showDeleteAllDialog = false }) {
                    Text("מחק הכל", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton    = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("ביטול") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryLogCard(log: AlarmLog, onDelete: () -> Unit, onLoad: ((String, Int?, Int?) -> Unit)?) {
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    var showDelete by remember { mutableStateOf(false) }

    val (actionColor, actionLabel, actionIcon) = when (log.action) {
        "STOPPED" -> Triple(Green, "נעצר", Icons.Rounded.CheckCircle)
        "SNOOZED" -> Triple(Gold,  "נודניק", Icons.Rounded.Bedtime)
        "MISSED"  -> Triple(Red,   "פוספס", Icons.Rounded.ErrorOutline)
        else      -> Triple(Blue,  "הופעל", Icons.Rounded.Alarm)
    }

    Surface(
        modifier      = Modifier.fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { showDelete = true }),
        shape         = RoundedCornerShape(14.dp),
        color         = MaterialTheme.colorScheme.surface,
        border        = BorderStroke(1.dp, actionColor.copy(.25f)),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Action icon circle
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(actionColor.copy(.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(actionIcon, null, Modifier.size(20.dp), tint = actionColor)
            }
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(log.alarmName.ifBlank { "שעמור לא ידוע" },
                    fontWeight = FontWeight.Bold,
                    style      = MaterialTheme.typography.bodyLarge,
                    maxLines   = 1)
                Spacer(Modifier.height(2.dp))
                Text(fmt.format(Date(log.firedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Surface(
                shape  = RoundedCornerShape(999.dp),
                color  = actionColor.copy(.1f),
                border = BorderStroke(1.dp, actionColor.copy(.3f)),
            ) {
                Text(actionLabel,
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style      = MaterialTheme.typography.labelSmall,
                    color      = actionColor,
                    fontWeight = FontWeight.ExtraBold)
            }
        }
    }
    // Hint for long-press + optional load button
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("לחיצה ארוכה למחיקה",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        if (onLoad != null) {
            TextButton(
                onClick = {
                    // scheduledFor only reliably holds the alarm's actual configured fire
                    // time on a FIRED row — STOPPED/SNOOZED/MISSED rows log it as the
                    // moment of that action instead, which can be minutes after the real
                    // fire time. Only trust it for FIRED; otherwise just carry the name.
                    if (log.action == "FIRED") {
                        val cal = Calendar.getInstance().apply { timeInMillis = log.scheduledFor }
                        onLoad(log.alarmName, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                    } else {
                        onLoad(log.alarmName, null, null)
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Rounded.Replay, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("טען שוב", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title            = { Text("מחק רשומה") },
            text             = { Text("מחק את הרשומה של \"${log.alarmName}\"?") },
            confirmButton    = {
                TextButton(onClick = { showDelete = false; onDelete() }) {
                    Text("מחק", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton    = { TextButton({ showDelete = false }) { Text("ביטול") } },
        )
    }
}
