"""Repeated E2d runs (AMLworld HI-Small, daily stream) K times -> speedup mean +/- std.

Context: the main Paper 2 speedup (E2 on Elliptic-49) ALREADY has a std (37.9 +/- 0.5x).
E2d is a secondary check on real AML data and is currently SINGLE-RUN (26.0x). This
runner adds a std for E2d. OPTIONAL — does not block submission.

MUST run on the official measurement machine (i7-10750H); TURN OFF Google Drive sync while measuring.
Do NOT overwrite `e2d_amlworld_stream.csv` (use a temporary CSV, deleted when done).

Cost: each run ~20-25 min (static re-run ~20 min). --runs 3 ~1.2 h; --runs 5 ~2 h.

Run:
    "%USERPROFILE%\\.venvs\\inchusm\\Scripts\\python.exe" experiments\\scripts\\e2d_repeat_std.py --runs 3

The "REPEATED E2d RESULTS" block at the end reports the E2d speedup mean ± std.
"""
from __future__ import annotations

import argparse
import csv
import os
import shutil
import statistics
import subprocess
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
RESULTS = SCRIPTS.parents[1] / "results"
E2D = SCRIPTS / "e2d_amlworld_stream.py"


def totals(csv_path: Path):
    rows = list(csv.DictReader(csv_path.open(encoding="utf-8")))
    inc = sum(float(r["inc_ms"]) for r in rows) / 1000.0
    stat = sum(float(r["static_ms"]) for r in rows) / 1000.0
    n_ok = sum(1 for r in rows if r.get("match") == "OK")
    return inc, stat, n_ok, len(rows), int(rows[-1]["high_total"])


def fmt(xs, nd=1):
    if len(xs) > 1:
        return f"{statistics.mean(xs):.{nd}f} ± {statistics.stdev(xs):.{nd}f}"
    return f"{xs[0]:.{nd}f}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3, help="number of repetitions (>=3 for a std)")
    args = ap.parse_args()

    tmp = RESULTS / "_e2d_repeat_tmp.csv"
    incs, stats, sps, oks, highs = [], [], [], [], []
    for k in range(1, args.runs + 1):
        print(f"[e2d-repeat] run {k}/{args.runs} (each ~20-25 min) ...", flush=True)
        subprocess.run(
            [sys.executable, str(E2D), "--out", str(tmp)],
            env={**os.environ, "PYTHONIOENCODING": "utf-8"}, check=True,
        )
        inc, stat, n_ok, n, hi = totals(tmp)
        sp = stat / inc
        incs.append(inc); stats.append(stat); sps.append(sp)
        oks.append(f"{n_ok}/{n}"); highs.append(hi)
        print(f"  -> inc={inc:.1f}s static={stat:.1f}s speedup={sp:.1f}x "
              f"(match={n_ok}/{n}, high={hi})", flush=True)
    tmp.unlink(missing_ok=True)
    shutil.rmtree(RESULTS / "e2d_snapshots", ignore_errors=True)  # intermediate, regenerable

    print(f"\n{'='*60}\nREPEATED E2d RESULTS (n={args.runs})\n{'='*60}")
    print(f"inc total (s)   : {fmt(incs)}")
    print(f"static total (s): {fmt(stats)}")
    print(f"speedup (x)     : {fmt(sps)}")
    inv_ok = len(set(oks)) == 1 and len(set(highs)) == 1
    print(f"invariant counts: match {sorted(set(oks))}, high {sorted(set(highs))}"
          + ("  same every run" if inv_ok else "  FAIL DIFFER — investigate!"))
    print("Paste 'speedup mean ± std' into the Paper 2 E2d section. The paper keeps 26.0x as the representative run.")


if __name__ == "__main__":
    main()
