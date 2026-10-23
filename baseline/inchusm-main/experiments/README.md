# experiments/ — IncHUSM stream experiments (Python)

The Python side drives the Java miner (`inchusm-miner/`) over snapshot streams
and checks correctness against a static re-run. Build the jar first:

```bash
mvn -f ../inchusm-miner/pom.xml package     # 12 tests, produces the jar
python -m venv ~/.venvs/inchusm && pip install -r ../requirements.txt
```

`experiments/src` is a source root; scripts add it to `sys.path` automatically.
Java and Python communicate through `.lg` / `.jsonl` files (no JNI), via
`src/inchusm/miner_bridge.py`.

## Scripts (result CSVs go to `results/`)

```bash
# E2 — IncHUSM vs static re-run on Elliptic-49 (49/49 exact match, 83 patterns):
python scripts/e2_inchusm_stream.py
python scripts/e2_inchusm_stream.py --bound eu  --no-compare    # upper-bound ablation
python scripts/e2_inchusm_stream.py --bound ivu --no-compare
python scripts/e2_inchusm_stream.py --measure support --min-value 200 --no-compare

# E2b — Citeseer trend check (static baseline is a faithful HUSM re-implementation):
python scripts/e2b_citeseer_trend.py

# E2c — controlled synthetic streams:
python scripts/e2c_synthetic_stream.py --tag S
python scripts/e2c_synthetic_stream.py --tag L --n 3000 --min-value 1600

# E2d — real AML stream (AMLworld HI-Small, one snapshot per day; takes hours):
python scripts/e2d_amlworld_stream.py

# Verify the deterministic values of every experiment above (machine-independent):
python scripts/check_results.py

# Repeated-run timing (mean +/- std; run on the declared measurement machine):
python scripts/e2_repeat_std.py --runs 5
python scripts/e2_ablation_repeat_std.py --runs 5
python scripts/e2d_repeat_std.py --runs 3

# Figures:
python scripts/make_figures_paper2.py
```

`run_paper2.bat` / `run_extra_std.bat` at the repository root wrap these for
Windows. Timing must be measured on one idle machine; cloud sync off.

## Outputs

Each stream step writes one CSV to `results/`, one row per snapshot:

| Column | Meaning |
|---|---|
| `snapshot` | snapshot index (1-based) |
| `vertices`, `edges` | vertex / edge count of this snapshot |
| `tracked` | pattern-states held in the store |
| `high_total` | high-utility patterns through this snapshot (cumulative) |
| `newly_high` | patterns promoted to high at this snapshot |
| `seg_last` | segment evaluations for the arriving snapshot alone |
| `inc_ms` | incremental update time for this snapshot (ms) |
| `static_ms` | static re-mine time on the accumulated union (ms; `-1` without `--compare-static`) |
| `static_high` | high count from the static re-run (`-1` without `--compare-static`) |
| `match` | `OK` / `MISMATCH` vs the static re-run (blank without `--compare-static`) |

Approximate wall-clock per run: E2 ~10 min, each ablation round ~55 s, E2d several hours.

## Package (`src/inchusm/`)

| Module | Role |
|---|---|
| `miner_bridge.py` | export `.lg` snapshot streams + utilities, invoke the jar, parse per-snapshot telemetry |
| `data/amlworld.py` | AMLworld transaction loader (chunked, memory-efficient) |
