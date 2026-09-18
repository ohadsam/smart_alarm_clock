package com.smartring.app.util

/**
 * The answer to "why didn't my alarm ring", assembled from what the app can actually
 * observe.
 *
 * Every input here was already surfaced somewhere — ReliabilityChecks in Settings, the
 * next-registered-alarm row, the ring history, the technical log. What was missing is the
 * one place that puts them together in the order they break. Someone whose alarm failed at
 * 06:30 should not have to know that "exact alarm permission" is the thing to go looking
 * for.
 *
 * Pure, taking every reading as a parameter, so the wording and the ordering are testable
 * without a device. The ordering is the substance: these are listed most-likely-cause
 * first, not alphabetically, and the first failing row is the one to act on.
 */

enum class DiagnosisSeverity { OK, WARNING, BLOCKER }

data class DiagnosisItem(
    val title: String,
    val detail: String,
    val severity: DiagnosisSeverity,
    /** Which system screen fixes it, for the row's action button. Null when nothing to open. */
    val fix: DiagnosisFix? = null,
)

enum class DiagnosisFix { EXACT_ALARM, BATTERY, NOTIFICATIONS, FULL_SCREEN, VOLUME, APP_DETAILS }

/** Everything the diagnosis is computed from. */
data class DiagnosisInputs(
    val hasAnyAlarm: Boolean,
    val hasArmedAlarm: Boolean,
    val nextArmedAtMillis: Long?,
    val systemNextAlarmIsOurs: Boolean,
    val systemHasNextAlarm: Boolean,
    val canScheduleExactAlarms: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val notificationsGranted: Boolean,
    val canUseFullScreenIntent: Boolean,
    val alarmVolumeAudible: Boolean,
)

/**
 * The diagnosis, worst first.
 *
 * A BLOCKER is something that stops an alarm ringing or stops the user noticing it. A
 * WARNING degrades it. Ordering by severity and then by the order below puts the thing
 * most likely to be the cause at the top, which is the entire point of the screen.
 */
fun diagnoseRinging(inputs: DiagnosisInputs, nowMillis: Long = System.currentTimeMillis()): List<DiagnosisItem> {
    val items = mutableListOf<DiagnosisItem>()

    // Asked first because it is the most common answer and costs nothing to check: an
    // alarm that is switched off cannot fail to ring, it was never going to.
    items += when {
        !inputs.hasAnyAlarm -> DiagnosisItem(
            "אין שעמורים", "לא מוגדר אף שעמור באפליקציה.", DiagnosisSeverity.BLOCKER,
        )
        !inputs.hasArmedAlarm -> DiagnosisItem(
            "אין שעמור פעיל",
            "יש שעמורים, אבל כולם כבויים או מוקפאים. שעמור חד-פעמי נכבה אוטומטית אחרי שהוא מצלצל — " +
                "צריך להפעיל אותו שוב כדי שיצלצל בפעם הבאה.",
            DiagnosisSeverity.BLOCKER,
        )
        else -> DiagnosisItem(
            "יש שעמור פעיל",
            inputs.nextArmedAtMillis?.let { "הקרוב: ${formatDayAndTime(it, nowMillis)}." }
                ?: "השעמור הקרוב מתוזמן.",
            DiagnosisSeverity.OK,
        )
    }

    // Second, because it is the one check that reflects what the OS itself believes,
    // rather than what this app intended. A disagreement here is the strongest signal
    // available that the alarm is not really armed.
    items += when {
        !inputs.hasArmedAlarm -> DiagnosisItem(
            "רישום במערכת", "אין מה לרשום כל עוד אין שעמור פעיל.", DiagnosisSeverity.WARNING,
        )
        inputs.systemNextAlarmIsOurs -> DiagnosisItem(
            "רישום במערכת",
            "מערכת ההפעלה מכירה את השעמור הקרוב שלך. זה גם מה שמציג את סמל השעמור בשורת המצב.",
            DiagnosisSeverity.OK,
        )
        inputs.systemHasNextAlarm -> DiagnosisItem(
            "רישום במערכת",
            "השעמור הבא הרשום במערכת שייך לאפליקציה אחרת, ולכן הוא יצלצל לפני שלך.",
            DiagnosisSeverity.WARNING,
        )
        else -> DiagnosisItem(
            "רישום במערכת",
            "יש שעמור פעיל, אבל מערכת ההפעלה לא מדווחת על שעמור רשום. " +
                "בדרך כלל הסיבה היא הרשאת השעמורים המדויקים למטה.",
            DiagnosisSeverity.BLOCKER,
        )
    }

    items += if (inputs.canScheduleExactAlarms) DiagnosisItem(
        "הרשאת שעמורים מדויקים", "מאושרת — השעמור יצלצל בשעה המדויקת.", DiagnosisSeverity.OK,
    ) else DiagnosisItem(
        "הרשאת שעמורים מדויקים",
        "חסרה. בלעדיה אנדרואיד רשאי לדחות את השעמור בדקות ואף יותר, והסמל בשורת המצב לא יופיע.",
        DiagnosisSeverity.BLOCKER, DiagnosisFix.EXACT_ALARM,
    )

    items += if (inputs.ignoringBatteryOptimizations) DiagnosisItem(
        "אופטימיזציית סוללה", "מבוטלת עבור האפליקציה — הרקע לא ייסגר.", DiagnosisSeverity.OK,
    ) else DiagnosisItem(
        "אופטימיזציית סוללה",
        "פעילה. זו הסיבה הנפוצה ביותר לשעמור שלא צלצל כלל: המערכת סוגרת את האפליקציה ברקע.",
        DiagnosisSeverity.BLOCKER, DiagnosisFix.BATTERY,
    )

    items += if (inputs.alarmVolumeAudible) DiagnosisItem(
        "עוצמת ערוץ השעמור", "גדולה מאפס — יהיה אפשר לשמוע.", DiagnosisSeverity.OK,
    ) else DiagnosisItem(
        "עוצמת ערוץ השעמור",
        "אפס. השעמור יפעל אבל לא יישמע דבר — עוצמת השעמור במכשיר נפרדת מעוצמת המדיה והצלצול.",
        DiagnosisSeverity.BLOCKER, DiagnosisFix.VOLUME,
    )

    items += if (inputs.notificationsGranted) DiagnosisItem(
        "הרשאת התראות", "מאושרת.", DiagnosisSeverity.OK,
    ) else DiagnosisItem(
        "הרשאת התראות",
        "חסרה. השעמור עדיין יצלצל, אבל ההתראה שמלווה אותו לא תוצג ויהיה קשה יותר לעצור אותו.",
        DiagnosisSeverity.WARNING, DiagnosisFix.NOTIFICATIONS,
    )

    items += if (inputs.canUseFullScreenIntent) DiagnosisItem(
        "מסך מלא בזמן צלצול", "מאושר — מסך הצלצול ייפתח גם על מסך נעול.", DiagnosisSeverity.OK,
    ) else DiagnosisItem(
        "מסך מלא בזמן צלצול",
        "חסר. השעמור יצלצל, אבל במקום מסך הצלצול תוצג התראה בלבד — קל לפספס אותה על מסך נעול.",
        DiagnosisSeverity.WARNING, DiagnosisFix.FULL_SCREEN,
    )

    return items
}

/**
 * The one-line verdict shown at the top.
 *
 * Deliberately not a count of problems: "3 בעיות" tells the user nothing about whether
 * their alarm will ring tomorrow. The blocker/warning distinction does.
 */
fun diagnosisHeadline(items: List<DiagnosisItem>): String {
    val blockers = items.count { it.severity == DiagnosisSeverity.BLOCKER }
    val warnings = items.count { it.severity == DiagnosisSeverity.WARNING }
    return when {
        blockers == 1 -> "נמצאה בעיה אחת שתמנע מהשעמור לצלצל."
        blockers > 1  -> "נמצאו $blockers בעיות שימנעו מהשעמור לצלצל."
        // Singular spelled out rather than "1 דברים": the headline is the one line the
        // user reads, and broken agreement there reads as a bug in everything below it.
        warnings == 1 -> "השעמור אמור לצלצל, אבל יש דבר אחד ששווה לתקן."
        warnings > 1  -> "השעמור אמור לצלצל, אבל יש $warnings דברים ששווה לתקן."
        else          -> "הכול תקין — השעמור אמור לצלצל כמתוכנן."
    }
}
