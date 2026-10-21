@echo off
setlocal
cd /d "%~dp0"
if /i "%~1"=="--no-open" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1" -Cms -NoOpen
) else (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1" -Cms
)
set "newsapp_exit=%errorlevel%"
if not "%newsapp_exit%"=="0" pause
exit /b %newsapp_exit%
