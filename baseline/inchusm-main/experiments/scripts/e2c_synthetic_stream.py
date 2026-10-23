"""E2c — CONTROLLED synthetic snapshot stream for Paper 2 (RIVF).

Purpose: show that the E1/E2 trends (100% correctness + speedup widening with t) are
NOT specific to Elliptic, on a stream where every parameter is controlled:
  - T snapshots, each snapshot n vertices / ~edge_factor*n random edges;
  - vertex label ~ a SKEWED Zipf-like distribution (p ∝ 1/(rank+1)): rare label = high
    utility (u(i)=i+1), modelling the "rare but high-value pattern" of AML;
  - plant in each snapshot a number of 3-vertex label chains (k-3 → k-2 → k-1) on
    dedicated vertices — a recurring high-value pattern accumulating CMNI/utility across snapshots.

Measured exactly like E2 Elliptic: run_inchusm_stream(compare_static=True) ⇒ per-step
match (E1) + inc_ms vs static_ms (E2).

Run:
  python scripts/e2c_synthetic_stream.py --tag S                 # small configuration
  python scripts/e2c_synthetic_stream.py --tag L --n 3000        # larger snapshots
Results: results/e2c_synth_{tag}.csv + prints a summary.
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

RESULTS = Path(__file__).resolve().parents[1] / "results"


def gen_stream(T: int, n: int, edge_factor: float, k: int, plant: int, seed: int):
    """Generate T disjoint snapshots. Return list[(labels Series, edges df)]."""
    rng = np.random.default_rng(seed)
    ranks = np.arange(1, k + 1, dtype=float)
    probs = (1.0 / ranks) / (1.0 / ranks).sum()      # Zipf-ish: label 0 most common
    snapshots = []
    for _ in range(T):
        labels = rng.choice(k, size=n, p=probs).astype(np.int64)
        # plant `plant` 3-vertex label chains (k-3, k-2, k-1) on the first vertices
        chain_edges = []
        for j in range(plant):
            base = 3 * j
            labels[base], labels[base + 1], labels[base + 2] = k - 3, k - 2, k - 1
            chain_edges += [(base, base + 1), (base + 1, base + 2)]
        # random background edges (dedup + self-loops removed)
        m = int(edge_factor * n)
        src = rng.integers(0, n, size=2 * m)
        dst = rng.integers(0, n, size=2 * m)
        seen, rand_edges = set(), []
        for u, v in zip(src, dst):
            if u == v:
                continue
            key = (u, v) if u < v else (v, u)
            if key in seen:
                continue
            seen.add(key)
            rand_edges.append(key)
            if len(rand_edges) >= m:
                break
        edges = pd.DataFrame(chain_edges + rand_edges, columns=["src", "dst"])
        snapshots.append((pd.Series(labels, index=np.arange(n)), edges))
    return snapshots


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="S", help="configuration name (S/L/...)")
    ap.add_argument("--T", type=int, default=30)
    ap.add_argument("--n", type=int, default=1500)
    ap.add_argument("--edge-factor", type=float, default=1.5)
    ap.add_argument("--k", type=int, default=6)
    ap.add_argument("--plant", type=int, default=30)
    ap.add_argument("--min-value", type=float, default=800.0)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--no-compare", action="store_true")
    args = ap.parse_args()

    work = RESULTS / f"e2c_snapshots_{args.tag}"
    print(f"[E2c-{args.tag}] Generating {args.T} snapshots (n={args.n}, k={args.k}, "
          f"plant={args.plant}, seed={args.seed}) ...", flush=True)
    t0 = time.time()
    snaps = gen_stream(args.T, args.n, args.edge_factor, args.k, args.plant, args.seed)
    export_snapshot_stream(snaps, work)
    util_tbl = {i: float(i + 1) for i in range(args.k)}
    util_path = work / "utilities.tsv"
    export_utilities(util_tbl, util_path)
    n_edges = sum(len(e) for _, e in snaps)
    print(f"[E2c-{args.tag}] generation done: {args.T} snap, {args.T*args.n} vertices total, "
          f"{n_edges} edges, util={util_tbl} ({time.time()-t0:.1f}s)", flush=True)

    out_csv = RESULTS / f"e2c_synth_{args.tag}.csv"
    df, stdout = run_inchusm_stream(
        work, util_path, args.min_value, measure="utility", bound="ceu",
        max_edges=5, compare_static=not args.no_compare, out_csv=out_csv,
    )
    print(stdout)

    n = len(df)
    print(f"\n{'='*64}\nE2c SUMMARY configuration {args.tag} "
          f"(T={args.T}, n={args.n}, θ={args.min_value})\n{'='*64}")
    print(f"final high patterns: {int(df['high_total'].iloc[-1])}")
    print(f"inc total          : {df['inc_ms'].sum()/1000:.1f} s")
    if not args.no_compare:
        ok = int((df["match"] == "OK").sum())
        print(f"correctness (E1)   : {ok}/{n} match=OK" + ("" if ok == n else "  MISMATCH"))
        st = df["static_ms"].sum() / 1000
        print(f"static re-run total: {st:.1f} s")
        print(f"SPEEDUP            : {st*1000/max(1, df['inc_ms'].sum()):.1f}×")
        for t in [10, 20, n]:
            r = df[df.snapshot <= t]
            print(f"  cumulative speedup at t={t:2d}: "
                  f"{r.static_ms.sum()/max(1, r.inc_ms.sum()):.1f}×")
    print(f"CSV: {out_csv}")


if __name__ == "__main__":
    main()
