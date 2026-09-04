# SmartRing – מדריך Claude Code v2

## פתיחת שיחה
```
קרא: README.md, docs/ARCHITECTURE.md, domain/model/Alarm.kt, data/db/AlarmDao.kt
SmartRing, Kotlin+Compose, MVVM+Repository+Hilt+Room, DB v2
```

## סדר עדכון שדה חדש
```
Alarm.kt → AlarmEntities.kt → MIGRATION_1_2 → AppDatabase (bump version)
→ AlarmMapper.kt → AlarmRepository.kt → AlarmEditViewModel.kt → AlarmEditScreen.kt
```

## כללים
- State: _state.update { it.copy(...) } בלבד
- אין Android imports ב-domain/
- startForeground() לפני repository.getAlarm()
- prepareAsync() ולא prepare()
- PendingIntent.FLAG_IMMUTABLE תמיד
