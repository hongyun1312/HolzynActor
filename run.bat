@echo off
rem ============================================================
rem  HolzynActor clone-and-run launcher (Windows)
rem
rem  - Uses <repo>\data (local H2 database, auto-created) and <repo>\uploads
rem    so all data stays under the repo.
rem  - If backend\target\holzyn-actor-1.1.0-beta.2.jar is missing,
rem    it runs build.bat first (needs JDK21 + Maven + Node).
rem  - Zero-build path: clone -> just JDK 21 -> run.bat
rem ============================================================
setlocal
set "ROOT=%~dp0"

rem ---- JDK 21 ----
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" goto :have_java
)
rem (public release) no hardcoded JDK path: install JDK 21 and set JAVA_HOME, or put java on PATH
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 21 not found. Install JDK 21 or set JAVA_HOME.
    pause
    exit /b 1
)
:have_java
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- canonical data & upload dirs (local data) ----
set "HOLOZYN_ACTOR_DATA_DIR=%ROOT%data"
set "HOLOZYN_ACTOR_UPLOAD_DIR=%ROOT%uploads"

rem ---- 服务端口（长期固定 18080；已有环境变量则以环境变量为准）----
if not defined HOLOZYN_ACTOR_SERVER_PORT set "HOLOZYN_ACTOR_SERVER_PORT=18080"

rem ---- build jar if missing ----
set "JAR=%ROOT%backend\target\holzyn-actor-1.1.0-beta.2.jar"
if not exist "%JAR%" (
    echo [run] jar not found - building first ...
    call "%ROOT%build.bat"
    if errorlevel 1 exit /b 1
)

echo [run] data dir : %HOLOZYN_ACTOR_DATA_DIR%
echo [run] jar     : %JAR%
echo [run] url     : http://localhost:%HOLOZYN_ACTOR_SERVER_PORT%
java -jar "%JAR%"
