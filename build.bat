@echo off
rem ============================================================
rem  HolzynActor full build: frontend -> static -> backend jar
rem  Run from repo root. Produces backend\target\holzyn-actor-1.1.2-beta.1.jar
rem  (run it with run.bat, or java -jar directly)
rem
rem  Requires: JDK 21 + Maven + Node.js (npm)
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

rem ---- Maven ----
set "MVN=mvn"
where mvn >nul 2>nul
rem (public release) Maven fallback removed: install Maven and put mvn on PATH

rem ---- 1. frontend build ----
rem 先清 dist：Vite 未清空 outDir 时会残留历史 hash 分片，xcopy 会把它们一并带进 static
if exist "%ROOT%frontend\dist" rd /s /q "%ROOT%frontend\dist"
if exist "%ROOT%debug-frontend\dist" rd /s /q "%ROOT%debug-frontend\dist"
echo [build] npm install (frontend) ...
pushd "%ROOT%frontend"
call npm install
if errorlevel 1 goto :fail
echo [build] npm run build ...
call npm run build
if errorlevel 1 goto :fail
popd

rem ---- 1b. debug-frontend build (2026-08-20 调试可视化前端) ----
echo [build] npm install (debug-frontend) ...
pushd "%ROOT%debug-frontend"
call npm install
if errorlevel 1 goto :fail
echo [build] debug-frontend npm run build ...
call npm run build
if errorlevel 1 goto :fail
popd

rem ---- 2. sync dist -> backend static (clear old assets) ----
echo [build] sync dist -^> backend/src/main/resources/static ...
if exist "%ROOT%backend\src\main\resources\static\assets" rd /s /q "%ROOT%backend\src\main\resources\static\assets"
xcopy /e /i /y "%ROOT%frontend\dist\*" "%ROOT%backend\src\main\resources\static\" >nul

rem ---- 2b. sync debug dist -> backend static/debug (clear old) ----
echo [build] sync debug dist -^> backend/src/main/resources/static/debug ...
if exist "%ROOT%backend\src\main\resources\static\debug" rd /s /q "%ROOT%backend\src\main\resources\static\debug"
xcopy /e /i /y "%ROOT%debug-frontend\dist\*" "%ROOT%backend\src\main\resources\static\debug\" >nul
if exist "%ROOT%backend\target\classes\static" rd /s /q "%ROOT%backend\target\classes\static"

rem ---- 3. backend package (clean) ----
echo [build] mvn clean package ...
"%MVN%" -f "%ROOT%backend\pom.xml" -q "-Dmaven.test.skip=true" clean package
if errorlevel 1 goto :fail

echo.
echo [build] OK: backend\target\holzyn-actor-1.1.2-beta.1.jar
echo [build] Tip: run it with run.bat (auto-builds the jar if missing).
pause
exit /b 0

:fail
echo [build] FAILED
pause
exit /b 1
