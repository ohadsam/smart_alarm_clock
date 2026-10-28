package com.smartring.app.presentation.devices

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.data.db.PendingTransferEntity
import com.smartring.app.domain.model.Alarm
import com.smartring.app.sync.DeviceRole
import com.smartring.app.sync.nfc.NfcReader
import com.smartring.app.sync.platform.BluetoothSupport
import com.smartring.app.sync.platform.PairedDevice
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.decodeSyncAlarm

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    onAddDevice: () -> Unit,
    onEditAlarm: (Long) -> Unit,
    onAddAlarmFor: (String) -> Unit,
    vm: DevicesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val name by vm.deviceName.collectAsStateWithLifecycle()
    val listen by vm.listenInBackground.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val copies by vm.pendingCopies.collectAsStateWithLifecycle()
    val childAlarms by vm.childAlarms.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var unpairing by remember { mutableStateOf<PairedDevice?>(null) }
    var nfcReceive by remember { mutableStateOf(false) }

    // Asking for the Bluetooth permission only when the person turns listening on (or pairs): a
    // permission prompt at startup, for a feature most people never use, would be the wrong order.
    val askListen = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) vm.setListen(true)
    }

    // Reader mode only while this switch is on and this screen is showing.
    val activity = context.findActivity()
    DisposableEffect(nfcReceive) {
        if (nfcReceive && activity != null) {
            val main = Handler(Looper.getMainLooper())
            NfcReader.enable(activity) { offer -> main.post { vm.onNfcOffer(offer); nfcReceive = false } }
        }
        onDispose { if (activity != null) NfcReader.disable(activity) }
    }

    unpairing?.let { d -> UnpairDialog(d, onConfirm = { vm.unpair(d); unpairing = null }, onDismiss = { unpairing = null }) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "חזור") } },
                title = { Text("מכשירים מקושרים", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ThisDeviceCard(
                    name = name,
                    listen = listen,
                    onRename = vm::setDeviceName,
                    onListen = { on ->
                        if (on && !BluetoothSupport.canConnect(context)) askListen.launch(BluetoothSupport.connectPermissions().toTypedArray())
                        else vm.setListen(on)
                    },
                )
            }

            if (copies.isNotEmpty()) {
                item { SectionTitle("שעמורים שמחכים לאישור שלך") }
                items(copies, key = { it.transferId }) { c ->
                    CopyCard(c, onAccept = { vm.acceptCopy(c.transferId) }, onDecline = { vm.declineCopy(c.transferId) })
                }
            }

            item { SectionTitle("מכשירים מקושרים") }
            if (devices.isEmpty()) {
                item {
                    Text(
                        "עוד לא קושר מכשיר. אחרי צימוד, הורה יכול להגדיר שעמורים במכשיר של הילד, " +
                            "ושני מכשירים שווים יכולים לשלוח זה לזה עותקים של שעמורים.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(devices, key = { it.deviceId }) { d ->
                DeviceCard(
                    device = d,
                    isSyncing = d.deviceId in syncing,
                    alarms = childAlarms[d.deviceId].orEmpty(),
                    onEnabled = { vm.setEnabled(d.deviceId, it) },
                    onSync = { vm.syncNow(d) },
                    onUnpair = { unpairing = d },
                    onEditAlarm = onEditAlarm,
                    onAddAlarm = { onAddAlarmFor(d.deviceId) },
                    onToggleAlarm = vm::setChildAlarmEnabled,
                    onDeleteAlarm = vm::deleteChildAlarm,
                )
            }
            item {
                Button(onAddDevice, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("הוסף מכשיר")
                }
            }

            if (NfcReader.isSupported(context)) {
                item {
                    NfcReceiveCard(
                        active = nfcReceive,
                        nfcOn = NfcReader.isEnabled(context),
                        onToggle = { nfcReceive = it },
                        onOpenSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

@Composable
private fun ThisDeviceCard(name: String, listen: Boolean, onRename: (String) -> Unit, onListen: (Boolean) -> Unit) {
    var text by remember(name) { mutableStateOf(name) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("המכשיר הזה", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(24) },
                label = { Text("שם המכשיר") },
                supportingText = { Text("כך המכשיר יוצג אצל מכשירים מקושרים") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (text.isNotBlank() && text.trim() != name) {
                        IconButton({ onRename(text) }) { Icon(Icons.Rounded.Check, "שמור שם") }
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("האזנה ברקע", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "כדי לקבל שעמורים מהורה גם כשהאפליקציה סגורה. מציג התראה קבועה קטנה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(listen, onListen)
            }
        }
    }
}

private fun roleLabel(d: PairedDevice) = when (d.myRole) {
    DeviceRole.PARENT -> "הילד שלך"
    DeviceRole.CHILD -> "ההורה שלך"
    DeviceRole.PEER -> "מכשיר שווה"
}

@Composable
private fun DeviceCard(
    device: PairedDevice,
    isSyncing: Boolean,
    alarms: List<Alarm>,
    onEnabled: (Boolean) -> Unit,
    onSync: () -> Unit,
    onUnpair: () -> Unit,
    onEditAlarm: (Long) -> Unit,
    onAddAlarm: () -> Unit,
    onToggleAlarm: (Alarm, Boolean) -> Unit,
    onDeleteAlarm: (Alarm) -> Unit,
) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(roleLabel(device), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Switch(
                    device.isEnabled, onEnabled,
                    modifier = Modifier.semantics { contentDescription = "מותר לתקשר עם ${device.name}" },
                )
            }
            if (!device.isEnabled) {
                Text("מושבת — המכשיר הזה לא יתקשר איתך עד שתפעיל שוב", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val status = when {
                isSyncing -> "מסנכרן…"
                device.lastError != null -> "הסנכרון האחרון נכשל: ${device.lastError}"
                device.lastSyncAt != null -> "סונכרן לאחרונה ${DateUtils.formatDateTime(context, device.lastSyncAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)}"
                else -> "טרם סונכרן"
            }
            Text(status, style = MaterialTheme.typography.bodySmall,
                color = if (device.lastError != null && !isSyncing) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (device.myRole != DeviceRole.CHILD && device.isEnabled) {
                    FilledTonalButton(onSync, enabled = !isSyncing, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("סנכרן עכשיו")
                    }
                }
                TextButton(onUnpair) { Text("בטל צימוד", color = MaterialTheme.colorScheme.error) }
            }

            if (device.myRole == DeviceRole.PARENT) {
                HorizontalDivider()
                Text("שעמורים עבור ${device.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (alarms.isEmpty()) {
                    Text("עוד לא הגדרת שעמור לילד הזה.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                alarms.forEach { a -> ChildAlarmRow(a, onEditAlarm, onToggleAlarm, onDeleteAlarm) }
                OutlinedButton(onAddAlarm, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("הוסף שעמור ל${device.name}")
                }
            }
        }
    }
}

@Composable
private fun ChildAlarmRow(
    alarm: Alarm,
    onEdit: (Long) -> Unit,
    onToggle: (Alarm, Boolean) -> Unit,
    onDelete: (Alarm) -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable { onEdit(alarm.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${alarm.timeFormatted}  ${alarm.name}", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(alarm.scheduleSummary(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            // The honest state of delivery: an edit made while the child is out of range has not
            // reached it, and saying "saved" would imply it has.
            Text(
                if (alarm.sync.isAwaitingDelivery) "ממתין לשליחה למכשיר" else "נמסר",
                style = MaterialTheme.typography.labelSmall,
                color = if (alarm.sync.isAwaitingDelivery) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            alarm.isEnabled, { onToggle(alarm, it) },
            modifier = Modifier.semantics { contentDescription = "שעמור ${alarm.name} בשעה ${alarm.timeFormatted}, ${if (alarm.isEnabled) "פעיל" else "כבוי"}" },
        )
        IconButton({ onDelete(alarm) }) {
            Icon(Icons.Rounded.DeleteOutline, "מחק את ${alarm.name}", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CopyCard(copy: PendingTransferEntity, onAccept: () -> Unit, onDecline: () -> Unit) {
    // Decoded here only to describe it; the real validation happens again on accept.
    val preview = remember(copy.transferId) {
        try { decodeSyncAlarm(copy.payload).let { "%02d:%02d  %s".format(it.hour, it.minute, it.name) } }
        catch (e: ProtocolException) { "שעמור לא תקין" }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${copy.fromDeviceName} שולח לך שעמור", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(preview, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onAccept) { Text("הוסף לשעמורים שלי") }
                TextButton(onDecline) { Text("דחה") }
            }
        }
    }
}

@Composable
private fun NfcReceiveCard(active: Boolean, nfcOn: Boolean, onToggle: (Boolean) -> Unit, onOpenSettings: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("קבלת שעמור במגע (NFC)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "כדי לקבל שעמור ממכשיר שליד: לחצו כאן, ואז הצמידו את המכשירים גב אל גב. השעמור לא נוסף " +
                    "עד שתאשרו אותו, ומסומן כשעמור שהתקבל ב-NFC (השולח לא מאומת).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!nfcOn) {
                TextButton(onOpenSettings) { Text("NFC כבוי — פתח הגדרות") }
            } else if (active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("ממתין למכשיר…", Modifier.weight(1f))
                    TextButton({ onToggle(false) }) { Text("בטל") }
                }
            } else {
                OutlinedButton({ onToggle(true) }) { Text("קבל שעמור") }
            }
        }
    }
}

@Composable
private fun UnpairDialog(device: PairedDevice, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("לבטל את הצימוד עם ${device.name}?") },
        text = {
            Text(
                when (device.myRole) {
                    DeviceRole.PARENT -> "השעמורים שהגדרת עבור ${device.name} יימחקו מכאן. אם המכשיר בטווח, הוא יקבל הודעה, " +
                        "והשעמורים שהגדרת יחזרו להיות שלו כשעמורים רגילים."
                    DeviceRole.CHILD -> "השעמורים ש-${device.name} הגדיר לך יישארו, אבל יפסיקו להיות נעולים — הם יהיו שלך."
                    DeviceRole.PEER -> "עותקים שכבר קיבלתם יישארו אצלכם. שעמורים שממתינים לאישור יימחקו."
                } + "\n\nכדי לחבר שוב צריך קוד חדש."
            )
        },
        confirmButton = { TextButton(onConfirm) { Text("בטל צימוד", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onDismiss) { Text("השאר") } },
    )
}
