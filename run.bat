@echo off
rem ============================================================
rem  HolzynActor clone-and-run launcher (Windows)
rem
rem  - Clean start: H2 database is auto-created at <repo>\data on
rem    first run (no committed database needed).
rem  - If backend\target\holzyn-actor-0.1.0.jar is missing, runs
rem    build.bat first (needs JDK 21 + Maven + Node).
rem ============================================================
setlocal
set "ROOT=%~dp0"

rem ---- JDK 21 ----
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" goto :have_java
)
where java >nul 2>nul
if %errorlevel%==0 goto :have_java
echo [ERROR] JDK 21 not found. Install JDK 21 or set JAVA_HOME.
pause
exit /b 1
:have_java
if not defined JAVA_HOME (
    for /f "delims=" %%i in ('where java') do set "JAVA_HOME=%%~dpi.."
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- data & upload dirs (repo root, auto-created) ----
set "HOLOZYN_ACTOR_DATA_DIR=%ROOT%data"
set "HOLOZYN_ACTOR_UPLOAD_DIR=%ROOT%uploads"

rem ---- build jar if missing ----
set "JAR=%ROOT%backend\target\holzyn-actor-0.1.0.jar"
if not exist "%JAR%" (
    echo [run] jar not found - building first ...
    call "%ROOT%build.bat"
    if errorlevel 1 exit /b 1
)

echo [run] data dir : %HOLOZYN_ACTOR_DATA_DIR%
echo [run] jar     : %JAR%
java -jar "%JAR%"
