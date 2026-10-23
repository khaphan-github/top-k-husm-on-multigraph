"""E2d — A second REAL transaction stream for Paper 2: AMLworld HI-Small BY DAY.

Answering the "single dataset" criticism: E1/E2 also run on a real AML transaction
stream (IBM's synthetic-realistic data), alongside Elliptic-49.

Modelling (declared in the paper):
- Each snapshot = the transaction graph of ONE DAY; a vertex is an "account-day" (the
  same account on two days is two different vertices) ⇒ the snapshots are pairwise
  disjoint, matching the add-only stream setup; a pattern = an INTRA-DAY structure,
  matching AML velocity motifs.
- Per-day pre-filtering (like a real AML pipeline): drop transactions with
  amount < min_amount; drop vertices with in-day degree > deg_cap.
- Vertex label = quantile bucket (k levels) of the TOTAL transacted amount of the
  account that day; bucket edges are estimated on the FIRST DAY then fixed (no leakage).
  Utility u(i)=i+1.
- Use days 0..days-1 (default 10 — containing >99.9% of HI-Small's transactions).

Run:
  python scripts/e2d_amlworld_stream.py --days 3 --min-value 2000   # probe
  python scripts/e2d_amlworld_stream.py                             # full
Results: results/e2d_amlworld_stream.csv + E1/E2 summary.
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

from inchusm.data.amlworld import load_transactions  # noqa: E402
from inchusm.miner_bridge import (  # noqa: E402
    export_snapshot_stream, export_utilities, run_inchusm_stream,
)

ROOT = Path(__file__).resolve().parents[2]
RESULTS = Path(__file__).resolve().parents[1] / "results"


def build_daily_snapshots(days: int, min_amount: float, deg_cap: int, k: int):
    edges, _ = load_transactions(ROOT / "datasets" / "amlworld" / "HI-Small_Trans.csv")
    edges["day"] = ((edges["time"] - edges["time"].min()) // 1440).astype(np.int32)
    edges = edges[(edges.day < days) & (edges.amount >= min_amount)]

    bins = None
    snapshots = []
    for d in range(days):
        g = edges[edges.day == d]
        # in-day degree cap
        deg = pd.concat([g.src, g.dst]).value_counts()
        keep = deg[deg <= deg_cap].index
        g = g[g.src.isin(keep) & g.dst.isin(keep)]
        # total amount per account that day (in + out)
        amt = pd.concat([
            g.groupby("src")["amount"].sum(),
            g.groupby("dst")["amount"].sum(),
        ]).groupby(level=0).sum()
        logv = np.log10(amt + 1.0)
        if bins is None:                       # fit on the FIRST DAY, frozen
            _, bins = pd.qcut(logv, k, retbins=True, duplicates="drop")
            bins = bins.copy()
            bins[0], bins[-1] = -np.inf, np.inf
        labels = pd.cut(logv, bins=bins, labels=False, include_lowest=True).astype(np.int64)
        # drop self-loops + dedup edges (undirected .lg graph)
        g = g[g.src != g.dst]
        und = pd.DataFrame({
            "src": np.minimum(g.src, g.dst), "dst": np.maximum(g.src, g.dst),
        }).drop_duplicates()
        snapshots.append((labels, und))
        print(f"  day {d}: {len(labels):7d} vertices, {len(und):7d} edges", flush=True)
    n_labels = int(bins.size - 1)
    return snapshots, n_labels


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=10)
    ap.add_argument("--min-amount", type=float, default=5000.0)
    ap.add_argument("--deg-cap", type=int, default=30)
    ap.add_argument("--k", type=int, default=4)
    ap.add_argument("--min-value", type=float, default=2000.0)
    ap.add_argument("--no-compare", action="store_true")
    ap.add_argument("--out", default=None,
                    help="output CSV (default results/e2d_amlworld_stream.csv); "
                         "use for repeated std runs to AVOID overwriting the official file")
    args = ap.parse_args()

    print(f"[E2d] AMLworld HI-Small daily stream: days={args.days} "
          f"min_amount={args.min_amount} deg_cap={args.deg_cap} k={args.k}", flush=True)
    t0 = time.time()
    snaps, n_labels = build_daily_snapshots(args.days, args.min_amount, args.deg_cap, args.k)
    work = RESULTS / "e2d_snapshots"
    export_snapshot_stream(snaps, work)
    util = {i: float(i + 1) for i in range(n_labels)}
    util_path = work / "utilities.tsv"
    export_utilities(util, util_path)
    print(f"[E2d] {len(snaps)} snapshots, {n_labels} labels, util={util} "
          f"({time.time()-t0:.0f}s)", flush=True)

    out_csv = Path(args.out) if args.out else RESULTS / "e2d_amlworld_stream.csv"
    df, stdout = run_inchusm_stream(
        work, util_path, args.min_value, measure="utility", bound="ceu",
        max_edges=5, compare_static=not args.no_compare, out_csv=out_csv,
        timeout_s=14400,
    )
    print(stdout)

    n = len(df)
    print(f"\n{'='*64}\nE2d SUMMARY (AMLworld HI-Small, θ={args.min_value})\n{'='*64}")
    print(f"final high patterns: {int(df['high_total'].iloc[-1])}")
    print(f"inc total          : {df['inc_ms'].sum()/1000:.1f} s")
    if not args.no_compare:
        ok = int((df["match"] == "OK").sum())
        print(f"correctness (E1)   : {ok}/{n} match=OK" + ("" if ok == n else "  MISMATCH"))
        st = df["static_ms"].sum() / 1000
        print(f"static re-run total: {st:.1f} s")
        print(f"SPEEDUP            : {st*1000/max(1, df['inc_ms'].sum()):.1f}×")
    print(f"CSV: {out_csv}")


if __name__ == "__main__":
    main()
