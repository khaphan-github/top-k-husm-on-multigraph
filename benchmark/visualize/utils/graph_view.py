"""Helpers for loading and drawing the benchmark multigraphs.

The files live in ``benchmark/dataset/formated/<dataset>.txt`` and follow the
edge-list format consumed by ``topk.husm.mg.graph.MgGraphLoader``::

    src dst label1[,label2,...]

Every comma-separated label is one parallel edge of that type, so
``4 5 8,8,24`` means two type-8 edges plus one type-24 edge.  The converters cap
multiplicities (4 busiest types per pair, at most 4 parallel edges per type --
see ``benchmark/dataset/converter/README.md``), therefore edge width in a plot
shows the *capped* multiplicity, not the raw interaction count.
"""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import networkx as nx

SEED = 42
MAX_NODES = 400

UTILS_DIR = Path(__file__).resolve().parent


def find_benchmark_dir(start: Path | str | None = None) -> Path:
    """Walk up from ``start`` (default: this file) until the ``benchmark`` folder."""
    here = Path(start).resolve() if start is not None else UTILS_DIR
    for base in (here, *here.parents):
        if base.name == "benchmark" and (base / "dataset" / "formated").is_dir():
            return base
    raise FileNotFoundError(f"cannot locate 'benchmark/dataset/formated' above {here}")


BENCHMARK_DIR = find_benchmark_dir()
FORMATED_DIR = BENCHMARK_DIR / "dataset" / "formated"
#: default location for rendered figures (<repo>/benchmark/visualize/out)
FIG_DIR = BENCHMARK_DIR / "visualize" / "out"


# --------------------------------------------------------------------- loading
def load_multigraph(path: Path | str, max_lines: int | None = None) -> nx.MultiGraph:
    """Read ``src dst label1[,label2,...]`` into a ``MultiGraph`` (1 label = 1 edge)."""
    graph = nx.MultiGraph()
    with open(path, encoding="utf-8") as fh:
        for i, line in enumerate(fh):
            if max_lines is not None and i >= max_lines:
                break
            src, dst, labels = line.split(None, 2)
            for label in labels.strip().split(","):
                graph.add_edge(int(src), int(dst), type=int(label))
    return graph


def load_dataset(name: str, formated_dir: Path | str | None = None) -> nx.MultiGraph:
    """Load ``<name>.txt`` from the formatted-data folder (``.txt`` is optional)."""
    folder = Path(formated_dir) if formated_dir is not None else FORMATED_DIR
    path = folder / f"{Path(name).stem}.txt"
    if not path.is_file():
        available = sorted(p.stem for p in folder.glob("*.txt"))
        raise FileNotFoundError(f"{path} not found; available datasets: {available}")
    return load_multigraph(path)


def project(graph: nx.MultiGraph) -> nx.Graph:
    """Collapse to a simple ``Graph``: ``weight`` = parallel edges, ``types`` = type set."""
    simple = nx.Graph()
    for src, dst, etype in graph.edges(keys=False, data="type"):
        data = simple.get_edge_data(src, dst) or {"weight": 0, "types": set()}
        simple.add_edge(src, dst, weight=data["weight"] + 1, types=data["types"] | {etype})
    return simple


def graph_stats(graph: nx.MultiGraph) -> dict:
    """Summary numbers worth printing before drawing anything."""
    simple = project(graph)
    degrees = dict(graph.degree())
    max_degree = max(degrees.items(), key=lambda kv: kv[1])
    max_mult = max(d["weight"] for *_, d in simple.edges(data=True))
    return {
        "nodes": graph.number_of_nodes(),
        "edges": graph.number_of_edges(),
        "pairs": simple.number_of_edges(),
        "types": len({d["type"] for *_, d in graph.edges(data=True)}),
        "max_degree_node": max_degree[0],
        "max_degree": max_degree[1],
        "max_parallel": max_mult,
        "components": nx.number_connected_components(simple),
    }


# -------------------------------------------------------------------- sampling
def sample(graph: nx.MultiGraph, max_nodes: int = MAX_NODES, radius: int = 2) -> nx.MultiGraph:
    """Keep a readable neighbourhood around the highest-degree vertex.

    A plain BFS renders as a star (one hub plus leaves), so take the ego-network
    of ``radius`` hops, prefer the highest-degree vertices inside it, and finally
    keep only the largest connected component (stray clusters squeeze the layout).
    """
    simple = project(graph)
    start = max(simple.degree, key=lambda kv: kv[1])[0]
    pool = list(nx.single_source_shortest_path_length(simple, start, cutoff=radius))
    if len(pool) > max_nodes:
        pool = sorted(pool, key=lambda v: -simple.degree[v])[:max_nodes]
    sub = graph.subgraph(pool).copy()
    main = max(nx.connected_components(project(sub)), key=len)
    return sub.subgraph(main).copy()


def layout(simple: nx.Graph, seed: int = SEED):
    """Spring layout with a larger ``k`` than the default, so nodes do not overlap."""
    n = max(simple.number_of_nodes(), 1)
    return nx.spring_layout(simple, seed=seed, k=2.0 / n**0.5, iterations=150)


# -------------------------------------------------------------------- drawing
def draw_multi(
    graph: nx.MultiGraph,
    max_nodes: int = MAX_NODES,
    seed: int = SEED,
    title: str | None = None,
    save: Path | str | None = None,
    ax=None,
    figsize: tuple[float, float] = (9, 9),
):
    """Draw a sample of ``graph``: node size/colour ~ degree, edge width ~ multiplicity."""
    sample_graph = sample(graph, max_nodes=max_nodes)
    simple = project(sample_graph)
    if simple.number_of_nodes() == 0:
        raise ValueError("empty graph")

    pos = layout(simple, seed=seed)
    degree = dict(simple.degree)
    edges = list(simple.edges(data=True))
    max_degree = max(degree.values())
    max_weight = max(d["weight"] for *_, d in edges)

    created = ax is None
    if created:
        fig, ax = plt.subplots(figsize=figsize)
    else:
        fig = ax.figure

    nx.draw_networkx_edges(
        simple,
        pos,
        ax=ax,
        edgelist=[(u, v) for u, v, _ in edges],
        width=[0.3 + 2.5 * d["weight"] / max_weight for *_, d in edges],
        edge_color="#94a3b8",
        alpha=0.6,
    )
    nodes = nx.draw_networkx_nodes(
        simple,
        pos,
        ax=ax,
        nodelist=list(degree),
        node_size=[30 + 420 * (degree[v] / max_degree) ** 0.6 for v in degree],
        node_color=list(degree.values()),
        cmap=plt.cm.viridis,
        linewidths=0.4,
        edgecolors="white",
    )
    ax.set_title(
        title
        or f"{simple.number_of_nodes():,} vertices / {simple.number_of_edges():,} pairs "
        f"(sample of {graph.number_of_nodes():,})",
        fontsize=10,
    )
    ax.axis("off")
    ax.margins(0.03)
    fig.colorbar(nodes, ax=ax, shrink=0.62, label="degree (number of neighbours)")

    if save:
        out = Path(save)
        out.parent.mkdir(parents=True, exist_ok=True)
        fig.savefig(out, dpi=150, bbox_inches="tight")
        print("saved:", out)
    if created:
        plt.show()
    return fig


def draw_dataset(
    name: str,
    max_nodes: int = MAX_NODES,
    seed: int = SEED,
    save: Path | str | None = None,
    formated_dir: Path | str | None = None,
):
    """``load_dataset`` + ``draw_multi`` in one call (saves to ``out/<name>.png`` by default)."""
    graph = load_dataset(name, formated_dir=formated_dir)
    stats = graph_stats(graph)
    print(
        f"{name}: {stats['nodes']:,} vertices | {stats['edges']:,} edges | "
        f"{stats['pairs']:,} pairs | {stats['types']:,} edge types | "
        f"max degree {stats['max_degree']:,} (vertex {stats['max_degree_node']}) | "
        f"max parallel {stats['max_parallel']:,} | components {stats['components']:,}"
    )
    target = FIG_DIR / f"{Path(name).stem}.png" if save is None else save
    return draw_multi(graph, max_nodes=max_nodes, seed=seed, title=name, save=target)


def draw_grid(
    names: list[str],
    max_nodes: int = 120,
    seed: int = SEED,
    save: Path | str | None = None,
    formated_dir: Path | str | None = None,
):
    """Side-by-side panels, one sample per dataset (same node budget for each)."""
    fig, axes = plt.subplots(1, len(names), figsize=(6.2 * len(names), 6.4), squeeze=False)
    for ax, name in zip(axes[0], names):
        draw_multi(load_dataset(name, formated_dir=formated_dir), max_nodes=max_nodes,
                   seed=seed, title=name, ax=ax)
    fig.suptitle(f"Same budget of {max_nodes} vertices per dataset", fontsize=12)
    fig.tight_layout()
    if save:
        out = Path(save)
        out.parent.mkdir(parents=True, exist_ok=True)
        fig.savefig(out, dpi=150, bbox_inches="tight")
        print("saved:", out)
    plt.show()
    return fig
