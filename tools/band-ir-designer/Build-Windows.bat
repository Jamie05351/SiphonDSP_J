@echo off
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe py -3 -m venv .venv
if errorlevel 1 goto failed
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto failed
.venv\Scripts\python.exe -m unittest discover -s tests -v
if errorlevel 1 goto failed
.venv\Scripts\python.exe -m PyInstaller --noconfirm --clean --windowed --onedir --name BandIRDesigner app.py
if errorlevel 1 goto failed
echo Built dist\BandIRDesigner\BandIRDesigner.exe
pause
exit /b 0
:failed
echo Build failed. Read the error above.
pause
exit /b 1
