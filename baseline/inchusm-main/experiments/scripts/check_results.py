"""Self-grade PASS/FAIL of the DETERMINISTIC values of Paper 2 after running on a new machine.

Only checks COUNT values (match, #HUS, Citeseer ordering) — does NOT check timing (timing
varying by machine is normal). Reference values match docs/reproduce/paper2.md.

Run after run_paper2.bat:
    "%USERPROFILE%\\.venvs\\inchusm\\Scripts\\python.exe" experiments\\scripts\\check_results.py
No external libraries needed (reads CSV with the standard library only).
"""

from __future__ import annotations

import csv
import sys
from pathlib import Path

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")

RESULTS = Path(__file__).resolve().parents[1] / "results"


def rows(name: str):
    p = RESULTS / name
    if not p.exists():
        return None
    with p.open(encoding="utf-8") as f:
        return list(csv.DictReader(f))


def check_stream(name: str, exp_rows: int | None, exp_high: int | None):
    r = rows(name)
    if r is None:
        return name, False, "MISSING FILE (step not run yet?)"
    if not r:
        return name, False, "EMPTY FILE (0 rows — step interrupted/killed)"
    n = len(r)
    n_ok = sum(1 for x in r if x.get("match") == "OK")
    last_high = int(r[-1]["high_total"]) if r else -1
    msgs, ok = [], True
    if exp_rows is not None:
        good = n == exp_rows
        ok &= good
        msgs.append(f"{n}/{exp_rows} snapshot" + ("" if good else " FAIL"))
    # match is only meaningful under compare-static (column has value OK/…)
    has_match = any(x.get("match") for x in r)
    if has_match:
        good = n_ok == n
        ok &= good
        msgs.append(f"match={n_ok}/{n} OK" + ("" if good else " FAIL"))
    if exp_high is not None:
        good = last_high == exp_high
        ok &= good
        msgs.append(f"high last={last_high} (need {exp_high})" + ("" if good else " FAIL"))
    return name, ok, ", ".join(msgs)


def check_citeseer():
    name = "e2b_citeseer_trend.csv"
    r = rows(name)
    if r is None:
        return name, False, "MISSING FILE"
    # #HUS by min_util must decrease monotonically 77→51→32→15→7→3
    expected = {50: 77, 100: 51, 200: 32, 400: 15, 800: 7, 1600: 3}
    by_mu = {}
    for x in r:
        by_mu.setdefault(int(x["min_util"]), set()).add(int(x["HUS"]))
    ok, msgs = True, []
    for mu, exp in expected.items():
        got = by_mu.get(mu)
        good = got == {exp}           # identical across the 3 bounds AND correct value
        ok &= good
        msgs.append(f"θ{mu}:{sorted(got) if got else '—'}→{exp}" + ("" if good else " FAIL"))
    # candidates: CEU ≤ EU ≤ IVU at every min_util
    cand = {}
    for x in r:
        cand[(int(x["min_util"]), x["bound"])] = int(x["candidates"])
    mono = all(cand.get((mu, "ceu"), 0) <= cand.get((mu, "eu"), 0) <= cand.get((mu, "ivu"), 0)
               for mu in expected)
    ok &= mono
    msgs.append("CEU≤EU≤IVU" + ("" if mono else " FAIL"))
    return name, ok, " ".join(msgs)


def main() -> None:
    print(f"PAPER 2 DETERMINISTIC-VALUE CHECK — {RESULTS}\n" + "=" * 60)
    checks = [
        check_stream("e2_stream_utility_ceu.csv", 49, 83),   # main E2
        check_stream("e2_stream_utility_eu.csv", 49, 83),
        check_stream("e2_stream_utility_ivu.csv", 49, 83),
        check_stream("e2_stream_support_ceu.csv", 49, 72),
        check_stream("e2c_synth_S.csv", 30, 73),
        check_stream("e2c_synth_L.csv", 30, 70),
        check_stream("e2d_amlworld_stream.csv", 10, None),   # 10 days, match=OK; #HUS not yet fixed
        check_citeseer(),
    ]
    n_pass = 0
    for name, ok, msg in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name:<28} {msg}")
        n_pass += ok
    print("=" * 60)
    print(f"{n_pass}/{len(checks)} checks PASS"
          + (" - ALL MATCH" if n_pass == len(checks) else " — MISMATCH, see FAIL above"))
    sys.exit(0 if n_pass == len(checks) else 1)


if __name__ == "__main__":
    main()
