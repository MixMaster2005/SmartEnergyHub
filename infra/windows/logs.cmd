@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0se.ps1" logs %*
exit /b %ERRORLEVEL%
