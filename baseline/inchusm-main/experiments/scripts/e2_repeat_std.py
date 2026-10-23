"""Repeated E2 timing runs (Elliptic-49) K times -> speedup mean +/- std.

Purpose: add a standard deviation to the speedup claim.
MUST run on the official measurement machine (i7-10750H) to stay consistent with the
other timing numbers in the paper. The COUNT values (49/49, #HUS) are deterministic and
need no repetition; only TIMING needs repeating.

Do NOT overwrite the official result file: each run uses --out to a temporary CSV.

Run (on the i7, TURN OFF Google Drive sync while measuring):
    "%USERPROFILE%\\.venvs\\inchusm\\Scripts\\python.exe" experiments\\scripts\\e2_repeat_std.py --runs 5

Each run ~10 min (static re-run of 49 snapshots), so --runs 5 takes ~50 min; --runs 3 (~30 min) is the minimum to get a std.
The "REPEATED-RUN RESULTS" block at the end reports the speedup mean ± std.
"""
from __future__ import annotations

import argparse
import csv
import os
import statistics
import subprocess
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
RESULTS = SCRIPTS.parents[1] / "results"
E2 = SCRIPTS / "e2_inchusm_stream.py"


def totals(csv_path: Path):
    rows = list(csv.DictReader(csv_path.open(encoding="utf-8")))
    inc = sum(float(r["inc_ms"]) for r in rows) / 1000.0
    stat = sum(float(r["static_ms"]) for r in rows) / 1000.0
    n_ok = sum(1 for r in rows if r.get("match") == "OK")
    return inc, stat, n_ok, len(rows), rows[-1]["high_total"]


def fmt(xs):
    if len(xs) > 1:
        return f"{statistics.mean(xs):.1f} ± {statistics.stdev(xs):.1f}"
    return f"{xs[0]:.1f}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=5, help="number of repetitions (>=3 for a std)")
    args = ap.parse_args()

    tmp = RESULTS / "_repeat_tmp.csv"
    incs, stats, sps = [], [], []
    for k in range(1, args.runs + 1):
        print(f"[repeat] run {k}/{args.runs} ...", flush=True)
        subprocess.run(
            [sys.executable, str(E2), "--out", str(tmp)],
            env={**os.environ, "PYTHONIOENCODING": "utf-8"}, check=True,
        )
        inc, stat, n_ok, n, hi = totals(tmp)
        sp = stat / inc
        incs.append(inc); stats.append(stat); sps.append(sp)
        print(f"  -> inc={inc:.1f}s static={stat:.1f}s speedup={sp:.1f}x "
              f"(match={n_ok}/{n}, high={hi})", flush=True)
    tmp.unlink(missing_ok=True)

    print(f"\n{'='*56}\nREPEATED-RUN RESULTS (n={args.runs})\n{'='*56}")
    print(f"inc total (s)   : {fmt(incs)}")
    print(f"static total (s): {fmt(stats)}")
    print(f"speedup (x)     : {fmt(sps)}")
    print("Paste 'speedup mean ± std' into the paper (E2 + abstract).")


if __name__ == "__main__":
    main()
