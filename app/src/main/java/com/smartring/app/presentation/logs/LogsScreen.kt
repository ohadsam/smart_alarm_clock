package com.smartring.app.presentation.logs

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.smartring.app.domain.model.AppLogEntry
import java.text.SimpleDateFormat
import java.util.*

// A fresh formatter per call rather than one shared top-level instance:
// SimpleDateFormat is not thread-safe, and this is now also called from a background
// dispatcher (the file export below), where a shared instance would be a real race.
// Also re-reads Locale.getDefault() each time instead of pinning it at class-init.
private fun logTimeFormat() = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

// logs is DESC (newest-first, for on-screen display); exported/copied text reads
// oldest-first, like a conventional log file, so an event sequence follows top-to-bottom.
internal fun logsAsText(logs: List<AppLogEntry>): String {
    val fmt = logTimeFormat()
    return logs.asReversed().joinToString("\n") { "[${fmt.format(Date(it.timestamp))}] ${it.tag}: ${it.message}" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit, vm: LogsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }
    var showCopiedSnackbar by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var exportError by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        // Off the main thread: this callback runs on it, and writing a full log file
        // through a SAF provider (which can be a cloud target, not local storage) is a
        // blocking IO round-trip — long enough to jank, and at the far end an ANR.
        // Wrapped, too: a failed write (revoked permission, no space, provider error)
        // used to throw straight out of the callback and take the app down.
        uri?.let { target ->
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(target)?.use { out ->
                        out.write(logsAsText(s.logs).toByteArray())
                    } ?: error("no output stream")
                }.isSuccess
                withContext(Dispatchers.Main) { exportError = !ok }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "חזור") } },
                title = { Text("לוגים", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    IconButton({
                        clipboard.setText(AnnotatedString(logsAsText(s.logs)))
                        showCopiedSnackbar = true
                    }) { Icon(Icons.Rounded.ContentCopy, "העתק הכל") }
                    IconButton({
                        val name = "smartring-logs-${System.currentTimeMillis()}.txt"
                        // ACTION_CREATE_DOCUMENT needs a documents provider, which a
                        // stripped build may not have; launch() then throws straight out
                        // of this click handler. The copy button still works, so say so
                        // rather than closing the app.
                        exportError = runCatching { saveLauncher.launch(name) }.isFailure
                    }) { Icon(Icons.Rounded.Download, "הורד כקובץ") }
                    if (s.logs.isNotEmpty()) {
                        IconButton({ showClearDialog = true }) {
                            Icon(Icons.Rounded.DeleteSweep, "נקה לוגים", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "רישום טכני של פעולות השעמורים ברקע (תזמון, אתחול, צלצולים) — מתנקה אוטומטית אחרי 3 ימים.",
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (exportError) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3000)
                    exportError = false
                }
                Text("שמירת הקובץ נכשלה", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (showCopiedSnackbar) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    showCopiedSnackbar = false
                }
                Text("הועתק ללוח", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            when {
                s.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                s.logs.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Description, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("אין רישומים עדיין", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(s.logs, key = { it.id }) { log -> LogRow(log) }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("נקה את כל הלוגים") },
            text  = { Text("פעולה זו תמחק לצמיתות את כל רישומי הלוג.") },
            confirmButton = {
                TextButton({ vm.clearAll(); showClearDialog = false }) {
                    Text("נקה", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton({ showClearDialog = false }) { Text("ביטול") } },
        )
    }
}

@Composable
private fun LogRow(log: AppLogEntry) {
    // Remembered per row rather than shared at file level: SimpleDateFormat is not
    // thread-safe, and the export path now formats on a background dispatcher.
    val rowFmt = remember { logTimeFormat() }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp, 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // The tag is free text supplied by whatever logged the line, so it has no
                // bounded length — it has to yield rather than push the timestamp out.
                Text(log.tag, Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary)
                Text(rowFmt.format(Date(log.timestamp)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(log.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
