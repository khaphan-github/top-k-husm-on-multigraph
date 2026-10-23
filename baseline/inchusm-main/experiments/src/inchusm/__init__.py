"""inchusm — Incremental High-Utility Subgraph Mining over snapshot streams.

Python side: bridge to the Java miner (miner_bridge) and the AMLworld loader.
The miner maintains the exact high-utility subgraph set as disjoint add-only
snapshots arrive, without re-mining from scratch.
"""

__version__ = "0.1.0"
