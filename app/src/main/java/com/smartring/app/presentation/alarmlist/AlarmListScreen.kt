package com.smartring.app.presentation.alarmlist
import androidx.compose.foundation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.domain.model.Alarm
import com.smartring.app.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(onAddAlarm: ()->Unit, onEditAlarm: (Long)->Unit, onOpenHistory: ()->Unit, onOpenSettings: ()->Unit,
    vm: AlarmListViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showControls by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title={Text("SmartRing",fontWeight=FontWeight.Black,fontSize=24.sp)},
                colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background),
                actions={
                    IconButton({showControls=true}){Icon(Icons.Rounded.Tune,"שליטה כללית")}
                    IconButton(onOpenHistory){Icon(Icons.Rounded.History,"היסטוריה")}
                    IconButton(onOpenSettings){Icon(Icons.Rounded.Settings,"הגדרות")}
                })
        },
        floatingActionButton={
            FloatingActionButton(onAddAlarm,shape=CircleShape,containerColor=MaterialTheme.colorScheme.primary){
                Icon(Icons.Rounded.Add,null,tint=White)
            }
        },
        containerColor=MaterialTheme.colorScheme.background,
    ) { pad ->
        if (state.isLoading) Box(Modifier.padding(pad).fillMaxSize(),Alignment.Center){CircularProgressIndicator()}
        else if (state.alarms.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(),Alignment.Center){
                Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Icon(Icons.Rounded.AlarmOff,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("אין שעמורים עדיין",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text("לחץ + להוספת שעמור",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.padding(pad),contentPadding=PaddingValues(16.dp,8.dp,16.dp,96.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(state.alarms,key={it.id}) { alarm ->
                    AlarmCardItem(alarm,{vm.toggle(alarm,it)},{onEditAlarm(alarm.id)},{vm.delete(alarm)})
                }
            }
        }
    }
    if (showControls) {
        val frozenCount = state.alarms.count { it.isFrozen }
        val activeCount = state.alarms.count { it.isActive }
        ModalBottomSheet({ showControls = false }) {
            Column(
                Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("שליטה כללית", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    "$activeCount פעילים · $frozenCount מוקפאים",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Button({ vm.enableAll(); showControls = false }, Modifier.fillMaxWidth()) {
                    Text("✅ הפעל הכל")
                }
                OutlinedButton(
                    onClick = {
                        if (frozenCount > 0) vm.unfreezeAll() else vm.freezeAll()
                        showControls = false
                    },
                    Modifier.fillMaxWidth(),
                ) {
                    Text(if (frozenCount > 0) "❄️ בטל הקפאה ($frozenCount)" else "❄️ הקפא הכל")
                }
                Button(
                    onClick = { vm.disableAll(); showControls = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("🔕 כבה הכל")
                }
            }
        }
    }}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AlarmCardItem(alarm: Alarm, onToggle:(Boolean)->Unit, onEdit:()->Unit, onDelete:()->Unit) {
    var showDel by remember { mutableStateOf(false) }
    val dotColor = if (alarm.isFrozen) Blue else if (alarm.isActive) Green else MaterialTheme.colorScheme.outline

    // Swipe (either direction) surfaces the same confirm dialog as long-press, rather
    // than deleting outright — a quicker, more discoverable gesture without an
    // accidental-delete risk. confirmValueChange always returns false so the card
    // snaps back to place once the dialog is shown; the dialog owns the real delete.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) showDel = true
            false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val alignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
                Alignment.CenterStart else Alignment.CenterEnd
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Icon(Icons.Rounded.Delete, "מחק", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
    Surface(Modifier.fillMaxWidth().combinedClickable(onClick=onEdit,onLongClick={showDel=true}),
        RoundedCornerShape(16.dp), color=MaterialTheme.colorScheme.surface,
        border=BorderStroke(1.5.dp,dotColor.copy(.3f))) {
        Row(Modifier.padding(16.dp,14.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)){
                Text(alarm.timeFormatted,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold,
                    color=if(alarm.isActive)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(alarm.name,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                alarm.reminderText?.takeIf{it.isNotBlank()}?.let{
                    Spacer(Modifier.height(2.dp))
                    Text("📝 $it",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary,maxLines=1)
                }
            }
            Column(horizontalAlignment=Alignment.End){
                Switch(alarm.isEnabled,onToggle,
                    modifier=Modifier.semantics{contentDescription=
                        "שעמור ${alarm.name.ifBlank{alarm.timeFormatted}} בשעה ${alarm.timeFormatted}, ${if(alarm.isEnabled)"פעיל" else "כבוי"}"})
                Text("לחץ לחיצה ארוכה למחיקה",
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f),
                    fontSize=9.sp)
            }
        }
    }
    }
    if (showDel) AlertDialog({showDel=false},title={Text("מחק שעמור")},text={Text("מחק את \"${alarm.name}\"?")},
        confirmButton={TextButton({showDel=false;onDelete()}){Text("מחק",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({showDel=false}){Text("ביטול")}})
}
