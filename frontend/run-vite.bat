@echo off
rem HolzynActor 前端开发服务器脚本（Vite，端口 5174）
cd /d "%~dp0"
npm run dev > "%~dp0vite.log" 2>&1