@echo off
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
  echo [ERROR] Missing virtual environment: .venv
  echo Run setup.bat first.
  exit /b 1
)

".venv\Scripts\python.exe" main.py
exit /b %errorlevel%
