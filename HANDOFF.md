# SmartRing – Handoff Document for Claude Code
# סיכום מצב הפרויקט להמשך עבודה

**גרסה:** v5-final | **תאריך:** יוני 2026

---

## 1. מה הפרויקט

**SmartRing** – אפליקציית שעון מעורר מלאה לאנדרואיד בלבד.

| פרט | ערך |
|-----|-----|
| שפה | Kotlin 2.0.0 |
| UI | Jetpack Compose + Material 3 |
| ארכיטקטורה | MVVM + Repository + Hilt DI |
| DB | Room **v3** (migrations 1→2→3 קיימות) |
| תזמון | AlarmManager `setAlarmClock()` (פטור מ-Doze, מציג את סמל השעמור הבא; v1.5.0) |
| ווידג'ט | Glance API (4 גדלים) |
| CI/CD | GitHub Actions – מייצר APK |
| minSdk | 26 (Android 8.0) |
| targetSdk | 34 |

---

## 2. פיצ'רים מיושמים ✅

| # | פיצ'ר | קבצים מרכזיים |
|---|-------|--------------|
| 1 | שם לשעמור | `Alarm.name`, `AlarmEditScreen` |
| 2 | תאריך + שעה ספציפיים | `Alarm.specificDateTime`, `DateTimePickerInline` |
| 3 | חזרתיות עשירה (WEEKLY/BIWEEKLY/MONTHLY) | `Alarm.repeatDaysBitmask`, `AlarmScheduler.nextFireTime()` — ברירת מחדל לשעמור חדש (0 ימים) מוצגת כחד-פעמי, לא כ"שבועי" נבחר (v1.4.0), ובאמת מכובה אוטומטית אחרי צלצול אחד (`nextRecurringFireTime()`, v1.5.0) |
| 4 | סיום חזרתיות (FOREVER/UNTIL/COUNT) | `RecurrenceEnd`, `RecurrenceEndSection` |
| 5 | צלצולים חוזרים (עד 10) | `AlarmRing`, `AlarmDao`, `AlarmFiringService.startAudioSequence()` (מנגן ברצף, v1.1.0), `RingsSection` ב-`AlarmEditScreen` (UI לעריכה, v1.1.0) |
| 6 | רטט 4 מצבים | `VibrationMode`, `AlarmFiringService.fireAlarm()` |
| 7 | Crescendo | `Alarm.volumeAtSecond()`, `startCrescendo()` |
| 8 | נודניק (עם reschedule מהמסך) | `AlarmRingViewModel.snooze()` + `AlarmScheduler.scheduleAt()`; מגבלת `snoozeMaxCount` נאכפת מההיסטוריה (`snoozeCountSinceLastFire`, תוקן v1.5.0) ושורד ריבוט (`rescheduleAll`, v1.5.0) |
| 9 | טקסט תזכורת | `Alarm.reminderText` |
| 10 | ניהול גלובלי | GlobalControls BottomSheet, freeze/unfreeze toggle חכם |
| 11 | מסך היסטוריה | `HistoryScreen`, `HistoryViewModel`, `AlarmLog` |
| 12 | טעינת שעמור מהיסטוריה | `onLoadAlarm` callback, כפתור Replay, `AlarmEditViewModel.prefill()` (v1.1.0) |
| 13 | Dirty-state check | `BackHandler` + `isDirty` + dialog אישור |
| 14 | ווידג'ט 4 גדלים | `SmartRingWidget.kt`, Glance API |
| 15 | עברית RTL + אנגלית | `values/strings.xml`, `values-en/strings.xml` |
| 16 | Dark/Light Mode | `SmartRingTheme`, `SettingsViewModel` |
| 17 | FLAG_KEEP_SCREEN_ON | `AlarmRingScreen` + `DisposableEffect` |
| 18 | Boot reschedule | `BootReceiver` → `RescheduleWorker` |
| 19 | הפעלה/כיבוי נודניק לשעמור בודד | `Alarm.snoozeEnabled`, מתג ב-`AlarmEditScreen` (v1.2.0) — ברירת מחדל כבוי מ-v1.4.0 |
| 20 | מצב שבת (חוסם אינטראקציה בזמן צפצוף) | `Alarm.acceptsInteraction`, `AlarmRingScreen`, `buildNotification()` (v1.2.0) |
| 21 | הזנת מספר מדויקת + תצוגת זמן קריאה | `EditableValueBadge`, `formatDurationSeconds()` (v1.2.0) |
| 22 | כפתורי מידע (ⓘ) על שדות הגדרה | `FieldLabel` ב-`AlarmEditScreen` (v1.2.0) |
| 23 | מסך לוגים טכני (צפייה/העתקה/הורדה/ניקוי) | `LogsScreen`, `AppLogger`, `LogCleanupWorker` (v1.2.0) |
| 24 | בדיקות אמינות ברקע (התראות/שעמורים מדויקים/סוללה/מסך מלא/עוצמת שעמור) | `ReliabilityChecks`, `SettingsScreen` (v1.2.0; מסך מלא + עוצמה נוספו v1.5.0); `ReliabilityGate` ב-`AlarmListScreen` מציע זאת פרואקטיבית בכניסה לאפליקציה (v1.4.0) |
| 25 | חלון "מה חדש" אחרי עדכון גרסה | `WhatsNewDialog`, `WhatsNewViewModel` (v1.2.0) |
| 26 | עדכון APK במקום (ללא הסרה+התקנה) | `signingConfigs` משותף ב-`build.gradle.kts` (v1.2.0) |
| 27 | שעון חי בווידג'טים (ללא העיר את האפליקציה) | `widget_clock.xml` (TextClock) + `AndroidRemoteViews`, `SmartRingWidget.kt` (v1.3.0) |
| 28 | אינדיקציית זמן עד לשעמור הבא בווידג'טים | `AlarmScheduler.effectiveNextFireTime()`/`pendingSnoozeUntil()`, `formatCountdownUntil()`, `WidgetRefresher` (v1.3.0) |
| 29 | מסגרת דקה סביב הווידג'טים | `WidgetFrame()` ב-`SmartRingWidget.kt` (v1.3.0) |
| 30 | מסך צלצול נסגר אוטומטית בתום משך הצלצול | `AlarmRingViewModel.tick()` (עוגן ל-`SystemClock.elapsedRealtime()` + timestamp אמיתי, לא ספירה מקומית) (v1.4.0) |
| 31 | גלילה אוטומטית לשדה שם בשגיאת ולידציה | `AlarmEditViewModel.scrollToNameRequests`, `AlarmEditScreen` (v1.4.0) |
| 32 | סיכום ימי חזרה בכרטיס ברשימה ("כל יום"/"ימי חול"/"חד־פעמי"/תאריך) | `Alarm.scheduleSummary()`, `AlarmListScreen` (v1.5.0) |
| 33 | תזמון מחדש אחרי שינוי אזור זמן/שעת מכשיר | `BootReceiver` (TIME_SET/TIMEZONE_CHANGED) → `RescheduleWorker` (v1.5.0) |
| 34 | wake lock + `setWakeMode` בזמן צלצול (מסך כבוי) | `AlarmFiringService.acquireWakeLock()`, `playOneRing()` (v1.5.0) |
| 35 | התראה חלופית אם המערכת חוסמת הפעלת שירות הצלצול | `AlarmNotifications.postFallback()`, `AlarmReceiver` (v1.5.0) |
| 36 | ווידג'טים מותאמים למצב תצוגה בהיר/כהה | `WidgetPalette`/`paletteFor()` ב-`SmartRingWidget.kt`, `SmartRingApp.onConfigurationChanged()` (v1.6.0) |
| 37 | תיאור ווידג'ט תקין (גודל מינימלי, פריסת ביניים, שינוי גודל, תיאור בבורר) | `res/xml/widget_*_info.xml`, `res/layout/widget_loading.xml` (v1.6.0) |
| 38 | רטט שעמור פטור מ-DND | `AlarmFiringService.startVibration()` עם `VibrationAttributes.USAGE_ALARM` (v1.6.0) |
| 39 | ביטול תזמון קודם כששעמור נערך למצב שלא מצלצל | `AlarmScheduler.schedule()` (v1.6.0) |
| 40 | אזהרה במסך העריכה כשאין מועד צלצול עתידי | `AlarmEditUiState.neverFires`, `AlarmEditScreen` (v1.6.0) |
| 41 | חסימת "חזור" בזמן צלצול (כולל מצב שבת) | `BackHandler` ב-`AlarmRingScreen` (v1.6.0) |
| 42 | סימון "מוקפא — לא יצלצל" בכרטיס ברשימה | `AlarmListScreen` MiniBadge (v1.6.0) |
| 43 | מקור אחד להמרות תאריך (יום שנבחר מול רגע מקומי) | `util/CalendarDates.kt` (v1.6.1) |
| 44 | מחיקה בהחלקה גם בהיסטוריה | `HistoryScreen` SwipeToDismissBox (v1.6.1) |
| 45 | ייצוא לוגים ברקע עם טיפול בשגיאה | `LogsScreen` (v1.6.1) |
| 46 | תאריכים ספציפיים פועלים *בנוסף* לחזרה השבועית, לא במקומה | `AlarmScheduler.nextFireTime()` (v1.6.2) |
| 47 | "עד תאריך" נאכף על מועד הצלצול עצמו, לא רק על "האם עבר" | `AlarmScheduler.nextFromRecurrence()` (v1.6.2) |
| 48 | שעמור שנדחק ע"י שעמור אחר באותה דקה עדיין משלים תזמון | `AlarmFiringService` generation counter (v1.6.2) |
| 49 | מסגרת הווידג'ט נראית בפועל (הרקע הפנימי הסתיר אותה לחלוטין) | `WidgetFrame` ב-`SmartRingWidget.kt` — padding על התיבה החיצונית (v1.6.3) |
| 50 | פינות מעוגלות לווידג'טים גם מתחת ל-API 31 | `res/drawable/widget_frame_border.xml`/`widget_frame_inner.xml`/`widget_row_bg.xml` (v1.6.3) |
| 51 | ניגודיות AA לכל טקסט בווידג'ט, כולל על שורת שעמור; רקע אטום | `WidgetPalette` + `WidgetPaletteTest` (v1.6.3) |
| 52 | ספירה לאחור חיה בווידג'ט בשעה שלפני הצלצול | `Chronometer` ב-`res/layout/widget_countdown.xml` דרך `AndroidRemoteViews` (v1.6.3) |
| 53 | מד "צלצול מתחזק" מציג את העוצמה שבאמת נשמעת, ורק כשמתנגן צליל | `Alarm.audibleVolumeAtSecond()`/`ringAtSecond()`, `AlarmRingScreen` (v1.6.3) |
| 54 | אזהרות על תצורות צלצול/רטט/התחזקות שלא יעבדו כמובטח | `util/RingSetup.kt`, `AlarmEditScreen` (v1.6.3) |
| 55 | תזמון מחדש אחרי שינוי הרשאת "שעמורים מדויקים" | `BootReceiver` (SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) (v1.6.4) |
| 56 | נפילה חלקה לצליל ברירת המחדל (ואז לרטט) כשהצליל שנבחר לא ניתן לניגון | `AlarmFiringService.startPlayer()`/`playOneRing()` (v1.6.4) |
| 57 | בקשת הרשאת קריאת שמע לפני בורר הצלצולים | `RingsSection` ב-`AlarmEditScreen` (v1.6.4) |
| 58 | נעילת מעבד רציפה מקבלת ההתראה ועד עליית שירות הצלצול | `util/AlarmHandoffWakeLock.kt` (v1.6.4) |
| 59 | מסך צלצול אחד בלבד בכל רגע, ובלי לאבד את מיקום הניווט | `SmartRingNavGraph` (v1.6.4) |
| 60 | הודעת שגיאה כששמירת שעמור נכשלת (במקום טעינה אינסופית) | `AlarmEditUiState.saveError`, `AlarmEditScreen` (v1.6.4) |
| 61 | כללי כפתורי ההתראה (שבת/נודניק כבוי/תקרת נודניקים) כפונקציה טהורה ונבדקת | `util/NotificationActions.kt` (v1.6.5) |
| 62 | ניגודיות AA לכל תפקיד צבע בשתי הערכות, כולל טקסט על כפתור צבוע | `presentation/theme/Theme.kt` + `ThemeContrastTest` (v1.6.6) |
| 63 | מעבר בטוח למסכי הגדרות מערכת (עם נפילה חלקה למסך האפליקציה) | `util/SystemScreens.kt` (v1.6.6) |
| 64 | Android Lint רץ ב-CI (שגיאות מפילות את הבילד, הדוח מודפס ללוג) | `lint {}` ב-`app/build.gradle.kts` + שלב ב-`build-apk.yml` (v1.6.7) |
| 65 | בדיקת עשן ל-APK ה-release המוקטן (R8 + shrinkResources) על האמולטור | `build-apk.yml`, job `instrumented` (v1.6.7) |
| 66 | קבועי הצבע הגולמיים private — מסכים חייבים לעבור דרך תפקידי הצבע | `presentation/theme/Theme.kt` (v1.6.7) |
| 67 | כללי keep ל-R8 לכל מה ש-Hilt מאתר לפי שם (ViewModel/EntryPoint/Worker) | `app/proguard-rules.pro` (v1.6.8) |
| 68 | כללי keep ל-R8 לגשר בין `LocalLifecycleOwner` של lifecycle לזה של compose-ui — בלעדיהם ה-APK הסופי קורס בפתיחה | `app/proguard-rules.pro` (v1.6.9) |
| 69 | הבדיקה ששומרת על שני אלה: `ProguardRulesTest` נכשלת אם כלל keep קריטי נמחק | `ProguardRulesTest` (v1.6.9) |
| 70 | הטסטים על האמולטור רצים על מטריצת API 26/30/34 (minSdk / baseline / targetSdk) | `.github/workflows/build-apk.yml` (v1.6.10) |
| 71 | טסט קצה-לקצה של יצירת שעמור דרך ה-UI, כולל אימות שנשמר וקיבל מועד עתידי | `AlarmCreationFlowInstrumentedTest` (v1.6.10) |
| 72 | בדיקת העשן מאמתת גם שהפלטפורמה רואה את ספקי הווידג'טים ב-APK המוקטן | `scripts/release-smoke-test.sh` (v1.6.10) |
| 73 | `SCHEDULE_EXACT_ALARM` מוגבל ל-API 32; מ-33 ואילך רק `USE_EXACT_ALARM` | `AndroidManifest.xml` (v1.6.10) |
| 74 | סכמת Room מקובצת ב-git; הבילד נכשל אם הסכמה שנוצרה שונה מזו שמקובצת | `app/schemas/` + שלב ב-`build-apk.yml` (v1.6.10) |
| 75 | החצי המהיר של אותה בדיקה — נכשל בשניות אם `@Database(version)` זז בלי JSON תואם | `RoomSchemaTest` (v1.6.10) |
| 76 | פלט Gradle של האמולטור נשמר ומודפס בכישלון; כשלי JUnit מודפסים כשלב אחרון | `scripts/instrumented-test.sh` + `build-apk.yml` (v1.6.10) |
| 77 | הזנת משך זמן בשני שדות (דקות/שניות) עם תצוגה מקדימה, וולידציה וגלישה אוטומטית | `util/DurationInput.kt` + `DurationInputDialog` (v1.6.11) |
| 78 | כל שורת תווית+פקד מקבלת `weight(1f)` — מתג לא נצבע על הטקסט שלצידו | `AlarmEditScreen`/`HistoryScreen`/`LogsScreen`/`AlarmRingScreen` (v1.6.11) |
| 79 | כפתורי התאריך והשעה ברוחב מלא; שעה עם `softWrap = false` ולא נשברת לשורות | `DateTimePickerInline` (v1.6.11) |
| 80 | התרעה על שעמור מאפליקציה אחרת (כבוי כברירת מחדל) — זיהוי בלבד, כיבוי בלתי אפשרי ב-Android | `util/ForeignAlarms.kt` + `ForeignAlarmBanner` (v1.6.12) |
| 81 | שמות מתודות ב-androidTest חייבים להיות camelCase — D8 פוסל רווחים מתחת ל-API 30 | `DurationInputInstrumentedTest` (v1.6.12) |
| 82 | מתג "השעמור פעיל" במסך העריכה; הפעלה מחדש מאפסת את מונה החזרות | `AlarmEditViewModel` (v1.7.0) |
| 83 | תאריך ספציפי שעבר נדחה בשמירה — לא נשמר-ואז-מבוטל | `AlarmEditViewModel.save` (v1.7.0) |
| 84 | `Alarm.hasFinished` — שעמור חד-פעמי שצלצל; מוצג עם קו חוצה ותגית | `domain/model/Alarm.kt` + `AlarmFinishedTest` (v1.7.0) |
| 85 | שכפול פותח שעמור חדש לא-שמור (ולכן עובר דרך דחיית התאריך שעבר) | `NavGraph` + `loadAsCopy` (v1.7.0) |
| 86 | 15 ברירות מחדל לשעמור חדש, עם שחזור פרטני לכל אחת | `AlarmDefaultsRepository` (v1.7.0) |
| 87 | השמעת הצליל של סבב בעוצמה שנבחרה, דרך ערוץ ההתראה האמיתי | `util/RingtonePreview.kt` (v1.7.0) |
| 88 | אבחון בהגדרות: מה רשום במערכת (שלנו / של אפליקציה אחרת / כלום) | `ForeignAlarms.nextRegistered` (v1.7.0) |
| 89 | הסבר מחושב למה שיישמע (אורך מחזור, מספר חזרות) במקום פרוזה | `util/RingPlan.kt` + `RingPlanTest` (v1.7.1) |
| 90 | שעמור מזדמן = one-off עם `specificDateTime`; ללא מיגרציה | `Alarm.isOneOffDated` (v1.7.1) |
| 91 | "היום" נדחה אם השעה עברה; "תזמן ליום הבא" לא מייצר אף פעם זמן בעבר | `util/OccasionalAlarm.kt` + `OccasionalAlarmTest` (v1.7.1) |
| 92 | `scheduleForNextDay` קורא מחדש לפי id — `saveAlarm` מחליף סבבים ותאריכים במלואם | `AlarmListViewModel` (v1.7.1) |
| 93 | כפתור הפעלה/כיבוי לכל שעמור בווידג'ט; הווידג'טים מציגים גם שעמורים כבויים | `ToggleWidgetAlarmAction` + `util/WidgetRows.kt` (v1.8.0) |
| 94 | הפעלה מהווידג'ט של שעמור מזדמן שעבר — מתזמנת ליום הבא במקום להדליק ללא צלצול | `ToggleWidgetAlarmAction` (v1.8.0) |
| 95 | אייקון אפור לווידג'ט ריק, עם הבחנה בין "אין פעיל" ל"אין בכלל" | `ic_widget_alarm_off.xml` + `WidgetEmptyState` (v1.8.0) |
| 96 | כלל keep ל-Glance ActionCallback — נטען לפי שם, אחרת כל כפתור ווידג'ט מושתק ב-release | `app/proguard-rules.pro` + `ProguardRulesTest` (v1.8.0) |
| 97 | כלל בחירה אחד לווידג'טים; `buildUpcomingAlarms` נמחק לטובת `buildWidgetRows` | `util/WidgetRows.kt` + `WidgetRowsTest` (v1.8.0) |
| 98 | בדיקת צלצול במסך מרחב-קודי-בקשה שלישי (`id + 200_000`) — רֵהרסל לא דורס את התזמון האמיתי | `AlarmScheduler.scheduleTestRing()` (v1.8.1) |
| 99 | `EXTRA_IS_TEST` — בדיקה לא כותבת היסטוריה, לא מקדמת מונה חזרות ולא מתזמנת מחדש | `AlarmReceiver` + `AlarmFiringService` (v1.8.1) |
| 100 | מסך "למה השעמור לא צלצל?" — 7 בדיקות מדורגות (BLOCKER/WARNING/OK) עם כפתור תיקון לכל אחת | `util/RingDiagnosis.kt` (טהור, נבדק) + `presentation/diagnosis/` (v1.8.1) |
| 101 | מחיקה מיידית עם "בטל" בסנאקבר במקום דיאלוג אישור; שחזור תחת ה-id המקורי | `AlarmListViewModel.undoDelete()` + נפילה ל-insert ב-`saveAlarmTransaction` (v1.8.1) |
| 102 | `@Update` על שורה שנמחקה משנה 0 שורות ולא זורק — לכן `updateAlarm` מחזיר `Int` ויש נפילה ל-`insertAlarm` | `AlarmDao.saveAlarmTransaction()` + `AlarmDaoTest` (v1.8.1) |
| 103 | יצירה מהירה: "עוד שעה" / "עוד 8 שעות" / "מחר" — שעמור מזדמן מברירות המחדל בלחיצה אחת | `quickAlarmInHours`/`quickAlarmTomorrowAt` ב-`util/OccasionalAlarm.kt` (v1.8.1) |
| 104 | לחיצה ארוכה בוחרת במקום למחוק; בחירה מרובה עם הפעלה/כיבוי/מחיקה קבוצתית | `AlarmListViewModel.selectedIds` + `AlarmListScreen` (v1.8.1) |
| 105 | קיבוץ הרשימה לפי מועד הצלצול האמיתי; "פעיל + יש מועד" הוא תנאי אחד | `util/AlarmSections.kt` + `AlarmSectionsTest` (v1.9.0) |
| 106 | החלוקה סופרת ימי לוח ולא שעות — 23:30 + 40 דק' הוא "מחר"; צעידה על הלוח בגלל שעון קיץ | `calendarDaysBetween` ב-`util/AlarmSections.kt` (v1.9.0) |
| 107 | `AlarmListUiState.rows` — כל שעמור עם מועד הצלצול מהמתזמן (נודניק/חזרתיות שנגמרה) | `AlarmListViewModel` (v1.9.0) |
| 108 | חיפוש לפי שם מעל 10 שעמורים; נשאר גלוי בבחירה מרובה | `SEARCH_VISIBLE_FROM`/`filterAlarms` (v1.9.0) |
| 109 | שמירה של שעמור ללא מועד עתידי שואלת; מחושב מהמתזמן בזמן השמירה, לא מ-`neverFires` | `AlarmEditViewModel.save(force)` (v1.9.0) |
| 110 | אריח הגדרות מהירות דו-כיווני; `exported=true` + `BIND_QUICK_SETTINGS_TILE` | `service/AlarmsTileService.kt` + `AndroidManifest.xml` (v1.9.0) |
| 111 | הסבר פתיחה חד-פעמי, ניתן לפתיחה חוזרת מההגדרות; תור: מה חדש ← הסבר ← אמינות | `presentation/intro/` (v1.9.0) |
| 112 | רטט אישור על עצירה/נודניק/שמירה; לחיצה ארוכה כבר מקבלת אותו מ-`combinedClickable` | `AlarmRingScreen`/`AlarmEditScreen` (v1.9.0) |
| 113 | **הווידג'טים נבדקים ברינדור** — `runGlanceAppWidgetUnitTest`; קודם שום טסט לא רינדר ווידג'ט | `WidgetRenderTest` + `WidgetUiState` (v1.10.0) |
| 114 | ספירה לאחור = `Chronometer` בכל מרחק; מחרוזת קבועה לא יכולה להתעדכן ב-Doze | `Countdown` ב-`SmartRingWidget.kt` (v1.10.0) |
| 115 | `LazyColumn` במקום `Column` + `take(3)/take(4)` — כל השעמורים, עם גלילה | `ListBody` (v1.10.0) |
| 116 | `sizeMode = SizeMode.Exact` — בלעדיו שינוי גודל לא מרנדר מחדש | `SmartRingBaseWidget` (v1.10.0) |
| 117 | תווית יום בכל שורה; חשבון הימים משותף עם קיבוץ הרשימה ולא עותק שני | `util/WidgetLabels.kt` + `WidgetLabelsTest` (v1.10.0) |
| 118 | קישורי עומק מהווידג'ט (שורה→עריכה, ＋→חדש) עם `data` URI ייחודי לכל שעמור | `MainActivity`/`NavGraph.WidgetDestination` (v1.10.0) |
| 119 | קיצורי יצירה מהירה שהמשתמש מגדיר; קודק ידני (אין תלות serialization), פענוח מגונן | `util/QuickPresets.kt` + `QuickPresetsRepository` (v1.11.0) |
| 120 | סימון הצגה ומכסה נפרדים לכל משטח; הסדר קובע מי נכנס למכסה | `presetsForApp`/`presetsForWidget` (v1.11.0) |
| 121 | `buildQuickAlarm` אחד לשני המשטחים — אחרת הווידג'ט והאפליקציה מתפצלים | `util/QuickAlarmFactory.kt` (v1.11.0) |
| 122 | תפריט פעולות מהירות בווידג'ט; הדגל במצב ה-Glance של המופע, לא במאגר | `PANEL_OPEN_KEY` + `ToggleQuickPanelAction` (v1.11.0) |
| 123 | פעולות קבוצתיות מהווידג'ט (כבה/הפעל/הקפא/בטל) דרך אותן קריאות של "שליטה כללית" | `BulkAlarmAction` (v1.11.0) |
| 124 | שינוי קיצורים ב-DataStore מרענן ווידג'טים — ה-collector של טבלת השעמורים לא רואה אותו | `SmartRingApp` (v1.11.0) |
| 125 | `WidgetRefresher` רושם כמה ווידג'טים עודכנו, ובמפורש כשזה אפס — קודם רק כישלונות נרשמו | `WidgetRefresher` (v1.12.0) |
| 126 | `updateAll` לבדו יכול לעבור על אפס מזהים ולהצליח; הרענון שולח גם `APPWIDGET_UPDATE` עם מזהי `AppWidgetManager` | `refreshAllWidgets` (v1.12.0) |
| 127 | חריגה ב-`provideGlance` נתפסת ונרשמת; Glance בולע אותה לפריסת שגיאה משלו בלי לוג | `widgetState` + `WidgetLoadError` (v1.12.0) |
| 128 | מספר הגרסה נרשם בכל הפעלה — `MY_PACKAGE_REPLACED` לבדו לא מזהה איזו גרסה רצה | `SmartRingApp` (v1.12.0) |
| 129 | כפתור התפריט בווידג'ט = המבורגר, וגם בבינוני; הברק נקרא כקישוט | `ic_widget_menu.xml` (v1.12.0) |
| 130 | **`AndroidRemoteViews` חייב `wrapContentSize()` מפורש** — ברירת המחדל מתפרסת ובולעת את הווידג'ט | `LiveClock`/`Countdown` (v1.12.1) |
| 131 | הווידג'ט הקטן ללא שעון שעה נוכחית; כפתורי הכותרת לפני השעון כדי שהם לא יידחקו | `SmallBody`/`WidgetHeader` (v1.12.1) |
| 132 | `runGlanceAppWidgetUnitTest` לא פורס ולא מודד — לא יתפוס תוכן שנגזר מחוץ לווידג'ט | `WidgetRenderTest` (v1.12.1) |
| 133 | `WIDGET_SIZES` internal, ונבדק שכל ארבעת ה-receivers נכללים בכל רענון בדיוק פעם אחת | `WidgetRenderTest` (v1.12.2) |
| 134 | בדיקת מסלול הנתונים על מכשיר רצה על כל ארבעת הגדלים, לא רק על הרחב | `WidgetDataPathInstrumentedTest` (v1.12.2) |
| 135 | גם ל-2x2 יש ☰ ו-＋; הפאנל מחליף את הגוף, ולכן המקום שהוא צריך כבר קיים | `SmallBody` (v1.12.3) |
| 136 | "פתח אפליקציה" ב-2x2 יושב על אזור השעמור, לא על השורש — אחרת הוא מתחת לכפתורים | `SmallBody` (v1.12.3) |
| 137 | הווידג'ט רושם ללוג **מה הוא צייר**, מדודפ לפי תוכן — לא רק שהרענון רץ | `SmartRingBaseWidget.logRender` (v1.12.4) |
| 138 | כל רענון נרשם עם סיבה; `found` ו-`updated` נפרדים, ושגיאת `updateAll` לא נבלעת | `WidgetRefresher`/`WidgetRefreshReport` (v1.12.4) |
| 139 | `APPWIDGET_UPDATE` אינו שידור מוגן (בניגוד ל-`_UPDATE_OPTIONS`/`_DELETED`/`_ENABLED`) — מותר לשלוח אותו | `refreshAllWidgets` (v1.12.4) |
| 140 | **`updateAll` לא אמין** — הוא מוצא ווידג'טים דרך מיפוי מתמיד שיכול להיות ריק, ואז מעדכן אפס ומסיים בהצלחה | `refreshAllWidgets` (v1.12.5) |
| 141 | עדכון לפי `getGlanceIdBy(appWidgetId)` עוקף את המיפוי לגמרי — זה המסלול הנכון | `refreshAllWidgets` (v1.12.5) |
| 142 | ה-2x2 מציג "ועוד N פעילים"; בלעדיו ווידג'ט תקוע נראה כמו תקין | `widgetMoreAlarmsLabel` (v1.12.5) |

---

## 3. מבנה קבצים

```
smartring-kotlin/
├── .github/workflows/build-apk.yml    ← CI/CD
├── app/
│   ├── build.gradle.kts               ← ksp{} TOP-LEVEL (לא בתוך android{})
│   ├── proguard-rules.pro             ← כללים מלאים (DataStore, Glance, Hilt)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/smartring/app/
│           ├── domain/model/Alarm.kt  ← Pure Kotlin, אין Android imports
│           ├── data/db/
│           │   ├── AlarmEntities.kt   ← DB entities
│           │   ├── AlarmDao.kt        ← @Transaction saveAlarmTransaction()
│           │   └── AppDatabase.kt     ← version=2
│           ├── data/repository/
│           │   ├── AlarmMapper.kt
│           │   └── AlarmRepository.kt
│           ├── di/AppModule.kt        ← Hilt + MIGRATION_1_2
│           ├── util/AlarmScheduler.kt ← nextFireTime() עם BIWEEKLY/MONTHLY
│           ├── receiver/              ← AlarmReceiver, BootReceiver
│           ├── service/               ← AlarmFiringService (prepareAsync, startForeground FIRST)
│           │                            StopAlarmReceiver, SnoozeAlarmReceiver, RescheduleWorker
│           ├── presentation/
│           │   ├── theme/             ← SmartRingTheme + AppTypography
│           │   ├── navigation/NavGraph.kt  ← 5 routes
│           │   ├── alarmlist/         ← AlarmListScreen + ViewModel
│           │   ├── alarmedit/         ← AlarmEditScreen + ViewModel (הכי מורכב)
│           │   ├── alarmring/         ← AlarmRingScreen + ViewModel
│           │   ├── history/           ← HistoryScreen + ViewModel
│           │   ├── settings/          ← SettingsScreen + ViewModel
│           │   └── widget/SmartRingWidget.kt
│           ├── SmartRingApp.kt        ← @HiltAndroidApp + WorkManager
│           └── MainActivity.kt
└── docs/
    ├── HANDOFF.md     ← מסמך זה
    ├── FEATURES.md
    ├── ARCHITECTURE.md
    └── CLAUDE_CODE.md
```

---

## 4. Routes

```
/list       → AlarmListScreen  (מציג WhatsNewDialog פעם אחת אחרי עדכון גרסה)
/edit/{id}?name&hour&minute → AlarmEditScreen  (id=0 = חדש; name/hour/minute = prefill מהיסטוריה)
/ring/{id}  → AlarmRingScreen
/history    → HistoryScreen
/settings   → SettingsScreen
/logs       → LogsScreen  (v1.2.0)
/diagnosis  → DiagnosisScreen  ("למה השעמור לא צלצל?", v1.8.1)

אין route ל-IntroDialog — הוא דיאלוג מעל /list (פעם אחת בהתקנה) ומעל /settings (בלחיצה), v1.9.0.
```

---

## 5. DB Schema – version 3

```sql
-- alarms (עמודות חדשות ב-v2/v3 מסומנות)
CREATE TABLE alarms (
  id INTEGER PRIMARY KEY,
  name TEXT, hour INTEGER, minute INTEGER,
  specificDateTime INTEGER,      -- v2: epoch ms לצלצול חד-פעמי
  isEnabled INTEGER, isFrozen INTEGER,
  repeatDaysBitmask INTEGER,     -- bit0=Sun…bit6=Sat
  repeatFrequency TEXT,          -- NONE/WEEKLY/BIWEEKLY/MONTHLY
  recurrenceEndType TEXT,        -- v2: FOREVER/UNTIL/COUNT
  recurrenceUntilDate INTEGER,   -- v2
  recurrenceCount INTEGER,       -- v2
  occurrencesFired INTEGER,      -- v2: כמה פעמים הצלצול כבר הצלצל
  ringDurationSeconds INTEGER,
  snoozeEnabled INTEGER,         -- v3 NEW: נודניק פעיל לשעמור הזה
  snoozeMinutes INTEGER, snoozeMaxCount INTEGER,
  isShabbatMode INTEGER,         -- v3 NEW: חוסם אינטראקציה בזמן צפצוף
  reminderText TEXT,
  vibrationMode TEXT, vibrationOnlySeconds INTEGER,
  crescendoEnabled INTEGER, crescendoStartVolume INTEGER,
  crescendoStepSeconds INTEGER, crescendoStepPercent INTEGER
);

-- alarm_rings (FK CASCADE on alarm delete)
-- alarm_dates (FK CASCADE on alarm delete)

-- alarm_logs (FK SET_NULL on alarm delete – v2 changed) — user-facing ring history
CREATE TABLE alarm_logs (
  id INTEGER PRIMARY KEY,
  alarmId INTEGER,               -- NULLABLE (SET_NULL כשהשעמור נמחק)
  alarmName TEXT,                -- v2: שם נשמר גם אחרי מחיקת שעמור
  firedAt INTEGER,
  scheduledFor INTEGER,          -- v2
  action TEXT                    -- FIRED/STOPPED/SNOOZED/MISSED
);

-- app_logs (v3 NEW) — technical/diagnostic log, separate from alarm_logs; no FK,
-- viewed/copied/downloaded/cleared from Settings → Logs, trimmed to 3 days by
-- LogCleanupWorker (runs daily).
CREATE TABLE app_logs (
  id INTEGER PRIMARY KEY,
  timestamp INTEGER,
  tag TEXT, message TEXT
);
```

---

## 6. כללי פיתוח – חובה לשמור

```kotlin
// ✅ 1. ksp{} – TOP-LEVEL בלבד (לא בתוך android{})
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// ✅ 2. startForeground() לפני כל DB query
startForeground(NOTIF_ID, buildPlaceholderNotification())  // ← ראשון!
val alarm = repository.getAlarm(id)                        // ← אחר כך

// ✅ 3. MediaPlayer – prepareAsync() בלבד
player.prepareAsync()   // ✓ non-blocking
// player.prepare()     // ✗ BLOCKS main thread!

// ✅ 4. Snooze PendingIntent – requestCode ייחודי
buildSnoozePendingIntent: requestCode = (id + 100_000).toInt()

// ✅ 5. State mutation – תמיד copy()
_state.update { it.copy(fieldName = newValue) }

// ✅ 6. Domain model – pure Kotlin בלבד
// אין import android.* או import androidx.* ב-domain/model/Alarm.kt

// ✅ 7. כל שינוי ב-Entity → bump DB version + Migration
@Database(version = 3)  // לדוגמא
val MIGRATION_2_3 = object : Migration(2, 3) { ... }

// ✅ 8. PendingIntent.FLAG_IMMUTABLE תמיד
PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

// ✅ 9. בדיקת "מצב שבת" – תמיד דרך alarm.acceptsInteraction, לא isShabbatMode ישירות
// (מקור אמת יחיד ב-Alarm.kt; אחרת קל לפספס מקום אחד מ-4: buildNotification,
// StopAlarmReceiver, SnoozeAlarmReceiver, AlarmRingViewModel)
if (!alarm.acceptsInteraction) return

// ✅ 10. אל תיגע ב-signingConfigs ב-build.gradle.kts בלי סיבה מפורשת
// keystore משותף אחד (app/smartring.keystore) לשני build types – זה מה שמאפשר
// עדכון APK במקום. שינוי כאן שובר עדכון במקום לכל המשתמשים הקיימים.

// ✅ 11. "מתי מצלצל השעמור הבא" – תמיד דרך AlarmScheduler.effectiveNextFireTime(),
// לא nextFireTime() ישירות – האחרון לא יודע על נודניק פעיל (מתוזמן דרך scheduleAt(),
// לא נגזר מ-Alarm עצמו, נשמר ב-SharedPreferences pending_snooze).

// ✅ 12. רענון ווידג'טים (v1.4.0) – schedule()/cancel()/cancelAll() לא קוראים
// widgetRefresher.refresh() בעצמם: SmartRingApp.onCreate() מאזין ל-
// AlarmRepository.observeAlarms() ומרענן על כל כתיבה לטבלת alarms, וכל קריאה
// אמיתית ל-schedule/cancel/cancelAll כבר מלווה בכתיבה כזו. רק scheduleAt() (נודניק)
// ו-rescheduleAll() (ל-RescheduleWorker בלבד; יש לו refreshWidgets: Boolean=true עבור
// זה, ו-unfreezeAll/enableAll קוראים לו עם false) מרעננים בעצמם – הם לא תמיד מלווים
// בכתיבה. הוספת שיטת AlarmScheduler חדשה עם side-effect? בדוק כל forEach שקורא לה
// per-alarm ותן לו טיפול דומה (מונע N רענונים כמעט-בו-זמניים לפעולה אחת).

// ✅ 13. "כמה זמן עבר מאז שהשעמור צלצל" (מסך הצלצול) – תמיד לפי
// SystemClock.elapsedRealtime() מעוגן ל-alarm_logs (action='FIRED'), לא ספירת
// טיקים מקומית של המסך (מתאפסת ב-rotation) ולא System.currentTimeMillis() חוזר
// (רגיש לקפיצת שעון). AlarmFiringService כותב את רשומת ה-FIRED *לפני* פרסום
// ההתראה, לא אחריה – אחרת מסך הצלצול עלול לקרוא timestamp ישן מצלצול קודם.
```

---

## 7. איך להוסיף שדה חדש ל-Alarm

```
סדר עדכון:
1. domain/model/Alarm.kt          → שדה + ברירת מחדל
2. data/db/AlarmEntities.kt       → AlarmEntity
3. di/AppModule.kt                → ALTER TABLE ב-MIGRATION_X_Y
   data/db/AppDatabase.kt         → bump version
4. data/repository/AlarmMapper.kt → toDomain() + toEntity()
5. presentation/alarmedit/
   AlarmEditViewModel.kt          → AlarmEditUiState + setter
   AlarmEditScreen.kt             → UI widget
```

---

## 8. בניית APK

```bash
# GitHub Actions (מומלץ – אין צורך ב-SDK מקומי)
# 1. העלה לGitHub → 2. Actions → "Build SmartRing APK" → Run workflow
# 3. הורד artifact: SmartRing-debug-N → app-debug.apk

# מקומי (אם יש Android Studio)
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

---

## 9. Backlog – מה נשאר

### עדיפות גבוהה
- [x] ~~"טען שוב" מהיסטוריה~~ – **בוצע v1.1.0**: `HistoryScreen` מעביר name/hour/minute (מ-`log.scheduledFor`) דרך `NavGraph` route args אל `AlarmEditViewModel.prefill()`.
- [ ] **גופן Heebo** – `Typography.kt` מוכן; צריך קבצי TTF ב-`res/font/`
- [ ] **תרגום לאנגלית** – נבחן ב-v1.9.0 ונדחה במפורש, לא נשכח: כל מסך מחזיק מחרוזות עבריות קבועות בקוד, ולכן זה באצ' ייעודי ולא שיפור UI. ההגדרה מושבתת בכוונה עם "בקרוב" ולא מעמידה פנים.
- [ ] **הגדלת כל יעדי המגע ל-48dp** – נבחן ב-v1.9.0 ונדחה במפורש: היה מנפח את גובה השורה של האפליקציה כולה. 32–40dp היא הפשרה שהפריסה סופגת, וזה כתוב במפורש ולא מוצג כעמידה בתקן.
- [x] ~~בחירת קובץ שמע~~ – **בוצע v1.1.0**: `RingsSection` ב-`AlarmEditScreen` פותח RingtoneManager system picker לכל סבב צלצול.

### עדיפות בינונית
- [x] ~~SwipeToDismiss על כרטיסיות~~ – **בוצע v1.1.0**: `SwipeToDismissBox` נוסף לצד long-press, שניהם פותחים את אותו דיאלוג אישור.
- [x] ~~Alarm preview – "נסה עכשיו" בעריכה~~ – **בוצע v1.8.1**: "בדוק צלצול עכשיו" במסך העריכה של שעמור שמור. עובר במסלול האמיתי (AlarmManager → AlarmReceiver → שירות → מסך צלצול) על מרחב קודי בקשה נפרד, ולכן לא נוגע בתזמון האמיתי ולא מבצע שום רישום.
- [x] ~~Widget deep link → AlarmListScreen~~ – **בוצע v1.1.0**: כל 4 הווידג'טים פותחים את האפליקציה בלחיצה.
- [x] ~~קיבוץ הרשימה~~ – **בוצע v1.9.0**: "היום"/"מחר"/"השבוע"/"בהמשך"/"כבויים", ב-`util/AlarmSections.kt`.
- [x] ~~קיצורי דרך במסך הראשי~~ – **בוצע v1.8.1**: שבבי "עוד שעה"/"עוד 8 שעות"/"מחר".
- [ ] Accessibility labels על Switch/IconButtons – רק המתג ברשימת השעמורים קיבל תווית (v1.1.0); ה-Switch/IconButtons במסך העריכה עדיין ללא.
- [x] ~~Unit tests ל-AlarmScheduler.nextFireTime()~~ – **בוצע v1.4.1**: ראה סעיף 13 (בדיקות אוטומטיות). כלל גם את הרפקטור שהוזכר כאן (`now` כפרמטר ניתן להזרקה).
- [x] ~~הזנת מספר מדויקת לצד סליידרים~~ – **בוצע v1.2.0**: `EditableValueBadge` (לחיצה על התג פותחת דיאלוג הזנת מספר) בכל הסליידרים.
- [x] ~~כפתורי מידע על שדות הגדרה~~ – **בוצע v1.2.0**: `FieldLabel` עם אייקון ⓘ ברוב שדות מסך העריכה.
- [x] ~~מסך לוגים~~ – **בוצע v1.2.0**: `LogsScreen` בהגדרות, עם ניקוי אוטומטי יומי (retention 3 ימים).
- [x] ~~בדיקות אמינות ברקע~~ – **בוצע v1.2.0**: התראות/שעמורים מדויקים/סוללה, עם קישור לתיקון.
- [x] ~~עדכון APK ללא הסרה מחדש~~ – **בוצע v1.2.0**: keystore קבוע משותף ל-debug/release.

### עדיפות נמוכה
- [x] ~~חיפוש/סינון~~ – **בוצע v1.9.0**: שדה חיפוש שמופיע מ-10 שעמורים ומעלה.
- [x] ~~הסבר בפעם הראשונה~~ – **בוצע v1.9.0**: `presentation/intro/`, ניתן לפתיחה חוזרת מההגדרות.
- [x] ~~קיצורי Quick Settings~~ – **בוצע v1.9.0**: `AlarmsTileService`, דו-כיווני.
- [x] ~~Haptics על פעולות מרכזיות~~ – **בוצע v1.9.0**: עצירה, נודניק, שמירה.
- [ ] Export/Import JSON של שעמורים
- [ ] פעולת "דלג על המופע הבא" בווידג'ט ובכרטיס — נשקלה שוב ב-v1.11.0 לתפריט הפעולות המהירות ונדחתה: אין לה ייצוג במודל — לא מומשה כי אין לה ייצוג במודל הנתונים כרגע (`occurrencesFired` סופר צלצולים שקרו, לא דילוגים); דורשת שדה משלה ומיגרציה
- [ ] Custom accent color

---

## 10. בעיות ידועות

| בעיה | מקום | חומרה |
|------|-------|--------|
| BIWEEKLY parity נגזרת מתאריך מוחלט (`daysSinceEpoch/7`) ולא מהשבוע שבו המשתמש יצר את השעמור — קצב "כל שבועיים" יציב, אבל הצלצול הראשון עלול להיות שבוע אחרי הצפוי | `AlarmScheduler.nextFireTime()` | נמוכה |
| Long.toInt() ל-id גדול | `AlarmScheduler.buildIntent()` | נמוכה |
| מסך הצלצול (רשת ביטחון) עלול "לנצח" את טיימר השירות האמיתי אם `AlarmFiringService.onStartCommand()` איטי מ-GRACE_SECONDS (2 שניות) — במקרה כזה רשומת "MISSED" לא תיכתב | `AlarmRingViewModel.tick()`, `AlarmFiringService.fireAlarm()` | נמוכה (v1.4.0) |
| `recurrenceUntilDate` של שעמורים שנשמרו לפני v1.5.0 עדיין מכיל חצות UTC (מסתיים יום מוקדם). לא מנורמל בטעינה בכוונה — זה היה מסמן את המסך כ"עם שינויים שלא נשמרו" עוד לפני שהמשתמש נגע במשהו. נפתר ברגע שהמשתמש בוחר תאריך סיום מחדש | `AlarmEditViewModel.setRecurrenceUntilDate()` | נמוכה (v1.5.0) |
| הווידג'טים עוקבים אחרי מצב התצוגה של *המערכת*, לא אחרי הגדרת העיצוב באפליקציה (אוטומטי/כהה/בהיר). זו ההתנהגות המקובלת לווידג'ט מסך בית, וגם המעשית: ההגדרה שמורה ב-DataStore ואינה משנה את ה-Configuration של התהליך, שממנו הרינדור נגזר | `paletteFor()` ב-`SmartRingWidget.kt` | נמוכה (v1.6.0) |
| שינוי מצב תצוגה מרענן את הווידג'טים דרך `Application.onConfigurationChanged()`, שנקרא רק כשהתהליך חי. אם התהליך אינו רץ בזמן המעבר, הערכה מתעדכנת ברענון התקופתי הבא (עד 15 דקות) | `SmartRingApp.onConfigurationChanged()` | נמוכה (v1.6.0) |
| עוצמת הצלצול היא אחוז מתוך עוצמת ערוץ השעמורים של המכשיר; אם היא 0 הצלצול שקט. בכוונה לא נכתבת מחדש על ידי האפליקציה (אפליקציה שמשנה את עוצמת המכשיר בלי לשאול, ועלולה להשאיר אותה משונה אם התהליך נהרג באמצע צלצול, גרועה יותר) — רק מוצגת כבדיקת אמינות | `ReliabilityChecks.isAlarmVolumeAudible()` | נמוכה (v1.5.0) |

---

## 11. Context מוכן לפתיחת שיחת Claude Code

העתק-הדבק זאת כהודעה ראשונה:

```
אתה ממשיך לפתח את SmartRing – אפליקציית שעון מעורר לאנדרואיד.

קרא קודם את הקבצים הבאים (בסדר הזה):
1. HANDOFF.md – סיכום מצב הפרויקט
2. docs/ARCHITECTURE.md
3. domain/model/Alarm.kt
4. data/db/AlarmDao.kt
5. presentation/navigation/NavGraph.kt

מצב נוכחי: v1.5.0, DB version 3, כל הפיצ'רים ב-HANDOFF.md סעיף 2 מיושמים (כולל מצב שבת,
נודניק ניתן-לכיבוי, לוגים, בדיקות אמינות (כולל בקשה פרואקטיבית בכניסה), What's New, עדכון APK
במקום, שעון חי + אינדיקציית זמן לשעמור הבא + מסגרת בווידג'טים, וסגירה אוטומטית אמינה של מסך
הצלצול). מ-v1.4.1 יש גם סוויטת בדיקות אוטומטיות שרצה ב-CI (ראה סעיף 13), וה-CI ירוק.
עברו מספר סיבובי code review – הכל תקין. v1.4.0 היה batch של תיקוני באגים אמיתיים שנמצאו
בבדיקה בפועל על מכשיר; v1.4.1 הוסיף בדיקות ללא שינוי משתמש; v1.4.2 תיקן שני כשלי build
שחסמו את ה-CI של v1.4.1 (mockk/kotlin-stdlib, ואז import חסר/שגוי בבדיקות עצמן), באג
אמיתי במכשיר — עיגול "ש" (שבת) בבורר ימי החזרה נחתך — וגם באג אמיתי שהבדיקות עצמן חשפו:
פתיחת שעמור קיים לעריכה סימנה אותו כ"מלוכלך" (unsaved changes) מיידית בלי לגעת בכלום.

כללים שאסור לשכוח (ראה HANDOFF.md סעיף 6):
- ksp{} תמיד top-level
- startForeground() לפני DB query
- prepareAsync() לא prepare()
- State רק דרך copy()
- Domain model – Pure Kotlin

המשך לפי הbacklog בסעיף 9 לפי עדיפות, או טפל במה שאבקש.
```

---

## 12. גרסאות ספריות

```toml
agp          = "8.4.2"
kotlin       = "2.0.0"
ksp          = "2.0.0-1.0.22"   # חייב להתחיל עם kotlin version
hilt         = "2.51.1"
compose-bom  = "2024.06.00"     # → M3 1.2.1, Compose UI 1.6.8
glance       = "1.1.0"
room         = "2.6.1"
lifecycle    = "2.8.2"
navigation   = "2.7.7"
work         = "2.9.0"
datastore    = "1.1.1"
junit        = "4.13.2"
robolectric  = "4.16.1"
mockk        = "1.14.2"   # לא לעדכן ל->1.14.4+ בלי לבדוק — ראה סעיף 13
androidx-test-junit  = "1.2.1"   # androidTest (אמולטור)
androidx-test-runner = "1.6.2"
androidx-test-rules  = "1.6.1"
espresso             = "3.6.1"
```

---

## 13. בדיקות אוטומטיות (v1.4.1, הורחב בכל גרסה מ-v1.5.0 עד v1.6.6)

`app/src/test/` — בדיקות JVM (חלקן Robolectric: סביבת אנדרואיד קלה על ה-JVM, **לא**
אמולטור אמיתי — הרבה יותר מהיר ואמין ב-CI). רץ אוטומטית ב-CI לפני assembleDebug/Release
(`.github/workflows/build-apk.yml`, שלב "Run unit tests").

| קובץ | מכסה |
|------|------|
| `util/AlarmSchedulerTest.kt` | `nextFireTime()` (WEEKLY/BIWEEKLY כולל חצות שנה/MONTHLY/תאריך ספציפי/one-time), `pendingSnoozeUntil`/`effectiveNextFireTime`, ש-`schedule()`/`cancel()` באמת מפעילים/מבטלים אזעקת `AlarmManager` אמיתית (Robolectric shadow) |
| `domain/model/AlarmTest.kt` | `volumeAtSecond()` (כולל שני ה-clamps ההגנתיים), `isRecurrenceExpired()` |
| `util/TimeFormatTest.kt` | `formatDurationSeconds()`/`formatCountdownUntil()` |
| `presentation/alarmring/AlarmRingViewModelTest.kt` | טיימר סגירה אוטומטית (`ShadowSystemClock.advanceBy()`), מצב שבת, נודניק שמתדרדר לעצירה |
| `presentation/alarmedit/AlarmEditViewModelTest.kt` | ולידציית שם ריק + אירוע הגלילה, ו-`isDirty` לא נשאר "מלוכלך" לצמיתות אחרי ולידציה כושלת, וגם לא נהיה "מלוכלך" באופן שגוי מיד אחרי טעינה (v1.4.2) |
| `data/db/AlarmDaoTest.kt` | `saveAlarmTransaction()` (insert מול update, לא REPLACE), `lastFiredAt`, `snoozeCountSinceLastFire`, SET_NULL FK במחיקה |
| `util/UpcomingAlarmsTest.kt` (v1.6.0) | `buildUpcomingAlarms()` — מה הווידג'טים מציגים ובאיזה סדר: מיון לפי מועד הצלצול האמיתי (לא לפי שעה ביום), עדיפות לנודניק, ששעמור בנודניק מציג את שעת הנודניק ולא את שעתו המקורית, ושחזרתיות שהסתיימה נעלמת — אלא אם המופע האחרון שלה בנודניק |
| `presentation/widget/WidgetProviderInfoTest.kt` (v1.6.0) | ארבעת קובצי `widget_*_info.xml`: שיש `minWidth`/`minHeight` (הבאג של אנדרואיד 8–11), `initialLayout`, `resizeMode` ו-`description`, ושהגודל המינימלי תואם למספר התאים המוצהר |
| `data/db/MigrationTest.kt` (v1.6.1) | **MIGRATION_1_2 ו-MIGRATION_2_3 בפועל** — עד v1.6.1 אף בדיקה לא הריצה מיגרציה אפילו פעם אחת. בונה כל סכימה ישנה ב-SQL גולמי במספר הגרסה האמיתי שלה ופותח דרך Room, שמריץ את אובייקטי המיגרציה האמיתיים ומאמת את התוצאה מול ה-entities — בדיוק המסלול שרץ במכשיר של משתמש שמעדכן. בנוסף: ששעמור שנוצר לפני העדכון שומר את ההגדרות שלו, שההיסטוריה שורדת את בנייתה מחדש של `alarm_logs`, ושמחיקת שעמור אחר כך משאירה את ההיסטוריה |
| `util/CalendarDatesTest.kt` (v1.6.1) | ארבע ההמרות בין "יום שנבחר" (חצות UTC) ל"רגע מקומי", כל מקרה בארבעה אזורי זמן כולל היסט שלילי — שם ורק שם שני באגי התצוגה של v1.6.1 היו נראים |
| `presentation/whatsnew/WhatsNewTest.kt` (v1.6.1) | ההחלטה מה להציג אחרי עדכון, כולל ההבחנה בין התקנה חדשה למשתמש ותיק שמעדכן מגרסה שקדמה לפיצ'ר (שניהם lastSeen=0), ותקינות `WHATS_NEW_HISTORY` עצמו (סדר, כפילויות) |
| `util/ReliabilityChecksTest.kt` (v1.6.2) | שערי ה-SDK של ארבע בדיקות האמינות (`@Config(sdk=...)`): מתחת ל-API הרלוונטי כל בדיקה חייבת להחזיר "תקין", אחרת מוצגת למשתמש באנדרואיד 8 הרשאה חסרה שאין לו שום דרך להעניק |
| `presentation/logs/LogsFormattingTest.kt` (v1.6.1) | שהייצוא הפוך לסדר המסך (קובץ לוג נקרא מהישן לחדש) |
| `presentation/alarmlist/AlarmListViewModelTest.kt` (v1.6.0) | כל פעולות "שליטה כללית" — ובעיקר ש-`disableAll`/`freezeAll` קוראות את רשימת הפעילים *לפני* הכתיבה שמנקה אותה, אחרת לא מבוטל שום תזמון |
| `domain/model/AlarmPlaybackTest.kt` (v1.6.3) | מה באמת נשמע בשנייה N: `ringAtSecond()`/`audibleVolumeAtSecond()` בכל ארבעת מצבי הרטט, ברצף רב-סבבים, בהשהיות השקטות בין סבבים ובגלגול חוזר של הרצף — המודל שמסך הצלצול מצייר ממנו, ושחייב להסכים עם `AlarmFiringService` שנייה-שנייה |
| `util/RingSetupTest.kt` (v1.6.3) | שלושת כללי האזהרה על תצורות שהסליידרים מרשים אך שלא יתנהגו כמובטח (רטט ארוך ממשך הצלצול, התחזקות שלא יכולה לעלות, התחזקות שלא מספיקה להסתיים) — כולל שני מקרי הגבול ושהאזהרות לא נדלקות זו על זו |
| `presentation/widget/WidgetPaletteTest.kt` (v1.6.3) | בחירת פלטה לפי `night`/`notnight`, שצבעי ה-XML (שהמסגרת והפינות המעוגלות חייבות אותם) זהים לפלטת ה-Kotlin, ושכל צבע טקסט עובר AA 4.5:1 גם על גוף הווידג'ט וגם על שורת שעמור |
| `util/AlarmHandoffWakeLockTest.kt` (v1.6.4) | הנעילה שמכסה את הפער בין קבלת ההתראה לעליית שירות הצלצול: תפיסה, שחרור, תפיסה חוזרת, ושתפיסה שנייה לא מחליפה נעילה מוחזקת (reference counting כבוי — נעילה שהוחלפה הייתה נשארת מוחזקת לנצח). שני כיווני הכשל כאן בלתי נראים עד הרגע שבו הם חשובים |
| `util/NotificationActionsTest.kt` (v1.6.5) | כללי שני כפתורי ההתראה, שעד v1.6.5 לא היו נגישים לאף סוויטה (`goAsync()` דורש שידור אמיתי, `@AndroidEntryPoint` דורש את גרף Hilt): סירוב מוחלט במצב שבת בשני הכפתורים, fail-open כשלא ניתן לקרוא את השעמור, נודניק כבוי ותקרת נודניקים שיורדים לעצירה (ונרשמים כ-STOPPED מול MISSED — שני סיפורים שונים בהיסטוריה), ושני צדי הגבול של התקרה, ושמצב שבת גובר על כל סיבה אחרת לפעול |
| `data/repository/AlarmMapperTest.kt` (v1.6.5) | הגבול שכל שעמור חוצה פעמיים בדרך לדיסק וממנו — עד כה נבדק רק בעקיפין דרך Room, כך ששדה שנשמט מאחד משני כיווני ההמרה עדיין "עובר הלוך ושוב" בהצלחה. השוואת אובייקט שלם (שדה חדש ב-Alarm שנשכח באחד הכיוונים נכשל בלי שאיש יזכור להוסיף מקרה), מיון סבבים לפי orderIndex ולא לפי סדר השורות, שיוך מחדש של שורות בת בשמירה, ונפילה חלקה של ערכי enum לא מוכרים |
| `receiver/BootReceiverActionsTest.kt` (v1.6.5) | שקבוצת השידורים שמפעילים תזמון מחדש זהה בין הקוד למניפסט. פעולה שקיימת רק באחד מהם נכשלת בשקט מוחלט, וזה בדיוק מה שאיפשר למקרה של הרשאת "שעמורים מדויקים" לעבור מתחת לרדאר. הבדיקה מפענחת כל פעולה דרך ה-PackageManager האמיתי מול המניפסט המנותח |
| `presentation/theme/ThemeContrastTest.kt` (v1.6.6) | כל תפקיד צבע מול כל משטח שהוא יכול להיות מצויר עליו (background/surface/surfaceVariant), בשתי הערכות, וגם זוגות ה-on/accent (טקסט על כפתור צבוע). מה שזה תפס כשנכתב: tertiary בהיר ב-3.13:1, error בהיר ב-4.34:1, ו-onSurfaceVariant כהה ב-4.21:1 — אותו ערך ואותה טעות שכבר תוקנו בפלטת הווידג'טים שתי גרסאות קודם ומעולם לא נבדקו כאן |
| `util/SystemScreensTest.kt` (v1.6.6) | ש-`openSystemScreen()` מדווח על כישלון במקום לזרוק, כשהמסך המבוקש לא קיים במכשיר. משתמש ב-`checkActivities(true)` של Robolectric — בלעדיו כל Intent "נפתר" וכל מחלקת הבאגים הזו בלתי נראית לבדיקות |

### בדיקות על אמולטור אמיתי (`app/src/androidTest/`, v1.5.0)

רצות ב-CI ב-job נפרד (`instrumented` ב-`build-apk.yml`) על אמולטור API 30 דרך
`reactivecircus/android-emulator-runner`, במקביל לבניית ה-APK. מכסות בדיוק את מה
ש-Robolectric *לא* יכול: shadow ישמח לרשום קריאה שהמערכת האמיתית הייתה דוחה או מטפלת
בה אחרת.

| קובץ | מכסה |
|------|------|
| `util/AlarmSchedulerInstrumentedTest.kt` | ש-`schedule()` באמת נרשם ב-`AlarmManager.getNextAlarmClock()` — ההוכחה היחידה שהמעבר ל-`setAlarmClock()` (v1.5.0) אכן קרה; ביטול, נודניק, ושחזור נודניק אחרי ריבוט |
| `data/AlarmRepositoryInstrumentedTest.kt` | Room מול SQLite אמיתי: round-trip של שעמור + סבבים, שעריכה לא מוחקת היסטוריה, וסמנטיקת `snoozeCountSinceLastFire` |
| `util/AlarmNotificationsInstrumentedTest.kt` | שערוץ ההתראות קיים, IMPORTANCE_HIGH, **ובלי** צליל/רטט משל עצמו (הבאג של צליל כפול), ושהערוץ הישן נמחק |
| `presentation/AlarmListScreenInstrumentedTest.kt` | smoke end-to-end: MainActivity האמיתי עולה ומצייר דרך גרף Hilt אמיתי + Room אמיתי |
| `presentation/WidgetProviderInstrumentedTest.kt` (v1.6.0) | שה-`AppWidgetManager` האמיתי מזהה את כל ארבעת הווידג'טים ומחזיר להם גודל מינימלי שאינו 0 — זה מה שמסך הבית קורא כדי להחליט אם ואיך למקם אותם |

`HiltTestRunner` (`app/src/androidTest/.../HiltTestRunner.kt`) מחליף את `SmartRingApp`
ב-`HiltTestApplication`, כך שתופעות הלוואי של `onCreate()` בפרודקשן (WorkManager
periodic, ה-collector של `observeAlarms()`) לא רצות מתחת לכל בדיקה.

**החלטות עיצוב:**
- `app/src/test/resources/robolectric.properties` מגדיר `application=android.app.Application`
  (לא את `SmartRingApp` האמיתי) — כדי שבדיקות Robolectric לא יפעילו הזרקת Hilt אמיתית
  (DB אמיתי, WorkManager, ה-collector של `observeAlarms()`). כל מחלקה תחת בדיקה נבנית
  ישירות עם dependencies מזויפים/מדומים (mockk), לא דרך גרף ה-DI.
- `AlarmScheduler.nextFireTime()` מקבל `now: Long` אופציונלי (ברירת מחדל: השעון האמיתי)
  במקום לקרוא ל-`System.currentTimeMillis()` ישירות — זה מה שהופך את חישובי
  WEEKLY/BIWEEKLY/MONTHLY לניתנים לבדיקה דטרמיניסטית. כל בדיקה חדשה שצריכה זמן "עכשיו"
  קבוע צריכה אותו סוג seam.
- כדי לשלוט ב-`SystemClock.elapsedRealtime()` בבדיקות (למשל טיימר הסגירה האוטומטית של
  מסך הצלצול) יש להשתמש ב-`org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration)`.
- אין עדיין בדיקות migration (`MIGRATION_1_2`/`MIGRATION_2_3`) — אלה דורשות קובצי schema
  שה-KSP מייצא (`app/schemas/*.json`, לפי `room.schemaLocation` ב-`build.gradle.kts`),
  שעדיין לא נוצרו/הוצמדו כי אין build מקומי בסביבה הזו. ברגע שיש build ראשון עם schema
  מיוצא, שווה להוסיף `androidx.room:room-testing` + `MigrationTestHelper`.
- **v1.5.0: יש עכשיו בדיקות אמולטור** (ראה מעלה) לצד Robolectric — לא במקומו. החלוקה:
  לוגיקה, חישובי תאריכים, ViewModels ושאילתות → JVM (מהיר, רץ על כל push); אינטגרציה
  עם ה-framework האמיתי (AlarmManager, NotificationManager, SQLite, עליית האפליקציה) →
  אמולטור. בדיקת *רינדור* Compose ברזולוציות שונות עדיין לא מכוסה (באג חיתוך עיגול
  "ש" ב-v1.4.2 היה מסוג כזה) — screenshot testing יהיה הצעד הבא אם זה יחזור.
- אין בדיקה אוטומטית של `AlarmFiringService` עצמו מקצה לקצה (צלצול אמיתי + MediaPlayer
  על אמולטור ללא אודיו) — במקום זה מכוסים החלקים הניתנים לבדיקה דטרמיניסטית: הגדרות
  ערוץ ההתראות, `nextRecurringFireTime()` (הכלל שלפיו השירות מחליט לכבות שעמור
  חד-פעמי), וחישובי ה-crescendo/משך ב-JVM.
- **מ-mockk 1.14.4 ואילך `kotlin-stdlib` שנמשך טרנזיטיבית עולה מ-`2.0.0` ל-`2.1.20`+**
  (וב-1.14.11 ל-`2.2.21`) — לא תואם ל-`kotlin`/`ksp` הנעוצים ב-`2.0.0` בפרויקט הזה,
  ונכשל דווקא ב-`kspDebugUnitTestKotlin` בלי שגיאה ברורה על "mockk" בשם. `mockk-jvm`
  עבר לארכיטקטורת Kotlin Multiplatform (מפוצל ל-`mockk-dsl`/`mockk-core`/`mockk-agent*`
  נמשכים דרך Gradle Module Metadata) — כדי לבדוק את גרסת ה-stdlib שגרסת mockk מסוימת
  מושכת, לבדוק את קובץ ה-`.module` שלה ב-Maven Central (`.pom` לבד לא מספיק, כי
  ה-dependencies האמיתיים חיים שם), לא רק את מספר הגרסה. אם צריך לשדרג mockk בעתיד —
  לוודא תחילה ש-`kotlin-stdlib` שהוא מושך תואם ל-`kotlin`/`ksp` הנוכחיים בפרויקט.
- **`import io.mockk.match` הוא import לא תקין** — `match`/`coMatch` הן פונקציות-חבר
  של `MockKMatcherScope` (לא top-level ב-`io.mockk`), כבר בסקופ בתוך `every{}`/
  `coVerify{}` בלי import כלל, בדיוק כמו `any()`/`eq()`. ו-`advanceUntilIdle()` כן
  צריך import מפורש (`kotlinx.coroutines.test.advanceUntilIdle`, extension אמיתי על
  `TestScope`) — הוא לא מגיע אוטומטית מ-`runTest`. שתי הטעויות האלה בבדיקות שנכתבו
  ב-v1.4.1 לא נתפסו עד v1.4.2 כי כשל ה-mockk/kotlin-stdlib חסם את הקומפילציה של
  קובצי הבדיקה מלכתחילה — כשל מוקדם בפייפליין יכול להסתיר כשל אמיתי מאוחר יותר.
