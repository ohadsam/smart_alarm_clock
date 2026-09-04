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
| DB | Room **v2** (migration מ-v1 קיים) |
| תזמון | AlarmManager (exact, wakeup) |
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
| 3 | חזרתיות עשירה (WEEKLY/BIWEEKLY/MONTHLY) | `Alarm.repeatDaysBitmask`, `AlarmScheduler.nextFireTime()` |
| 4 | סיום חזרתיות (FOREVER/UNTIL/COUNT) | `RecurrenceEnd`, `RecurrenceEndSection` |
| 5 | צלצולים חוזרים (עד 10) | `AlarmRing`, `AlarmDao` |
| 6 | רטט 4 מצבים | `VibrationMode`, `AlarmFiringService.fireAlarm()` |
| 7 | Crescendo | `Alarm.volumeAtSecond()`, `startCrescendo()` |
| 8 | נודניק (עם reschedule מהמסך) | `AlarmRingViewModel.snooze()` + `AlarmScheduler.scheduleAt()` |
| 9 | טקסט תזכורת | `Alarm.reminderText` |
| 10 | ניהול גלובלי | GlobalControls BottomSheet, freeze/unfreeze toggle חכם |
| 11 | מסך היסטוריה | `HistoryScreen`, `HistoryViewModel`, `AlarmLog` |
| 12 | טעינת שעמור מהיסטוריה | `onLoadAlarm` callback, כפתור Replay |
| 13 | Dirty-state check | `BackHandler` + `isDirty` + dialog אישור |
| 14 | ווידג'ט 4 גדלים | `SmartRingWidget.kt`, Glance API |
| 15 | עברית RTL + אנגלית | `values/strings.xml`, `values-en/strings.xml` |
| 16 | Dark/Light Mode | `SmartRingTheme`, `SettingsViewModel` |
| 17 | FLAG_KEEP_SCREEN_ON | `AlarmRingScreen` + `DisposableEffect` |
| 18 | Boot reschedule | `BootReceiver` → `RescheduleWorker` |

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
/list       → AlarmListScreen
/edit/{id}  → AlarmEditScreen  (id=0 = חדש)
/ring/{id}  → AlarmRingScreen
/history    → HistoryScreen
/settings   → SettingsScreen
```

---

## 5. DB Schema – version 2

```sql
-- alarms (עמודות חדשות ב-v2 מסומנות)
CREATE TABLE alarms (
  id INTEGER PRIMARY KEY,
  name TEXT, hour INTEGER, minute INTEGER,
  specificDateTime INTEGER,      -- v2 NEW: epoch ms לצלצול חד-פעמי
  isEnabled INTEGER, isFrozen INTEGER,
  repeatDaysBitmask INTEGER,     -- bit0=Sun…bit6=Sat
  repeatFrequency TEXT,          -- NONE/WEEKLY/BIWEEKLY/MONTHLY
  recurrenceEndType TEXT,        -- v2 NEW: FOREVER/UNTIL/COUNT
  recurrenceUntilDate INTEGER,   -- v2 NEW
  recurrenceCount INTEGER,       -- v2 NEW
  occurrencesFired INTEGER,      -- v2 NEW: כמה פעמים הצלצול כבר הצלצל
  ringDurationSeconds INTEGER,
  snoozeMinutes INTEGER, snoozeMaxCount INTEGER,
  reminderText TEXT,
  vibrationMode TEXT, vibrationOnlySeconds INTEGER,
  crescendoEnabled INTEGER, crescendoStartVolume INTEGER,
  crescendoStepSeconds INTEGER, crescendoStepPercent INTEGER
);

-- alarm_rings (FK CASCADE on alarm delete)
-- alarm_dates (FK CASCADE on alarm delete)

-- alarm_logs (FK SET_NULL on alarm delete – v2 changed)
CREATE TABLE alarm_logs (
  id INTEGER PRIMARY KEY,
  alarmId INTEGER,               -- NULLABLE (SET_NULL כשהשעמור נמחק)
  alarmName TEXT,                -- v2 NEW: שם נשמר גם אחרי מחיקת שעמור
  firedAt INTEGER,
  scheduledFor INTEGER,          -- v2 NEW
  action TEXT                    -- FIRED/STOPPED/SNOOZED/MISSED
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
- [ ] **"טען שוב" מהיסטוריה** – כרגע מנווט ל-edit ריק; צריך savedStateHandle להעביר נתוני log
- [ ] **גופן Heebo** – `Typography.kt` מוכן; צריך קבצי TTF ב-`res/font/`
- [ ] **בחירת קובץ שמע** – `AlarmRing.ringtoneUri` תומך; צריך RingtoneManager picker ב-UI

### עדיפות בינונית
- [ ] SwipeToDismiss על כרטיסיות (כרגע long-press)
- [ ] Alarm preview – "נסה עכשיו" בעריכה
- [ ] Widget deep link → AlarmListScreen
- [ ] Accessibility labels על Switch/IconButtons
- [ ] Unit tests ל-AlarmScheduler.nextFireTime()

### עדיפות נמוכה
- [ ] Export/Import JSON של שעמורים
- [ ] Custom accent color

---

## 10. בעיות ידועות

| בעיה | מקום | חומרה |
|------|-------|--------|
| BIWEEKLY week parity בחצות שנה | `AlarmScheduler.nextFireTime()` | נמוכה |
| Long.toInt() ל-id גדול | `AlarmScheduler.buildIntent()` | נמוכה |
| MediaPlayer error ללא fallback | `AlarmFiringService.startAudio()` | בינונית |

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

מצב נוכחי: גרסה v5-final, DB version 2, כל הפיצ'רים ב-HANDOFF.md סעיף 2 מיושמים.
עברו 4 סיבובי code review ע"י UI/UX/Architect/DevOps experts – הכל תקין.

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
```
