"""Base converter for SNAP-style temporal interaction streams (``u v timestamp``)."""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import ClassVar, Iterator, Mapping

from base_converter import BaseConverter, RawEdge


class TemporalConverter(BaseConverter):
    """``u v timestamp`` -> one line per undirected pair.

    Temporal networks have no edge labels, so the type is derived from the
    timestamp:

    ``--types bucket`` (default)
        split ``[min_ts, max_ts]`` into ``--time-buckets`` equal-width windows
        and use the window index as the edge type.  Parallel edges between the
        same pair then spread over several types, which is what makes the
        multigraph label (``(type, multiplicity)`` pairs) non-trivial.

    ``--types none``
        every edge gets type ``0``; multiplicity alone carries the interaction
        count (the classic "collapse a multigraph into a weighted graph" view).

    SNAP streams are heavy-tailed: one pair can exchange thousands of messages.
    Keeping every parallel edge would make ``MultiGraph.seedTypes()`` enumerate
    ``prod(count[t] + 1)`` vectors per pair (10^13 for the worst email-Eu pair),
    so by default only the 4 busiest time windows of a pair and at most 4
    parallel edges per window are kept (``--max-types`` / ``--max-mult``, ``0``
    disables a cap).  That bounds the vectors at ``5^4 = 625`` per pair, below
    the reference DBLP multigraph (max 2,048).
    """

    requires_time: ClassVar[bool] = True
    allowed_types: ClassVar[tuple[str, ...]] = ("none", "bucket")
    default_types: ClassVar[str] = "bucket"
    default_time_buckets: ClassVar[int] = 10
    default_max_types: ClassVar[int | None] = 4
    default_max_mult: ClassVar[int | None] = 4

    # ------------------------------------------------------------------ parsing
    @staticmethod
    def rows(path: Path) -> Iterator[tuple[int, int, int, int]]:
        """Yield ``(lineno, u, v, timestamp)``; fail loudly on malformed lines."""
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for lineno, line in enumerate(fh, 1):
                if not line.strip():
                    continue
                tok = line.split()
                if len(tok) < 3:
                    raise ValueError(f"{path}:{lineno}: expected 'u v timestamp', got {line.strip()!r}")
                yield lineno, int(tok[0]), int(tok[1]), int(tok[2])

    def scan(self, path: Path) -> tuple[set[int], dict]:
        raw_ids: set[int] = set()
        t_min: int | None = None
        t_max: int | None = None
        for _, u, v, ts in self.rows(path):
            raw_ids.add(u)
            raw_ids.add(v)
            if t_min is None or ts < t_min:
                t_min = ts
            if t_max is None or ts > t_max:
                t_max = ts
        return raw_ids, {"t_min": t_min, "t_max": t_max}

    def type_cardinality(self, meta: Mapping) -> int:
        return self.time_buckets if self.types == "bucket" else 1

    def iter_edges(self, path: Path, meta: Mapping) -> Iterator[RawEdge]:
        if self.types == "none":
            for _, u, v, _ in self.rows(path):
                yield u, v, 0
            return

        t_min = int(meta["t_min"])
        span = int(meta["t_max"]) - t_min + 1
        buckets = self.time_buckets
        for _, u, v, ts in self.rows(path):
            yield u, v, min((ts - t_min) * buckets // span, buckets - 1)

    # ------------------------------------------------------------------ CLI
    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument(
            "--types",
            choices=self.allowed_types,
            default=None,
            help="edge-type policy: 'bucket' = timestamp window (default), 'none' = always 0",
        )
        parser.add_argument(
            "--time-buckets",
            type=int,
            default=None,
            help=f"number of equal-width timestamp windows for --types bucket (default {self.default_time_buckets})",
        )

    def apply_arguments(self, args: argparse.Namespace) -> None:
        if args.types is not None:
            self.types = args.types.lower()
        if args.time_buckets is not None:
            self.time_buckets = args.time_buckets
