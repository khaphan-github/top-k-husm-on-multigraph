"""Class-based pipeline cho báo cáo benchmark.

Notebook chỉ cần 1 dòng để có tất cả::

    from utils.report import ReportViewer

    report = ReportViewer("benchmark/results/acsd/0519/runs.csv")
    report.coverage()      # dataset nào có MER / K / bound nào
    report.plot_all()      # mỗi dataset một panel, đủ mọi metric
    report.summary()       # bảng peak memory / allocation theo dataset

Toàn bộ phần "khó" (đọc CSV, metric phái sinh, layout, theme, export PNG+PDF)
nằm trong class; ``utils.report_plots`` chỉ còn là lớp vẽ cấp thấp.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Mapping, Sequence

import matplotlib.pyplot as plt
import pandas as pd

from .report_plots import (
    METRICS,
    coverage_table,
    find_repo_root,
    load_runs,
    plot_dataset_grid,
    plot_facet_bars,
)

#: metric phái sinh — chỉ tính lại từ cột có sẵn trong ``runs.csv``
DERIVED_METRICS: dict[str, tuple[str, str]] = {
    "throughput": ("Throughput (patterns/s)", "Throughput"),
    "alloc_kb_per_pattern": ("Allocation per pattern (KB)", "Allocation per pattern"),
    "cpu_wall_ratio": ("CPU / wall ratio", "CPU efficiency"),
    "alloc_over_peak": ("Allocated bytes / peak memory (×)", "Allocation churn"),
}

#: thứ tự vẽ mặc định của ``plot_all()``
DEFAULT_METRICS: tuple[str, ...] = (
    "time_s",
    "mem_logger_mb",
    "mem_mb",
    "patterns",
    "throughput",
    "alloc_kb_per_pattern",
    "cpu_wall_ratio",
    "alloc_over_peak",
)


@dataclass
class ChartConfig:
    """Mọi knob bố cục của một hình — truyền đè khi tạo class hoặc khi gọi ``plot``."""

    dataset: str = "dataset"          # cột nào là "mỗi panel một ..." (trục cột)
    x: str = "K"                      # trục X
    row: str | None = "mer"           # dòng = MER (None = chỉ 1 dòng)
    hue: str | None = "ieu_bound"     # thanh trong panel: IVU / EU / CEU
    hue_order: Sequence[str] | None = ("IVU", "EU", "CEU")
    dataset_order: Sequence[str] | None = None
    row_order: Sequence[float] | None = None
    x_order: Sequence[float] | None = None
    row_name: str | None = None
    x_label: str | None = None
    xtick_format: str = "K={:g}"
    share_y: str = "row"              # "row" | "all" | "none"
    max_cols: int = 4                 # số cột dataset tối đa trước khi xuống dòng
    log: bool | str = "auto"
    error: str = "none"               # "none" | "sd"
    panel_size: tuple[float, float] = (3.2, 2.5)
    legend: bool = True


class ReportViewer:
    """Đọc ``runs.csv`` và vẽ toàn bộ hình/bảng của report.

    Parameters
    ----------
    source:
        Đường dẫn ``runs.csv`` (hoặc list nhiều file). Tương đối thì tự tìm
        từ cwd/repo root.
    only_datasets:
        Lọc dataset, ``None`` = lấy hết (mỗi dataset một panel).
    status_ok_only:
        Chỉ giữ dòng ``status == ok``.
    out_dir:
        Nơi lưu PNG/PDF; mặc định ``<repo>/benchmark/visualize/out/report``.
    save:
        Bật/tắt export (mặc định bật, 300 dpi, PNG + PDF).
    config / **chart:
        Ghi đè :class:`ChartConfig`, ví dụ ``ReportViewer(csv, max_cols=3,
        share_y="none")``.
    """

    MAIN_METRICS: Mapping[str, tuple[str, str]] = METRICS
    DERIVED_METRICS: Mapping[str, tuple[str, str]] = DERIVED_METRICS

    def __init__(self, source, *, only_datasets: Sequence[str] | None = None,
                 status_ok_only: bool = True, out_dir: str | Path | None = None,
                 root: str | Path | None = None, save: bool = True,
                 dpi: int = 300, config: ChartConfig | None = None,
                 **chart: Any):
        self.cfg = replace(config or ChartConfig(), **chart)
        self.source = source
        self.root = find_repo_root(root or Path.cwd())
        self.out_dir = Path(out_dir) if out_dir is not None else (
            self.root / "benchmark" / "visualize" / "out" / "report"
        )
        self.save = bool(save)
        self.dpi = int(dpi)

        self.df_raw = load_runs(source, status_ok_only=status_ok_only,
                                root=self.root)
        if only_datasets:
            keep = {str(d) for d in only_datasets}
            self.df_raw = self.df_raw[self.df_raw[self.cfg.dataset].astype(str).isin(keep)]
        self.df_raw = self.df_raw.reset_index(drop=True)
        if self.df_raw.empty:
            raise ValueError(f"Không có dòng dữ liệu nào sau khi lọc: {source}")

        self.df = self._add_derived(self.df_raw.copy())
        self.datasets: list[str] = sorted(
            self.df_raw[self.cfg.dataset].dropna().astype(str).unique().tolist()
        )
        self.figures: dict[str, Any] = {}
        self.stats: dict[str, pd.DataFrame] = {}

    # ------------------------------------------------------------- dữ liệu
    @staticmethod
    def _add_derived(df: pd.DataFrame) -> pd.DataFrame:
        """Metric phái sinh — không đụng code thuật toán, chỉ chia cột có sẵn."""
        def _div(num: pd.Series, den: pd.Series) -> pd.Series:
            return num / den.where(den > 0)

        if {"evaluated", "time_s"} <= set(df.columns):
            df["throughput"] = _div(df["evaluated"], df["time_s"])
        if {"alloc_bytes", "evaluated"} <= set(df.columns):
            df["alloc_kb_per_pattern"] = _div(df["alloc_bytes"], df["evaluated"]) / 1024
        if {"cpu_ms", "wall_ms"} <= set(df.columns):
            df["cpu_wall_ratio"] = _div(df["cpu_ms"], df["wall_ms"])
        if {"alloc_bytes", "mem_logger_peak_bytes"} <= set(df.columns):
            df["alloc_over_peak"] = _div(df["alloc_bytes"], df["mem_logger_peak_bytes"])
        return df

    @property
    def n_datasets(self) -> int:
        return len(self.datasets)

    @property
    def n_rows(self) -> int:
        return int(len(self.df))

    def metrics(self) -> dict[str, tuple[str, str]]:
        """metric khả dụng -> (ylabel, tên ngắn) — bỏ metric không có dữ liệu."""
        out: dict[str, tuple[str, str]] = {}
        for key, meta in {**METRICS, **DERIVED_METRICS}.items():
            if key in self.df.columns and self.df[key].notna().any():
                out[key] = meta
        return out

    def coverage(self) -> pd.DataFrame:
        """Dataset nào có bao nhiêu MER / K / bound — xem trước khi vẽ."""
        return coverage_table(self.df, dataset=self.cfg.dataset, row=self.cfg.row,
                              x=self.cfg.x, hue=self.cfg.hue)

    def describe(self) -> pd.DataFrame:
        """Thống kê nhanh các metric phái sinh."""
        cols = [c for c in DERIVED_METRICS if c in self.df.columns]
        return self.df[cols].describe().T.round(2)

    def summary(self) -> pd.DataFrame:
        """Bảng đối chiếu bộ nhớ theo dataset/MER/K/bound.

        ``peak_memory`` = ``mem_logger_mb`` (convention SPMF MemoryLogger) —
        con số dùng trong paper; ``peak_heap_mxbean`` và ``allocated`` chỉ tham khảo.
        """
        cols = [c for c in ("mem_logger_mb", "heap_mb", "mem_mb")
                if c in self.df.columns]
        keys = [k for k in (self.cfg.dataset, self.cfg.row, self.cfg.x, self.cfg.hue) if k]
        tbl = self.df.groupby(keys, observed=True)[cols].mean().round(1)
        tbl = tbl.rename(columns={"mem_logger_mb": "peak_memory",
                                  "heap_mb": "peak_heap_mxbean",
                                  "mem_mb": "allocated"})
        if {"allocated", "peak_memory"} <= set(tbl.columns):
            tbl["alloc/peak_memory"] = (
                tbl["allocated"] / tbl["peak_memory"].where(tbl["peak_memory"] > 0)
            ).round(1)
        return tbl

    # ---------------------------------------------------------------- hình
    def _target(self, name: str, suffix: str = "per_dataset"):
        return self.out_dir / f"{name}_{suffix}" if self.save else None

    def plot(self, metric: str, ylabel: str | None = None, *,
             caption: str | None = None, name: str | None = None,
             show: bool = True, save: bool | None = None,
             **overrides) -> tuple[Any, Any, pd.DataFrame]:
        """Vẽ 1 metric: cột = dataset, dòng = MER, trục X = K, thanh = IVU/EU.

        ``overrides`` ghi đè :class:`ChartConfig` cho riêng hình này, ví dụ
        ``report.plot("mem_logger_mb", share_y="none", max_cols=2)``.
        """
        available = self.metrics()
        if metric not in self.df.columns:
            raise KeyError(f"Không có metric '{metric}'. Có: {sorted(available)}")

        cfg = replace(self.cfg, **overrides) if overrides else self.cfg
        ylab, short = available.get(metric, (ylabel or metric, metric))
        ylab = ylabel or ylab
        key = name or metric

        if caption is None:
            caption = (f"{short} theo {cfg.x_label or cfg.x.upper()}"
                       + (f" và {cfg.row_name or cfg.row.upper()}" if cfg.row else "")
                       + f" — {self.n_datasets} dataset, mỗi dataset một panel.")

        want_save = self.save if save is None else save
        fig, axes, stats = plot_dataset_grid(
            self.df, metric, ylab,
            dataset=cfg.dataset, x=cfg.x, hue=cfg.hue, row=cfg.row,
            dataset_order=cfg.dataset_order, row_order=cfg.row_order,
            x_order=cfg.x_order, hue_order=cfg.hue_order,
            row_name=cfg.row_name, x_label=cfg.x_label,
            xtick_format=cfg.xtick_format, share_y=cfg.share_y,
            max_cols=cfg.max_cols, log=cfg.log, error=cfg.error,
            panel_size=cfg.panel_size, legend=cfg.legend,
            caption=caption,
            save=self.out_dir / f"{key}_per_dataset" if want_save else None,
            dpi=self.dpi,
        )
        self.figures[key] = fig
        self.stats[key] = stats
        if show:
            plt.show()
        return fig, axes, stats

    def plot_pooled(self, metric: str, ylabel: str | None = None, *,
                    facet: str | None = None, caption: str | None = None,
                    name: str | None = None, show: bool = True,
                    save: bool | None = None) -> tuple[Any, Any, pd.DataFrame]:
        """Biểu đồ gộp mọi dataset (kiểu cũ) để đối chiếu."""
        available = self.metrics()
        if metric not in self.df.columns:
            raise KeyError(f"Không có metric '{metric}'. Có: {sorted(available)}")

        facet = facet or self.cfg.row or self.cfg.dataset
        ylab, short = available.get(metric, (ylabel or metric, metric))
        ylab = ylabel or ylab
        key = name or f"{metric}_pooled"

        want_save = self.save if save is None else save
        fig, axes, stats = plot_facet_bars(
            self.df, metric, ylab,
            facet=facet, x=self.cfg.x, hue=self.cfg.hue,
            facet_order=self.cfg.row_order, x_order=self.cfg.x_order,
            hue_order=self.cfg.hue_order,
            facet_name=facet.upper(), x_label=self.cfg.x_label,
            xtick_format=self.cfg.xtick_format,
            log=self.cfg.log, error=self.cfg.error,
            panel_size=self.cfg.panel_size, legend=self.cfg.legend,
            caption=caption or (
                f"{short} theo {self.cfg.x_label or self.cfg.x.upper()}"
                f" và {facet.upper()} (gộp mọi dataset)."
            ),
            save=self.out_dir / f"{key}" if want_save else None,
            dpi=self.dpi,
        )
        self.figures[key] = fig
        self.stats[key] = stats
        if show:
            plt.show()
        return fig, axes, stats

    def plot_all(self, metrics: Sequence[str] | None = None, *,
                 show: bool = True, close: bool = False) -> dict[str, Any]:
        """Vẽ tất cả metric (mặc định: :data:`DEFAULT_METRICS` có dữ liệu)."""
        names = [m for m in (metrics or DEFAULT_METRICS) if m in self.df.columns]
        for metric in names:
            self.plot(metric, show=False)
        if show:
            plt.show()
        if close:
            for fig in self.figures.values():
                plt.close(fig)
        return self.figures

    # ------------------------------------------------------------- tiện ích
    def save_summary(self, name: str = "memory_summary.csv") -> Path:
        path = self.out_dir / name
        path.parent.mkdir(parents=True, exist_ok=True)
        self.summary().to_csv(path)
        return path

    def close(self) -> None:
        for fig in self.figures.values():
            plt.close(fig)

    def __repr__(self) -> str:
        return (f"ReportViewer(datasets={self.n_datasets} {self.datasets}, "
                f"rows={self.n_rows:,}, metrics={len(self.metrics())}, "
                f"out_dir={self.out_dir})")
