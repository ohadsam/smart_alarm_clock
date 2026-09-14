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
- [x] ~~בחירת קובץ שמע~~ – **בוצע v1.1.0**: `RingsSection` ב-`AlarmEditScreen` פותח RingtoneManager system picker לכל סבב צלצול.

### עדיפות בינונית
- [x] ~~SwipeToDismiss על כרטיסיות~~ – **בוצע v1.1.0**: `SwipeToDismissBox` נוסף לצד long-press, שניהם פותחים את אותו דיאלוג אישור.
- [ ] Alarm preview – "נסה עכשיו" בעריכה
- [x] ~~Widget deep link → AlarmListScreen~~ – **בוצע v1.1.0**: כל 4 הווידג'טים פותחים את האפליקציה בלחיצה.
- [ ] Accessibility labels על Switch/IconButtons – רק המתג ברשימת השעמורים קיבל תווית (v1.1.0); ה-Switch/IconButtons במסך העריכה עדיין ללא.
- [x] ~~Unit tests ל-AlarmScheduler.nextFireTime()~~ – **בוצע v1.4.1**: ראה סעיף 13 (בדיקות אוטומטיות). כלל גם את הרפקטור שהוזכר כאן (`now` כפרמטר ניתן להזרקה).
- [x] ~~הזנת מספר מדויקת לצד סליידרים~~ – **בוצע v1.2.0**: `EditableValueBadge` (לחיצה על התג פותחת דיאלוג הזנת מספר) בכל הסליידרים.
- [x] ~~כפתורי מידע על שדות הגדרה~~ – **בוצע v1.2.0**: `FieldLabel` עם אייקון ⓘ ברוב שדות מסך העריכה.
- [x] ~~מסך לוגים~~ – **בוצע v1.2.0**: `LogsScreen` בהגדרות, עם ניקוי אוטומטי יומי (retention 3 ימים).
- [x] ~~בדיקות אמינות ברקע~~ – **בוצע v1.2.0**: התראות/שעמורים מדויקים/סוללה, עם קישור לתיקון.
- [x] ~~עדכון APK ללא הסרה מחדש~~ – **בוצע v1.2.0**: keystore קבוע משותף ל-debug/release.

### עדיפות נמוכה
- [ ] Export/Import JSON של שעמורים
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

## 13. בדיקות אוטומטיות (v1.4.1, הורחב ב-v1.5.0 וב-v1.6.0)

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
| `presentation/alarmlist/AlarmListViewModelTest.kt` (v1.6.0) | כל פעולות "שליטה כללית" — ובעיקר ש-`disableAll`/`freezeAll` קוראות את רשימת הפעילים *לפני* הכתיבה שמנקה אותה, אחרת לא מבוטל שום תזמון |

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
