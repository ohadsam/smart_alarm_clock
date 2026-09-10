package com.smartring.app.presentation.whatsnew

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Renders nothing when there's nothing new to show — safe to call unconditionally
 *  from the app's start screen. */
@Composable
fun WhatsNewDialog(vm: WhatsNewViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    if (s.entriesToShow.isEmpty()) return

    AlertDialog(
        onDismissRequest = vm::dismiss,
        title = { Text("מה חדש ב-SmartRing ${s.entriesToShow.last().versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.entriesToShow.flatMap { it.items }.forEach { item ->
                    Row {
                        Icon(Icons.Rounded.Circle, null, Modifier.size(6.dp).padding(top = 6.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(item, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(vm::dismiss) { Text("הבנתי", fontWeight = FontWeight.Bold) } },
    )
}
