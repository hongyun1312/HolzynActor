@echo off
rem ============================================================
rem  HolzynActor 桌面端一键构建（Windows）
rem  步骤：1) 后端 fat jar（含前端静态资源，build.bat）
rem        2) 裁剪 JRE 运行时（packaging/runtime，缺失时自动生成）
rem        3) electron-builder 打包 NSIS 安装版 + 免安装便携版
rem  产物：desktop\release\HolzynActor-Setup-1.1.2-beta.1.exe（安装版）
rem        desktop\release\HolzynActor-Portable-1.1.2-beta.1.exe（绿色版）
rem ============================================================
setlocal
set "ROOT=%~dp0.."

rem ---- 1. 后端 fat jar ----
call "%ROOT%build.bat"
if errorlevel 1 exit /b 1

rem ---- 2. 裁剪 JRE 运行时（已存在则跳过）----
if not exist "%ROOT%packaging\runtime\bin\java.exe" (
    echo [desktop] jlink runtime missing - generating ...
    call "%ROOT%packaging\make-runtime.bat"
    if errorlevel 1 exit /b 1
) else (
    echo [desktop] runtime exists: packaging\runtime
)

rem ---- 3. electron-builder ----
pushd "%~dp0"
call npm install
if errorlevel 1 exit /b 1
call npx electron-builder --win nsis portable
if errorlevel 1 popd & exit /b 1
popd

echo.
echo [desktop] OK: desktop\release\HolzynActor-Setup-*.exe 与 HolzynActor-Portable-*.exe
pause
