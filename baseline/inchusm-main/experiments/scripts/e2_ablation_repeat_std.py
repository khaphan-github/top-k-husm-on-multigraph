"""Repeated ablation runs of the upper bound (IVU / EU / CEU) K times -> inc time
mean +/- std, and check whether the ORDER CEU <= EU <= IVU is stable across all runs.

Context: the Paper 2 ablation table (16.3 / 16.6 / 21.1 s) is currently single-run. What
the paper CLAIMS is the ORDER (CEU tightest -> fastest) and the EQUIVALENCE of the bounds
(all three yield the same 83 high-utility patterns), not the absolute second counts. This
runner confirms both.

Intentional difference from the official run: there CEU runs ALONGSIDE the static re-run
in the same process (E2) while EU/IVU run --no-compare. This runner runs ALL THREE bounds
with --no-compare, i.e. under the SAME conditions -> a fairer comparison. The three bounds
are interleaved within each round so they share the same machine load.

MUST run on the official measurement machine (i7-10750H); TURN OFF Google Drive sync while measuring.
Do NOT overwrite `e2_stream_utility_*.csv` (use a temporary CSV, deleted when done).

Cost: ~55 s/round -> --runs 5 is only about 5 min.

Run:
    "%USERPROFILE%\\.venvs\\inchusm\\Scripts\\python.exe" experiments\\scripts\\e2_ablation_repeat_std.py --runs 5

The "REPEATED ABLATION RESULTS" block at the end reports the inc-time mean ± std per bound
and whether the CEU ≤ EU ≤ IVU order held.
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
BOUNDS = ["ceu", "eu", "ivu"]


def read_inc_high(csv_path: Path):
    rows = list(csv.DictReader(csv_path.open(encoding="utf-8")))
    inc = sum(float(r["inc_ms"]) for r in rows) / 1000.0
    return inc, int(rows[-1]["high_total"])


def fmt(xs):
    if len(xs) > 1:
        return f"{statistics.mean(xs):5.1f} ± {statistics.stdev(xs):.1f}"
    return f"{xs[0]:5.1f}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=5, help="number of repetitions (>=3 for a std)")
    args = ap.parse_args()

    tmp = RESULTS / "_ablation_repeat_tmp.csv"
    inc: dict[str, list[float]] = {b: [] for b in BOUNDS}
    high: dict[str, set[int]] = {b: set() for b in BOUNDS}
    order_ok = 0

    for k in range(1, args.runs + 1):
        print(f"[ablation-repeat] round {k}/{args.runs} ...", flush=True)
        for b in BOUNDS:
            subprocess.run(
                [sys.executable, str(E2), "--bound", b, "--no-compare", "--out", str(tmp)],
                env={**os.environ, "PYTHONIOENCODING": "utf-8"}, check=True,
                stdout=subprocess.DEVNULL,
            )
            t, h = read_inc_high(tmp)
            inc[b].append(t); high[b].add(h)
            print(f"    {b.upper():>3}: inc={t:5.1f}s  high={h}", flush=True)
        this = [inc[b][-1] for b in BOUNDS]                  # ceu, eu, ivu
        if this[0] <= this[1] <= this[2]:
            order_ok += 1
        else:
            print(f"    [warn] round {k}: ORDER NOT HELD (ceu={this[0]:.1f} eu={this[1]:.1f} "
                  f"ivu={this[2]:.1f})", flush=True)
    tmp.unlink(missing_ok=True)

    print(f"\n{'='*60}\nREPEATED ABLATION RESULTS (n={args.runs})\n{'='*60}")
    print(f"{'Bound':>5} | {'inc (s), mean ± std':>20} | #high-utility patterns")
    for b in BOUNDS:
        hs = sorted(high[b])
        print(f"{b.upper():>5} | {fmt(inc[b]):>20} | {hs if len(hs) > 1 else hs[0]}")
    all_high = set().union(*high.values())
    print(f"\nOrder CEU <= EU <= IVU held in {order_ok}/{args.runs} rounds"
          + ("  [ok]" if order_ok == args.runs else "  [review]"))
    print(f"Bounds equivalent (all bounds share #patterns): "
          + (f"all = {all_high.pop()}" if len(all_high) == 1
             else f"differ {sorted(all_high)}"))
    print("Paste this table into the Paper 2 ablation if you want to replace the single-run numbers with mean ± std.")


if __name__ == "__main__":
    main()
