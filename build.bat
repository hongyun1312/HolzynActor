@echo off
rem ============================================================
rem  HolzynActor full build: frontend -> static -> backend jar
rem  Run from repo root. Produces backend\target\holzyn-actor-0.1.0.jar
rem
rem  Requires: JDK 21 + Maven + Node.js (npm)
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

rem ---- Maven ----
where mvn >nul 2>nul
if not %errorlevel%==0 (
    echo [ERROR] Maven not found in PATH. Install Maven or add it to PATH.
    pause
    exit /b 1
)
set "MVN=mvn"

rem ---- 1. frontend build ----
echo [build] npm run build ...
pushd "%ROOT%frontend"
call npm run build
if errorlevel 1 goto :fail
popd

rem ---- 2. sync dist -> backend static (clear old assets) ----
echo [build] sync dist -^> backend/src/main/resources/static ...
if exist "%ROOT%backend\src\main\resources\static\assets" rd /s /q "%ROOT%backend\src\main\resources\static\assets"
xcopy /e /i /y "%ROOT%frontend\dist\*" "%ROOT%backend\src\main\resources\static\" >nul
if exist "%ROOT%backend\target\classes\static" rd /s /q "%ROOT%backend\target\classes\static"

rem ---- 3. backend package (clean) ----
echo [build] mvn clean package ...
"%MVN%" -f "%ROOT%backend\pom.xml" -q "-Dmaven.test.skip=true" clean package
if errorlevel 1 goto :fail

echo.
echo [build] OK: backend\target\holzyn-actor-0.1.0.jar
pause
exit /b 0

:fail
echo [build] FAILED
pause
exit /b 1
