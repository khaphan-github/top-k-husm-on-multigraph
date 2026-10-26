"""bn-fly-drosophila (Network Repository): medulla connectome, plain ``u v`` edges."""

from __future__ import annotations

from pathlib import Path
from typing import Iterator, Mapping

from base_converter import BaseConverter, RawEdge


class BnFlyDrosophilaConverter(BaseConverter):
    """bn-fly-drosophila -> ``bn-fly-drosophila.txt``.

    The file has no edge labels: every synapse record becomes an edge of type
    ``0``, so the number of parallel edges between two neurons (``mult`` in the
    artifact's ``(type, multiplicity)`` label) *is* the connection strength.
    """

    dataset_id = "bn-fly-drosophila"
    raw_rel_path = "bn-fly-drosophila/bn-fly-drosophila_medulla_1.edges"
    description = (
        "bn-fly-drosophila: u v (no label), 1,781 vertices / 33,641 synapses; "
        "all edges get type 0 and multiplicity = synapse count"
    )

    # A single edge type per pair makes``seedTypes()`` linear in the multiplicity
    # (max 328 vectors for the densest synapse pair), so nothing is capped and
    # the connection strength survives intact.
    default_max_types = 1
    default_max_mult = None

    def scan(self, path: Path) -> tuple[set[int], dict]:
        raw_ids: set[int] = set()
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for lineno, line in enumerate(fh, 1):
                if not line.strip():
                    continue
                tok = line.split()
                if len(tok) < 2:
                    raise ValueError(f"{path}:{lineno}: expected 'u v', got {line.strip()!r}")
                raw_ids.add(int(tok[0]))
                raw_ids.add(int(tok[1]))
        return raw_ids, {}

    def iter_edges(self, path: Path, meta: Mapping) -> Iterator[RawEdge]:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for lineno, line in enumerate(fh, 1):
                if not line.strip():
                    continue
                tok = line.split()
                if len(tok) < 2:
                    raise ValueError(f"{path}:{lineno}: expected 'u v', got {line.strip()!r}")
                yield int(tok[0]), int(tok[1]), 0

    def type_cardinality(self, meta: Mapping) -> int:
        return 1


if __name__ == "__main__":
    BnFlyDrosophilaConverter().cli()
