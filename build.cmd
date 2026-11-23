@echo off
REM ============================================================
REM  GnssSpoof build helper
REM  Pins JDK / Gradle home / SDK so nobody has to guess PATH.
REM
REM  Usage:
REM    build.cmd            -> assembleDebug
REM    build.cmd release    -> assembleRelease (unsigned unless keystore set)
REM    build.cmd test       -> unit tests
REM    build.cmd clean      -> clean
REM    build.cmd <task>     -> any gradle task, passed through
REM ============================================================
setlocal

set "JAVA_HOME=D:\tools\jdk-21"
set "GRADLE_USER_HOME=D:\tools\gradle\home"
cd /d "%~dp0"

set "TASK=assembleDebug"
if /i "%~1"=="release" set "TASK=assembleRelease"
if /i "%~1"=="test"    set "TASK=test"
if /i "%~1"=="clean"   set "TASK=clean"
if not "%~1"=="" if not "%TASK%"=="assembleDebug" if /i not "%~1"=="release" if /i not "%~1"=="test" if /i not "%~1"=="clean" set "TASK=%~1"

echo [build] JAVA_HOME        = %JAVA_HOME%
echo [build] GRADLE_USER_HOME = %GRADLE_USER_HOME%
echo [build] task             = %TASK%
echo.

call gradlew.bat %TASK% --no-daemon
set "RC=%ERRORLEVEL%"

echo.
if "%RC%"=="0" (
  echo [build] OK
  if exist "app\build\outputs\apk" (
    for /r "app\build\outputs\apk" %%F in (*.apk) do echo   APK: %%F
  )
) else (
  echo [build] FAILED  rc=%RC%
)
exit /b %RC%
