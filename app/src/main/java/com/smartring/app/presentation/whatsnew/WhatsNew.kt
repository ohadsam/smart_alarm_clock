package com.smartring.app.presentation.whatsnew

data class WhatsNewEntry(
    val versionCode: Int,
    val versionName: String,
    val items: List<String>,
)

/**
 * One entry per shipped version, oldest first. WhatsNewViewModel shows every entry
 * newer than the last version the user actually saw, so upgrading across several
 * versions at once (e.g. after being offline) doesn't skip anything.
 *
 * Add a new entry here as part of every release (see the release-checklist skill's
 * "Version + What's New" step) — versionCode must match app/build.gradle.kts's.
 */
val WHATS_NEW_HISTORY = listOf(
    WhatsNewEntry(
        versionCode = 3,
        versionName = "1.2.0",
        items = listOf(
            "עורך \"סבבי צלצול\" מלא — עד 10 סבבים, כל אחד עם צליל (בורר מהמערכת), עוצמה, משך והשהיה משלו",
            "מצב שבת לשעמור בודד — כפתורי עצירה/נודניק מושבתים לגמרי בזמן הצפצוף",
            "אפשרות לכבות נודניק לגמרי עבור שעמור ספציפי",
            "הזנת מספר מדויקת לצד כל סליידר (לא רק גרירה), עם תצוגת זמן קריאה (\"1 דק' 30 שנ'\")",
            "כפתור מידע (ⓘ) ליד שדות ההגדרה שמסביר מה כל שדה עושה",
            "מסך לוגים חדש (הגדרות ← לוגים): צפייה, העתקה, הורדה וניקוי של רישום פעולות טכני, עם ניקוי אוטומטי אחרי 3 ימים",
            "בדיקות אמינות ברקע בהגדרות: התראות, שעמורים מדויקים ואופטימיזציית סוללה — עם קישור ישיר לתיקון",
            "היסטוריה: \"טען שוב\" ממלא את השם והשעה האמיתיים במקום תמיד 07:00",
            "לחיצה על הווידג'ט פותחת את האפליקציה; מחיקת שעמור בהחלקה בנוסף ללחיצה ארוכה",
            "עדכון גרסה מותקן כעת מעל הגרסה הקודמת — אין צורך להסיר ולהתקין מחדש",
            "תיקוני יציבות: השעמור מפסיק להתריע כשאמור, נודניק לא מצלצל שוב אחרי מחיקה, ותיקוני RTL/ניגודיות",
        ),
    ),
)
