#!/usr/bin/env python3
"""Independent correctness check of an ``MgTopKMain --out`` report.

It re-implements, without the Java miner:

* vertex labels and per-type edge weights (exact ``java.util.Random`` port),
* MNI support with the ``mer`` ball constraint (``ball(root, mer)``),
* utility ``u(P) = s * mean(ext(type))`` with ``ext(t)`` = max weight of type ``t``,
* the IVU bound used by the report,

and then verifies every reported row plus completeness (all connected patterns
that have at least one valid occurrence must be present).

    python3 benchmark/validator/verify_mg_run.py \
        --edges artifact/src/test/resources/small/eight.txt \
        --report artifact/output/mg-run-eight-*.txt \
        --nlabels 3 --seed 42 --mer 1
"""

from __future__ import annotations

import argparse
import itertools
import math
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

MASK48 = (1 << 48) - 1
LABEL_SEED_XOR = 0x9E3779B97F4A7C15


# ------------------------------------------------------------------- java RNG
class JavaRandom:
    """Minimal ``java.util.Random`` port (nextInt and nextGaussian)."""

    def __init__(self, seed: int):
        self.seed = (seed ^ 0x5DEECE66D) & MASK48
        self._have_next_gaussian = False
        self._next_gaussian = 0.0

    def _next(self, bits: int) -> int:
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & MASK48
        return self.seed >> (48 - bits)

    def next_int(self, bound: int) -> int:
        if (bound & -bound) == bound:
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            val = bits % bound
            if bits - val + (bound - 1) < (1 << 31):
                return val

    def next_double(self) -> float:
        return ((self._next(26) << 27) + self._next(27)) * 2.0**-53

    def next_gaussian(self) -> float:
        if self._have_next_gaussian:
            self._have_next_gaussian = False
            return self._next_gaussian
        while True:
            v1 = 2.0 * self.next_double() - 1.0
            v2 = 2.0 * self.next_double() - 1.0
            s = v1 * v1 + v2 * v2
            if s < 1.0 and s != 0.0:
                break
        multiplier = math.sqrt(-2.0 * math.log(s) / s)
        self._next_gaussian = v2 * multiplier
        self._have_next_gaussian = True
        return v1 * multiplier


def draw_weight(rng: JavaRandom, wdist: str) -> float:
    if wdist == "gaussian":
        x = max(3.0 + rng.next_gaussian(), 1e-3)
    elif wdist == "lognormal":
        x = math.exp(3.0 + rng.next_gaussian())
    else:
        raise SystemExit(f"unknown wdist: {wdist}")
    return math.floor(x * 1000.0 + 0.5) / 1000.0  # Math.round(x*1000)/1000


# ------------------------------------------------------------------- graph
@dataclass
class Graph:
    n: int
    labels: list[int]
    pair_types: dict[tuple[int, int], dict[int, int]]  # pair -> type -> count
    ext: dict[int, float]                             # type -> max instance weight
    adj: dict[int, set[int]]

    def count(self, x: int, y: int, t: int) -> int:
        return self.pair_types.get((min(x, y), max(x, y)), {}).get(t, 0)

    def ball(self, center: int, mer: int) -> set[int]:
        seen = {center}
        frontier = [center]
        for _ in range(mer):
            nxt = []
            for u in frontier:
                for w in self.adj[u]:
                    if w not in seen:
                        seen.add(w)
                        nxt.append(w)
            frontier = nxt
        return seen

    def ext_omega(self, x: int, y: int) -> float:
        best = float("-inf")
        for t in self.pair_types.get((min(x, y), max(x, y)), {}):
            best = max(best, self.ext[t])
        return best


def load_graph(path: Path, nlabels: int, seed: int, wdist: str) -> Graph:
    pair_types: dict[tuple[int, int], dict[int, int]] = {}
    max_vertex = -1
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            if not line.strip():
                continue
            parts = line.split(None, 2)
            if len(parts) < 3:
                raise SystemExit(f"{path}:{lineno}: expected 'src dst label1[,label2...]'")
            u, v, raw = int(parts[0]), int(parts[1]), parts[2]
            if u == v:
                continue
            max_vertex = max(max_vertex, u, v)
            by_type = pair_types.setdefault((min(u, v), max(u, v)), {})
            for tok in raw.strip().split(","):
                t = int(tok)
                by_type[t] = by_type.get(t, 0) + 1

    n = max_vertex + 1
    rng = JavaRandom(seed ^ LABEL_SEED_XOR)
    labels = [rng.next_int(nlabels) for _ in range(n)]

    # Same draw order as MgGraphLoader.build: pairs sorted by (x,y), types ascending.
    rng = JavaRandom(seed)
    ext: dict[int, float] = {}
    for pair in sorted(pair_types):
        for t in sorted(pair_types[pair]):
            for _ in range(pair_types[pair][t]):
                w = draw_weight(rng, wdist)
                ext[t] = max(ext.get(t, float("-inf")), w)

    adj: dict[int, set[int]] = {v: set() for v in range(n)}
    for x, y in pair_types:
        adj[x].add(y)
        adj[y].add(x)
    return Graph(n, labels, pair_types, ext, adj)


# ------------------------------------------------------------------- report
@dataclass
class Row:
    u: float
    s: int
    m: int
    bound: float
    code: str
    labels: list[int]
    edges: list[tuple[int, int, int, int]]  # (a, b, type, mult)


TUPLE_RE = re.compile(r"\((\d+),(\d+),(-?\d+),\[([^\]]*)\],(-?\d+)\)")
COMP_RE = re.compile(r"(-?\d+):(\d+)")


def parse_code(code: str) -> tuple[list[int], list[tuple[int, int, int, int]]]:
    labels: dict[int, int] = {}
    edges: list[tuple[int, int, int, int]] = []
    for m in TUPLE_RE.finditer(code):
        i, j, li, comps, lj = (int(m.group(1)), int(m.group(2)), int(m.group(3)),
                               m.group(4), int(m.group(5)))
        for v, lab in ((i, li), (j, lj)):
            if v in labels and labels[v] != lab:
                raise ValueError(f"inconsistent label for vertex {v} in {code}")
            labels[v] = lab
        for comp in COMP_RE.finditer(comps):
            edges.append((i, j, int(comp.group(1)), int(comp.group(2))))
    n = len(labels)
    if sorted(labels) != list(range(n)):
        raise ValueError(f"non-contiguous vertex ids in {code}: {sorted(labels)}")
    return [labels[v] for v in range(n)], edges


def parse_report(path: Path) -> list[Row]:
    rows: list[Row] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 5:
            continue  # config/stat lines
        try:
            u, s, m, bound = float(parts[0]), int(parts[1]), int(parts[2]), float(parts[3])
        except ValueError:
            continue
        labels, edges = parse_code(parts[4])
        rows.append(Row(u, s, m, bound, parts[4], labels, edges))
    return rows


# --------------------------------------------------------- support / utility
def support(pattern_labels: list[int], edges: list[tuple[int, int, int, int]],
            g: Graph, mer: int) -> tuple[int, set[int]]:
    """CMNI support and the image set of vertex 0 (root), with ball(root, mer)."""
    n = len(pattern_labels)
    images: list[set[int]] = [set() for _ in range(n)]
    for root in range(g.n):
        if g.labels[root] != pattern_labels[0]:
            continue
        ball = g.ball(root, mer)
        candidates = [[x for x in ball if g.labels[x] == pattern_labels[v]] for v in range(n)]
        candidates[0] = [root]
        if any(not c for c in candidates):
            continue
        phi = [-1] * n
        used: set[int] = set()

        def rec(v: int) -> None:
            if v == n:
                for k in range(n):
                    images[k].add(phi[k])
                return
            for y in candidates[v]:
                if y in used:
                    continue
                ok = True
                for a, b, t, mult in edges:
                    if a == v and phi[b] != -1 and g.count(phi[b], y, t) < mult:
                        ok = False
                        break
                    if b == v and phi[a] != -1 and g.count(phi[a], y, t) < mult:
                        ok = False
                        break
                if not ok:
                    continue
                phi[v] = y
                used.add(y)
                rec(v + 1)
                used.remove(y)
                phi[v] = -1

        rec(0)

    if any(not img for img in images):
        return 0, set()
    return min(len(img) for img in images), images[0]


def expected_utility(s: int, edges: list[tuple[int, int, int, int]], g: Graph) -> float:
    m = sum(mult for *_, mult in edges)
    sum_ext = sum(mult * g.ext[t] for *_, t, mult in edges)
    return s * (sum_ext / m)


def expected_ivu(images0: set[int], g: Graph, mer: int) -> float:
    total = 0.0
    for r in images0:
        ball = g.ball(r, mer)
        best = float("-inf")
        for x in ball:
            for y in g.adj[x]:
                if x >= y or y not in ball:
                    continue
                best = max(best, g.ext_omega(x, y))
        if best != float("-inf"):
            total += best
    return total


# ------------------------------------------------------------- completeness
def canonical(labels: list[int], edges: list[tuple[int, int, int, int]]):
    """Isomorphism-invariant key of a labelled multigraph pattern."""
    n = len(labels)
    best = None
    for perm in itertools.permutations(range(n)):
        # perm maps old vertex -> new vertex, so labels are placed at the new index.
        labs = [0] * n
        for old, new in enumerate(perm):
            labs[new] = labels[old]
        ed = tuple(sorted((min(perm[a], perm[b]), max(perm[a], perm[b]), t, mult)
                          for a, b, t, mult in edges))
        key = (tuple(labs), ed)
        if best is None or key < best:
            best = key
    return best


def report_key(row: Row):
    return canonical(row.labels, row.edges)


def all_occurring_patterns(g: Graph, mer: int):
    """Every pattern class with at least one valid occurrence under ANY root.

    This is an upper bound for the miner's result set: support is evaluated at the
    canonical (minimum) DFS-code root, so a class whose only occurrences are centred
    on another vertex is correctly absent from the report. Exact completeness is
    checked by ``topk.husm.mg.VerifyMgRunReport``, which shares only ``MgMinCode``.
    """
    instances = [(x, y, t) for (x, y), by_type in g.pair_types.items()
                 for t, c in by_type.items() for _ in range(c)]
    classes: dict[object, tuple[list[int], list[tuple[int, int, int, int]]]] = {}
    for mask in range(1, 1 << len(instances)):
        chosen = [instances[k] for k in range(len(instances)) if mask >> k & 1]
        verts = sorted({v for x, y, _ in chosen for v in (x, y)})
        # connectivity first (cheap reject)
        parent = {v: v for v in verts}

        def find(a: int) -> int:
            while parent[a] != a:
                parent[a] = parent[parent[a]]
                a = parent[a]
            return a

        for x, y, _ in chosen:
            ra, rb = find(x), find(y)
            if ra != rb:
                parent[ra] = rb
        if len({find(v) for v in verts}) != 1:
            continue
        local = {v: i for i, v in enumerate(verts)}
        agg: dict[tuple[int, int, int], int] = defaultdict(int)
        for x, y, t in chosen:
            a, b = local[x], local[y]
            agg[(min(a, b), max(a, b), t)] += 1
        labels = [g.labels[v] for v in verts]
        edges = [(a, b, t, mult) for (a, b, t), mult in agg.items()]
        key = canonical(labels, edges)
        classes.setdefault(key, (labels, edges))

    supportable = {}
    for key, (labels, edges) in classes.items():
        n = len(labels)
        for root in range(n):
            order = [root, *[v for v in range(n) if v != root]]
            pos = {v: i for i, v in enumerate(order)}
            remapped = [(pos[a], pos[b], t, mult) for a, b, t, mult in edges]
            if any(a > b for a, b, _, _ in remapped):
                remapped = [(b, a, t, mult) for a, b, t, mult in remapped]
            s, _ = support([labels[v] for v in order], remapped, g, mer)
            if s > 0:
                supportable[key] = (labels, edges, s)
                break
    return supportable


# --------------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--edges", type=Path, required=True)
    ap.add_argument("--report", type=Path, required=True)
    ap.add_argument("--nlabels", type=int, default=3)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--wdist", default="gaussian", choices=["gaussian", "lognormal"])
    ap.add_argument("--mer", type=int, default=1)
    ap.add_argument("--skip-completeness", action="store_true")
    args = ap.parse_args()

    g = load_graph(args.edges, args.nlabels, args.seed, args.wdist)
    rows = parse_report(args.report)
    problems: list[str] = []

    if not rows:
        print("FAIL: no rows parsed from the report")
        return 1

    # 1. structural checks
    keys = [report_key(r) for r in rows]
    dups = {k for k in keys if keys.count(k) > 1}
    if dups:
        problems.append(f"{len(dups)} duplicated pattern class(es)")
    for i, r in enumerate(rows):
        m_code = sum(mult for *_, mult in r.edges)
        if m_code != r.m:
            problems.append(f"row {i}: m={r.m} but code has m={m_code}")
        if r.s <= 0:
            problems.append(f"row {i}: s={r.s} <= 0")
        if i and rows[i - 1].u < r.u - 1e-9:
            problems.append(f"row {i}: u not sorted ({rows[i-1].u} -> {r.u})")

    # 2. per-row semantic check vs the independent implementation
    checked = 0
    for i, r in enumerate(rows):
        s, images0 = support(r.labels, r.edges, g, args.mer)
        if s != r.s:
            problems.append(f"row {i} {r.code}: s={r.s}, recomputed {s}")
            continue
        u = expected_utility(s, r.edges, g)
        if abs(u - r.u) > 0.0051:
            problems.append(f"row {i} {r.code}: u={r.u}, recomputed {u:.4f}")
        ivu = expected_ivu(images0, g, args.mer)
        if abs(ivu - r.bound) > 0.0051:
            problems.append(f"row {i} {r.code}: bound={r.bound}, recomputed {ivu:.4f}")
        if r.u > r.bound + 1e-9:
            problems.append(f"row {i} {r.code}: u={r.u} > bound={r.bound}")
        checked += 1

    # 3. completeness: any reported pattern must occur; classes that occur only under a
    # non-canonical root are informational (support is anchored at the canonical code root).
    occurring = {}
    if not args.skip_completeness:
        occurring = all_occurring_patterns(g, args.mer)
        reported = set(keys)
        extra = reported - set(occurring)
        if extra:
            problems.append(f"{len(extra)} reported pattern(s) have no occurrence at all (e.g. "
                            f"{next(iter(extra))})")

    print(f"graph      : {args.edges}  n={g.n}  pairs={len(g.pair_types)}  "
          f"instances={sum(sum(d.values()) for d in g.pair_types.values())}")
    print(f"labels     : {g.labels}")
    print(f"ext(type)  : " + "  ".join(f"{t}={v:.3f}" for t, v in sorted(g.ext.items())))
    print(f"report     : {args.report}")
    print(f"rows       : {len(rows)}  (checked {checked})")
    if not args.skip_completeness:
        missing = set(occurring) - set(keys)
        print(f"occurring  : {len(occurring)} pattern classes (any root); "
              f"{len(missing)} of them only under a non-canonical root")
    if problems:
        print(f"\nFAIL: {len(problems)} problem(s)")
        for p in problems[:20]:
            print("  -", p)
        if len(problems) > 20:
            print(f"  ... {len(problems) - 20} more")
        return 1
    print("\nPASS: every row is correct; all reported patterns occur in the graph")
    if not args.skip_completeness:
        print("      exact completeness: run topk.husm.mg.VerifyMgRunReport "
              "(canonical-root set comparison)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
