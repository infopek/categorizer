from __future__ import annotations

import json
import re
import sys
from pathlib import Path


CATALOG_PATH = Path(__file__).with_name("mvp-car-catalog.json")
ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]*$")
REQUIRED = {"class_id", "make", "model", "display_name"}
OPTIONAL = {"generation_label", "approximate_year_range"}


def main() -> int:
    catalog_path = Path(sys.argv[1]) if len(sys.argv) > 1 else CATALOG_PATH
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    if "added_classes" in catalog:
        base = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        if catalog.get("catalog_id") == base.get("catalog_id"):
            if base["classes"][-len(catalog["added_classes"]):] != catalog["added_classes"]:
                print("ERROR accepted catalog suffix does not match amendment")
                return 1
            classes = base["classes"]
        elif catalog.get("base_catalog_id") != base.get("catalog_id"):
            print("ERROR proposal base_catalog_id does not match accepted catalog")
            return 1
        else:
            classes = base["classes"] + catalog["added_classes"]
    else:
        classes = catalog["classes"]
    errors: list[str] = []

    if not 100 <= len(classes) <= 200:
        errors.append(f"class count must be 100-200, got {len(classes)}")
    declared_count = catalog.get("combined_class_count", catalog.get("class_count"))
    if declared_count != len(classes):
        errors.append("declared class_count does not match classes")

    ids: set[str] = set()
    labels: set[str] = set()
    for index, item in enumerate(classes):
        keys = set(item)
        if not REQUIRED <= keys or not keys <= REQUIRED | OPTIONAL:
            errors.append(f"class[{index}] has invalid fields: {sorted(keys)}")
            continue
        if any(not isinstance(item[key], str) or not item[key].strip() for key in REQUIRED):
            errors.append(f"class[{index}] has a blank required value")
        class_id = item["class_id"]
        if not ID_PATTERN.fullmatch(class_id):
            errors.append(f"invalid class_id: {class_id}")
        if class_id in ids:
            errors.append(f"duplicate class_id: {class_id}")
        ids.add(class_id)
        normalized_label = item["display_name"].casefold()
        if normalized_label in labels:
            errors.append(f"duplicate display_name: {item['display_name']}")
        labels.add(normalized_label)
        if "trim" in keys:
            errors.append(f"trim is outside MVP scope: {class_id}")

    if errors:
        for error in errors:
            print(f"ERROR {error}")
        print(f"RESULT FAIL errors={len(errors)}")
        return 1

    makes = {item["make"] for item in classes}
    generation_classes = sum("generation_label" in item for item in classes)
    print(
        f"RESULT OK catalog={catalog['catalog_id']} classes={len(classes)} "
        f"makes={len(makes)} generation_classes={generation_classes}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
