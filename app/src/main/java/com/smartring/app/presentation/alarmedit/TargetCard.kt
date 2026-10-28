package com.smartring.app.presentation.alarmedit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartring.app.sync.platform.PairedDevice

/**
 * "Whose alarm is this?" — this phone, or one of the children paired to it.
 *
 * For a child's alarm the ringtone is the child's own default: ringtones are addresses into a
 * phone's own media store and mean nothing on another, so they are not sent. Said here, where the
 * choice is made, so nobody picks a favourite song on the parent's phone and wonders why the child
 * does not hear it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetCard(children: List<PairedDevice>, selected: String?, onSelect: (String?) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("השעמור הזה מיועד ל…", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected == null, { onSelect(null) }, label = { Text("המכשיר הזה") })
                children.forEach { child ->
                    FilterChip(selected == child.deviceId, { onSelect(child.deviceId) }, label = { Text(child.name) })
                }
                // A target that is no longer paired (removed since) still has to be shown as chosen,
                // or the screen would claim the alarm is for this phone while it is not.
                if (selected != null && children.none { it.deviceId == selected }) {
                    FilterChip(true, { }, label = { Text("מכשיר שהוסר") })
                }
            }
            if (selected != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "השעמור יצלצל במכשיר השני, לא כאן. הצלצול יהיה ברירת המחדל של אותו מכשיר, " +
                            "והוא יישלח אליו כשיהיה בטווח.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
