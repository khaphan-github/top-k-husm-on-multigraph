"""Run every dataset converter: one class -> one file in ``benchmark/dataset/formated``.

    python3 benchmark/dataset/converter/convert_all.py
    python3 benchmark/dataset/converter/convert_all.py --only college-msg dblp-multigraph
    python3 benchmark/dataset/converter/convert_all.py --types none      # collapse time types
    python3 benchmark/dataset/converter/convert_all.py --time-buckets 24
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from base_converter import OUT_DIR, BaseConverter
from bn_fly_drosophila_converter import BnFlyDrosophilaConverter
from college_msg_converter import CollegeMsgConverter
from dblp_mappedgraph_converter import DblpMappedgraphConverter
from email_eu_core_temporal_converter import EmailEuCoreTemporalConverter
from sx_askubuntu_converter import SxAskubuntuConverter
from sx_mathoverflow_converter import SxMathoverflowConverter
from sx_superuser_converter import SxSuperuserConverter

CONVERTERS: list[type[BaseConverter]] = [
    CollegeMsgConverter,
    EmailEuCoreTemporalConverter,
    SxAskubuntuConverter,
    SxMathoverflowConverter,
    SxSuperuserConverter,
    BnFlyDrosophilaConverter,
    DblpMappedgraphConverter,
]


def build_converters(args: argparse.Namespace) -> list[BaseConverter]:
    wanted = set(args.only) if args.only else None
    converters: list[BaseConverter] = []
    for cls in CONVERTERS:
        name = cls.dataset_id or cls.__name__
        if wanted is not None and name not in wanted and cls.__name__ not in wanted:
            continue
        kwargs: dict = {"out_dir": args.out_dir, "max_types": args.max_types, "max_mult": args.max_mult}
        if "bucket" in cls.allowed_types:
            kwargs["types"] = args.types
            kwargs["time_buckets"] = args.time_buckets
        converters.append(cls(**kwargs))  # type: ignore[arg-type]
    if wanted is not None and not converters:
        raise SystemExit(f"--only matched nothing; known ids: {[c.dataset_id for c in CONVERTERS]}")
    return converters


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Convert every benchmark dataset to the edge-list format")
    parser.add_argument("--only", nargs="+", default=None, metavar="ID", help="subset of dataset ids")
    parser.add_argument("--out-dir", type=Path, default=OUT_DIR, help=f"output directory (default {OUT_DIR})")
    parser.add_argument("--types", choices=["none", "bucket"], default="bucket",
                        help="edge-type policy for the temporal datasets")
    parser.add_argument("--time-buckets", type=int, default=10,
                        help="timestamp windows for --types bucket")
    parser.add_argument("--max-types", type=int, default=None,
                        help="max distinct edge types per pair, 0 = unlimited (default: per converter)")
    parser.add_argument("--max-mult", type=int, default=None,
                        help="max parallel edges per (pair, type), 0 = unlimited (default: per converter)")
    args = parser.parse_args(argv)

    converters = build_converters(args)
    for conv in converters:
        stats = conv.run()
        print(conv.format_stats(stats), flush=True)
    print(f"\n{len(converters)} file(s) written to {Path(args.out_dir).resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
