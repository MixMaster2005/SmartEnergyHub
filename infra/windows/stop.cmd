@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0se.ps1" stop %*
exit /b %ERRORLEVEL%
