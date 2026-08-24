# KSensor Gateway

אפליקציית אנדרואיד שסורקת/מתחברת לחיישני KBeacon K6/KBPro ומציגה
טמפרטורה ולחות. הפרויקט הזה **בנוי ומוכן להרצה בענן** דרך GitHub
Actions — אין צורך להתקין Android Studio במחשב שלך בכלל.

## איך מקבלים APK בלי להתקין כלום

### שלב 1: חשבון GitHub (חינם, אם אין לך כבר)
היכנס ל-https://github.com/signup וצור חשבון.

### שלב 2: צור repository חדש
1. https://github.com/new
2. תן שם (למשל `ksensor-gateway`), השאר "Public" או "Private" (שניהם
   עובדים עם GitHub Actions בחינם), **אל תסמן** "Add a README" (כדי
   שלא יהיה קונפליקט), ולחץ "Create repository".

### שלב 3: העלה את כל תוכן התיקייה
בדף שנפתח, יש קישור "uploading an existing file" — לחץ עליו.
גרור **את כל תוכן** התיקייה שקיבלת ממני (כולל התיקיות `app` ו-
`.github` עם כל מה שבפנים) לתוך חלון הדפדפן, ולחץ "Commit changes".

⚠️ חשוב: הגרירה חייבת לכלול את התיקייה הנסתרת `.github` (עם
`workflows/build.yml` בפנים) — בלעדיה ה-build לא ירוץ. אם הדפדפן לא
מאפשר לגרור תיקיות נסתרות בקלות, אפשר גם:
- להשתמש ב-GitHub Desktop (אפליקציה חינמית, גוררים תיקייה שלמה), או
- ל-zip את כל התיקייה ולהשתמש ב-`git` מהטרמינל (אם יש), או
- לבקש ממני בהמשך שלב-אחר-שלב עם GitHub Desktop אם ה-drag-and-drop לא עובד.

### שלב 4: המתן ל-build
בכרטיסייה "Actions" בראש הדף של ה-repository, אתה אמור לראות ריצה
בשם "Build APK" מתחילה אוטומטית (בערך 3-5 דקות). כשהיא מסתיימת
בסימן ✅ ירוק:
1. לחץ עליה
2. גלול למטה ל-"Artifacts"
3. הורד את `KSensorGateway-debug-apk` (קובץ zip שמכיל את ה-`app-debug.apk`)

### שלב 5: התקנה בטלפון
1. חלץ את ה-zip, שלח את `app-debug.apk` לטלפון (וואטסאפ לעצמך, גוגל
   דרייב, מייל — כל דרך).
2. פתח את הקובץ בטלפון. אנדרואיד יבקש לאשר "התקנה ממקורות לא ידועים"
   — אשר (זה תקין, זה קורה לכל APK שלא מגיע מ-Google Play).
3. האפליקציה תותקן ותופיע ברשימת האפליקציות כ-"KSensor Gateway".

## מבנה הפרויקט (למי שסקרן)
```
build.gradle.kts, settings.gradle.kts, gradle.properties   ← קבצי בנייה כלליים
.github/workflows/build.yml                                 ← ה"מתכון" שה-build בענן מריץ
app/build.gradle.kts                                         ← תלויות האפליקציה
app/src/main/AndroidManifest.xml                             ← הרשאות
app/src/main/java/com/agsense/ksensorgateway/*.kt            ← כל הקוד
app/src/main/res/layout/*.xml                                ← מסכים
app/src/main/res/mipmap-*/ic_launcher.png                    ← אייקון (זמני, אפשר להחליף)
```

## מה האפליקציה עושה
- **סריקה פסיבית** (כפתור "התחל סריקה") — מציגה כל חיישן KSensor
  שנראה בסביבה, בלי להתחבר אליו.
- **חיבור ישיר לפי MAC** (למטה במסך) — מתחבר ישירות לכתובת שתזין,
  מבצע אימות MD5 מול החיישן, מגדיר עליו דיווח טמפרטורה/לחות בזמן
  אמת, ומציג את הערכים.

## הערות טכניות חשובות (מהשיחה)
- **סדר טמפרטורה/לחות** בהודעת ה-trigger שנוי במחלוקת בין שני חלקים
  של מסמך הפרוטוקול של היצרן. אם הערכים בחיבור הישיר נראים הפוכים,
  פתח את `GattClient.kt`, פונקציה `handleIndicateFrame`, והחלף בין
  `a` ל-`b` בשורה `listener.onReading(a, b)`.
- **פענוח ה-advertisement** (`KSensorParser.kt`) מניח שאין "בייט
  מרווח" בין Sensor Mask ל-Voltage (יש חוסר-בהירות דומה במסמך).
  קבוע `ASSUME_GAP_BYTE` בראש הקובץ — שנה ל-`true` אם הטמפרטורה
  שמוצגת בסריקה הפסיבית לא הגיונית.
- ללוגים מפורטים (bytes גולמיים שנשלחים/מתקבלים): חבר את הטלפון
  ב-USB ל-Android Studio של מישהו אחר, או השתמש ב-`adb logcat` אם יש
  לך גישה למחשב עם adb, וסנן לפי `KSensorGateway`.
