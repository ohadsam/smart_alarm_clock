package com.smartring.app.presentation.intro

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The short explanation of the three things this app does that a user will not find on
 * their own.
 *
 * Stateless, so the same content serves both the once-per-install showing on the list
 * screen and the "הסבר קצר" entry in Settings. The alternative — a dialog that owns its
 * own visibility — would have meant two copies of this text, which is how the two drift
 * apart.
 */
@Composable
fun IntroDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.HelpOutline, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("שלוש דברים ששווה לדעת", fontWeight = FontWeight.ExtraBold) },
        text = {
            // Scrollable: at the largest system font size this content is taller than a
            // small phone's dialog, and text that cannot be reached is the same as text
            // that was never written.
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                IntroPoint(
                    Icons.Rounded.EventAvailable,
                    "שעמור מזדמן",
                    "שעמור לתאריך ושעה מסוימים, שמצלצל פעם אחת ואז נכבה מעצמו. במסך העריכה " +
                        "מפעילים \"תאריך ושעה ספציפיים\" ובוחרים \"היום\" או \"מחר\". אחרי " +
                        "שהוא צלצל, כפתור \"תזמן ליום הבא\" על הכרטיס מחזיר אותו לפעולה " +
                        "בלחיצה אחת — ולחיצה נוספת מקדמת ליום שאחרי.",
                )
                IntroPoint(
                    Icons.Rounded.Repeat,
                    "סבבי צלצול",
                    "אפשר להגדיר עד עשרה סבבים, כל אחד עם צליל, עוצמה, משך והשהיה משלו — " +
                        "למשל צליל שקט, המתנה של חמש דקות, ואז צליל חזק. \"משך צלצול כולל\" " +
                        "הוא השעון שעוצר את הכל: הסבבים חוזרים בתוכו עד שהוא נגמר.",
                )
                IntroPoint(
                    Icons.Rounded.Shield,
                    "מצב שבת",
                    "כשהוא דולק, בזמן הצלצול אין כפתור עצירה ואין נודניק — גם לא בהתראה. " +
                        "השעמור נעצר לבד בתום משך הצלצול הכולל, ולכן כדאי להגדיר אותו למשך " +
                        "שהגיוני להשאיר מצלצל.",
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "ואם שעמור לא צלצל: הגדרות ← אבחון ← \"למה השעמור לא צלצל?\" בודק את כל " +
                        "מה שיכול למנוע צלצול ומציע תיקון לכל ממצא.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("הבנתי") } },
    )
}

@Composable
private fun IntroPoint(icon: ImageVector, title: String, body: String) {
    Row {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
        }
    }
}
