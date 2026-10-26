"""CollegeMsg (SNAP): private messages between UCI students, ``u v timestamp``."""

from __future__ import annotations

from temporal_converter import TemporalConverter


class CollegeMsgConverter(TemporalConverter):
    """CollegeMsg -> ``college-msg.txt`` (1,899 vertices, 59,835 messages)."""

    dataset_id = "college-msg"
    raw_rel_path = "CollegeMsg/CollegeMsg.txt"
    description = "CollegeMsg: directed message stream u v timestamp, 1,899 vertices / 59,835 edges"


if __name__ == "__main__":
    CollegeMsgConverter().cli()
