"""sx-mathoverflow (SNAP): interactions on MathOverflow, ``u v timestamp``."""

from __future__ import annotations

from temporal_converter import TemporalConverter


class SxMathoverflowConverter(TemporalConverter):
    """sx-mathoverflow -> ``sx-mathoverflow.txt`` (24,818 vertices, 506,550 interactions).

    The raw stream has 116,109 self loops (users answering/commenting on their
    own posts); they are dropped like the artifact's loader does.
    """

    dataset_id = "sx-mathoverflow"
    raw_rel_path = "sx-mathoverflow/sx-mathoverflow.txt"
    description = "sx-mathoverflow: u v timestamp, 24,818 vertices / 506,550 interactions"


if __name__ == "__main__":
    SxMathoverflowConverter().cli()
