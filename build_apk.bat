@echo off
chcp 65001 >nul
rem Сборка APK без Android Studio: нужен установленный Android SDK (ANDROID_HOME) и JDK 17.
rem Проще всего: открыть эту папку в Android Studio и нажать Build → Build APK(s).
cd /d "%~dp0"
call gradlew.bat assembleDebug
if errorlevel 1 (pause & exit /b)
copy /y app\build\outputs\apk\debug\app-debug.apk TopoAR.apk
echo Готово: TopoAR.apk
pause
