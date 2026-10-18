@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0se.ps1" shell %*
exit /b %ERRORLEVEL%
