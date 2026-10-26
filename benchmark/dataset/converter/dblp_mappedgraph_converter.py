"""DBLP-Mappedgraph: already-labelled multigraph ``u v label[,label,...]``."""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import ClassVar, Iterator, Mapping

from base_converter import RAW_DIR, BaseConverter, RawEdge


class DblpMappedgraphConverter(BaseConverter):
    """DBLP-Mappedgraph -> ``dblp-multigraph.txt`` (default) or ``dblp-simple.txt``.

    This is the only raw source that already carries edge labels, so it is the
    reference for the target format.  Conversion still normalises it:
    ``src < dst``, one line per pair, labels sorted, sparse ids relabelled and
    self loops dropped.

    * ``multigraph`` -- 83,901 vertices / 141,471 pairs / types 0..49
    * ``simple``     -- 3,264 vertices / 4,536 pairs / types 1..7
    """

    dataset_id: ClassVar[str] = "dblp-multigraph"
    allowed_types: ClassVar[tuple[str, ...]] = ("file",)
    default_types: ClassVar[str] = "file"

    VARIANTS: ClassVar[dict[str, str]] = {
        "multigraph": "DBLP-Mappedgraph/multigraph/edges.txt",
        "simple": "DBLP-Mappedgraph/simple_graph/edges.txt",
    }

    def __init__(self, variant: str = "multigraph", **kwargs) -> None:
        if variant not in self.VARIANTS:
            raise ValueError(f"unknown DBLP variant {variant!r}; pick one of {sorted(self.VARIANTS)}")
        self.variant = variant
        self.dataset_id = f"dblp-{variant}"
        super().__init__(**kwargs)

    def default_raw_path(self) -> Path:
        return RAW_DIR / self.VARIANTS[self.variant]

    # ------------------------------------------------------------------ parsing
    @staticmethod
    def rows(path: Path) -> Iterator[tuple[int, int, list[int]]]:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for lineno, line in enumerate(fh, 1):
                if not line.strip():
                    continue
                tok = line.split(None, 2)
                if len(tok) < 2:
                    raise ValueError(f"{path}:{lineno}: expected 'u v labels', got {line.strip()!r}")
                labels = [int(x) for x in tok[2].split(",")] if len(tok) == 3 else [0]
                if not labels:
                    raise ValueError(f"{path}:{lineno}: empty label list")
                yield int(tok[0]), int(tok[1]), labels

    def scan(self, path: Path) -> tuple[set[int], dict]:
        raw_ids: set[int] = set()
        max_type = 0
        for u, v, labels in self.rows(path):
            raw_ids.add(u)
            raw_ids.add(v)
            max_type = max(max_type, max(labels))
        return raw_ids, {"n_types": max_type + 1}

    def type_cardinality(self, meta: Mapping) -> int:
        return int(meta["n_types"])

    def iter_edges(self, path: Path, meta: Mapping) -> Iterator[RawEdge]:
        for u, v, labels in self.rows(path):
            for label in labels:
                yield u, v, label

    # ------------------------------------------------------------------ CLI
    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument(
            "--variant",
            choices=sorted(self.VARIANTS),
            default=None,
            help="which DBLP file to convert (default multigraph)",
        )

    def apply_arguments(self, args: argparse.Namespace) -> None:
        if args.variant is not None and args.variant != self.variant:
            self.variant = args.variant
            self.dataset_id = f"dblp-{self.variant}"
            self.raw_path = self.default_raw_path()
            self.out_path = self.default_out_path()


if __name__ == "__main__":
    DblpMappedgraphConverter().cli()
