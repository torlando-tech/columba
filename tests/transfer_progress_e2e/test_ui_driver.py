from __future__ import annotations

from ui_driver import UiSnapshot


SAMPLE_XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node text="" content-desc="Attach" bounds="[900,1700][1000,1800]" clickable="true" />
  <node text="" content-desc="Transferring Resource, 10 percent" bounds="[480,1180][1020,1300]" clickable="false" />
  <node text="Transferring Resource" content-desc="" bounds="[500,1200][850,1260]" clickable="false" />
  <node text="10%" content-desc="" bounds="[900,1200][1000,1260]" clickable="false" />
</hierarchy>
"""


def test_snapshot_finds_text_and_content_description() -> None:
    snapshot = UiSnapshot.parse(SAMPLE_XML)

    assert snapshot.require_text("Transferring Resource").center == (675, 1230)
    assert snapshot.require_description("Attach").center == (950, 1750)


def test_snapshot_extracts_semantic_percentage() -> None:
    snapshot = UiSnapshot.parse(SAMPLE_XML)

    assert snapshot.semantic_percentage("Transferring Resource") == 10


def test_snapshot_rejects_percentage_from_unrelated_semantics() -> None:
    snapshot = UiSnapshot.parse(SAMPLE_XML)

    assert snapshot.semantic_percentage("Receiving messages") is None
