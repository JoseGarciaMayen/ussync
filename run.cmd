@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\ussync.exe" (
  echo Primero instala el proyecto siguiendo el README.
  pause
  exit /b 1
)
".venv\Scripts\ussync.exe" ui %*
