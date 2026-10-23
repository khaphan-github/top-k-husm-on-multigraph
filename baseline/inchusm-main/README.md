# inchusm

**IncHUSM** — Incremental High-Utility Subgraph Mining over add-only snapshot streams.

High-utility subgraph mining (HUSM) enumerates subgraphs scored by external
utility rather than by frequency alone — relevant to anti-money-laundering,
where the value carried by a structure matters as much as how often it recurs.
Existing HUSM algorithms are *static*: they re-mine the whole accumulated graph
whenever a new snapshot arrives. Utility has no downward closure, so the
incremental machinery built for frequent-subgraph mining does not transfer.

IncHUSM maintains the **exact** high-utility subgraph set as pairwise-disjoint,
add-only snapshots arrive, without re-mining from scratch. It keeps a
utility-fringe over the CEU upper bound (instead of over support) and only
processes each (pattern, snapshot) pair once over the whole stream.

## Layout

```
inchusm/
├── inchusm-miner/          # Java (Maven): static HUSM + IncHUSM, 12 tests
├── experiments/
│   ├── src/inchusm/        # Python: miner bridge + AMLworld loader
│   ├── scripts/            # E2 (Elliptic), E2b (Citeseer), E2c (synthetic), E2d (AMLworld)
│   └── results/            # output CSVs (not committed)
├── datasets/               # Elliptic, AMLworld, Citeseer — NOT committed
├── docs/reproduce/paper2.md
└── requirements.txt
```

## Build the miner

```bash
mvn -f inchusm-miner/pom.xml package    # runs 12 tests, produces target/inchusm-miner-*.jar
```

The Python scripts invoke this jar through `experiments/src/inchusm/miner_bridge.py`.

## Run the experiments

```bash
python -m venv ~/.venvs/inchusm && pip install -r requirements.txt
```

One command, cross-platform (E2, the bound and support ablations, E2b, E2c, figures;
skips the multi-hour E2d):

```bash
python experiments/scripts/run_all_paper2.py --quick
```

Or the individual steps (datasets in place; see docs/reproduce/paper2.md):

```bash
python scripts/e2_inchusm_stream.py       # Elliptic-49: 49/49 exact match vs static re-run
python scripts/e2b_citeseer_trend.py      # static-baseline faithfulness check
python scripts/e2c_synthetic_stream.py --tag S   # controlled synthetic stream
python scripts/e2d_amlworld_stream.py     # AMLworld daily stream
python scripts/check_results.py           # verify the deterministic values (any machine)
```

`check_results.py` grades 8 result CSVs (main E2, the EU/IVU and support ablations, E2c
S/L, E2d, Citeseer). Run the full command list in
[experiments/README.md](experiments/README.md) (including the ablations, which the short
list above omits) to reach 8/8.

Full workflow, hardware notes, and reference numbers:
[docs/reproduce/paper2.md](docs/reproduce/paper2.md).

## License

Source code is released under the MIT License — see [LICENSE](LICENSE). Datasets
(Elliptic, AMLworld, Citeseer) are public benchmarks with their own licenses and
are not part of this repository.
