"""Generate the Paper 2 (RIVF — IncHUSM streaming) figures from the E2 CSV.

Two panels (compact for a 6-page IEEE conference):
  (a) Cumulative runtime: incremental (new segments only) vs static re-run (re-mine the whole
      union) by snapshot count ⇒ the gap widens, speedup grows with t.
  (b) Memory/fringe growth: number of pattern-states held (tracked) + incremental work
      per snapshot (seg_last) by t.

English labels (so translating the paper needs no figure edits). Export vector PDF into results/.
Run:  python scripts/make_figures_paper2.py [--csv results/e2_stream_utility_ceu.csv]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")

import matplotlib
import pandas as pd

matplotlib.use("Agg")
matplotlib.rcParams.update({
    "pdf.fonttype": 42, "ps.fonttype": 42, "font.size": 10,
    "font.family": "sans-serif", "axes.grid": True, "grid.alpha": 0.3,
    "figure.autolayout": False,
})
import matplotlib.pyplot as plt  # noqa: E402

RESULTS = Path(__file__).resolve().parents[1] / "results"
# figures are written next to the result CSVs
FIGDIR = RESULTS

C_INC = "#56B4E9"    # sky blue — incremental (ours)
C_STAT = "#D55E00"   # orange — static re-run (baseline)
C_MEM = "#009E73"    # green — tracked states
C_SEG = "#CC79A7"    # pink-purple — segments/snapshot


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default=str(RESULTS / "e2_stream_utility_ceu.csv"))
    args = ap.parse_args()
    df = pd.read_csv(args.csv)
    df = df.sort_values("snapshot")
    t = df["snapshot"].to_numpy()

    fig, axes = plt.subplots(1, 2, figsize=(10.6, 3.9), constrained_layout=True)

    # (a) cumulative runtime
    ax = axes[0]
    inc_cum = df["inc_ms"].cumsum() / 1000.0
    ax.plot(t, inc_cum, "-s", color=C_INC, ms=3, label="IncHUSM (ours)")
    if (df["static_ms"] >= 0).all():
        stat_cum = df["static_ms"].cumsum() / 1000.0
        ax.plot(t, stat_cum, "-o", color=C_STAT, ms=3, label="Static re-run")
        sp = stat_cum.iloc[-1] / max(1e-9, inc_cum.iloc[-1])
        ax.text(0.32, 0.55, f"{sp:.0f}× faster\nat t={int(t[-1])}",
                transform=ax.transAxes, fontsize=11, color=C_STAT, fontweight="bold",
                ha="center")
    ax.set_xlabel("Snapshot t")
    ax.set_ylabel("Cumulative runtime (s)")
    ax.set_title("(a) Incremental vs. static re-run")
    ax.legend(fontsize=8, loc="upper left")

    # (b) memory / fringe growth
    ax = axes[1]
    ax.plot(t, df["tracked"], "-", color=C_MEM, label="Tracked states")
    ax.set_xlabel("Snapshot t")
    ax.set_ylabel("Tracked pattern-states", color=C_MEM)
    ax.tick_params(axis="y", labelcolor=C_MEM)
    ax.set_title("(b) State & per-snapshot work")
    ax2 = ax.twinx()
    ax2.plot(t, df["seg_last"], "--", color=C_SEG, label="Segments / snapshot")
    ax2.set_ylabel("Segments computed / snapshot", color=C_SEG)
    ax2.tick_params(axis="y", labelcolor=C_SEG)
    ax2.grid(False)
    lines = ax.get_lines() + ax2.get_lines()
    ax.legend(lines, [ln.get_label() for ln in lines], fontsize=8, loc="upper left")

    FIGDIR.mkdir(parents=True, exist_ok=True)
    out = FIGDIR / "Fig3_inchusm_speedup.pdf"
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)
    print("wrote", out)


if __name__ == "__main__":
    main()
