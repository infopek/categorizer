from __future__ import annotations

import json
import re
import sys
from pathlib import Path

CATALOG = Path(__file__).with_name("mvp-lepidoptera-catalog.json")
ID = re.compile(r"^[a-z0-9][a-z0-9-]*$")


def main() -> int:
    value = json.loads(CATALOG.read_text(encoding="utf-8"))
    classes = value.get("classes", [])
    errors: list[str] = []
    if value.get("catalog_id") != "lepidoptera-at-163-v1":
        errors.append("unexpected catalog_id")
    if value.get("category_id") != "lepidoptera":
        errors.append("unexpected category_id")
    if value.get("class_count") != 163 or len(classes) != 163:
        errors.append(f"expected 163 classes, got declared={value.get('class_count')} actual={len(classes)}")
    ids = [item.get("class_id", "") for item in classes]
    names = [item.get("scientific_name", "") for item in classes]
    if len(set(ids)) != len(ids) or any(not ID.fullmatch(item) for item in ids):
        errors.append("class IDs are invalid or duplicated")
    if len(set(name.casefold() for name in names)) != len(names):
        errors.append("scientific names are duplicated")
    if any(item.get("source_image_count", 0) < 50 for item in classes):
        errors.append("class below accepted 50-image threshold")
    if names != sorted(names, key=str.casefold):
        errors.append("classes are not sorted by scientific name")
    if errors:
        for error in errors:
            print(f"ERROR {error}")
        print(f"RESULT FAIL errors={len(errors)}")
        return 1
    print(
        "RESULT OK catalog=lepidoptera-at-163-v1 classes=163 "
        f"source_images={sum(item['source_image_count'] for item in classes)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
