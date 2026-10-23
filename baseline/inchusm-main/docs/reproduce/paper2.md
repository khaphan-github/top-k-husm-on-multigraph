# Reproducing Paper 2 (RIVF — IncHUSM) — run everything on another machine with a single command

> Use this when moving to the official MEASUREMENT MACHINE (recommended ≥16 GB RAM, idle).
> **Single-machine rule:** all TIMING numbers for Paper 2 must be measured on this one machine;
> after the run, update the hardware statement in the manuscript's Setup section to match the
> machine actually used (the log records the machine configuration automatically).

## 1. Get the code and the data onto the machine

### 1a. From a fresh clone (the usual case)

`git clone` gives you **source code and documentation only**: the datasets, the result CSVs and
the compiled jar are deliberately untracked (see `.gitignore`). After cloning you must therefore
download the datasets (Section 1c) and build the jar (Section 2, step 3).

### 1b. From an existing working folder

If another machine already holds a populated `inchusm/` (datasets present, jar built), copy
it directly instead — this skips the download and the build. In `cmd`:

```bat
robocopy "C:\path\to\inchusm" "D:\inchusm" /E /XD tools __pycache__ .git e2_snapshots e2c_snapshots_S e2c_snapshots_L e2c_snapshots_probe e2d_snapshots /XF *.log
```

- Change `D:\inchusm` to your target drive (preferably a **local SSD, OUTSIDE any cloud-synced
  folder** — to avoid I/O noise during timing).
- `/XD tools` skips AMLSim (not used here); the `*_snapshots*` folders are intermediate .lg
  files that get regenerated, so there is no need to copy them.

### 1c. Datasets

Total for the required part: Elliptic ~1.5 GB + AMLworld Small ~9 GB + Citeseer ~25 MB.

- **Elliptic** and **AMLworld** — public benchmarks on Kaggle. Get a token at
  kaggle.com/settings (saved as `~/.kaggle/kaggle.json`) and click "I Understand and Accept"
  once on each dataset page, then:
  ```bash
  pip install kaggle
  kaggle datasets download -d ellipticco/elliptic-data-set -p datasets/elliptic1 --unzip
  kaggle datasets download -d ealtman2019/ibm-transactions-for-anti-money-laundering-aml \
      -p datasets/amlworld --unzip
  ```
  You need `datasets/elliptic1/elliptic_txs_*.csv` and
  `datasets/amlworld/HI-Small_Trans.csv` (+ `_Patterns.txt`).
- **Citeseer** (needed by E2b only) — put `citeseer.content` and `citeseer.cites` under
  `datasets/citeseer/`; `e2b_citeseer_trend.py` converts them to `citeseer.lg` on first run.
  The copy used here came from <https://github.com/ZPowerZ/citeseer-dataset>.

## 2. Environment setup (once)

1. **Java 17 or newer** (17/21/23 all run the jar) — check: `java -version`.
2. **Python 3.12–3.14**, create a venv OUTSIDE the synced folder. Commands for CMD, **one per
   line** (do not paste them joined):
   ```bat
   python -m venv %USERPROFILE%\.venvs\inchusm
   "%USERPROFILE%\.venvs\inchusm\Scripts\python.exe" -m pip install -r requirements.txt
   ```
   (`requirements.txt` at the root of `inchusm/`: numpy/pandas/matplotlib.
   Missing `matplotlib` → the figure step fails.)
3. **Maven** — required after a fresh clone, because the jar is not tracked in git. Build it and
   run the 12 tests with:
   ```bat
   mvn -f inchusm-miner\pom.xml package
   ```
   This produces `inchusm-miner\target\inchusm-miner-0.1.0-SNAPSHOT.jar`, which the Python
   scripts invoke through `miner_bridge.py`. If you copied a working folder (Section 1b) the jar
   is already there and Maven is not needed.

## 3. Run

```bat
cd /d D:\inchusm
run_paper2.bat --quick     :: STEP 1: smoke-test the pipeline (~20–40 min, skips E2d)
run_paper2.bat --only-e2d  :: STEP 2: run the heavy AMLworld part (several hours, fine overnight)
```
or combine into a single `run_paper2.bat` command (E2d is scheduled last automatically). Before an
official timing run: **pause Google Drive/OneDrive sync, close heavy applications, do not touch
the machine**.

Orchestrator order: E2 Elliptic (compare) → EU/IVU bound ablation → measure=support ablation →
E2b Citeseer → E2c synthetic S/L → **E2d AMLworld (last)** → draw figures. A failing step does
not block the next; the stream CSV is written **line by line** (killing it midway does not lose
what already ran). Summary log + machine configuration: `experiments\results\run_all_paper2_log.txt`.

## 4. Reference numbers to CHECK (deterministic — every machine must produce these)

| Step | Expected (any machine) |
|---|---|
| E2 Elliptic compare | **49/49 match=OK**; final #HUS **83** |
| E2 EU/IVU bound | same **83** HUS (only timing differs: CEU ≤ EU ≤ IVU) |
| E2 measure=support (θ=200) | **72** HUS |
| E2b Citeseer | #HUS decreases monotonically **77→51→32→15→7→3**; candidates CEU≤EU≤IVU at every θ; #HUS identical across the 3 bounds |
| E2c S (n=1500, θ=800) | **30/30 OK**, **73** HUS |
| E2c L (n=3000, θ=1600) | **30/30 OK**, **70** HUS |
| E2d AMLworld (10 days, θ=2000) | **10/10 match=OK** (a 3-day probe once gave 3/3 OK, 15 HUS at t=3) |

TIMING/SPEEDUP numbers are machine-dependent. On the reference i7-10750H the E2 Elliptic
speedup is ~38x and the synthetic streams ~2.9x/3.1x; expect the same order of magnitude on
other machines.

## 5. After the run

1. Copy `experiments\results\*.csv` + `run_all_paper2_log.txt` back to the project folder (or let
   Drive sync it).
2. Update the manuscript: (a) the hardware statement in the Setup section for the new machine;
   (b) all timing/speedup numbers from the new CSVs; (c) the E2d AMLworld results paragraph.
   Regenerate the figures with `python experiments\scripts\make_figures_paper2.py` (it writes the
   figure PDFs to `results/`).
