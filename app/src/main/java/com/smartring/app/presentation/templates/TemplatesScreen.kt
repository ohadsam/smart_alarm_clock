package com.smartring.app.presentation.templates

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.domain.model.AlarmTemplate
import com.smartring.app.domain.model.TemplateFolder
import com.smartring.app.domain.model.TemplateLibrary

/** What the person is in the middle of doing to a template or folder. */
private sealed interface Pending {
    data class RenameTemplate(val t: AlarmTemplate) : Pending
    data class MoveTemplate(val t: AlarmTemplate) : Pending
    data class DeleteTemplate(val t: AlarmTemplate) : Pending
    data object NewFolder : Pending
    data class RenameFolder(val f: TemplateFolder) : Pending
    data class DeleteFolder(val f: TemplateFolder) : Pending
}

/**
 * Settings → templates: the whole library in one place. Create, edit, rename, duplicate, move and
 * delete templates; create, rename and delete folders. Editing a template opens the alarm editor in
 * template mode, where Save writes the changes back and "save as a new template" keeps the original.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    onEditTemplate: (String) -> Unit,
    onNewTemplate: (folderId: String?) -> Unit,
    vm: TemplatesViewModel = hiltViewModel(),
) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var query by remember { mutableStateOf("") }

    pending?.let { p -> PendingDialog(p, lib, vm, onDone = { pending = null }) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "חזור") } },
                title = { Text("תבניות", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    IconButton({ pending = Pending.NewFolder }) { Icon(Icons.Rounded.CreateNewFolder, "תיקייה חדשה") }
                    IconButton({ onNewTemplate(null) }) { Icon(Icons.Rounded.Add, "תבנית חדשה") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (lib.templates.isEmpty() && lib.folders.isEmpty()) {
                item {
                    Text(
                        "עוד אין תבניות. תבנית היא שעמור שמור שאפשר להתחיל ממנו שעמור חדש עם כל ההגדרות. " +
                            "כדי לשמור שעמור קיים כתבנית — פתחו אותו ולחצו על סימניית השמירה בסרגל העליון; " +
                            "אפשר גם ליצור תבנית חדשה מכאן (＋).",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                if (lib.templates.size >= 6) item {
                    OutlinedTextField(
                        query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("חיפוש תבנית") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    )
                }
                val q = query.trim()
                fun matches(t: AlarmTemplate) = q.isEmpty() || t.name.contains(q, ignoreCase = true) || t.alarm.name.contains(q, ignoreCase = true)

                lib.folders.forEach { folder ->
                    val inside = lib.inFolder(folder.id).filter(::matches)
                    if (q.isNotEmpty() && inside.isEmpty()) return@forEach
                    item(key = "f-${folder.id}") {
                        FolderHeader(folder, inside.size, onNewTemplate = { onNewTemplate(folder.id) },
                            onRename = { pending = Pending.RenameFolder(folder) }, onDelete = { pending = Pending.DeleteFolder(folder) })
                    }
                    items(inside, key = { it.id }) { t -> TemplateRow(t, lib, vm, onEditTemplate, setPending = { pending = it }) }
                }
                val top = lib.inFolder(null).filter(::matches)
                if (top.isNotEmpty()) {
                    if (lib.folders.isNotEmpty()) item(key = "top") {
                        Text("ללא תיקייה", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    items(top, key = { it.id }) { t -> TemplateRow(t, lib, vm, onEditTemplate, setPending = { pending = it }) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FolderHeader(folder: TemplateFolder, count: Int, onNewTemplate: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("${folder.name} ($count)", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        IconButton(onNewTemplate) { Icon(Icons.Rounded.Add, "תבנית חדשה בתיקייה ${folder.name}") }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "אפשרויות לתיקייה ${folder.name}") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("שנה שם") }, { menu = false; onRename() })
                DropdownMenuItem({ Text("מחק תיקייה", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun TemplateRow(
    t: AlarmTemplate,
    lib: TemplateLibrary,
    vm: TemplatesViewModel,
    onEdit: (String) -> Unit,
    setPending: (Pending) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().clickable { onEdit(t.id) },
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(t.summary(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "אפשרויות לתבנית ${t.name}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("ערוך") }, { menu = false; onEdit(t.id) })
                    DropdownMenuItem({ Text("שנה שם") }, { menu = false; setPending(Pending.RenameTemplate(t)) })
                    if (lib.folders.isNotEmpty() || t.folderId != null) {
                        DropdownMenuItem({ Text("העבר לתיקייה…") }, { menu = false; setPending(Pending.MoveTemplate(t)) })
                    }
                    DropdownMenuItem({ Text("שכפל") }, { menu = false; vm.duplicate(t.id) })
                    DropdownMenuItem({ Text("מחק", color = MaterialTheme.colorScheme.error) }, { menu = false; setPending(Pending.DeleteTemplate(t)) })
                }
            }
        }
    }
}

@Composable
private fun PendingDialog(p: Pending, lib: TemplateLibrary, vm: TemplatesViewModel, onDone: () -> Unit) {
    when (p) {
        is Pending.RenameTemplate -> NameDialog("שם התבנית", p.t.name, onDone) { vm.rename(p.t.id, it); onDone() }
        Pending.NewFolder -> NameDialog("תיקייה חדשה", "", onDone) { vm.addFolder(it); onDone() }
        is Pending.RenameFolder -> NameDialog("שם התיקייה", p.f.name, onDone) { vm.renameFolder(p.f.id, it); onDone() }
        is Pending.DeleteTemplate -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text("למחוק את \"${p.t.name}\"?") },
            text = { Text("שעמורים שכבר נוצרו ממנה לא ישתנו.") },
            confirmButton = { TextButton({ vm.delete(p.t.id); onDone() }) { Text("מחק", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onDone) { Text("ביטול") } },
        )
        is Pending.MoveTemplate -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text("להעביר את \"${p.t.name}\" ל…") },
            text = {
                Column {
                    FolderChoice("ללא תיקייה", p.t.folderId == null) { vm.move(p.t.id, null); onDone() }
                    lib.folders.forEach { f -> FolderChoice(f.name, p.t.folderId == f.id) { vm.move(p.t.id, f.id); onDone() } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onDone) { Text("ביטול") } },
        )
        is Pending.DeleteFolder -> {
            val count = lib.inFolder(p.f.id).size
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text("למחוק את התיקייה \"${p.f.name}\"?") },
                text = { Text(if (count == 0) "התיקייה ריקה." else "יש בה $count תבניות. אפשר להעביר אותן לראשי, או למחוק גם אותן.") },
                confirmButton = {
                    Row {
                        TextButton({ vm.deleteFolder(p.f.id, deleteContents = false); onDone() }) {
                            Text(if (count == 0) "מחק" else "מחק והשאר את התבניות")
                        }
                        if (count > 0) TextButton({ vm.deleteFolder(p.f.id, deleteContents = true); onDone() }) {
                            Text("מחק הכל", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                dismissButton = { TextButton(onDone) { Text("ביטול") } },
            )
        }
    }
}

@Composable
private fun FolderChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.Folder, null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(label)
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(com.smartring.app.domain.model.TemplateLimits.NAME_MAX_CHARS) }, singleLine = true, label = { Text("שם") }) },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank()) { Text("שמור") } },
        dismissButton = { TextButton(onDismiss) { Text("ביטול") } },
    )
}
