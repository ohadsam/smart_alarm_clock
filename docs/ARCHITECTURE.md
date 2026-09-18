# SmartRing – ארכיטקטורה v3

## שכבות
```
Compose UI → ViewModels (StateFlow) → AlarmRepository → Room DB v3
                                   → AlarmScheduler (AlarmManager)
                                   → AppLogger (app_logs, אבחון)
                                   → WorkManager (reschedule + log cleanup + widget refresh)
Glance Widgets (SmartRingWidget.kt) → AlarmScheduler.effectiveNextFireTime() ← WidgetRefresher
```

## מסכים
| מסך | Route |
|-----|-------|
| AlarmListScreen | /list (מציג WhatsNewDialog אחרי עדכון גרסה) |
| AlarmEditScreen | /edit/{id}?name&hour&minute |
| AlarmRingScreen | /ring/{id} |
| HistoryScreen   | /history |
| SettingsScreen  | /settings |
| LogsScreen      | /logs ← חדש (v1.2.0) |
| DiagnosisScreen | /diagnosis ← חדש (v1.8.1) — "למה השעמור לא צלצל?" |

מחוץ לגרף הניווט: `IntroDialog` (דיאלוג מעל /list ו-/settings, v1.9.0), ו-`AlarmsTileService`
— אריח Quick Settings שהמערכת קושרת אליו ישירות, ללא Activity (v1.9.0).

## DB v3 – עמודות/טבלאות חדשות
- `alarms`: `snoozeEnabled`, `isShabbatMode`
- `app_logs` (טבלה חדשה): לוג טכני נפרד מ-`alarm_logs` (שהוא היסטוריית צלצולים למשתמש)

## Migrations
`di/AppModule.kt` – MIGRATION_1_2, MIGRATION_2_3 (מוסיפה את העמודות/טבלה מעלה).

## חתימת APK ועדכון במקום (v1.2.0)
`app/build.gradle.kts` מגדיר `signingConfigs.create("shared")` המצביע על
`app/smartring.keystore` (מחובר ל-repo בכוונה), ומשתמש בו גם ל-`debug` וגם ל-`release`.
בלי זה, כל build ב-CI מייצר חתימה שונה (debug אקראי, release ללא חתימה כלל) ואי אפשר
להתקין APK חדש מעל הקיים — Android דורש חתימה זהה לעדכון במקום.

## ווידג'טים (v1.3.0)
`SmartRingWidget.kt` (4 גדלים) כולל:
- שעון חי (`res/layout/widget_clock.xml`, `android.widget.TextClock` דרך `AndroidRemoteViews`)
  — מתעדכן בעצמו בתוך תהליך ה-widget host, בלי להעיר את האפליקציה.
- אינדיקציית "בעוד X שע' Y דק'" לשעמור הבא, מחושבת מ-`AlarmScheduler.effectiveNextFireTime()`
  (לא `nextFireTime()` הרגיל — זה לא מודע לנודניק פעיל, שמתוזמן בנפרד דרך `scheduleAt()`
  ונשמר ב-SharedPreferences `pending_snooze` כדי ש-`pendingSnoozeUntil()` יוכל לראות אותו).
- `WidgetFrame()` — מסגרת דקה (Box מקונן, לא `border()` modifier, לתמיכה עקבית בין גרסאות Glance).
- רענון (v1.4.0): רוב שינויי התזמון (`schedule`/`cancel`/`cancelAll`) לא קוראים ל-`WidgetRefresher`
  ישירות יותר — `SmartRingApp.onCreate()` מאזין ל-`AlarmRepository.observeAlarms()` ומרענן על כל
  שינוי בטבלת `alarms`, מה שכל אחת מהפעולות האלה כבר כותבת אליה ממילא. `scheduleAt()` (נודניק)
  ו-`rescheduleAll()` (רק לקריאה מ-`RescheduleWorker` אחרי ריבוט) עדיין מרעננים ישירות, כי הם לא
  תמיד מלווים בכתיבה לטבלה. `WidgetRefreshWorker` רץ כל 15 דקות כ-fallback נוסף.

## ווידג'טים: תיאור, ערכת צבעים ובדיקות (v1.6.0)
- ארבעת קובצי `res/xml/widget_*_info.xml` מגדירים כעת גם `minWidth`/`minHeight`
  (לפי הנוסחה של אנדרואיד, `70 * תאים - 30`). `targetCellWidth`/`targetCellHeight`
  קיימים רק מ-API 31, וה-minSdk כאן הוא 26 — בלעדיהם הווידג'ט לא הכריז על גודל כלל
  באנדרואיד 8–11. בנוסף: `initialLayout` (חובה לפי חוזה `AppWidgetProviderInfo`,
  ומוצג בין הנחת הווידג'ט לרינדור הראשון של Glance), `resizeMode` ו-`description`.
- `SmartRingWidget.kt` בוחר `WidgetPalette` (בהיר/כהה) מ-`Configuration.UI_MODE_NIGHT_MASK`
  בזמן הרינדור, ולא דרך ColorProvider יום/לילה של Glance — אותם ערכים מזינים גם את
  צבע ה-TextClock המוטמע, שהוא ARGB int ולא ColorProvider, ומקור אחד לשניהם מונע מצב
  שבו השעון והתוכן נמצאים בצדדים מנוגדים של הערכה. `SmartRingApp.onConfigurationChanged()`
  מרענן את הווידג'טים בכל מעבר יום/לילה בפועל.
- הלוגיקה שקובעת *מה* מוצג (`buildUpcomingAlarms` ב-`util/UpcomingAlarms.kt`) הוצאה
  מתוך `provideGlance()` והיא פונקציה טהורה שמקבלת את שתי השאילתות כפרמטרים — כך היא
  נבדקת ב-JVM בלי AlarmManager, בלי מארח Glance ובלי שעון אמיתי. `UpcomingAlarm.timeText`
  נגזר מחותמת הזמן האמיתית של הצלצול ולא מ-`Alarm.timeFormatted`, כדי ששעה שמוצגת
  וספירה לאחור שלידה לא יוכלו לסתור זו את זו (שעמור בנודניק הציג "07:00 · בעוד 8 דק׳").

## ווידג'טים: מסגרת, עיגול פינות וספירה לאחור חיה (v1.6.3)
- **המסגרת.** `WidgetFrame` הוא תיבה חיצונית עם `widget_frame_border`, `padding` של
  `WIDGET_BORDER_DP`, ובתוכה תיבה עם `widget_frame_inner`. ה-padding חייב לשבת על
  התיבה ה**חיצונית**: padding של תצוגה מזיז את ילדיה, לא את עצמה, ולכן כשהוא ישב על
  הפנימית (כפי שהיה עד v1.6.3) הגוף מילא את התיבה החיצונית מקצה לקצה וכיסה את הגבול
  לחלוטין — המסגרת לא נראתה באף מכשיר.
- **למה drawables ולא `background(Color)` + `cornerRadius()`.** `cornerRadius()` של
  Glance מתורגם ל-`RemoteViews.setViewOutlinePreferredRadius`, שנוסף ב-API 31; מתחת
  לזה הוא no-op שקט. שלושת ה-shape drawables (`widget_frame_border` r=20dp,
  `widget_frame_inner` r=18dp — ההפרש הוא בדיוק `WIDGET_BORDER_DP` כדי ששתי הקשתות
  יישארו קונצנטריות — ו-`widget_row_bg` r=10dp) מתעגלים בכל API נתמך.
- **צבע מוגדר פעמיים, ובכוונה.** ה-drawables חייבים צבעי XML (`values/colors.xml` +
  `values-night`), והתוכן של Glance משתמש ב-`WidgetPalette` ב-Kotlin. `WidgetPaletteTest`
  מאמת ששתי ההגדרות זהות תחת כל qualifier של מצב לילה — דריפט ביניהן מייצר ווידג'ט
  שהמסגרת והגוף שלו מגיעים מערכות נושא מנוגדות.
- **רקע אטום.** הרקעים נשאו alpha (0xEE/0xF2) ואיפשרו לטפט לחלחל, מה ששינה את הרקע
  בפועל וגזל עד 1.5 stops של ניגודיות (מעל טפט בהיר: 3.5:1 לטקסט שורה, 3.47:1
  להדגשה). הם אטומים כעת, וכל צבע קדמי נבדק מול *שני* הרקעים — גוף הווידג'ט ושורת
  שעמור (`rowBackground` שקוף מעל הגוף).
- **ספירה לאחור.** הווידג'ט מתרענן רק בשינוי שעמור או ב-`WidgetRefreshWorker` כל 15
  דקות, כך שמחרוזת שחושבה ב-`provideGlance()` יכלה לפגר עד רבע שעה. בשעה שלפני
  הצלצול (`LIVE_COUNTDOWN_WINDOW_MS`) מוצג `Chronometer` אמיתי במצב ספירה לאחור
  (`res/layout/widget_countdown.xml` דרך `AndroidRemoteViews`, בדיוק כמו ה-TextClock),
  שמתקתק בתהליך המארח בלי להעיר את האפליקציה. ה-base של Chronometer הוא על שעון
  `elapsedRealtime`, ולכן היעד על שעון הקיר מומר ל"עכשיו + מה שנשאר".

## מה נשמע בשנייה N (v1.6.3)
`Alarm.effectiveRings` / `ringAtSecond()` / `audibleVolumeAtSecond()` הם המודל של מה
שבאמת מתנגן: איזה סבב, או שקט (רטט בלבד, חלון הרטט של "רטט→צלצול", או ההשהיה שאחרי
סבב). `AlarmFiringService.startAudioSequence()` קורא את `effectiveRings` מאותו מקום
במקום להחזיק עותק של כללי "רשימה ריקה" ו-`orderIndex`, ו-`AlarmRingScreen` מצייר את
מד ההתחזקות מ-`audibleVolumeAtSecond()`. עד v1.6.3 המסך גזר מספר משלו מול בסיס קשיח
של 100% — גזירה שנייה שלא יכלה שלא להיפרד מהנגינה בפועל.

`util/RingSetup.kt` מחזיק את כללי האזהרה על תצורות שהסליידרים מרשים אך שמתנהגות אחרת
ממה שהמסך מבטיח. פונקציה טהורה בלי טיפוסי אנדרואיד, כדי שהכללים ייבדקו ישירות ולא דרך
מסך Compose.

## מסך הצלצול ואמינות (v1.4.0)
- `AlarmRingViewModel` מחשב את הזמן שחלף מאז הצלצול לפי `SystemClock.elapsedRealtime()`,
  מעוגן ל-timestamp האמיתי (`alarm_logs` action='FIRED'), ולא ספירת טיקים מקומית של המסך —
  כך שהמסך נסגר בזמן אמיתי גם אם הוא נפתח מחדש (rotation) ולא מושפע מקפיצת שעון (DST/NTP).
  משמש כרשת ביטחון (עם buffer של 2 שניות) לצד הטיימר האמיתי של `AlarmFiringService`, למקרה
  שהשירות לא נסגר בזמן.
- `AlarmFiringService` כותב את רשומת ה-`FIRED` ל-DB *לפני* פרסום ההתראה (לא אחריה) —
  ה-full-screen intent יכול לפתוח את מסך הצלצול כמעט מיידית, וסדר הפוך השאיר חלון שבו
  מסך הצלצול קורא timestamp ישן מצלצול קודם.

## תזמון ואמינות ברקע (v1.5.0)
- `AlarmScheduler.armExact()` היא הנקודה היחידה שבה מזוינת אזעקה: מעדיפה
  `AlarmManager.setAlarmClock()` (פטור מ-Doze לחלוטין, ומציג את סמל השעמור הבא במכשיר)
  ונופלת ל-`setAndAllowWhileIdle()` כשאין הרשאת שעמורים מדויקים — במקום לזרוק
  `SecurityException` ולהפיל את מי שקרא לה. `setExactAndAllowWhileIdle()` לא בשימוש
  יותר: הוא מוגבל ל-~פעם ב-9 דקות במצב idle, מה שמאחר נודניק קצר.
- `AlarmScheduler.nextRecurringFireTime()` = "האם נשאר משהו אחרי הצלצול הזה?" — נפרדת
  מ-`nextFireTime()`, שה-fallback שלה ("אותה שעה מחר") נחוץ כדי לזיין שעמור חדש אבל
  היה מה שהפך כל שעמור חד-פעמי ליומי. `AlarmFiringService` מחליט לפיה בין תזמון מחדש
  לכיבוי השעמור.
- `AlarmFiringService`: אוחז `PARTIAL_WAKE_LOCK` לכל אורך הצלצול (+ `MediaPlayer.setWakeMode`)
  כי foreground service לבדו לא מבטיח CPU ער; קורא ל-`stopAll()` בתחילת כל
  `onStartCommand` כדי שצלצול שני לא ירוץ במקביל ויותיר `MediaPlayer` דלוף שממשיך לנגן;
  משחרר את ה-player ב-`finally`; ומתזמן את המופע הבא *לפני* תחילת הצלצול.
- `BootReceiver` מאזין גם ל-TIME_SET/TIMEZONE_CHANGED (אזעקות הן timestamp מוחלט שנגזר
  מהשעון המקומי), ו-`RescheduleWorker` מחזיר `retry()` במקום להיכשל.
- ערוץ ההתראות (`AlarmNotifications`) מושתק (`setSound(null,null)`, `enableVibration(false)`)
  כי השירות מנגן את הצליל והרטט בעצמו; מזהה חדש (`_v2`) כי הגדרות ערוץ אינן ניתנות
  לשינוי אחרי יצירה.
- `snoozeCountSinceLastFire` נמדד מהפעם האחרונה שהמופע *הסתיים* (STOPPED/MISSED) ולא
  מ-FIRED האחרון — כל צלצול-מחדש של נודניק כותב FIRED בעצמו, ולכן המגבלה מעולם לא נאכפה.

## בדיקות אוטומטיות (v1.4.1, הורחב ב-v1.5.0)
`app/src/test/` — JUnit4 + Robolectric (סביבת אנדרואיד על ה-JVM, לא אמולטור אמיתי) + mockk,
רץ ב-CI לפני assembleDebug/Release. `AlarmScheduler.nextFireTime()` מקבל `now: Long`
אופציונלי (ברירת מחדל: השעון האמיתי) בדיוק כדי לאפשר בדיקה דטרמיניסטית של חישובי
WEEKLY/BIWEEKLY/MONTHLY.

`app/src/androidTest/` (v1.5.0) — בדיקות instrumented על אמולטור API 30 ב-job נפרד
ב-CI, למה ש-Robolectric לא יכול לאמת: ש-`AlarmManager` האמיתי אכן רושם alarm-clock,
SQLite אמיתי, הגדרות ערוץ ההתראות האמיתי, ועליית האפליקציה דרך גרף Hilt אמיתי.
פירוט מלא ב-HANDOFF.md סעיף 13.

## צבעים וערכת נושא (v1.6.0)
ארבעת קבועי הצבע ב-`Theme.kt` (Blue/Green/Gold/Red) מכוונים למשטחים הכהים של הערכה
הכהה ואינם קריאים כטקסט או כ-tint על משטח לבן. לכן מסכים קוראים את הגוונים דרך
*תפקידי* הצבע של Material (`primary`=Blue, `secondary`=Gold, `tertiary`=Green,
`error`=Red), שמחזיקים ערך נפרד לכל ערכה, ומשתמשים בקבועים הגולמיים רק כשהרקע עצמו
הוא צבע כהה קבוע (למשל טקסט לבן על כפתור העצירה האדום).

## חישוב מועד הצלצול הבא (v1.6.2)
`nextFireTime()` מחשיב שלושה מקורות, ובוחר את המוקדם מביניהם:
1. `specificDateTime` — שעמור לרגע יחיד; בלעדי, זהו סוג שעמור אחר.
2. `specificDates` — תאריכים שהמשתמש הוסיף, **בנוסף** ללוח השבועי.
3. החזרה השבועית (`repeatDaysBitmask` + `repeatFrequency`), עם קיצוץ לפי
   `recurrenceEnd.untilDate` כשסוג הסיום הוא UNTIL.

שני הראשונים היו קודם בלעדיים זה לזה: החזרת התאריך הקרוב מתוך (2) עצרה בפועל את (3),
ולכן הוספת תאריך אחד לשעמור יומי ביטלה את היומיות שלו. הקיצוץ ב-(3) חייב לחול על
*המועד המועמד*, לא רק דרך `isRecurrenceExpired()` (ששואל רק אם התאריך כבר עבר) —
אחרת שעמור "עד ה-20" מזוין למופע הבא שלו ב-21 ומצלצל פעם אחת אחרי הסוף שנבחר.

## שתי מוסכמות תאריך (v1.6.1)
`util/CalendarDates.kt` הוא המקום היחיד שממיר ביניהן, אחרי ארבעה באגים שכולם נבעו
מבלבול בין השתיים:
- **יום שנבחר** — מה ש-`DatePicker` של Compose מחזיר ומה ש-`AlarmDate.date` שומר:
  חצות **UTC** של אותו יום. זה שם של יום, לא רגע, ואסור לקרוא אותו עם לוח שנה או
  מעצב טקסט מקומיים.
- **רגע אמיתי** — `Alarm.specificDateTime` ו-`RecurrenceEnd.untilDate`: נקודת זמן על
  השעון המקומי של המכשיר.

ערבוב ביניהן מזיז את התאריך בגודל היסט ה-UTC של המכשיר — בישראל (היסט חיובי) השגיאה
נשארת בתוך אותו יום ולכן בלתי נראית, וממערב לגריניץ' היא יום שלם. לכן
`CalendarDatesTest` מריץ כל מקרה גם בהיסט חיובי וגם בשלילי.

## דור צלצול ב-AlarmFiringService (v1.6.2)
כל קריאה ל-`onStartCommand` מקבלת `generation` עולה. `stopAll()` מפרק רק את צד
*הצלצול* (`ringJob` והג'objים שמתחתיו); קוד רישום התזמון של הצלצול שנדחק ממשיך לרוץ
עד הסוף ורק מדלג על החלק שמשמיע קול. קודם לכן בוטלה כל הקורוטינה, והיא כמעט תמיד
הייתה תלויה עדיין בקריאת מסד הנתונים הראשונה — כך ששני שעמורים באותה דקה גרמו לראשון
לא לתזמן את המופע הבא של עצמו כלל, ושעמור יומי נעצר באותו יום.

## שרשרת האמינות של צלצול יחיד (v1.6.4)

הצלצול עובר דרך ארבעה רכיבים, ולכל מעבר ביניהם יש דרך שקטה להיכשל:

1. **AlarmManager → AlarmReceiver.** המערכת מחזיקה נעילת מעבד רק למשך `onReceive`.
2. **AlarmReceiver → AlarmFiringService.** `startForegroundService()` אסינכרוני, ולכן
   המכשיר יכול לחזור לישון בין השניים. `AlarmHandoffWakeLock` נתפס לפני הפעלת השירות
   ומשוחרר ב-`onStartCommand` מיד אחרי `acquireWakeLock()`; פסק זמן של דקה הוא הרשת
   שמונעת נעילה תקועה אם השירות לא עלה בכלל (מצב סוללה "מוגבל").
3. **השירות → MediaPlayer.** `startPlayer()` מחזיר `null` במקום לזרוק, ו-`playOneRing()`
   יורד מדרגה: הצליל שנבחר ← צליל ברירת המחדל של המכשיר ← רטט. עד v1.6.4 חריגה
   מ-`setDataSource()` עלתה דרך לולאת הרצף והרגה את הקורוטינה — שעמור שלם ללא צליל.
4. **תזמון המופע הבא.** נעשה לפני שהצלצול מתחיל (ראה סעיף "דור צלצול") כדי שעצירה
   מוקדמת או הריגת התהליך לא ישאירו שעמור חוזר בלי מופע מתוזמן.

מעבר לכך, הרשאת "שעמורים מדויקים" היא נקודת כשל בפני עצמה: ביטולה מוחק את כל התזמונים
הקיימים והחזרתה לא משחזרת אותם, ולכן `BootReceiver` מטפל ב-
`SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` לצד אתחול ושינויי שעון — כולם מובילים
לאותו `RescheduleWorker`.

## הפיכת לוגיקה לניתנת לבדיקה (הדפוס החוזר)

חלק מהכללים החשובים ביותר באפליקציה ישבו בתוך רכיבי אנדרואיד שאף סוויטת בדיקות לא
יכולה להגיע אליהם. הדפוס שחוזר כאן בכל פעם: להוציא את *ההחלטה* לפונקציה טהורה, ולהשאיר
לרכיב רק את ביצועה.

| הכלל | איפה הוא היה | לאן עבר |
|------|--------------|---------|
| מה הווידג'טים מציגים ובאיזה סדר | בתוך `provideGlance()` | `util/UpcomingAlarms.kt` (v1.6.0) |
| מה נשמע בשנייה N | פעמיים — בשירות ובמסך הצלצול | `Alarm.ringAtSecond()`/`audibleVolumeAtSecond()` (v1.6.3) |
| תצורות צלצול שלא יעבדו כמובטח | בשום מקום (לא הייתה אזהרה) | `util/RingSetup.kt` (v1.6.3) |
| תווית "היום/מחר" | בתוך `AlarmEditViewModel` | `formatNextFireAt()` ב-`TimeFormat.kt` (v1.6.4) |
| כללי שני כפתורי ההתראה | בתוך שני `BroadcastReceiver`-ים | `util/NotificationActions.kt` (v1.6.5) |

הסיבה שהאחרון היה הדחוף מכולם: `goAsync()` דורש תוצאת שידור חיה ו-`@AndroidEntryPoint`
דורש את גרף Hilt, כך ששתי ההבטחות החזקות ביותר של האפליקציה — שמצב שבת לא מקבל שום
אינטראקציה בשום מקום, ושתקרת הנודניקים אמיתית — לא היו מכוסות כלל. שני כיווני הכשל שם
מזיקים: סירוב ללחיצה שהמשתמש זכאי לה משאיר טלפון מצלצל, וקבלה של לחיצה שאסור לקבל
שוברת את מצב שבת ממסך הנעילה, המקום היחיד שבו הבדיקה שבמסך הצלצול לא רצה בכלל.

## Security
allowBackup=false · exported=false · FLAG_IMMUTABLE · ProGuard · prepareAsync() · startForeground() ראשון
