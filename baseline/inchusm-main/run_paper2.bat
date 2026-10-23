@echo off
setlocal
REM ================================================================
REM  run_paper2.bat - run ALL Paper 2 (RIVF) experiments in one command.
REM
REM  Usage (open cmd in the inchusm folder, or double-click the file):
REM     run_paper2.bat                (everything; E2d AMLworld runs LAST, takes hours)
REM     run_paper2.bat --quick        (skip E2d - pipeline smoke test ~30 min)
REM     run_paper2.bat --only-e2d     (run only E2d once the other steps are done)
REM     set NO_PAUSE=1& run_paper2.bat --quick    (run inside a script, no key wait)
REM
REM  REQUIRES: (1) venv at %%USERPROFILE%%\.venvs\inchusm (see
REM  docs\reproduce\paper2.md); without it, system python is used.
REM  (2) Java 17 on PATH. (3) TURN OFF/PAUSE Google Drive sync during timing.
REM  Output: experiments\results\*.csv + log run_all_paper2_log.txt (records the
REM  machine configuration) + figure PDFs at the project root, if that folder exists.
REM ================================================================

set "VENV_PY=%USERPROFILE%\.venvs\inchusm\Scripts\python.exe"
set "RUNNER=%~dp0experiments\scripts\run_all_paper2.py"
set "PYTHONIOENCODING=utf-8"

java -version >nul 2>&1
if errorlevel 1 (
  echo [run_paper2] ERROR: java not found on PATH - install JDK 17 first.
  if not defined NO_PAUSE pause
  exit /b 2
)

if exist "%VENV_PY%" (
  echo [run_paper2] Using venv: %VENV_PY%
  "%VENV_PY%" "%RUNNER%" %*
) else (
  echo [run_paper2] venv inchusm not found - using system python.
  python "%RUNNER%" %*
)

set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (echo [run_paper2] DONE - all steps OK.) else (echo [run_paper2] FINISHED but some steps FAILED, rc=%RC% - see the log above.)
if not defined NO_PAUSE pause
exit /b %RC%
