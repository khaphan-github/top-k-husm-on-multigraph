"""sx-superuser (SNAP): interactions on Super User, ``u v timestamp``."""

from __future__ import annotations

from temporal_converter import TemporalConverter


class SxSuperuserConverter(TemporalConverter):
    """sx-superuser -> ``sx-superuser.txt`` (194,085 vertices, 1,443,339 interactions).

    The largest temporal set (~33 MB raw).  Aggregation is done with a single
    packed-int ``Counter`` so the pass stays within a few hundred MB.
    """

    dataset_id = "sx-superuser"
    raw_rel_path = "sx-superuser/sx-superuser.txt"
    description = "sx-superuser: u v timestamp, 194,085 vertices / 1,443,339 interactions"


if __name__ == "__main__":
    SxSuperuserConverter().cli()
