@echo off
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe py -3 -m venv .venv
if errorlevel 1 goto failed
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto failed
.venv\Scripts\python.exe app.py
if errorlevel 1 goto failed
exit /b 0
:failed
echo Designer could not start. Install Python 3.11 or newer with Tcl/Tk and the Python launcher.
pause
exit /b 1
