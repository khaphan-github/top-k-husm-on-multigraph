"""E2b — Citeseer trend-check: show that the STATIC HUSM miner (the Paper 2 baseline)
is a FAITHFUL re-implementation, not a strawman.

Why it is needed: E2 reports IncHUSM speedup vs static re-run. A reviewer will ask "was
the static made deliberately slow?". This shows the static miner reproduces the CORRECT
TRENDS of the original HUSM paper (InfSci 2024) on their own dataset (Citeseer), so the
baseline is competitive and the speedup is a genuine algorithmic benefit.

Two core trends (algorithmic invariants, holding on any graph but measured on Citeseer):
  (T-a) #HUS DECREASES MONOTONICALLY as minUtil rises (and runtime drops).
  (T-b) Bound tightness: #candidates(CEU) <= #candidates(EU) <= #candidates(IVU) at each
        minUtil — reproducing HUSM's central contribution (the CEU upper bound prunes most).

NOTE: the original paper's utility seed is NOT published ⇒ compare TRENDS only, not an
exact reproduction of Table 4. Utility = Gaussian(mu,sigma,seed) fixed over the 6 class
labels (explicitly declared).

Run:  python scripts/e2b_citeseer_trend.py
Results: results/e2b_citeseer_trend.csv + prints a table + PASS/FAIL per trend.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")

import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
RESULTS = Path(__file__).resolve().parents[1] / "results"
CITE = ROOT / "datasets" / "citeseer"
JAR = ROOT / "inchusm-miner" / "target" / "inchusm-miner-0.1.0-SNAPSHOT.jar"

MIN_UTILS = [50, 100, 200, 400, 800, 1600]
BOUNDS = ["ivu", "eu", "ceu"]
UTIL_GAUSS = "3.0,1.0,42"   # fixed, reproducible (original paper does not publish the seed)
MAX_EDGES = 4
SUMMARY = re.compile(
    r"evaluated=(\d+) candidates=(\d+) HUS=(\d+) BHUS=(\d+) runtime=(\d+)ms")


def build_lg(out: Path) -> tuple[int, int, int]:
    """Convert citeseer.content/.cites -> .lg (label = 6 classes; simple edges, drop
    self-loops and edges whose endpoint is not in content). Return (n_nodes, n_edges, n_labels)."""
    ids: dict[str, int] = {}
    labs: dict[str, int] = {}
    vlines = []
    for ln in (CITE / "citeseer.content").read_text(encoding="utf-8").splitlines():
        parts = ln.rstrip("\n").split("\t")
        pid, lab = parts[0], parts[-1]
        vid = ids.setdefault(pid, len(ids))
        lint = labs.setdefault(lab, len(labs))
        vlines.append((vid, lint))
    seen = set()
    elines = []
    for ln in (CITE / "citeseer.cites").read_text(encoding="utf-8").splitlines():
        a, b = ln.rstrip("\n").split("\t")[:2]
        if a in ids and b in ids and a != b:
            u, v = ids[a], ids[b]
            key = (u, v) if u < v else (v, u)
            if key not in seen:
                seen.add(key)
                elines.append(f"e {key[0]} {key[1]} 0")
    body = [f"v {vid} {lab}" for vid, lab in vlines] + elines
    out.write_text("\n".join(body) + "\n", encoding="ascii")
    return len(ids), len(elines), len(labs)


def run_mine(lg: Path, min_util: int, bound: str) -> dict:
    cmd = ["java", "-jar", str(JAR), "mine", "--graph", str(lg),
           "--util-gauss", UTIL_GAUSS, "--minutil", str(min_util),
           "--bound", bound, "--max-edges", str(MAX_EDGES), "--mer", "1"]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=1200)
    if proc.returncode != 0:
        raise RuntimeError(f"mine failed (minUtil={min_util} bound={bound}):\n{proc.stderr}")
    m = SUMMARY.search(proc.stdout)
    if not m:
        raise RuntimeError(f"could not parse summary:\n{proc.stdout}")
    ev, cand, hus, bhus, rt = map(int, m.groups())
    return {"min_util": min_util, "bound": bound, "evaluated": ev,
            "candidates": cand, "HUS": hus, "BHUS": bhus, "runtime_ms": rt}


def main() -> None:
    if not JAR.exists():
        raise FileNotFoundError(f"JAR not built: mvn -f {JAR.parents[1]/'pom.xml'} package")
    lg = CITE / "citeseer.lg"
    n, e, nl = build_lg(lg)
    print(f"[E2b] citeseer.lg: {n} vertices, {e} edges, {nl} class labels -> {lg}", flush=True)

    rows = []
    for mu in MIN_UTILS:
        for b in BOUNDS:
            r = run_mine(lg, mu, b)
            rows.append(r)
            print(f"  minUtil={mu:5d} bound={b:3s}: candidates={r['candidates']:6d} "
                  f"HUS={r['HUS']:4d} BHUS={r['BHUS']:3d} runtime={r['runtime_ms']:5d}ms",
                  flush=True)
    df = pd.DataFrame(rows)
    RESULTS.mkdir(exist_ok=True)
    out = RESULTS / "e2b_citeseer_trend.csv"
    df.to_csv(out, index=False)

    # ---- check the two trends ----
    print(f"\n{'='*64}\nTREND CHECK (Citeseer, util-gauss {UTIL_GAUSS})\n{'='*64}")
    ceu = df[df.bound == "ceu"].sort_values("min_util")
    hus_seq = ceu["HUS"].tolist()
    mono_hus = all(a >= b for a, b in zip(hus_seq, hus_seq[1:]))
    rt_seq = ceu["runtime_ms"].tolist()
    mono_rt = all(a >= b for a, b in zip(rt_seq, rt_seq[1:]))
    print(f"(T-a) #HUS by minUtil (ceu): {hus_seq}  -> "
          f"{'MONOTONE DECREASING' if mono_hus else 'NOT MONOTONE'}")
    print(f"      runtime by minUtil: {rt_seq}  -> "
          f"{'decreasing' if mono_rt else 'not decreasing (review)'}")

    tight_ok = True
    print("(T-b) bound tightness candidates(CEU)<=EU<=IVU at each minUtil:")
    for mu in MIN_UTILS:
        s = df[df.min_util == mu].set_index("bound")["candidates"]
        ok = s["ceu"] <= s["eu"] <= s["ivu"]
        tight_ok &= ok
        print(f"      minUtil={mu:5d}: IVU={s['ivu']:6d} >= EU={s['eu']:6d} "
              f">= CEU={s['ceu']:6d}  {'ok' if ok else 'FAIL'}")

    ok_all = mono_hus and tight_ok
    print(f"\nCONCLUSION: {'The STATIC baseline reproduces the original HUSM trends — NOT a strawman.' if ok_all else 'Some trend does not match — review.'}")
    print(f"CSV: {out}")
    sys.exit(0 if ok_all else 1)


if __name__ == "__main__":
    main()
