# SmartRing ⏰

אפליקציית שעון מעורר חכם לאנדרואיד – Kotlin + Jetpack Compose

## בנייה אוטומטית ← הורדת APK

### שלב 1 – העלה לGitHub
1. צור repository חדש ב-github.com
2. העלה את כל תוכן ה-ZIP לrepository

### שלב 2 – הרץ Build
1. לחץ על לשונית **Actions** ב-GitHub
2. בחר **"Build SmartRing APK"**
3. לחץ **"Run workflow"** ← **"Run workflow"**
4. המתן ~5-8 דקות

### שלב 3 – הורד APK
1. לחץ על ה-run שהסתיים (✅)
2. גלול למטה ← **Artifacts**
3. לחץ על **SmartRing-debug-N**
4. חלץ ZIP → קבל `app-debug.apk`

### שלב 4 – התקן על הטלפון
1. שלח את ה-APK לטלפון (WhatsApp / Email / USB)
2. פתח → "התקן" → אשר "Unknown sources"

## Tech Stack
- Kotlin 2.0 + Jetpack Compose + Material 3
- MVVM + Repository + Hilt DI
- Room DB + DataStore
- AlarmManager (exact) + ForegroundService
- Glance API (4 גדלי ווידג'ט)
- WorkManager (reschedule on boot, יומי לניקוי לוגים)

## עדכון גרסה
כל ה-APK-ים חתומים עם אותו מפתח קבוע — התקנת APK חדש **משדרגת** את הקיים, ולא
דורשת הסרה והתקנה מחדש (שהייתה מוחקת את כל השעמורים). בכניסה הראשונה אחרי עדכון
תוצג הודעת "מה חדש" עם השינויים.

## פיצ'רים עיקריים
שעמורים עם חזרתיות עשירה, עד 10 סבבי צלצול עם צליל/עוצמה/משך נפרדים, Crescendo,
מצב שבת (חוסם אינטראקציה בזמן צפצוף), נודניק שניתן לכבות לשעמור בודד, היסטוריה,
ווידג'טים, מסך לוגים לאבחון, ובדיקות אמינות רקע (התראות/שעמורים מדויקים/סוללה) —
פירוט מלא ב-`docs/FEATURES.md`.
