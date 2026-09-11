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
- רענון: `WidgetRefresher.refresh()` נקרא מכל שינוי תזמון (schedule/cancel/cancelAll/rescheduleAll
  ב-`AlarmScheduler`, וגם על re-fire של נודניק ב-`AlarmFiringService`), ובנוסף `WidgetRefreshWorker`
  רץ כל 15 דקות כ-fallback.

## Security
allowBackup=false · exported=false · FLAG_IMMUTABLE · ProGuard · prepareAsync() · startForeground() ראשון
