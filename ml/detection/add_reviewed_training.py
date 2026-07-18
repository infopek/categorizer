#!/usr/bin/env python3
"""Add resolved reviewed positives and negatives to a detection training split."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from collections import Counter
from pathlib import Path


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-dataset", type=Path, required=True)
    parser.add_argument("--reviewed", type=Path, required=True)
    parser.add_argument("--sample-manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--negative-validation-count", type=int, default=10)
    args = parser.parse_args()

    base_path = args.base_dataset / "dataset-manifest.json"
    base = json.loads(base_path.read_text(encoding="utf-8"))
    reviewed = json.loads(args.reviewed.read_text(encoding="utf-8"))
    sample = json.loads(args.sample_manifest.read_text(encoding="utf-8"))
    if base.get("status") != "reviewed_detection_dataset" or reviewed.get("status") != "human_review_applied":
        raise SystemExit("input manifest status is invalid")
    samples = {item["asset_id"]: item for item in sample["assets"]}
    images = args.output / "images"
    images.mkdir(parents=True, exist_ok=True)
    records = []
    for record in base["records"]:
        source = args.base_dataset / record["image_path"]
        destination = args.output / record["image_path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        if digest(destination) != record["image_sha256"]:
            raise SystemExit(f"base image identity mismatch: {record['asset_id']}")
        records.append(record)

    added = Counter()
    existing_ids = {record["asset_id"] for record in records}
    resolved = [asset for asset in reviewed["assets"] if asset["review"]["status"] in {"accepted", "not_visible"}]
    ranked_negatives = sorted(
        (asset for asset in resolved if asset["review"]["status"] == "not_visible"),
        key=lambda asset: hashlib.sha256(asset["asset_id"].encode()).digest(),
    )
    validation_negative_ids = {asset["asset_id"] for asset in ranked_negatives[: args.negative_validation_count]}
    for asset in resolved:
        status = asset["review"]["status"]
        if asset["asset_id"] in existing_ids:
            raise SystemExit(f"duplicate asset ID: {asset['asset_id']}")
        source_record = samples[asset["sample_asset_id"]]
        source = args.sample_manifest.parent / source_record["local_path"]
        if digest(source) != source_record["sha256"] or source_record["sha256"] != asset["source_sha256"]:
            raise SystemExit(f"remediation source identity mismatch: {asset['asset_id']}")
        suffix = source.suffix.lower() or ".jpg"
        destination = images / f'{asset["asset_id"]}{suffix}'
        shutil.copy2(source, destination)
        boxes = [{"xyxy": box["box_xyxy"], "label": 1} for box in asset["selected_boxes"]]
        split = "validation" if asset["asset_id"] in validation_negative_ids else "train"
        records.append(
            {
                "asset_id": asset["asset_id"],
                "class_id": source_record["class_id"],
                "split": split,
                "image_path": destination.relative_to(args.output).as_posix(),
                "image_sha256": source_record["sha256"],
                "width": asset["width"],
                "height": asset["height"],
                "boxes": boxes,
                "review_status": status,
                "license_id": source_record["license_id"],
                "description_url": source_record["description_url"],
            }
        )
        existing_ids.add(asset["asset_id"])
        added[status] += 1

    image_counts = Counter(record["split"] for record in records)
    box_counts = Counter()
    for record in records:
        box_counts[record["split"]] += len(record["boxes"])
    output = {
        **base,
        "schema_version": "0.2.0",
        "status": "reviewed_detection_dataset",
        "split": {**base["split"], "image_counts": dict(sorted(image_counts.items())), "box_counts": dict(sorted(box_counts.items()))},
        "remediation": {
            "base_manifest_sha256": digest(base_path),
            "reviewed_manifest_sha256": digest(args.reviewed),
            "sample_manifest_sha256": digest(args.sample_manifest),
            "added_resolved": dict(sorted(added.items())),
            "negative_validation_count": len(validation_negative_ids),
        },
        "records": sorted(records, key=lambda item: item["asset_id"]),
    }
    (args.output / "dataset-manifest.json").write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"RESULT OK images={len(records)} train={image_counts['train']} positives={added['accepted']} negatives={added['not_visible']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
