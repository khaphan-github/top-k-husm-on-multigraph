"""Orchestrate running ALL Paper 2 (RIVF) experiments with a single command.

Purpose: run on a SINGLE machine (a high-RAM, unloaded machine recommended) so that all
TIMING values of Paper 2 share one origin — after this run, the paper's Setup section declares
the exact machine used. The COUNT values (match, #HUS, candidates) are deterministic and every
machine must reproduce the reference values in docs/reproduce/paper2.md.

Order (light first, heavy later — E2d last because it takes the most hours):
  1. E2  Elliptic-49, utility/CEU, compare-static   (MAIN result: E1 + E2)
  2. E2  bound ablation: EU, IVU (no compare)
  3. E2  measure=support ablation (no compare)
  4. E2b Citeseer trend-check (baseline faithfulness)
  5. E2c synthetic S and L (compare)
  6. E2d AMLworld HI-Small daily stream (compare) — HEAVIEST (many CPU hours)
  7. Draw figures make_figures_paper2 (from the E2 Elliptic CSV)

Note: thanks to the flush-patched jar, the CSV of every stream step is written ROW BY ROW —
killing it mid-run does not lose the completed part.

Flags:
  --quick      skip E2d (fast smoke-test of the pipeline on a new machine)
  --only-e2d   run E2d only (when the other steps already have numbers)
"""

from __future__ import annotations

import argparse
import platform
import subprocess
import sys
import time
from pathlib import Path

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")

SCRIPTS = Path(__file__).resolve().parent
RESULTS = SCRIPTS.parents[0] / "results"
PY = sys.executable


def run(name: str, script: str, args: list[str]) -> dict:
    cmd = [PY, str(SCRIPTS / script), *args]
    print(f"\n{'='*70}\n[{name}] {' '.join([script, *args])}\n{'='*70}", flush=True)
    t0 = time.time()
    proc = subprocess.run(cmd)
    dt = time.time() - t0
    ok = proc.returncode == 0
    print(f"[{name}] {'OK' if ok else 'FAIL(rc=%d)' % proc.returncode} — {dt:.0f}s", flush=True)
    return {"step": name, "ok": ok, "seconds": round(dt, 1)}


def machine_info() -> str:
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "(Get-CimInstance Win32_Processor).Name; "
             "[math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB)"],
            capture_output=True, text=True, timeout=30).stdout.strip().splitlines()
        cpu = out[0].strip() if out else "?"
        ram = out[1].strip() if len(out) > 1 else "?"
        return f"{cpu} | RAM {ram} GB | {platform.platform()}"
    except Exception:
        return platform.platform()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true", help="skip E2d (smoke-test)")
    ap.add_argument("--only-e2d", action="store_true")
    args = ap.parse_args()

    info = machine_info()
    print(f"MEASUREMENT MACHINE: {info}")
    print("REMINDER: turn off/pause Google Drive sync, do not use the machine while timing.\n")

    t0 = time.time()
    rows = []
    if not args.only_e2d:
        rows.append(run("E2 Elliptic (compare)", "e2_inchusm_stream.py", []))
        rows.append(run("E2 bound=EU", "e2_inchusm_stream.py", ["--bound", "eu", "--no-compare"]))
        rows.append(run("E2 bound=IVU", "e2_inchusm_stream.py", ["--bound", "ivu", "--no-compare"]))
        rows.append(run("E2 measure=support", "e2_inchusm_stream.py",
                        ["--measure", "support", "--min-value", "200", "--no-compare"]))
        rows.append(run("E2b Citeseer trend", "e2b_citeseer_trend.py", []))
        rows.append(run("E2c synthetic S", "e2c_synthetic_stream.py", ["--tag", "S"]))
        rows.append(run("E2c synthetic L", "e2c_synthetic_stream.py",
                        ["--tag", "L", "--n", "3000", "--min-value", "1600"]))
    if not args.quick:
        rows.append(run("E2d AMLworld daily (compare)", "e2d_amlworld_stream.py",
                        ["--days", "10", "--min-value", "2000"]))
    if not args.only_e2d:
        rows.append(run("Figures Paper 2", "make_figures_paper2.py", []))

    total = time.time() - t0
    print(f"\n{'='*70}\nSUMMARY ({total:.0f}s)\n{'='*70}")
    width = max(len(r["step"]) for r in rows)
    lines = [f"{r['step']:<{width}}  {'OK  ' if r['ok'] else 'FAIL'}  {r['seconds']:>9.1f}s"
             for r in rows]
    for ln in lines:
        print(ln)
    n_fail = sum(1 for r in rows if not r["ok"])
    print(f"\n{len(rows)-n_fail}/{len(rows)} steps OK"
          + (f" — {n_fail} FAILED (see log above)" if n_fail else ""))

    RESULTS.mkdir(exist_ok=True)
    log = RESULTS / "run_all_paper2_log.txt"
    log.write_text(
        f"run_all_paper2  total={total:.0f}s\nmachine: {info}\n\n" + "\n".join(lines) + "\n",
        encoding="utf-8",
    )
    print(f"\nLog: {log}\nCSV output: {RESULTS}")
    sys.exit(1 if n_fail else 0)


if __name__ == "__main__":
    main()
