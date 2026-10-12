@echo off
rem ============================================================
rem  HolzynActor 裁剪 JRE 运行时生成（jlink，约 62MB）→ packaging\runtime
rem  供桌面端（Electron sidecar）与 jpackage 便携版随包分发。
rem  需要 JDK 21（JAVA_HOME）。
rem ============================================================
setlocal
if not defined JAVA_HOME (
    echo [ERROR] JAVA_HOME not set ^(JDK 21 required^).
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\jlink.exe" (
    echo [ERROR] jlink.exe not found under JAVA_HOME. JDK 21 required.
    exit /b 1
)
set "OUT=%~dp0runtime"
if exist "%OUT%" rd /s /q "%OUT%"

rem 模块清单：Spring Boot 4.1 + H2 + MySQL 驱动 + java.awt(浏览器自动打开) 运行所需全集
"%JAVA_HOME%\bin\jlink.exe" ^
  --add-modules java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,^
java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,^
java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,^
jdk.charsets,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.crypto.mscapi,jdk.localedata,jdk.management,^
jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.sctp,jdk.security.auth,jdk.security.jgss,jdk.unsupported,^
jdk.xml.dom,jdk.zipfs ^
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "%OUT%"
if errorlevel 1 exit /b 1
echo [runtime] OK: %OUT%
