@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0se.ps1" first-start %*
exit /b %ERRORLEVEL%
