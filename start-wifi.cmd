@echo off
setlocal
cd /d "%~dp0"
if /i "%~1"=="--no-open" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1" -Lan -NoOpen
) else (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1" -Lan
)
if errorlevel 1 pause
