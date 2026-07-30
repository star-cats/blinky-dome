#!/usr/bin/env python3
"""Apply shared BlinkyTriangle display settings to static harness fixtures."""

from __future__ import annotations

import json

from constants import BLINKY_TRIANGLE, FIXTURES_DIR

HARNESS_FILES = [
    "BlinkyH0.lxf",
    "BlinkyH1.lxf",
    "BlinkyH2.lxf",
    "BlinkyH3.lxf",
]


def update_harness(path) -> int:
    fixture = json.loads(path.read_text(encoding="utf-8"))
    updated = 0
    for component in fixture.get("components", []):
        if component.get("type") == "BlinkyTriangle":
            component["hasCustomPointSize"] = True
            component["pointSize"] = BLINKY_TRIANGLE["point_size"]
            updated += 1
    path.write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
    return updated


def main() -> None:
    total = 0
    for file_name in HARNESS_FILES:
        path = FIXTURES_DIR / file_name
        count = update_harness(path)
        total += count
        print(f"Wrote {path} ({count} BlinkyTriangle components)")
    print(f"Updated {total} BlinkyTriangle components")


if __name__ == "__main__":
    main()
