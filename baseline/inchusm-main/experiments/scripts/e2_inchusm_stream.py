"""E2 — IncHUSM streaming on Elliptic-49 (Paper 2): correctness + speedup.

Paper 2 narrative: when transaction snapshots arrive SEQUENTIALLY, IncHUSM computes
only the new segment (additive L1) instead of re-mining the whole union from scratch.
This script:
  (E1 — correctness) the match='OK' column after EVERY snapshot ⇒ the incremental high
                     set == static re-run on the cumulative union (INV2). Any non-OK ⇒
                     the script raises.
  (E2 — speedup)     inc_ms (new segment only) vs static_ms (re-mine the whole union) ⇒
                     speedup.
  (memory/fringe)    the tracked column (number of pattern-states held) + seg_last
                     (number of CSP evals for the new snapshot alone) over t ⇒ growth
                     curve.

LOCKED protocol (strict-inductive):
- Vertex label = feature f0 discretized into K levels by quantile; BIN EDGES fit on
  train (step<=34) then FROZEN, applied to all 49 snapshots (no future leakage).
- External utility of each label = a fixed positive profile (proxy) locked from the
  start: label i -> i+1.
- Elliptic snapshots are disjoint (no cross-step edges) ⇒ union = disjoint union (the
  Java disjointUnion offsets ids itself), matching the true nature of the incremental
  problem.

Run:
  python scripts/e2_inchusm_stream.py                         # utility, ceu, 49 snap, compare
  python scripts/e2_inchusm_stream.py --max-steps 4           # quick pipeline test
  python scripts/e2_inchusm_stream.py --measure support --min-value 200  # support ablation (72 patterns)
  python scripts/e2_inchusm_stream.py --bound eu --no-compare # ablation bound, drop static
Results: results/e2_stream_{measure}_{bound}.csv + prints a summary table.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from inchusm.miner_bridge import (  # noqa: E402
    export_snapshot_stream, export_utilities, run_inchusm_stream,
)

ROOT = Path(__file__).resolve().parents[2]
RESULTS = Path(__file__).resolve().parents[1] / "results"
ELLIPTIC = ROOT / "datasets" / "elliptic1"
TRAIN_MAX_STEP = 34


def build_snapshots(k: int, max_steps: int):
    """Return (snapshots, n_labels): each snapshot = (labels Series indexed by txId, edges df).

    Label = quantile bucket of f0, bins fit on step<=34 then frozen.
    """
    df = pd.read_csv(
        ELLIPTIC / "elliptic_txs_features.csv", header=None, usecols=[0, 1, 2],
        names=["txId", "step", "f0"],
        dtype={0: np.int64, 1: np.int16, 2: np.float32},
    )
    # bin edges fit on TRAIN, frozen, applied to every step (out-of-range values -> clip)
    train_f0 = df.loc[df.step <= TRAIN_MAX_STEP, "f0"]
    _, bins = pd.qcut(train_f0, k, retbins=True, duplicates="drop")
    bins = bins.copy()
    bins[0], bins[-1] = -np.inf, np.inf   # clip tails for test steps
    df["label"] = pd.cut(df["f0"], bins=bins, labels=False, include_lowest=True).astype(np.int32)
    n_labels = int(df["label"].nunique())

    edges = pd.read_csv(ELLIPTIC / "elliptic_txs_edgelist.csv")
    edges.columns = ["src", "dst"]
    step_of = df.set_index("txId")["step"]
    edges["step"] = step_of.reindex(edges["src"]).to_numpy(dtype=np.int16)

    steps = sorted(df["step"].unique())[:max_steps]
    snapshots = []
    for s in steps:
        nd = df[df.step == s]
        labels = nd.set_index("txId")["label"]
        ed = edges.loc[edges.step == s, ["src", "dst"]]
        snapshots.append((labels, ed))
    return snapshots, n_labels


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--k", type=int, default=4, help="number of label buckets (f0 quantile)")
    ap.add_argument("--min-value", type=float, default=500.0)
    ap.add_argument("--measure", choices=["utility", "support"], default="utility")
    ap.add_argument("--bound", choices=["ivu", "eu", "ceu"], default="ceu")
    ap.add_argument("--max-edges", type=int, default=5)
    ap.add_argument("--max-steps", type=int, default=49)
    ap.add_argument("--no-compare", action="store_true", help="skip static re-run (measure inc only)")
    ap.add_argument("--out", default=None, help="output CSV (default results/e2_stream_{measure}_{bound}.csv); use for repeated std runs to AVOID overwriting the official file")
    args = ap.parse_args()

    work = RESULTS / "e2_snapshots"
    print(f"[E2] Building snapshots (k={args.k}, max_steps={args.max_steps}) ...", flush=True)
    t0 = time.time()
    snapshots, n_labels = build_snapshots(args.k, args.max_steps)
    export_snapshot_stream(snapshots, work)
    # external utility: label i -> i+1 (positive, locked, documented)
    util_tbl = {i: float(i + 1) for i in range(n_labels)}
    util_path = work / "utilities.tsv"
    export_utilities(util_tbl, util_path)
    print(f"[E2] {len(snapshots)} snapshots, {n_labels} labels, util={util_tbl} "
          f"({time.time()-t0:.1f}s)", flush=True)

    out_csv = Path(args.out) if args.out else RESULTS / f"e2_stream_{args.measure}_{args.bound}.csv"
    print(f"[E2] Running IncHUSM stream (measure={args.measure} bound={args.bound} "
          f"minValue={args.min_value} compare={not args.no_compare}) ...", flush=True)
    df, stdout = run_inchusm_stream(
        work, util_path, args.min_value, measure=args.measure, bound=args.bound,
        max_edges=args.max_edges, compare_static=not args.no_compare, out_csv=out_csv,
    )
    print(stdout)

    # ---- summary ----
    n = len(df)
    print(f"\n{'='*64}\nE2 SUMMARY ({args.measure}/{args.bound})\n{'='*64}")
    print(f"snapshots          : {n}")
    print(f"final high patterns: {int(df['high_total'].iloc[-1])}")
    print(f"inc total          : {int(df['inc_ms'].sum())} ms")
    if not args.no_compare:
        n_ok = int((df["match"] == "OK").sum())
        print(f"correctness (INV2) : {n_ok}/{n} snapshot match=OK"
              + ("" if n_ok == n else "  MISMATCH FOUND"))
        static_tot = int(df["static_ms"].sum())
        inc_tot = max(1, int(df["inc_ms"].sum()))
        print(f"static re-run total: {static_tot} ms")
        print(f"SPEEDUP (static/inc): {static_tot/inc_tot:.2f}×")
    print(f"\nCSV: {out_csv}")


if __name__ == "__main__":
    main()
