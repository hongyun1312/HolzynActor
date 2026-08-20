@echo off
rem ============================================================
rem  HolzynActor backend launcher (mvn spring-boot:run)
rem
rem  - data & upload dirs are canonicalized to the repo root
rem    (<backend>\..\data and ..\uploads), shared by every run method.
rem  - Clean start: H2 database is auto-created at <repo>\data.
rem
rem  Requires: JDK 21 (JAVA_HOME or on PATH) + Maven
rem  Optional: backend\.env for environment overrides
rem ============================================================
setlocal

rem ---- 1. load optional backend\.env overrides ----
if exist "%~dp0.env" for /f "usebackq tokens=1,* delims==" %%a in ('findstr /b /v "^#" "%~dp0.env"') do set "%%a=%%b"

rem ---- 2. locate JDK 21 ----
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" goto :find_maven
)
where java >nul 2>nul
if %errorlevel%==0 (
    for /f "delims=" %%i in ('where java') do set "JAVA_HOME=%%~dpi.."
    goto :find_maven
)
echo [ERROR] JDK 21 not found. Install JDK 21 or set JAVA_HOME.
pause
exit /b 1

:find_maven
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- 3. canonical data & upload dirs (repo root, shared with run.bat) ----
set "HOLOZYN_ACTOR_DATA_DIR=%~dp0..\data"
set "HOLOZYN_ACTOR_UPLOAD_DIR=%~dp0..\uploads"

rem ---- 4. run via maven spring-boot:run ----
where mvn >nul 2>nul
if %errorlevel%==0 (
    mvn -f "%~dp0pom.xml" spring-boot:run > "%~dp0run.log" 2>&1
) else (
    echo [ERROR] Maven not found in PATH. Install Maven or add it to PATH.
    pause
    exit /b 1
)
