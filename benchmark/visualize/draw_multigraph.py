#!/usr/bin/env python3
"""Draw one multigraph edge file with networkx.

Input format is the one consumed by ``MgGraphLoader``::

    src dst label1[,label2,...]

Every comma-separated label is one parallel edge of that type, so the drawing
keeps each instance as a separate curved edge (unlike ``utils/graph_view.py``,
which collapses parallel edges into a single weighted edge).

    python3 benchmark/visualize/draw_multigraph.py \
        artifact/src/test/resources/small/eight.txt \
        --out artifact/output/eight.png
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # headless: write a PNG, never block on plt.show()

import matplotlib.pyplot as plt
import networkx as nx


def load_multigraph(path: Path) -> nx.MultiGraph:
    graph = nx.MultiGraph()
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            if not line.strip():
                continue
            parts = line.split(None, 2)
            if len(parts) < 3:
                raise SystemExit(f"{path}:{lineno}: expected 'src dst label1[,label2...]'")
            src, dst, labels = parts
            for label in labels.strip().split(","):
                graph.add_edge(int(src), int(dst), type=int(label))
    return graph


def pair_offsets(graph: nx.MultiGraph) -> dict[tuple[int, int, int], float]:
    """Arc curvature per parallel edge, centred on the straight line."""
    counts = Counter((min(u, v), max(u, v)) for u, v in graph.edges())
    seen: Counter[tuple[int, int]] = Counter()
    offsets: dict[tuple[int, int, int], float] = {}
    for u, v, key in sorted(graph.edges(keys=True)):
        pair = (min(u, v), max(u, v))
        total = counts[pair]
        idx = seen[pair]
        seen[pair] += 1
        offsets[(u, v, key)] = 0.0 if total == 1 else (idx - (total - 1) / 2) * 0.18
    return offsets


def draw(graph: nx.MultiGraph, title: str, out: Path,
         colors: dict[int, str] | None = None,
         names: dict[int, str] | None = None) -> None:
    simple = nx.Graph(graph)  # layout and node degree on the collapsed structure
    pos = nx.kamada_kawai_layout(simple, weight=None)
    offsets = pair_offsets(graph)

    types = sorted({d["type"] for *_, d in graph.edges(data=True)})
    cmap = plt.get_cmap("tab10")
    colors = colors or {}
    names = names or {}
    color = {t: colors.get(t, cmap(i % 10)) for i, t in enumerate(types)}

    fig, ax = plt.subplots(figsize=(9, 8))
    for u, v, key, data in sorted(graph.edges(keys=True, data=True)):
        rad = offsets[(u, v, key)]
        nx.draw_networkx_edges(
            graph, pos, ax=ax, edgelist=[(u, v, key)],
            edge_color=[color[data["type"]]],
            width=2.0, alpha=0.9,
            arrows=True, arrowstyle="-",  # FancyArrowPatch: needed for curvature
            connectionstyle=f"arc3,rad={rad}",
            min_source_margin=0, min_target_margin=0,
        )

    degree = dict(simple.degree)
    max_degree = max(degree.values())
    nodes = nx.draw_networkx_nodes(
        simple, pos, ax=ax, nodelist=list(degree),
        node_size=[120 + 480 * (degree[v] / max_degree) for v in degree],
        node_color=list(degree.values()), cmap=plt.cm.viridis,
        linewidths=0.6, edgecolors="white",
    )
    nx.draw_networkx_labels(simple, pos, ax=ax, font_size=9, font_color="black")

    handles = [
        plt.Line2D([], [], color=color[t], lw=2.2,
                   label=(f"{t} : {names[t]}" if t in names else f"type {t}")
                         + f" ({sum(1 for *_, d in graph.edges(data=True) if d['type'] == t)})")
        for t in types
    ]
    ax.legend(handles=handles, title="edge type", loc="upper right", fontsize=8)
    ax.set_title(
        f"{title}\n{graph.number_of_nodes()} vertices | "
        f"{graph.number_of_edges()} edge instances | "
        f"{simple.number_of_edges()} pairs | {len(types)} types",
        fontsize=10,
    )
    ax.axis("off")
    ax.margins(0.05)
    fig.colorbar(nodes, ax=ax, shrink=0.62, label="degree (parallel edges counted)")

    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=160, bbox_inches="tight")
    plt.close(fig)
    print(f"saved: {out}")


def parse_kv(text: str | None) -> dict[int, str]:
    """``"1=green,2=blue"`` -> ``{1: "green", 2: "blue"}``."""
    out: dict[int, str] = {}
    for item in (text or "").split(","):
        item = item.strip()
        if not item:
            continue
        key, _, value = item.partition("=")
        out[int(key)] = value.strip()
    return out


def default_out(stem: str) -> Path:
    """<repo>/artifact/output/<stem>.png, also when cwd is already ``artifact/``."""
    base = Path("output") if Path.cwd().name == "artifact" else Path("artifact") / "output"
    return base / f"{stem}.png"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("edges_file", type=Path, help="edge list 'src dst label1[,label2...]'")
    parser.add_argument("--out", type=Path, default=None,
                        help="PNG path (default: artifact/output/<stem>.png)")
    parser.add_argument("--title", default=None)
    parser.add_argument("--colors", default=None,
                        help="per-type colours, e.g. 1=green,2=blue,3=purple,4=red,5=black")
    parser.add_argument("--names", default=None,
                        help="per-type names for the legend, e.g. 1=lunch,2=facebook")
    args = parser.parse_args()

    graph = load_multigraph(args.edges_file)
    out = args.out or default_out(args.edges_file.stem)
    draw(graph, args.title or args.edges_file.stem, out,
         colors=parse_kv(args.colors), names=parse_kv(args.names))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
