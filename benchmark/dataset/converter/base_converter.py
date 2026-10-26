"""Shared pipeline for the benchmark dataset converters.

Every dataset under ``benchmark/dataset/raw_data/`` has exactly one converter
class (``<DatasetName>Converter``) and produces exactly one file in
``benchmark/dataset/formated/``.  The output uses the edge-list format consumed
by ``topk.husm.mg.graph.MgGraphLoader``:

    src dst label1[,label2,...]

Semantics enforced here:

* vertex ids are relabelled to a dense ``0..n-1`` range -- the loader allocates
  ``n = max(id) + 1`` and derives degree-bucket vertex labels from ``n``, so
  keeping SNAP's sparse ids (e.g. 4..515280) would pollute the label strata;
* one line per *undirected* vertex pair, written as ``src < dst``;
* every comma-separated label is one parallel edge of that type, so a pair with
  three interactions of type 5 and one of type 7 becomes ``u v 5,5,5,7``
  (that is exactly how the loader recovers the multiplicity);
* self loops are dropped (the loader drops them too);
* lines are sorted by ``(src, dst)`` and labels within a line by type, so the
  output is byte-for-byte reproducible.

The file carries no vertex labels: the mining artifact synthesizes them
(``--label-mode degree|random``), so per-vertex attributes in the raw data
(department, node label, ...) are intentionally not propagated.
"""

from __future__ import annotations

import argparse
import time
from abc import ABC, abstractmethod
from collections import Counter
from pathlib import Path
from typing import ClassVar, Iterator, Mapping

CONVERTER_DIR = Path(__file__).resolve().parent


def _find_benchmark_dir(start: Path) -> Path:
    """Walk up until the directory that owns ``dataset/`` (survives folder moves)."""
    for candidate in (start, *start.parents):
        if (candidate / "dataset" / "raw_data").is_dir() and candidate.name == "benchmark":
            return candidate
    raise RuntimeError(f"cannot locate the 'benchmark' directory above {start}")


BENCHMARK_DIR = _find_benchmark_dir(CONVERTER_DIR)
DATASET_DIR = BENCHMARK_DIR / "dataset"
RAW_DIR = DATASET_DIR / "raw_data"
OUT_DIR = DATASET_DIR / "formated"

# raw edge (raw_src, raw_dst, edge_type)
RawEdge = tuple[int, int, int]


class BaseConverter(ABC):
    """Turns one raw dataset into one edge-list file."""

    dataset_id: ClassVar[str] = ""
    description: ClassVar[str] = ""
    raw_rel_path: ClassVar[str] = ""

    #: edge-type policies accepted by this converter
    allowed_types: ClassVar[tuple[str, ...]] = ("none",)
    default_types: ClassVar[str] = "none"
    default_time_buckets: ClassVar[int] = 10
    requires_time: ClassVar[bool] = False

    #: keep at most this many distinct edge types per vertex pair (None = all)
    default_max_types: ClassVar[int | None] = None
    #: keep at most this many parallel edges per (pair, type) (None = all)
    default_max_mult: ClassVar[int | None] = None

    def __init__(
        self,
        raw: Path | str | None = None,
        out: Path | str | None = None,
        out_dir: Path | str | None = None,
        types: str | None = None,
        time_buckets: int | None = None,
        max_types: int | None = None,
        max_mult: int | None = None,
    ) -> None:
        if not self.dataset_id:
            raise ValueError(f"{type(self).__name__}: dataset_id is not set")
        self._out_dir = Path(out_dir) if out_dir is not None else OUT_DIR
        self.raw_path = Path(raw) if raw is not None else self.default_raw_path()
        self.out_path = Path(out) if out is not None else self.default_out_path()
        self.types = (types or self.default_types).lower()
        self.time_buckets = int(time_buckets or self.default_time_buckets)
        self.max_types = (self.default_max_types if max_types is None else max_types) or None
        self.max_mult = (self.default_max_mult if max_mult is None else max_mult) or None
        self.validate()

    # ------------------------------------------------------------------ naming
    def default_raw_path(self) -> Path:
        return RAW_DIR / self.raw_rel_path

    def default_out_path(self) -> Path:
        return self._out_dir / f"{self.dataset_id}.txt"

    def validate(self) -> None:
        if self.types not in self.allowed_types:
            raise ValueError(
                f"{type(self).__name__}: edge-type policy must be one of "
                f"{self.allowed_types}, got {self.types!r}"
            )
        if self.types == "bucket":
            if not self.requires_time:
                raise ValueError(f"{type(self).__name__} has no timestamps; use --types none")
            if self.time_buckets < 1:
                raise ValueError("--time-buckets must be >= 1")
        if self.max_types is not None and self.max_types < 1:
            raise ValueError("--max-types must be >= 1 (0 = unlimited)")
        if self.max_mult is not None and self.max_mult < 1:
            raise ValueError("--max-mult must be >= 1 (0 = unlimited)")

    # ------------------------------------------------------------------ hooks
    @abstractmethod
    def scan(self, path: Path) -> tuple[set[int], dict]:
        """Pass 1: collect raw vertex ids and whatever metadata ``iter_edges`` needs."""

    @abstractmethod
    def iter_edges(self, path: Path, meta: Mapping) -> Iterator[RawEdge]:
        """Pass 2: stream ``(raw_src, raw_dst, edge_type)``; one tuple per parallel edge."""

    @abstractmethod
    def type_cardinality(self, meta: Mapping) -> int:
        """Number of distinct edge types the stream can emit (used to pack keys)."""

    # ------------------------------------------------------------------ pipeline
    def run(self) -> dict:
        started = time.perf_counter()
        raw_ids, meta = self.scan(self.raw_path)
        if not raw_ids:
            raise ValueError(f"{self.raw_path}: no usable edges found")

        id_map = {raw_id: new_id for new_id, raw_id in enumerate(sorted(raw_ids))}
        n = len(id_map)
        n_types = max(1, int(self.type_cardinality(meta)))

        packed: Counter[int] = Counter()
        read = self_loops = 0
        for raw_u, raw_v, etype in self.iter_edges(self.raw_path, meta):
            read += 1
            if raw_u == raw_v:
                self_loops += 1
                continue
            if not 0 <= etype < n_types:
                raise ValueError(f"{self.raw_path}: edge type {etype} outside [0,{n_types})")
            u, v = id_map[raw_u], id_map[raw_v]
            a, b = (u, v) if u < v else (v, u)
            packed[(a * n + b) * n_types + etype] += 1

        written = self._write(packed, n, n_types)

        stats = {
            "dataset": self.dataset_id,
            "raw": str(self.raw_path),
            "out": str(self.out_path),
            "vertices": n,
            "raw_edges": read,
            "edges": written["emitted"],
            "edges_before_caps": sum(packed.values()),
            "pairs": written["pairs"],
            "self_loops": self_loops,
            "types": written["types"],
            "max_parallel": written["max_parallel"],
            "seed_vectors": written["seed_vectors"],
            "max_seed_vectors": written["max_seed_vectors"],
            "max_types": self.max_types,
            "max_mult": self.max_mult,
            "bytes": self.out_path.stat().st_size,
            "seconds": time.perf_counter() - started,
        }
        self.verify(stats, n_types)
        return stats

    def _apply_caps(self, counts: dict[int, int]) -> dict[int, int]:
        """Keep the strongest types of one pair, then cap each multiplicity.

        ``MgHUSM`` calls ``MultiGraph.seedTypes()``, which enumerates every
        vector of per-type multiplicities of a pair: ``prod(count[t] + 1)``
        items.  A pair with 5 types and 500 interactions each would enumerate
        ~10^13 vectors, so the caps are what keep the search tractable.
        Types are ranked by multiplicity (ties by smaller type id), which keeps
        the dominant windows of a temporal pair.
        """
        if self.max_types is not None and len(counts) > self.max_types:
            ranked = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
            counts = dict(ranked[: self.max_types])
        if self.max_mult is not None:
            counts = {t: min(c, self.max_mult) for t, c in counts.items()}
        return counts

    def _write(self, packed: Counter[int], n: int, n_types: int) -> dict:
        self.out_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.out_path.with_name(self.out_path.name + ".tmp")

        stats = {"pairs": 0, "emitted": 0, "max_parallel": 0,
                 "seed_vectors": 0, "max_seed_vectors": 0, "types": 0}
        types_seen: set[int] = set()
        pair_id, counts = -1, {}

        with tmp.open("w", encoding="utf-8", newline="\n") as fh:
            def flush() -> None:
                if not counts:
                    return
                kept = self._apply_caps(counts)
                labels = [etype for etype in sorted(kept) for _ in range(kept[etype])]
                x, y = divmod(pair_id, n)
                fh.write(f"{x} {y} {','.join(map(str, labels))}\n")
                vectors = 1
                for count in kept.values():
                    vectors *= count + 1
                stats["pairs"] += 1
                stats["emitted"] += len(labels)
                stats["max_parallel"] = max(stats["max_parallel"], len(labels))
                stats["seed_vectors"] += vectors
                stats["max_seed_vectors"] = max(stats["max_seed_vectors"], vectors)
                types_seen.update(kept)

            for key in sorted(packed):
                current_pair, etype = divmod(key, n_types)
                if current_pair != pair_id:
                    flush()
                    pair_id, counts = current_pair, {}
                counts[etype] = packed[key]
            flush()

        tmp.replace(self.out_path)
        stats["types"] = len(types_seen)
        return stats

    def verify(self, stats: Mapping, n_types: int) -> None:
        """Re-read the output and check the contract of ``MgGraphLoader``."""
        seen_pairs = 0
        with self.out_path.open("r", encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, 1):
                tok = line.split()
                if len(tok) != 3:
                    raise AssertionError(f"{self.out_path}:{lineno}: expected 3 fields, got {line!r}")
                u, v = int(tok[0]), int(tok[1])
                if not 0 <= u < v < stats["vertices"]:
                    raise AssertionError(f"{self.out_path}:{lineno}: bad pair {u} {v}")
                for label in tok[2].split(","):
                    etype = int(label)
                    if not 0 <= etype < n_types:
                        raise AssertionError(
                            f"{self.out_path}:{lineno}: bad type {etype} (n_types={n_types})"
                        )
                seen_pairs += 1
        if seen_pairs != stats["pairs"]:
            raise AssertionError(
                f"{self.out_path}: wrote {stats['pairs']} pairs but read back {seen_pairs}"
            )

    # ------------------------------------------------------------------ CLI
    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        """Extra command-line switches (edge-type policy, dataset variant, ...)."""

    def apply_arguments(self, args: argparse.Namespace) -> None:
        """Copy parsed switches into ``self``; must leave the object consistent."""

    def cli(self, argv: list[str] | None = None) -> dict:
        parser = argparse.ArgumentParser(
            prog=f"{type(self).__name__}",
            description=self.description or f"Convert {self.dataset_id}",
        )
        parser.add_argument("--raw", type=Path, default=None, help="override the raw input file")
        parser.add_argument("--out", type=Path, default=None, help="override the output file")
        parser.add_argument("--max-types", type=int, default=None,
                            help="max distinct edge types per pair (0 = unlimited)")
        parser.add_argument("--max-mult", type=int, default=None,
                            help="max parallel edges per (pair, type) (0 = unlimited)")
        parser.add_argument("--quiet", action="store_true", help="only print on failure")
        self.add_arguments(parser)
        args = parser.parse_args(argv)

        if args.raw is not None:
            self.raw_path = args.raw
        if args.out is not None:
            self.out_path = args.out
        if args.max_types is not None:
            self.max_types = args.max_types or None
        if args.max_mult is not None:
            self.max_mult = args.max_mult or None
        self.apply_arguments(args)
        self.validate()

        stats = self.run()
        if not args.quiet:
            print(self.format_stats(stats))
        return stats

    def format_stats(self, stats: Mapping) -> str:
        caps = "none"
        if stats["max_types"] or stats["max_mult"]:
            caps = f"maxTypes={stats['max_types'] or 'inf'}, maxMult={stats['max_mult'] or 'inf'}"
        return (
            f"[{stats['dataset']}] {stats['vertices']:,} vertices | "
            f"{stats['raw_edges']:,} raw edges -> {stats['pairs']:,} pairs / "
            f"{stats['edges']:,} edges ({stats['edges_before_caps']:,} before caps) | "
            f"{stats['types']} edge types | "
            f"self-loops dropped {stats['self_loops']:,} | "
            f"max parallel {stats['max_parallel']:,} | "
            f"seed vectors {stats['seed_vectors']:,} (max/pair {stats['max_seed_vectors']:,}) | "
            f"caps {caps} | "
            f"{stats['bytes'] / 1e6:.1f} MB in {stats['seconds']:.1f}s\n"
            f"    {stats['raw']}\n -> {stats['out']}"
        )
