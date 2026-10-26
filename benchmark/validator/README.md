# Validate run reports

Checks two consistency properties of `MgTopKMain --out FILE` reports:

1. **Top-K**: the top-10 patterns of a `K=10` run must also be the top-10 of a
   `K=50` run (same DFS codes and utilities; ties at the cutoff are reported as
   `WARN`, not as failures).
2. **Bound**: `--ieu-bound ivu`, `eu` and `ceu` change pruning only, so the
   top-K patterns must be identical for the same `K`.

## 1. Generate reports

One report per `(K, bound)`; the file name is arbitrary, the content is parsed.

```bash
for K in 10 50; do
  for B in ivu eu ceu; do
    java -cp artifact/target/top-k-husm-mg-0.1.0-SNAPSHOT.jar \
      topk.husm.mg.cli.MgTopKMain college-msg \
      --edges-file benchmark/dataset/formated/college-msg.txt \
      --K "$K" --ieu-bound "$B" \
      --out "benchmark/results/reports/college-msg-K${K}-${B}.txt"
  done
done
```

Add `--out-all` if you also want every evaluated pattern in the report (the
validator only needs the top-K section).

## 2. Validate

```bash
python3 benchmark/validator/validate_reports.py benchmark/results/reports \
  --out benchmark/results/validation.txt
```

Defaults: `--k-small 10 --k-large 50 --bounds ivu,eu,ceu --tolerance 1e-6`.
The full report is written to `--out`, a summary is printed to stdout, and the
exit code is `1` if any check fails (useful in CI).

Reports are grouped by `dataset` + `mer`, `nlabels`, `label_mode`, `seed`,
`weight`, `incremental_domains`, `prune`, `max_m`, `max_v`; only `K` and
`ieu_bound` are allowed to differ inside a group.
