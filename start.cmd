@echo off
setlocal
cd /d "%~dp0"
if /i "%~1"=="--no-open" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1" -NoOpen
) else (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1"
)
if errorlevel 1 pause
