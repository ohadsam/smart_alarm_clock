package com.smartring.app.presentation.devices

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.domain.model.Alarm
import com.smartring.app.sync.nfc.NfcReader
import kotlinx.coroutines.launch

/**
 * "Send a copy of this alarm to…". A copy, not the alarm: the receiver decides whether to keep it,
 * and what they keep is theirs to change. For managing another phone's alarms there is pairing as a
 * parent; this is for sharing.
 */
@Composable
fun ShareAlarmDialog(alarm: Alarm, onDismiss: () -> Unit, vm: ShareViewModel = hiltViewModel()) {
    val peers by vm.peers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var offering by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { vm.stopOffer() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("שלח עותק של \"${alarm.name}\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                peers.forEach { peer ->
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                status = if (vm.sendCopy(alarm, peer)) "נשלח ל-${peer.name} כשיהיה בטווח" else "אי אפשר לשלוח את השעמור הזה"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(peer.name) }
                }
                if (peers.isEmpty()) {
                    Text("אין מכשיר שווה מקושר. אפשר לצמד מכשיר כ\"שווים\" דרך הגדרות ← מכשירים מקושרים.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (NfcReader.isSupported(context)) {
                    HorizontalDivider()
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                offering = vm.offerOverNfc(alarm)
                                status = if (offering) "הצמידו את המכשירים גב אל גב. במכשיר השני: מכשירים מקושרים ← קבל שעמור."
                                         else "אי אפשר לשלוח את השעמור הזה"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (offering) "מציע במגע…" else "שתף במגע (NFC)") }
                    Text("במגע, המקבל לא יכול לאמת מי שלח — הוא יראה שהשעמור התקבל ב-NFC ויחליט אם לקבל.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("סגור") } },
    )
}
