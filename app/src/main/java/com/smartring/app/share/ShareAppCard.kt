package com.smartring.app.share

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smartring.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Send this app to someone": a fresh copy of the installed APK, with install instructions, to
 * WhatsApp, e-mail or any app. Used on the Settings screen and on the pairing screen, where it is
 * most needed — the other phone has to have the app before it can be paired with.
 */
@Composable
fun ShareAppCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zip by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun share(target: AppSharer.Target) {
        scope.launch {
            busy = true
            error = null
            try {
                // A copy of an APK is tens of megabytes: off the main thread.
                val intent = withContext(Dispatchers.IO) {
                    val file = AppSharer.prepare(context, BuildConfig.VERSION_NAME, zip)
                    AppSharer.intent(context, file, BuildConfig.VERSION_NAME, zip, target)
                }
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                error = "לא נמצאה אפליקציה לשיתוף"
            } catch (e: Exception) {
                error = "לא ניתן להכין את הקובץ לשיתוף"
            } finally {
                busy = false
            }
        }
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "שלחו את האפליקציה לחבר או לבן משפחה — הקובץ הוא הגרסה שמותקנת אצלכם עכשיו (${BuildConfig.VERSION_NAME}), " +
                "יחד עם הוראות התקנה. מי שכבר מתקין אותה יכול לעדכן מעליה בלי להסיר.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ share(AppSharer.Target.WHATSAPP) }, enabled = !busy) { Text("WhatsApp") }
            OutlinedButton({ share(AppSharer.Target.EMAIL) }, enabled = !busy) { Text("אימייל") }
            OutlinedButton({ share(AppSharer.Target.ANY) }, enabled = !busy) { Text("אחר…") }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ארוז כ-zip (לאפליקציות שחוסמות קובץ APK)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(zip, { zip = it }, modifier = Modifier.semantics { contentDescription = "ארוז את הקובץ כ-zip" })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
