"""Paper-style figures for the benchmark report (low-level plotting layer).

Notebook nên dùng class ở :mod:`utils.report` — nó bọc sẵn việc đọc CSV,
metric phái sinh, layout và export::

    from utils.report import ReportViewer

    report = ReportViewer("benchmark/results/runs.csv")
    report.plot_all()

Các hàm ở module này chỉ là lớp vẽ cấp thấp (theme, gom nhóm mean/std, axes,
legend, export PNG+PDF) để class hoặc script riêng gọi lại::

    from utils.report_plots import load_runs, plot_facet_bars

    df = load_runs("benchmark/results/runs.csv")
    plot_facet_bars(df, "time_s", "Runtime (s)", facet="mer", x="K",
                    hue="ieu_bound", save=out_dir / "runtime")
"""

from __future__ import annotations

import math
from collections import defaultdict
from pathlib import Path

import matplotlib as mpl
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from matplotlib.patches import Patch
from matplotlib.ticker import FuncFormatter, LogLocator

# ---------------------------------------------------------------- constants
#: Okabe-Ito: an toàn cho người mù màu.
PALETTE = ["#0072B2", "#E69F00", "#009E73", "#D55E00",
           "#CC79A7", "#56B4E9", "#F0E442", "#4D4D4D"]

BAR_EDGE = "#1a1a1a"
GRID_COLOR = "#dcdcdc"

#: metric column -> (ylabel, short title)
METRICS = {
    "time_s": ("Runtime (s)", "Runtime"),
    "mem_mb": ("Allocated memory (MB)", "Memory allocation"),
    #: peak memory chính thức — convention SPMF MemoryLogger
    #: (Runtime.totalMemory() - freeMemory(), không ép GC)
    "mem_logger_mb": ("Peak memory (MB)", "Peak memory (SPMF)"),
    #: chỉ để tham khảo: peak của các JVM memory pool, không dùng trong paper
    "heap_mb": ("Peak heap, JVM pools (MB)", "Peak heap (MXBean)"),
    "patterns": ("Evaluated patterns", "Evaluated patterns"),
}

NUMERIC_COLS = [
    "K", "mer", "nlabels", "theta", "evaluated", "expanded", "pruned",
    "cpu_ms", "wall_ms", "alloc_bytes", "peak_heap_bytes", "peak_live_bytes",
    "mem_logger_peak_bytes", "mem_logger_interval_ms", "mem_logger_samples",
    "warmup", "repeats",
]

REQUIRED_COLS = {
    "dataset", "ieu_bound", "incremental_domains", "evaluated",
    "wall_ms", "alloc_bytes", "peak_heap_bytes",
}

_STYLE_APPLIED = False


# ------------------------------------------------------------------- paths
def find_repo_root(start: Path | str | None = None) -> Path:
    """Đi lên từ ``start`` (mặc định: cwd) tới thư mục gốc repo."""
    here = Path(start).resolve() if start is not None else Path.cwd().resolve()
    for base in (here, *here.parents):
        if (base / "benchmark" / "results").is_dir():
            return base
    return here


def resolve_path(p: str | Path, root: Path | str | None = None) -> Path:
    """Tìm file tương đối từ cwd, repo root hoặc bất kỳ thư mục cha nào."""
    p = Path(p).expanduser()
    if p.is_absolute():
        candidates = [p]
    else:
        base = Path(root) if root is not None else find_repo_root()
        candidates = [Path.cwd() / p, base / p,
                      *(parent / p for parent in Path.cwd().parents)]
    for cand in candidates:
        if cand.is_file():
            return cand.resolve()
    raise FileNotFoundError(
        f"Không tìm thấy CSV: {p}\nĐã thử: {[str(c) for c in candidates[:5]]}"
    )


# -------------------------------------------------------------------- load
def load_runs(csv_path, status_ok_only: bool = True,
              root: Path | str | None = None) -> pd.DataFrame:
    """Đọc một hoặc nhiều ``runs.csv`` và thêm các cột metric dễ đọc.

    Cột thêm vào: ``time_s``, ``cpu_s``, ``mem_mb``, ``heap_mb``,
    ``live_mb``, ``mem_logger_mb``, ``patterns``, ``variant``.
    """
    paths = [resolve_path(p, root=root) for p in (
        csv_path if isinstance(csv_path, (list, tuple)) else [csv_path]
    )]

    frames = []
    for path in paths:
        frame = pd.read_csv(path)
        frame["source_csv"] = str(path)
        frames.append(frame)
    runs = pd.concat(frames, ignore_index=True)

    missing = REQUIRED_COLS - set(runs.columns)
    if missing:
        raise ValueError(f"CSV thiếu cột bắt buộc: {sorted(missing)}")

    for col in NUMERIC_COLS:
        if col in runs.columns:
            runs[col] = pd.to_numeric(runs[col], errors="coerce")

    if "timestamp" in runs.columns:
        runs["timestamp"] = pd.to_datetime(runs["timestamp"], errors="coerce", utc=True)

    runs["incremental_domains"] = (
        runs["incremental_domains"].astype(str).str.strip().str.lower()
        .isin({"true", "1", "yes", "y"})
    )
    runs["variant"] = (
        runs["ieu_bound"].astype(str).str.upper()
        + runs["incremental_domains"].map({True: "+ID", False: ""}).fillna("")
    )

    runs["time_s"] = runs["wall_ms"] / 1e3
    runs["cpu_s"] = runs["cpu_ms"] / 1e3 if "cpu_ms" in runs.columns else np.nan
    runs["mem_mb"] = runs["alloc_bytes"] / 2**20
    runs["heap_mb"] = runs["peak_heap_bytes"] / 2**20
    runs["live_mb"] = (
        runs["peak_live_bytes"] / 2**20 if "peak_live_bytes" in runs.columns else np.nan
    )
    runs["mem_logger_mb"] = (
        runs["mem_logger_peak_bytes"] / 2**20
        if "mem_logger_peak_bytes" in runs.columns else np.nan
    )
    runs["patterns"] = runs["evaluated"]

    if status_ok_only and "status" in runs.columns:
        runs = runs[runs["status"].astype(str).str.lower() == "ok"]
    return runs.reset_index(drop=True)


# ------------------------------------------------------------------- theme
def set_paper_style(base_size: int = 9) -> None:
    """rcParams kiểu hình trong paper data-mining."""
    mpl.rcParams.update({
        "figure.dpi": 120,
        "savefig.dpi": 300,
        "savefig.bbox": "tight",
        "savefig.pad_inches": 0.02,
        # Typography
        "font.family": "serif",
        "font.serif": ["Times New Roman", "Times", "Nimbus Roman",
                       "Liberation Serif", "DejaVu Serif"],
        "mathtext.fontset": "stix",
        "font.size": base_size,
        "axes.titlesize": base_size,
        "axes.labelsize": base_size,
        "xtick.labelsize": base_size - 1,
        "ytick.labelsize": base_size - 1,
        "legend.fontsize": base_size - 1,
        # Spines / ticks
        "axes.linewidth": 0.8,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.edgecolor": "#222222",
        "axes.labelcolor": "#111111",
        "xtick.color": "#111111",
        "ytick.color": "#111111",
        "xtick.direction": "out",
        "ytick.direction": "out",
        "xtick.major.width": 0.7,
        "ytick.major.width": 0.7,
        "xtick.major.size": 2.5,
        "ytick.major.size": 2.5,
        # Legend / background
        "legend.frameon": False,
        "legend.handlelength": 1.4,
        "legend.columnspacing": 1.4,
        "figure.facecolor": "white",
        "axes.facecolor": "white",
        "text.color": "#111111",
        "axes.grid": False,
    })


def ensure_style() -> None:
    global _STYLE_APPLIED
    if not _STYLE_APPLIED:
        set_paper_style()
        _STYLE_APPLIED = True


# ------------------------------------------------------------------ helpers
def _sorted_unique(series) -> list:
    vals = list(pd.unique(series.dropna()))
    try:
        return sorted(vals)
    except TypeError:
        return sorted(vals, key=str)


def _auto_log(values) -> bool:
    vals = np.asarray(values, dtype=float)
    vals = vals[np.isfinite(vals) & (vals > 0)]
    return bool(vals.size >= 2 and vals.max() / vals.min() > 100)


def _fmt_cat(v) -> str:
    if isinstance(v, (float, np.floating)) and float(v).is_integer():
        return str(int(v))
    return str(v)


def _fmt_axis(v, _pos=None) -> str:
    """Tick labels: 25,000 · 1.2M (không đổi đơn vị giữa chừng)."""
    if v == 0:
        return "0"
    if abs(v) >= 1e6:
        return f"{v / 1e6:g}M"
    if abs(v) >= 1:
        return f"{v:,.0f}"
    return f"{v:g}"


# -------------------------------------------------------------------- plot
def plot_facet_bars(df: pd.DataFrame, metric: str, ylabel: str | None = None, *,
                    facet: str = "mer", x: str = "K", hue: str | None = "ieu_bound",
                    facet_order=None, x_order=None, hue_order=None,
                    facet_name: str | None = None, x_label: str | None = None,
                    xtick_format: str = "{}",
                    title: str | None = None, caption: str | None = None,
                    ncols: int | None = None, log: bool | str = "auto",
                    legend: bool = True,
                    error: str = "none", panel_size: tuple[float, float] = (3.7, 2.9),
                    save=None, dpi: int = 300):
    """Bar chart nhiều panel, kiểu "Hình 2: runtime theo K và MER".

    Mỗi panel là một giá trị ``facet`` (ví dụ MER=1/2/3), trục X là ``x``
    (K), mỗi nhóm cột là một giá trị ``hue`` (IEU/EU). Trong cùng một
    (facet, x, hue) có nhiều dòng thì vẽ mean (và std nếu ``error="sd"``).

    Returns ``(fig, axes, stats)``.
    """
    ensure_style()
    for col in (metric, facet, x, hue):
        if col and col not in df.columns:
            raise KeyError(f"Không có cột '{col}' trong DataFrame")

    work = df.dropna(subset=[metric]).copy()
    if work.empty:
        raise ValueError(f"Không có dữ liệu cho metric '{metric}'")

    ylabel = ylabel or METRICS.get(metric, (metric,))[0]

    keys = [facet, x] + ([hue] if hue else [])
    stats = (
        work.groupby(keys, observed=True)[metric]
        .agg(mean="mean", std="std", n="count")
        .reset_index()
    )

    facets = list(facet_order) if facet_order else _sorted_unique(stats[facet])
    facets = [f for f in facets if f in set(stats[facet])]
    xcats = list(x_order) if x_order else _sorted_unique(stats[x])
    hues = ([None] if not hue else
            [h for h in (hue_order or _sorted_unique(stats[hue]))
             if h in set(stats[hue])])

    use_log = log is True or (log == "auto" and _auto_log(stats["mean"]))
    has_std = error == "sd" and stats["n"].gt(1).any() and stats["std"].notna().any()

    ncols = ncols or min(len(facets), 4)
    nrows = math.ceil(len(facets) / ncols)
    head = 0.92 if (legend and len(hues) > 1) else 1.0
    foot = 0.06 if caption else 0.0
    fig, axes = plt.subplots(
        nrows, ncols, squeeze=False,
        figsize=(panel_size[0] * ncols + 0.30 * (ncols - 1),
                 panel_size[1] * nrows + 0.55 * (nrows - 1)
                 + (0.28 if caption else 0) + (0.22 if head < 1 else 0)),
        sharey=False,
    )
    flat = axes.ravel()

    nh = max(len(hues), 1)
    width = 0.8 / nh
    centers = np.arange(len(xcats))
    facet_label = facet_name or facet.upper()

    for i, f in enumerate(facets):
        ax = flat[i]
        data = stats[stats[facet] == f]

        for j, h in enumerate(hues):
            sub = data if h is None else data[data[hue] == h]
            series = sub.set_index(x)["mean"].reindex(xcats).to_numpy(dtype=float)

            err = None
            if has_std:
                sd = sub.set_index(x)["std"].reindex(xcats).fillna(0).to_numpy(dtype=float)
                err = np.minimum(sd, series * 0.9) if use_log else sd

            ax.bar(
                centers + (j - (nh - 1) / 2) * width,
                series,
                width=width * 0.9,
                color=PALETTE[j % len(PALETTE)],
                edgecolor=BAR_EDGE,
                linewidth=0.45,
                label=None if h is None else str(h),
                yerr=err,
                capsize=2.2 if err is not None else 0,
                error_kw={"elinewidth": 0.7, "capthick": 0.7, "ecolor": "#333333"},
                zorder=3,
            )

        ax.set_title(f"{facet_label}={_fmt_cat(f)}", fontweight="bold", pad=6)
        ax.set_xticks(centers)
        ax.set_xticklabels([xtick_format.format(v) for v in xcats])
        ax.set_xlim(-0.5, len(xcats) - 0.5)
        ax.set_xlabel(x_label or x)
        ax.set_ylabel(ylabel)

        if use_log:
            ax.set_yscale("log")
            positive = data.loc[data["mean"] > 0, "mean"]
            if not positive.empty:
                ax.set_ylim(bottom=float(positive.min()) / 3)
            ax.yaxis.set_major_locator(LogLocator(base=10, numticks=8))
        else:
            ax.set_ylim(bottom=0)

        ax.yaxis.set_major_formatter(FuncFormatter(_fmt_axis))
        ax.grid(axis="y", color=GRID_COLOR, linestyle="-", linewidth=0.6, zorder=0)
        ax.set_axisbelow(True)
        ax.tick_params(length=2.5, width=0.7, pad=2)

    for ax in flat[len(facets):]:
        ax.set_visible(False)

    if legend and len(hues) > 1:
        handles = [
            Patch(facecolor=PALETTE[j % len(PALETTE)], edgecolor=BAR_EDGE,
                  linewidth=0.45, label=str(h))
            for j, h in enumerate(hues)
        ]
        fig.legend(handles, [str(h) for h in hues], loc="upper center",
                   ncol=len(hues), frameon=False, bbox_to_anchor=(0.5, 1.0),
                   handlelength=1.4, columnspacing=1.4)

    if caption:
        fig.text(0.5, 0.012, caption, ha="center", va="bottom",
                 fontsize=mpl.rcParams["font.size"], color="#333333")

    fig.tight_layout(rect=(0, foot, 1, head))

    if save is not None:
        save = Path(save)
        save.parent.mkdir(parents=True, exist_ok=True)
        targets = ([save] if save.suffix.lower() in {".png", ".pdf", ".svg"}
                   else [save.with_suffix(".png"), save.with_suffix(".pdf")])
        for target in targets:
            fig.savefig(target, dpi=dpi)

    return fig, axes, stats


# ------------------------------------------------- dataset-aware grid plot
def coverage_table(df: pd.DataFrame, *, dataset: str = "dataset",
                   row: str | None = "mer", x: str = "K",
                   hue: str | None = "ieu_bound") -> pd.DataFrame:
    """Bảng phủ dữ liệu: mỗi dataset có bao nhiêu MER / K / bound thực sự có dòng.

    Dùng để biết "có bao nhiêu dataset" và dataset nào thiếu cấu hình trước
    khi vẽ, tránh panel trống bất ngờ.
    """
    rows = []
    for ds, g in df.groupby(dataset, observed=True):
        row_vals = _sorted_unique(g[row]) if (row and row in g.columns) else []
        x_vals = _sorted_unique(g[x]) if x in g.columns else []
        hue_vals = _sorted_unique(g[hue]) if (hue and hue in g.columns) else []
        rows.append({
            dataset: ds,
            "rows": len(g),
            (row or "row"): len(row_vals),
            "values": ", ".join(_fmt_cat(v) for v in row_vals) if row_vals else "—",
            x: len(x_vals),
            "K values": ", ".join(_fmt_cat(v) for v in x_vals) if x_vals else "—",
            "bounds": ", ".join(map(str, hue_vals)) if hue_vals else "—",
        })
    tbl = pd.DataFrame(rows).set_index(dataset)
    if not tbl.empty:
        tbl = tbl.sort_index()
    return tbl


def plot_dataset_grid(df: pd.DataFrame, metric: str, ylabel: str | None = None, *,
                      dataset: str = "dataset", x: str = "K",
                      hue: str | None = "ieu_bound", row: str | None = "mer",
                      dataset_order=None, row_order=None,
                      x_order=None, hue_order=None,
                      row_name: str | None = None, x_label: str | None = None,
                      xtick_format: str = "{}", log: bool | str = "auto",
                      share_y: str = "row", legend: bool = True,
                      error: str = "none",
                      panel_size: tuple[float, float] = (3.2, 2.5),
                      max_cols: int = 4, caption: str | None = None,
                      save=None, dpi: int = 300):
    """Lưới panel ``dataset × row``: mỗi dataset một panel, không gộp mất dataset.

    Cột = dataset (tự giãn theo số dataset thực có, ``max_cols`` chỉ là số cột
    tối đa trước khi xuống dòng), dòng = ``row`` (mặc định MER), trục X = ``x``
    (K), mỗi nhóm cột = ``hue`` (IEU bound). Dataset nào thiếu cấu hình thì
    panel ghi "no data" thay vì bị bỏ im lặng.

    ``share_y``: ``"row"`` (cùng thang trong 1 dòng MER — so sánh được giữa
    dataset), ``"all"`` (một thang cho cả hình), ``"none"`` (mỗi panel tự co).

    Returns ``(fig, axes, stats)`` với ``axes`` là ndarray 2 chiều
    ``(nrows, ncols)``.
    """
    ensure_style()
    for col in (metric, dataset, x):
        if col not in df.columns:
            raise KeyError(f"Không có cột '{col}' trong DataFrame")
    if hue is not None and hue not in df.columns:
        hue = None
    row = row if (row and row in df.columns) else None

    work = df.dropna(subset=[metric]).copy()
    if work.empty:
        raise ValueError(f"Không có dữ liệu cho metric '{metric}'")

    ylabel = ylabel or METRICS.get(metric, (metric,))[0]

    ds_present = set(work[dataset].dropna())
    ds_all = _sorted_unique(work[dataset])
    ds_list = [d for d in (dataset_order or ds_all) if d in ds_present]
    if not ds_list:
        raise ValueError(f"Không có dataset nào khớp {dataset_order}")

    rows_all = _sorted_unique(work[row]) if row else [None]
    row_list = [r for r in (row_order or rows_all) if r in set(rows_all)]
    if not row_list:
        row_list = list(rows_all)

    keys = [dataset] + ([row] if row else []) + [x] + ([hue] if hue else [])
    stats = (
        work.groupby(keys, observed=True)[metric]
        .agg(mean="mean", std="std", n="count")
        .reset_index()
    )

    xcats = list(x_order) if x_order else _sorted_unique(stats[x])
    hues = ([None] if not hue else
            [h for h in (hue_order or _sorted_unique(stats[hue]))
             if h in set(stats[hue])])
    if not hues:
        hues = [None]

    use_log = log is True or (log == "auto" and _auto_log(stats["mean"]))
    has_std = (error == "sd" and stats["n"].gt(1).any()
               and stats["std"].notna().any())

    # ---- layout: dataset giãn theo cột, xuống dòng khi vượt max_cols
    ncols = max(1, min(len(ds_list), int(max_cols)))
    blocks = math.ceil(len(ds_list) / ncols)
    nrows = blocks * len(row_list)

    head = 0.94 if (legend and len(hues) > 1) else 1.0
    foot = 0.07 if caption else 0.0
    left = 0.075 if row else 0.02
    fig, axes = plt.subplots(
        nrows, ncols, squeeze=False,
        figsize=(panel_size[0] * ncols + 0.40 * (ncols - 1) + 0.35,
                 panel_size[1] * nrows + 0.60 * (nrows - blocks)
                 + (0.45 if blocks > 1 else 0.0)
                 + (0.30 if caption else 0.0) + (0.24 if head < 1 else 0.0)),
        sharey=False,
    )

    nh = max(len(hues), 1)
    width = 0.8 / nh
    centers = np.arange(len(xcats))
    panel_axes: dict[tuple[int, int, int], plt.Axes] = {}
    panel_vals: dict[tuple[int, int, int], list[float]] = {}

    for ri, rv in enumerate(row_list):
        for bi in range(blocks):
            for ci in range(ncols):
                idx = bi * ncols + ci
                if idx >= len(ds_list):
                    continue
                ds = ds_list[idx]
                ax = axes[ri * blocks + bi, ci]

                sub_all = stats[stats[dataset] == ds]
                if rv is not None:
                    sub_all = sub_all[sub_all[row] == rv]

                vals: list[float] = []
                for j, h in enumerate(hues):
                    sub = sub_all if h is None else sub_all[sub_all[hue] == h]
                    series = (sub.groupby(x)["mean"].first()
                              .reindex(xcats).to_numpy(dtype=float))
                    err = None
                    if has_std:
                        sd = (sub.groupby(x)["std"].first()
                              .reindex(xcats).fillna(0.0).to_numpy(dtype=float))
                        err = np.minimum(sd, series * 0.9) if use_log else sd
                    ax.bar(
                        centers + (j - (nh - 1) / 2) * width,
                        series,
                        width=width * 0.9,
                        color=PALETTE[j % len(PALETTE)],
                        edgecolor=BAR_EDGE,
                        linewidth=0.45,
                        label=None if h is None else str(h),
                        yerr=err,
                        capsize=2.0 if err is not None else 0,
                        error_kw={"elinewidth": 0.6, "capthick": 0.6,
                                  "ecolor": "#333333"},
                        zorder=3,
                    )
                    vals.extend(float(v) for v in series
                                if np.isfinite(v) and v > 0)

                panel_axes[(ri, bi, ci)] = ax
                panel_vals[(ri, bi, ci)] = vals

                # tiêu đề panel: dòng đầu tiên của mỗi block dataset mang tên dataset
                if ri == 0:
                    ax.set_title(ds, fontweight="bold", pad=5,
                                 fontsize=mpl.rcParams["font.size"])

                if not vals:
                    ax.text(0.5, 0.5, "no data", ha="center", va="center",
                            transform=ax.transAxes, fontsize=mpl.rcParams["font.size"] - 1,
                            color="#999999", style="italic")

                ax.set_xticks(centers)
                ax.set_xticklabels([xtick_format.format(v) for v in xcats])
                ax.set_xlim(-0.5, len(xcats) - 0.5)
                if ri * blocks + bi == nrows - 1:
                    ax.set_xlabel(x_label or x)
                if ci == 0:
                    if row is None:
                        ax.set_ylabel(ylabel)
                    else:
                        # nhãn dòng (MER=…) làm ylabel panel trái ⇒ không đè tick label
                        ax.set_ylabel(f"{row_name or row.upper()}={_fmt_cat(rv)}",
                                      fontweight="bold", labelpad=4)

    if row is not None:
        fig.supylabel(ylabel, x=0.022, fontsize=mpl.rcParams["axes.labelsize"])

    for ax in axes.ravel():
        if ax in panel_axes.values():
            if use_log:
                ax.set_yscale("log")
                ax.yaxis.set_major_locator(LogLocator(base=10, numticks=8))
            ax.yaxis.set_major_formatter(FuncFormatter(_fmt_axis))
            ax.grid(axis="y", color=GRID_COLOR, linewidth=0.6, zorder=0)
            ax.set_axisbelow(True)
            ax.tick_params(length=2.5, width=0.7, pad=2)
        else:
            ax.set_visible(False)

    # ---- đồng bộ thang trục Y
    if share_y in {"row", "all"}:
        buckets: dict = defaultdict(list)
        for (ri, bi, ci), ax in panel_axes.items():
            key = (ri, bi) if share_y == "row" else 0
            buckets[key].append((ax, panel_vals[(ri, bi, ci)]))
        for items in buckets.values():
            pos = [v for _, vals in items for v in vals]
            if not pos:
                continue
            lo, hi = min(pos), max(pos)
            for ax, _ in items:
                if use_log:
                    ax.set_ylim(lo / 3.0, hi * 3.0)
                else:
                    ax.set_ylim(0, hi * 1.18)
    else:
        for key, ax in panel_axes.items():
            vals = panel_vals[key]
            if not vals:
                continue
            ax.set_ylim(bottom=min(vals) / 3.0 if use_log else 0)

    if legend and len(hues) > 1:
        handles = [
            Patch(facecolor=PALETTE[j % len(PALETTE)], edgecolor=BAR_EDGE,
                  linewidth=0.45, label=str(h))
            for j, h in enumerate(hues)
        ]
        fig.legend(handles, [str(h) for h in hues], loc="upper center",
                   ncol=len(hues), frameon=False, bbox_to_anchor=(0.5, 1.0),
                   handlelength=1.4, columnspacing=1.4)

    if caption:
        fig.text(0.5, 0.012, caption, ha="center", va="bottom",
                 fontsize=mpl.rcParams["font.size"], color="#333333")

    fig.tight_layout(rect=(left, foot, 1, head))

    if save is not None:
        save = Path(save)
        save.parent.mkdir(parents=True, exist_ok=True)
        targets = ([save] if save.suffix.lower() in {".png", ".pdf", ".svg"}
                   else [save.with_suffix(".png"), save.with_suffix(".pdf")])
        for target in targets:
            fig.savefig(target, dpi=dpi)

    return fig, axes, stats
