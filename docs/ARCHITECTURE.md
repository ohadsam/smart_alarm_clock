# SmartRing – ארכיטקטורה v2

## שכבות
```
Compose UI → ViewModels (StateFlow) → AlarmRepository → Room DB v2
                                   → AlarmScheduler (AlarmManager)
                                   → WorkManager
```

## מסכים
| מסך | Route |
|-----|-------|
| AlarmListScreen | /list |
| AlarmEditScreen | /edit/{id} |
| AlarmRingScreen | /ring/{id} |
| HistoryScreen   | /history ← חדש |
| SettingsScreen  | /settings |

## DB v2 – עמודות חדשות ב-alarms
`specificDateTime, recurrenceEndType, recurrenceUntilDate, recurrenceCount, occurrencesFired`

## Migration 1→2
`di/AppModule.kt` – MIGRATION_1_2 מוסיף עמודות + משדרג alarm_logs.

## Security
allowBackup=false · exported=false · FLAG_IMMUTABLE · ProGuard · prepareAsync() · startForeground() ראשון
