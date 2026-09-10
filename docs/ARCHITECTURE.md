# SmartRing – ארכיטקטורה v3

## שכבות
```
Compose UI → ViewModels (StateFlow) → AlarmRepository → Room DB v3
                                   → AlarmScheduler (AlarmManager)
                                   → AppLogger (app_logs, אבחון)
                                   → WorkManager (reschedule + log cleanup)
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

## Security
allowBackup=false · exported=false · FLAG_IMMUTABLE · ProGuard · prepareAsync() · startForeground() ראשון
