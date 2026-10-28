package com.smartring.app.presentation.devices

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.sync.DeviceRole
import com.smartring.app.sync.nfc.NfcOffer
import com.smartring.app.sync.nfc.NfcReader
import com.smartring.app.sync.pairing.PairingCodes
import com.smartring.app.sync.pairing.PeerSummary
import com.smartring.app.sync.platform.BluetoothSupport
import com.smartring.app.sync.platform.PairingUiState
import kotlinx.coroutines.delay

private enum class Mode { HOST, JOIN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(onBack: () -> Unit, vm: PairViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf<Mode?>(null) }

    LaunchedEffect(state) { if (state is PairingUiState.Done) vm.finished() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton({
                        // Backing out of a pairing in progress abandons it; nothing was stored.
                        when {
                            state is PairingUiState.Idle && mode != null -> mode = null
                            else -> { vm.cancel(); onBack() }
                        }
                    }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "חזור") }
                },
                title = { Text("הוספת מכשיר", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val s = state) {
                PairingUiState.Idle -> when (mode) {
                    null -> ChooseMode { mode = it }
                    Mode.HOST -> HostSetup(vm)
                    Mode.JOIN -> JoinSetup(vm)
                }
                is PairingUiState.Hosting -> HostingView(s, onCancel = { vm.cancel(); mode = null })
                is PairingUiState.Working -> WorkingView(s.message, onCancel = { vm.cancel(); mode = null })
                is PairingUiState.Confirm -> ConfirmView(s.summary, vm::confirm)
                is PairingUiState.Done -> DoneView(s, onDone = { vm.reset(); onBack() })
                is PairingUiState.Failed -> FailedView(s, onRetry = { vm.reset(); mode = null }, onBack = { vm.reset(); onBack() })
            }
        }
    }
}

@Composable
private fun ChooseMode(onPick: (Mode) -> Unit) {
    Text(
        "צימוד דורש שני מכשירים קרובים זה לזה, ואדם ליד כל אחד. מכשיר אחד מציג קוד בן ${PairingCodes.LENGTH} ספרות, " +
            "והשני מקליד אותו. בלי הקוד אי אפשר לחבר, וכל צימוד חדש (גם אחרי ביטול) דורש קוד חדש.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BigChoice("הצג קוד", "במכשיר הזה יוצג קוד. הקלידו אותו במכשיר השני.", Icons.Rounded.Pin) { onPick(Mode.HOST) }
    BigChoice("הקלד קוד", "במכשיר השני מוצג קוד. הקלידו אותו כאן.", Icons.Rounded.Keyboard) { onPick(Mode.JOIN) }
}

@Composable
private fun BigChoice(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── The phone that shows the code ───────────────────────────────────────────

@Composable
private fun HostSetup(vm: PairViewModel) {
    val context = LocalContext.current
    var role by remember { mutableStateOf(DeviceRole.PARENT) }

    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    fun go() {
        vm.startHosting(role)
        // Makes this phone findable by the other one's search. Optional: without it the other phone
        // can still connect if it already knows this one. Asked for after hosting has started so the
        // system dialog can never delay the code appearing.
        runCatching {
            discoverable.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180)
            )
        }
    }
    var afterEnable by remember { mutableStateOf<(() -> Unit)?>(null) }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (BluetoothSupport.isEnabled(context)) afterEnable?.invoke()
        afterEnable = null
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) {
            if (BluetoothSupport.isEnabled(context)) go()
            else { afterEnable = { go() }; enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        }
    }

    Text("מה המכשיר הזה יהיה למכשיר השני?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    RoleOption(DeviceRole.PARENT, role, "הורה",
        "המכשיר הזה יגדיר, ישנה וימחק שעמורים במכשיר השני. השעמורים האלה ינעלו אצלו.") { role = it }
    RoleOption(DeviceRole.CHILD, role, "ילד",
        "המכשיר השני יגדיר שעמורים במכשיר הזה. הם יסומנו ויהיו נעולים כאן.") { role = it }
    RoleOption(DeviceRole.PEER, role, "שווים",
        "אפשר לשלוח זה לזה עותקים של שעמורים. כל צד מחליט אם לקבל.") { role = it }

    Button(
        onClick = {
            val needed = BluetoothSupport.advertisePermissions()
            when {
                !BluetoothSupport.hasAll(context, needed) -> permissions.launch(needed.toTypedArray())
                !BluetoothSupport.isEnabled(context) -> { afterEnable = { go() }; enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                else -> go()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("הצג קוד") }
}

@Composable
private fun RoleOption(value: DeviceRole, selected: DeviceRole, title: String, text: String, onPick: (DeviceRole) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable { onPick(value) },
        shape = RoundedCornerShape(14.dp),
        color = if (value == selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            RadioButton(value == selected, { onPick(value) })
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HostingView(s: PairingUiState.Hosting, onCancel: () -> Unit) {
    var left by remember(s.expiresAtMillis) { mutableStateOf((s.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0)) }
    LaunchedEffect(s.expiresAtMillis) {
        while (left > 0) { delay(1_000); left = (s.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0) }
    }
    Text("הקלידו את הקוד הזה במכשיר השני:", style = MaterialTheme.typography.titleSmall)
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            s.code, Modifier.padding(vertical = 28.dp).fillMaxWidth(),
            textAlign = TextAlign.Center, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace, letterSpacing = 4.sp,
        )
    }
    Text(
        "תוקף הקוד עוד ${left / 60_000}:${"%02d".format((left / 1000) % 60)}. נותרו ${s.attemptsLeft} ניסיונות. " +
            "אפשר גם להצמיד את המכשירים גב אל גב (NFC) כדי שהקוד יועבר אוטומטית.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        when (s.myRole) {
            DeviceRole.PARENT -> "המכשיר הזה: הורה"
            DeviceRole.CHILD -> "המכשיר הזה: ילד"
            DeviceRole.PEER -> "המכשירים שווים"
        },
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    )
    OutlinedButton(onCancel, Modifier.fillMaxWidth()) { Text("בטל") }
}

// ── The phone that types the code ───────────────────────────────────────────

@Composable
private fun JoinSetup(vm: PairViewModel) {
    val context = LocalContext.current
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var wantedName by remember { mutableStateOf<String?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) vm.scan()
    }
    var afterEnable by remember { mutableStateOf<(() -> Unit)?>(null) }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (BluetoothSupport.isEnabled(context)) afterEnable?.invoke()
        afterEnable = null
    }
    fun search() {
        val needed = BluetoothSupport.scanPermissions()
        when {
            !BluetoothSupport.hasAll(context, needed) -> permissions.launch(needed.toTypedArray())
            !BluetoothSupport.isEnabled(context) -> { afterEnable = { vm.scan() }; enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            else -> vm.scan()
        }
    }
    LaunchedEffect(Unit) { search() }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    // A tap with the other phone gives the code and the name its Bluetooth goes by, which saves
    // typing the one and picking from a list for the other. The device is chosen as soon as the
    // search finds one with that name; the code still goes through the full handshake.
    val activity = context.findActivity()
    DisposableEffect(Unit) {
        if (activity != null && NfcReader.isEnabled(context)) {
            val main = Handler(Looper.getMainLooper())
            NfcReader.enable(activity) { offer ->
                if (offer is NfcOffer.PairInvite) main.post { code = offer.code; wantedName = offer.bluetoothName }
            }
        }
        onDispose { if (activity != null) NfcReader.disable(activity) }
    }
    LaunchedEffect(nearby, wantedName) {
        val want = wantedName ?: return@LaunchedEffect
        nearby.firstOrNull { it.name == want }?.let { selected = it.address }
    }

    Text("1. בחרו את המכשיר שמציג קוד", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    if (nearby.isEmpty()) {
        Text(
            if (scanning) "מחפש מכשירים קרובים…" else "לא נמצאו מכשירים. במכשיר השני בחרו \"הצג קוד\", ואז חפשו שוב.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    nearby.forEach { d ->
        Surface(
            Modifier.fillMaxWidth().clickable { selected = d.address },
            shape = RoundedCornerShape(12.dp),
            color = if (selected == d.address) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surface,
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bluetooth, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(d.name ?: "מכשיר ללא שם", Modifier.weight(1f))
                if (selected == d.address) Icon(Icons.Rounded.Check, "נבחר", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ search() }, enabled = !scanning) { Text(if (scanning) "מחפש…" else "חפש שוב") }
        if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }

    Text("2. הקלידו את הקוד", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter { c -> c.isDigit() || c == ' ' || c == '-' }.take(12) },
        label = { Text("קוד בן ${PairingCodes.LENGTH} ספרות") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val valid = PairingCodes.normalize(code) != null && selected != null
    Button({ selected?.let { vm.join(it, code) } }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("התחבר") }
}

// ── The middle and the end ──────────────────────────────────────────────────

@Composable
private fun WorkingView(message: String, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(14.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
    OutlinedButton(onCancel, Modifier.fillMaxWidth()) { Text("בטל") }
}

@Composable
private fun ConfirmView(summary: PeerSummary, onAnswer: (Boolean) -> Unit) {
    Text("לחבר את ${summary.name}?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            when (summary.myRole) {
                DeviceRole.PARENT -> "המכשיר הזה יהיה ההורה. תוכלו להגדיר, לשנות ולמחוק שעמורים ב-${summary.name}. " +
                    "הם יסומנו שם כשעמורים שהגדרתם, ולא יהיה אפשר לשנות אותם ממנו."
                DeviceRole.CHILD -> "${summary.name} יהיה ההורה. הוא יוכל להגדיר, לשנות ולמחוק שעמורים במכשיר הזה, " +
                    "ולא תוכלו לשנות אותם כאן. הם יסומנו כשעמורים שהוא הגדיר. את הצימוד אפשר לבטל בכל עת."
                DeviceRole.PEER -> "המכשירים שווים. כל אחד יכול לשלוח לשני עותק של שעמור, והשני מחליט אם לקבל אותו."
            },
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text("ודאו שהמספר הבא זהה במכשיר השני:", style = MaterialTheme.typography.bodyMedium)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f), modifier = Modifier.fillMaxWidth()) {
        Text(
            summary.verification.chunked(3).joinToString(" "),
            Modifier.padding(vertical = 22.dp).fillMaxWidth(), textAlign = TextAlign.Center,
            fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
        )
    }
    Text(
        "אם המספר שונה, מישהו אחר מתערב בחיבור — אל תאשרו.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
    )
    Button({ onAnswer(true) }, Modifier.fillMaxWidth()) { Text("המספרים זהים — חבר") }
    OutlinedButton({ onAnswer(false) }, Modifier.fillMaxWidth()) { Text("ביטול") }
}

@Composable
private fun DoneView(s: PairingUiState.Done, onDone: () -> Unit) {
    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    Text("${s.name} חובר", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
    Text(
        when (s.myRole) {
            DeviceRole.PARENT -> "עכשיו אפשר להגדיר שעמורים עבורו: מסך המכשירים ← הוסף שעמור."
            DeviceRole.CHILD -> "שעמורים שיוגדרו כאן על ידי ${s.name} יסומנו וינעלו. כדי לקבל אותם גם כשהאפליקציה סגורה, השאירו את ההאזנה ברקע פעילה."
            DeviceRole.PEER -> "אפשר לשלוח עותקים של שעמורים לשני."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onDone, Modifier.fillMaxWidth()) { Text("סיום") }
}

@Composable
private fun FailedView(s: PairingUiState.Failed, onRetry: () -> Unit, onBack: () -> Unit) {
    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
    Text(s.message, style = MaterialTheme.typography.titleMedium)
    if (s.retryable) Button(onRetry, Modifier.fillMaxWidth()) { Text("נסה שוב") }
    OutlinedButton(onBack, Modifier.fillMaxWidth()) { Text("חזרה") }
}
