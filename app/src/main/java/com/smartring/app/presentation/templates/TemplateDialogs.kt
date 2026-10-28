package com.smartring.app.presentation.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartring.app.domain.model.AlarmTemplate
import com.smartring.app.domain.model.TemplateFolder
import com.smartring.app.domain.model.TemplateLibrary
import com.smartring.app.domain.model.TemplateLimits

/**
 * "Save this alarm as a template": a name, and where to file it — an existing folder, a new one, or
 * none. Offered from the alarm itself, so what is saved is exactly what the editor shows, including
 * changes not yet saved to the alarm.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveAsTemplateDialog(
    defaultName: String,
    library: TemplateLibrary,
    onSave: (name: String, folderId: String?, newFolder: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultName.take(TemplateLimits.NAME_MAX_CHARS)) }
    var folderId by remember { mutableStateOf<String?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf("") }
    val canSave = name.isNotBlank() && (!creatingFolder || newFolder.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("שמירה כתבנית") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(TemplateLimits.NAME_MAX_CHARS) }, singleLine = true,
                    label = { Text("שם התבנית") }, modifier = Modifier.fillMaxWidth())
                Text("תיקייה", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(!creatingFolder && folderId == null, { creatingFolder = false; folderId = null }, label = { Text("ללא") })
                    library.folders.forEach { f ->
                        FilterChip(!creatingFolder && folderId == f.id, { creatingFolder = false; folderId = f.id }, label = { Text(f.name) })
                    }
                    if (library.folders.size < TemplateLimits.MAX_FOLDERS) {
                        FilterChip(creatingFolder, { creatingFolder = true }, label = { Text("＋ תיקייה חדשה") })
                    }
                }
                if (creatingFolder) {
                    OutlinedTextField(newFolder, { newFolder = it.take(TemplateLimits.NAME_MAX_CHARS) }, singleLine = true,
                        label = { Text("שם התיקייה") }, modifier = Modifier.fillMaxWidth())
                }
                Text("נשמרות כל ההגדרות של השעמור. את התבניות אפשר לנהל בהגדרות ← תבניות.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton({ onSave(name, if (creatingFolder) null else folderId, if (creatingFolder) newFolder else null) }, enabled = canSave) { Text("שמור") }
        },
        dismissButton = { TextButton(onDismiss) { Text("ביטול") } },
    )
}

/** Choose a template to start a new alarm from. Folders are headings; a search box appears when the list is long. */
@Composable
fun TemplatePickerDialog(library: TemplateLibrary, onPick: (AlarmTemplate) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    fun matches(t: AlarmTemplate) = q.isEmpty() || t.name.contains(q, ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("בחירת תבנית") },
        text = {
            if (library.templates.isEmpty()) {
                Text("עוד אין תבניות. כדי ליצור: פתחו שעמור ולחצו על סימניית השמירה, או הגדרות ← תבניות.")
            } else Column {
                if (library.templates.size >= 6) {
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("חיפוש") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    val groups: List<Pair<TemplateFolder?, List<AlarmTemplate>>> =
                        library.folders.map { it to library.inFolder(it.id).filter(::matches) } +
                            listOf(null to library.inFolder(null).filter(::matches))
                    groups.forEach { (folder, list) ->
                        if (list.isEmpty()) return@forEach
                        if (folder != null) item {
                            Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(folder.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        items(list, key = { it.id }) { t ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 8.dp)) {
                                Text(t.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(t.summary(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("סגור") } },
    )
}
