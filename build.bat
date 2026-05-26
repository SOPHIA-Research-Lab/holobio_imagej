@echo off
REM HoloBio ImageJ build — delegates to build.ps1 (paths with spaces break plain cmd javac).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
exit /b %ERRORLEVEL%
