package com.smartring.app.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.app.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(val manager: UpdateManager) : ViewModel()

/** Hebrew text for each way an update can fail — with what to do about it, not an error code. */
internal fun Reason.text(): String = when (this) {
    Reason.OFFLINE -> "אין חיבור לאינטרנט, או ש-GitHub לא זמין כרגע. נסו שוב מאוחר יותר."
    Reason.CHECK_FAILED -> "לא הצלחתי לקרוא את פרטי הגרסה האחרונה. נסו שוב, או הורידו ידנית."
    Reason.NO_ASSET -> "הגרסה החדשה עוד לא מוכנה להורדה. נסו שוב בעוד כמה דקות."
    Reason.HTTP -> "ההורדה נכשלה (השרת לא החזיר את הקובץ). אפשר להוריד ידנית."
    Reason.SIZE -> "הקובץ גדול מהצפוי, ולכן לא הורד."
    Reason.CHECKSUM -> "הקובץ שהורד לא תואם לסכום הביקורת שפורסם — לא הותקן. נסו שוב."
    Reason.INVALID_APK -> "הקובץ שהורד אינו קובץ התקנה תקין."
    Reason.WRONG_PACKAGE -> "הקובץ שהורד שייך לאפליקציה אחרת, ולא הותקן."
    Reason.NOT_NEWER -> "הקובץ שהורד אינו חדש יותר מהמותקן."
    Reason.SIGNATURE -> "הקובץ שהורד לא חתום באותו מפתח כמו האפליקציה המותקנת, ולכן אי אפשר לעדכן מעליה."
    Reason.INSTALL -> "אנדרואיד סירב להתקין את העדכון. אפשר לנסות להוריד ולהתקין ידנית."
    Reason.FAILED -> "העדכון נכשל. נסו שוב, או הורידו ידנית."
}

/**
 * The update box: check, see what is new, download and install over the existing app — data stays.
 * A manual download link is always there too, for when the in-app route does not work.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier, vm: UpdateViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by vm.manager.state.collectAsStateWithLifecycle()
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Verifying ||
        state is UpdateState.Installing || state is UpdateState.Confirm

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("גרסה מותקנת: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)

        when (val s = state) {
            UpdateState.Idle -> Text("הבדיקה פונה ל-GitHub ורק כשלוחצים — שום דבר לא נשלח עליכם.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UpdateState.Checking -> Progress("בודק עדכונים…", -1)
            is UpdateState.UpToDate -> Text("יש לכם את הגרסה העדכנית (${s.version}).", color = MaterialTheme.colorScheme.primary)
            is UpdateState.Available -> {
                Text("יש גרסה חדשה: ${s.version}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                if (s.notes.isNotBlank()) Text(s.notes, style = MaterialTheme.typography.bodySmall)
            }
            is UpdateState.Downloading -> Progress("מוריד עדכון…", s.pct)
            UpdateState.Verifying -> Progress("מוודא את הקובץ…", -1)
            UpdateState.Installing -> Progress("מתקין…", -1)
            UpdateState.Confirm -> Text("אנדרואיד מבקש אישור להתקנה — אשרו בחלון שנפתח.")
            UpdateState.Done -> Text("העדכון הותקן. האפליקציה תיפתח מחדש בגרסה החדשה.", color = MaterialTheme.colorScheme.primary)
            UpdateState.NeedPermission -> {
                Text("כדי להתקין עדכון מתוך האפליקציה, אנדרואיד צריך שתאשרו פעם אחת \"התקנת אפליקציות לא מוכרות\" עבור SmartRing. אחרי האישור חזרו ולחצו שוב.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton({
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    }
                }) { Text("פתח הגדרה") }
            }
            is UpdateState.Error -> Text(s.reason.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val available = state is UpdateState.Available
            if (available) {
                Button({ vm.manager.install() }, enabled = !busy) { Text("הורד והתקן") }
                OutlinedButton({ vm.manager.check() }, enabled = !busy) { Text("בדוק שוב") }
            } else {
                Button({ vm.manager.check() }, enabled = !busy) { Text("בדוק עדכונים") }
            }
        }
        // Always available: the in-app route needs a permission and a network round trip that can fail.
        TextButton({
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.manager.manualUrl))) }
        }, contentPadding = PaddingValues(0.dp)) { Text("הורדה ידנית של הגרסה האחרונה (APK)") }
    }
}

@Composable
private fun Progress(label: String, pct: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            if (pct >= 0) Text("$pct%")
        }
        if (pct >= 0) LinearProgressIndicator(progress = { pct / 100f }, Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
