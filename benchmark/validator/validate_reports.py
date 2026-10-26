#!/usr/bin/env python3
"""Validate MgTopKMain text reports (`--out FILE`).

Only Top-K reports are checked: a report must contain a `# top-<K> patterns` section.
Reports written by `MgMinUtilMain` (no `K`, `enable_top_k=false`) carry no such section
and are listed as skipped instead of being silently dropped.

Two consistency checks over the same dataset/config:

1. Top-K monotonicity: the top-10 of a K=10 run must also be the top-10 of a
   K=50 run (same DFS codes, same utility, allowing ties at the cutoff).
2. Bound independence: changing `--ieu-bound` (ivu/eu/ceu) only changes pruning,
   so the top-K patterns must be identical for the same K.

Usage (from the repository root):

    python3 benchmark/validator/validate_reports.py benchmark/results \
        --out benchmark/results/validation.txt

The script reads every `*.txt` report in the given files/directories, groups
them by dataset + mining config, prints a PASS/FAIL summary and writes the full
report to `--out`. Exit code is 1 when any check fails.

Only the Python standard library is used.
"""

from __future__ import annotations

import argparse
import glob
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

REPORT_HEADER = "# Top-K HUSM multigraph run report"
TOP_HEADER_RE = re.compile(r"^#\s+top-(\d+)\s+patterns")


@dataclass
class Report:
    path: Path
    dataset: str
    edges_file: str
    params: Dict[str, str]
    k: int
    bound: str
    top: Dict[str, float] = field(default_factory=dict)

    @property
    def group_key(self) -> Tuple[str, ...]:
        p = self.params
        return (
            self.dataset,
            p.get("mer", "?"),
            p.get("nlabels", "?"),
            p.get("label_mode", "?"),
            p.get("seed", "?"),
            p.get("weight", "?"),
            p.get("incremental_domains", "?"),
            p.get("prune", "?"),
            p.get("max_m", "?"),
            p.get("max_v", "?"),
        )

    def group_label(self) -> str:
        return " ".join(f"{k}={v}" for k, v in zip(
            ("dataset", "mer", "nlabels", "label_mode", "seed", "weight",
             "incremental_domains", "prune", "max_m", "max_v"),
            self.group_key))


@dataclass
class Check:
    kind: str          # "K" or "bound"
    group: str
    detail: str
    status: str        # PASS / WARN / FAIL
    notes: List[str] = field(default_factory=list)


def parse_config(line: str) -> Dict[str, str]:
    """`config: K=10 mer=1 ... ieu_bound=ivu` -> dict."""
    out: Dict[str, str] = {}
    for token in line.split():
        if "=" in token:
            key, value = token.split("=", 1)
            out[key] = value
    return out


def parse_report(path: Path) -> Optional[Report]:
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None
    if not lines or not lines[0].startswith(REPORT_HEADER):
        return None

    dataset = ""
    edges_file = ""
    params: Dict[str, str] = {}
    k = -1
    top: Dict[str, float] = {}
    in_top = False

    for line in lines:
        if line.startswith("dataset: "):
            dataset = line[len("dataset: "):].strip()
        elif line.startswith("edges_file: "):
            edges_file = line[len("edges_file: "):].strip()
        elif line.startswith("config: "):
            params = parse_config(line[len("config: "):])
        elif in_top:
            if not line.strip():
                break  # top section is followed by a blank line
            if line.startswith("#"):
                continue
            cols = line.split("\t")
            if len(cols) < 5:
                continue
            try:
                u = float(cols[0])
            except ValueError:
                continue
            top[cols[4]] = u
        else:
            m = TOP_HEADER_RE.match(line)
            if m:
                in_top = True
                k = int(m.group(1))

    if not dataset or k < 0:
        return None
    bound = params.get("ieu_bound", "?").upper()
    return Report(path, dataset, edges_file, params, k, bound, top)


def discover(paths: Iterable[str]) -> List[Path]:
    """Expand files, directories (recursive) and glob patterns into *.txt files."""
    found: List[Path] = []
    for raw in paths:
        p = Path(raw)
        if any(ch in raw for ch in "*?["):
            found.extend(Path(x) for x in glob.glob(raw, recursive=True))
        elif p.is_dir():
            found.extend(sorted(p.rglob("*.txt")))
        elif p.is_file():
            found.append(p)
        else:
            print(f"warning: '{raw}' not found, skipping", file=sys.stderr)
    seen = set()
    unique = []
    for p in found:
        key = p.resolve()
        if key not in seen:
            seen.add(key)
            unique.append(p)
    return unique


def compare_maps(left: Dict[str, float], right: Dict[str, float],
                 tol: float) -> Tuple[List[str], List[str], List[str]]:
    """Return (missing_in_right, extra_in_right, value_mismatch)."""
    missing = [c for c in left if c not in right]
    extra = [c for c in right if c not in left]
    mismatch = [c for c in left
                if c in right and abs(left[c] - right[c]) > tol]
    return missing, extra, mismatch


def short(codes: List[str], limit: int = 3) -> str:
    shown = ", ".join(codes[:limit])
    return shown + (f" (+{len(codes) - limit} more)" if len(codes) > limit else "")


def check_k_pair(small: Report, large: Report, tol: float) -> Check:
    """Top-`small.k` of the small run must equal the top-`small.k` of the large run."""
    detail = (f"bound={small.bound} | K={small.k} (n={len(small.top)}) vs "
              f"K={large.k} (n={len(large.top)}) | "
              f"{small.path.name} vs {large.path.name}")
    if not small.top:
        return Check("K", small.group_label(), detail, "WARN",
                     ["K=10 report has no pattern with support"])

    boundary = min(small.top.values())
    # Patterns the large run claims are at least as good as the small cutoff.
    relevant = {c: u for c, u in large.top.items() if u >= boundary - tol}
    missing, _, mismatch = compare_maps(small.top, relevant, tol)
    higher = [c for c, u in relevant.items()
              if c not in small.top and u > boundary + tol]
    ties = [c for c, u in relevant.items()
            if c not in small.top and abs(u - boundary) <= tol]

    notes = []
    if missing:
        notes.append("missing from large run: " + short(sorted(missing)))
    if higher:
        notes.append("higher-u patterns missing from small run: " + short(sorted(higher)))
    if mismatch:
        notes.append("utility mismatch: " + short(sorted(mismatch)))
    if ties:
        notes.append("tie at cutoff (accepted): " + short(sorted(ties)))

    if missing or higher or mismatch:
        return Check("K", small.group_label(), detail, "FAIL", notes)
    if ties:
        return Check("K", small.group_label(), detail, "WARN", notes)
    return Check("K", small.group_label(), detail, "PASS", notes)


def check_bounds(base: Report, other: Report, tol: float) -> Check:
    detail = f"K={base.k} | baseline {base.bound} (n={len(base.top)}) vs " \
             f"{other.bound} (n={len(other.top)})"
    missing, extra, mismatch = compare_maps(base.top, other.top, tol)
    notes = []
    if missing:
        notes.append(f"only in {base.bound}: " + short(sorted(missing)))
    if extra:
        notes.append(f"only in {other.bound}: " + short(sorted(extra)))
    if mismatch:
        notes.append("utility mismatch: " + short(sorted(mismatch)))
    status = "PASS" if not (missing or extra or mismatch) else "FAIL"
    return Check("bound", base.group_label(), detail, status, notes)


def append_check(check: Check, lines: List[str]) -> Check:
    lines.append(f"[{check.status}] {check.detail}")
    for note in check.notes:
        lines.append(f"        {note}")
    return check


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Validate MgTopKMain text reports (top-K monotonicity and bound "
                    "independence).")
    ap.add_argument("paths", nargs="*", default=["benchmark/results"],
                    help="report files, directories or globs (default: benchmark/results)")
    ap.add_argument("--out", default="benchmark/results/validation.txt",
                    help="write the validation report here (default: benchmark/results/validation.txt)")
    ap.add_argument("--k-small", type=int, default=10, help="smaller K (default: 10)")
    ap.add_argument("--k-large", type=int, default=50, help="larger K (default: 50)")
    ap.add_argument("--bounds", default="ivu,eu,ceu",
                    help="bounds to compare, comma separated (default: ivu,eu,ceu)")
    ap.add_argument("--tolerance", type=float, default=1e-6,
                    help="absolute utility tolerance (default: 1e-6)")
    args = ap.parse_args()

    out_path = Path(args.out).resolve()
    files = [p for p in discover(args.paths) if p.resolve() != out_path]
    parsed = [parse_report(p) for p in files]
    reports = [r for r in parsed if r is not None]
    skipped = [p for p, r in zip(files, parsed) if r is None]

    if not reports:
        print("error: no MgTopKMain report found (a report needs a '# top-<K> patterns' "
              "section). Generate reports with `MgTopKMain ... --out FILE` "
              "(or `--out DIR/`).", file=sys.stderr)
        return 2

    wanted_bounds = [b.strip().upper() for b in args.bounds.split(",") if b.strip()]
    groups: Dict[Tuple[str, ...], Dict[str, Dict[int, Report]]] = {}
    duplicate_warnings: List[str] = []
    for r in reports:
        by_bound = groups.setdefault(r.group_key, {})
        by_k = by_bound.setdefault(r.bound, {})
        if r.k in by_k:
            duplicate_warnings.append(
                f"duplicate {r.dataset} K={r.k} bound={r.bound}: "
                f"keeping {r.path} over {by_k[r.k].path}")
        by_k[r.k] = r

    lines: List[str] = [
        "# Top-K report validation",
        f"generated: {datetime.now().isoformat(timespec='seconds')}",
        f"tolerance: {args.tolerance:g}",
        f"reports: {len(reports)}",
        f"groups: {len(groups)}",
        "",
    ]
    for warning in duplicate_warnings:
        lines.append(f"[WARN] {warning}")
    if duplicate_warnings:
        lines.append("")
    for path in skipped:
        lines.append(f"[SKIP] {path}: no '# top-<K> patterns' section "
                     "(min-util report? use MgTopKMain for K checks)")
    if skipped:
        lines.append("")

    checks: List[Check] = []
    for group_key in sorted(groups):
        by_bound = groups[group_key]
        sample = next(iter(next(iter(by_bound.values())).values()))
        lines.append(f"## {sample.group_label()}")
        lines.append(f"files: {len([r for d in by_bound.values() for r in d.values()])}")

        # --- 1) top-K monotonicity -------------------------------------
        lines.append(f"### K check: K={args.k_small} vs K={args.k_large}")
        k_found = 0
        for bound in sorted(by_bound):
            by_k = by_bound[bound]
            small, large = by_k.get(args.k_small), by_k.get(args.k_large)
            if small is None or large is None:
                continue
            k_found += 1
            checks.append(append_check(
                check_k_pair(small, large, args.tolerance), lines))
        if k_found == 0:
            lines.append(f"[SKIP] no bound has both K={args.k_small} and K={args.k_large}")
            lines.append("")

        # --- 2) bound independence -------------------------------------
        lines.append("### bound check")
        for k in sorted({r.k for d in by_bound.values() for r in d.values()}):
            if k not in (args.k_small, args.k_large) and args.k_small != args.k_large:
                continue
            available = [b for b in wanted_bounds if k in by_bound.get(b, {})]
            if len(available) < 2:
                continue
            baseline = by_bound[available[0]][k]
            for other in available[1:]:
                checks.append(append_check(
                    check_bounds(baseline, by_bound[other][k], args.tolerance), lines))
        lines.append("")

    passed = sum(1 for c in checks if c.status == "PASS")
    warned = sum(1 for c in checks if c.status == "WARN")
    failed = sum(1 for c in checks if c.status == "FAIL")

    lines.append("# summary")
    lines.append(f"checks={len(checks)} pass={passed} warn={warned} fail={failed}")
    lines.append("")
    lines.append("RESULT: " + ("PASS" if failed == 0 else f"FAIL ({failed} check(s))"))

    text = "\n".join(lines) + "\n"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(text, encoding="utf-8")

    print(text)
    print(f"validation report written: {out_path}", file=sys.stderr)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
