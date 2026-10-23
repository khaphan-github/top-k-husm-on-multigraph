@echo off
REM ===========================================================================
REM  run_extra_std.bat - Extra REPEATED runs (std) for the 2 timing results still
REM  measured only once.
REM
REM  MUST run on the official MEASUREMENT MACHINE (i7-10750H / 64GB / Win11).
REM  TURN OFF Google Drive sync before running (timing is noisy while Drive syncs).
REM
REM  NO script overwrites the official CSVs in experiments\results\.
REM
REM    [1] Upper-bound ablation (CEU/EU/IVU)  ~5 min    -> mean +- std + order check
REM    [2] E2d AMLworld speedup               ~1.2 hr   -> mean +- std (--runs 3)
REM
REM  Usage:  run_extra_std.bat            (run both)
REM          run_extra_std.bat quick      (only [1], skip the 1.2 hr E2d)
REM
REM  To save output to a file for sharing:  run_extra_std.bat > extra_std.txt 2>&1
REM ===========================================================================
setlocal
cd /d "%~dp0"

set "PY=%USERPROFILE%\.venvs\inchusm\Scripts\python.exe"
if not exist "%PY%" set "PY=python"
set "PYTHONIOENCODING=utf-8"

echo.
echo === [1/2] Upper-bound ablation (CEU/EU/IVU), 5 runs, ~5 min ===
"%PY%" experiments\scripts\e2_ablation_repeat_std.py --runs 5
if errorlevel 1 goto :fail

if /i "%~1"=="quick" (
  echo.
  echo [skip] E2d ^(quick mode^). Run again without "quick" to measure E2d.
  goto :done
)

echo.
echo === [2/2] E2d AMLworld speedup, 3 runs, ~1.2 hr ===
"%PY%" experiments\scripts\e2d_repeat_std.py --runs 3
if errorlevel 1 goto :fail

:done
echo.
echo ============================================================
echo  DONE. The "REPEATED-RUN RESULTS ..." blocks above report the mean +- std.
echo ============================================================
if not "%NO_PAUSE%"=="1" pause
exit /b 0

:fail
echo.
echo *** ERROR: a step failed (see the message just above).
if not "%NO_PAUSE%"=="1" pause
exit /b 1
