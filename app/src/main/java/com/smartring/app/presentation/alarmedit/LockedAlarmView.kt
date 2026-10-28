package com.smartring.app.presentation.alarmedit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartring.app.domain.model.Alarm

/**
 * An alarm another phone set for this one: what it is and who set it, with nothing to change.
 *
 * A read-only view rather than the editor with its controls disabled. The editor has fifteen
 * kinds of control, and a disabled one still looks like something the person could have; this says
 * plainly that it is not theirs to edit, and by whom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockedAlarmView(alarm: Alarm, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "חזור") } },
                title = { Text("שעמור נעול", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .10f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "השעמור הזה הוגדר על ידי ${alarm.sync.originDeviceName ?: "מכשיר אחר"}. " +
                            "רק הוא יכול לשנות אותו או להסיר אותו.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(alarm.timeFormatted, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                    Text(alarm.name, style = MaterialTheme.typography.titleMedium)
                    Text(alarm.scheduleSummary(), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    alarm.reminderText?.takeIf { it.isNotBlank() }?.let {
                        Text("📝 $it", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(if (alarm.isEnabled) "פעיל" else "כבוי", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "אפשר לשכפל אותו מהרשימה כדי ליצור שעמור משלך. כשמבטלים את הצימוד עם המכשיר, " +
                    "השעמורים שהגדיר חוזרים להיות שלך.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
