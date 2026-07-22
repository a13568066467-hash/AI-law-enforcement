@echo off
cd /d "%~dp0"
if not exist "venv\Scripts\python.exe" (
  echo [backend] venv missing. First-time setup:
  echo   python -m venv venv
  echo   venv\Scripts\python.exe -m pip install -r requirements.txt
  echo   copy .env.example .env
  exit /b 1
)
venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload %*
