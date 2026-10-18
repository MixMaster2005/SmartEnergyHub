@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0se.ps1" restore %*
exit /b %ERRORLEVEL%
