"""sx-askubuntu (SNAP): interactions on Ask Ubuntu, ``u v timestamp``."""

from __future__ import annotations

from temporal_converter import TemporalConverter


class SxAskubuntuConverter(TemporalConverter):
    """sx-askubuntu -> ``sx-askubuntu.txt`` (159,316 vertices, 964,437 interactions).

    Raw ids are sparse (4..515280), so relabelling to ``0..n-1`` matters here:
    without it the artifact would allocate a 515,281-slot array for 159,316
    real vertices and the degree buckets would be filled with holes.
    """

    dataset_id = "sx-askubuntu"
    raw_rel_path = "sx-askubuntu/sx-askubuntu.txt"
    description = "sx-askubuntu: u v timestamp, 159,316 vertices / 964,437 interactions"


if __name__ == "__main__":
    SxAskubuntuConverter().cli()
