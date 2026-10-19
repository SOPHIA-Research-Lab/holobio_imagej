@echo off
REM From HoloBio_ImageJ: build jar, generate numpy golden, run Java, compare.
setlocal
cd /d "%~dp0.."
echo.| call build.bat
if errorlevel 1 exit /b 1
cd /d "%~dp0"
set VENV_PY=%~dp0..\..\HoloBio_Python\venv\Scripts\python.exe
if exist "%VENV_PY%" (
  "%VENV_PY%" generate_propagation_golden.py "%~dp0parity_workspace" 256 angular
) else (
  python generate_propagation_golden.py "%~dp0parity_workspace" 256 angular
)
if errorlevel 1 exit /b 1
if exist "%VENV_PY%" (
  "%VENV_PY%" compare_propagation_par.py "%~dp0parity_workspace" "%~dp0..\HoloBio_.jar"
) else (
  python compare_propagation_par.py "%~dp0parity_workspace" "%~dp0..\HoloBio_.jar"
)
endlocal
