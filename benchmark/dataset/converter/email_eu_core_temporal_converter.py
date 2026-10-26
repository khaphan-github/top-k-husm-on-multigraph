"""email-Eu-core-temporal (SNAP): internal emails of a European research institute."""

from __future__ import annotations

from temporal_converter import TemporalConverter


class EmailEuCoreTemporalConverter(TemporalConverter):
    """email-Eu-core-temporal -> ``email-eu-core-temporal.txt``.

    The companion file ``email-Eu-core-department-labels.txt`` holds one
    department per person.  Vertex labels are synthesized by the artifact, so
    the department map is not embedded in the edge list.
    """

    dataset_id = "email-eu-core-temporal"
    raw_rel_path = "email-Eu-core-temporal/email-Eu-core-temporal.txt"
    description = (
        "email-Eu-core-temporal: u v timestamp, 986 vertices / 332,334 emails "
        "(department labels are vertex attributes and therefore not emitted)"
    )


if __name__ == "__main__":
    EmailEuCoreTemporalConverter().cli()
