#!/usr/bin/env python3
"""Read benchmark.yaml and print shell assignments for run_benchmark.sh.

    eval "$(python3 benchmark/runner/config.py benchmark/runner/benchmark.yaml)"

In `parameters`: a list is swept, a scalar is fixed.
Exits non-zero (with a message on stderr) when the config is unusable.
"""

import shlex
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("error: pyyaml is required (pip install pyyaml)")


def val(value) -> str:
    if value is True:
        return "true"
    if value is False:
        return "false"
    return "" if value is None else str(value)


def arr(values) -> str:
    return "(" + " ".join(shlex.quote(val(v)) for v in values) + ")"


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "benchmark/runner/benchmark.yaml")
    if not path.is_file():
        sys.exit(f"error: config not found: {path}")

    cfg = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    run = cfg.get("run") or {}
    params = cfg.get("parameters") or {}
    datasets = cfg.get("datasets") or []

    if not datasets:
        sys.exit(f"error: no dataset in {path}")
    for ds in datasets:
        if not ds.get("id") or not ds.get("path"):
            sys.exit(f"error: dataset needs both 'id' and 'path': {ds}")

    # `run_datasets` selects a subset of the catalog; empty/missing means all.
    wanted = run.get("run_datasets") or []
    if isinstance(wanted, str):
        wanted = [wanted]
    if wanted:
        catalog = {d["id"]: d for d in datasets}
        unknown = [w for w in wanted if w not in catalog]
        if unknown:
            sys.exit(
                f"error: run_datasets has unknown id(s): {', '.join(unknown)}\n"
                f"       available: {', '.join(catalog)}"
            )
        datasets = [catalog[w] for w in wanted]

    for ds in datasets:
        if not Path(ds["path"]).is_file():
            sys.exit(f"error: dataset not found: {ds['path']} (from {path})")

    def axis(name, default):
        value = params.get(name, default)
        return value if isinstance(value, list) else [value]

    # `core_ids` pins each worker to one CPU core; empty means "let the OS decide".
    core_ids = run.get("core_ids") or []
    if isinstance(core_ids, int):
        core_ids = [core_ids]
    try:
        core_ids = [int(c) for c in core_ids]
    except (TypeError, ValueError):
        sys.exit(f"error: core_ids must be a list of integers, got: {run.get('core_ids')}")
    if any(c < 0 for c in core_ids):
        sys.exit("error: core_ids must be >= 0")

    jobs = run.get("jobs")
    if jobs is None:
        jobs = len(core_ids) or 1
    if core_ids and jobs > len(core_ids):
        print(
            f"note: jobs={jobs} clamped to {len(core_ids)} (one per core_id)",
            file=sys.stderr,
        )
        jobs = len(core_ids)

    scalars = {
        "JAR": run.get("jar", ""),
        "MAIN": run.get("main", "topk.husm.mg.cli.MgBenchOne"),
        "WARMUP": run.get("warmup", 3),
        "REPEATS": run.get("repeats", 5),
        "TIMEOUT": run.get("timeout_sec", 1800),
        "JOBS": jobs,
        "HEAP": run.get("heap", ""),
        "OUT": run.get("output_dir", "benchmark/results"),
        "NLABELS": axis("nlabels", [20])[0],
        "LABEL_MODE": axis("label_mode", ["random"])[0],
        "WDIST": axis("wdist", ["gaussian"])[0],
        "MAX_M": axis("max_m", [None])[0],
        "MAX_V": axis("max_v", [None])[0],
        "MIN_UTIL": axis("min_util", [None])[0],
        "MEM_LOGGER_MS": axis("mem_logger_ms", [0])[0],
        "LOG_LEVEL": run.get("log_level", ""),
        "PROFILE": run.get("profile", ""),
    }
    for name, value in scalars.items():
        print(f"{name}={shlex.quote(val(value))}")

    print("K_LIST=" + arr(axis("K", [10])))
    print("MER_LIST=" + arr(axis("mer", [1])))
    print("BOUNDS=" + arr(axis("ieu_bound", ["ivu"])))
    print("INCREMENTAL=" + arr(axis("incremental_domains", [True])))
    print("TOPK=" + arr(axis("enable_top_k", [True])))
    print("DS_IDS=" + arr([d["id"] for d in datasets]))
    print("DS_PATHS=" + arr([d["path"] for d in datasets]))
    print("CORE_LIST=" + arr(core_ids))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
