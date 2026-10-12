@echo off
rem ============================================================
rem  HolzynActor backend launcher (mvn spring-boot:run)
rem
rem  IMPORTANT: data & upload dirs are canonicalized to the repo
rem  root (<backend>\..\data and ..\uploads), so EVERY run method
rem  (run.bat / run-mvn.bat / java -jar) shares the SAME local
rem  H2 database "data/holzyn-actor.mv.db" (auto-created on first run).
rem
rem  Requires: JDK 21 (JAVA_HOME) + Maven
rem  Optional: backend\.env for environment overrides
rem ============================================================
setlocal

rem ---- 1. load optional backend\.env overrides ----
if exist "%~dp0.env" for /f "usebackq tokens=1,* delims==" %%a in ('findstr /b /v "^#" "%~dp0.env"') do set "%%a=%%b"

rem ---- 2. locate JDK 21 ----
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" goto :find_maven
)
rem (public release) no hardcoded JDK path: install JDK 21 and set JAVA_HOME, or put java on PATH
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 21 not found. Install JDK 21 or set JAVA_HOME.
    pause
    exit /b 1
)

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
    echo [ERROR] Maven not found. Install Maven and put mvn on PATH. & pause & exit /b 1
)
