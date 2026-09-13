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

## מסך הצלצול ואמינות (v1.4.0)
- `AlarmRingViewModel` מחשב את הזמן שחלף מאז הצלצול לפי `SystemClock.elapsedRealtime()`,
  מעוגן ל-timestamp האמיתי (`alarm_logs` action='FIRED'), ולא ספירת טיקים מקומית של המסך —
  כך שהמסך נסגר בזמן אמיתי גם אם הוא נפתח מחדש (rotation) ולא מושפע מקפיצת שעון (DST/NTP).
  משמש כרשת ביטחון (עם buffer של 2 שניות) לצד הטיימר האמיתי של `AlarmFiringService`, למקרה
  שהשירות לא נסגר בזמן.
- `AlarmFiringService` כותב את רשומת ה-`FIRED` ל-DB *לפני* פרסום ההתראה (לא אחריה) —
  ה-full-screen intent יכול לפתוח את מסך הצלצול כמעט מיידית, וסדר הפוך השאיר חלון שבו
  מסך הצלצול קורא timestamp ישן מצלצול קודם.

## בדיקות אוטומטיות (v1.4.1)
`app/src/test/` — JUnit4 + Robolectric (סביבת אנדרואיד על ה-JVM, לא אמולטור אמיתי) + mockk,
רץ ב-CI לפני assembleDebug/Release. `AlarmScheduler.nextFireTime()` מקבל `now: Long`
אופציונלי (ברירת מחדל: השעון האמיתי) בדיוק כדי לאפשר בדיקה דטרמיניסטית של חישובי
WEEKLY/BIWEEKLY/MONTHLY. פירוט מלא ב-HANDOFF.md סעיף 13.

## Security
allowBackup=false · exported=false · FLAG_IMMUTABLE · ProGuard · prepareAsync() · startForeground() ראשון
