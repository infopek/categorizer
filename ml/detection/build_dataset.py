#!/usr/bin/env python3
"""Materialize reviewed box manifests into a deterministic detection dataset."""
from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from collections import defaultdict
from pathlib import Path

from PIL import Image


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ranked(assets: list[dict[str, object]], seed: str) -> list[dict[str, object]]:
    return sorted(
        assets,
        key=lambda item: hashlib.sha256(f'{seed}\0{item["asset_id"]}'.encode()).digest(),
    )


def source_content(asset: dict[str, object], source: Path) -> bytes:
    if source.is_dir():
        relative = Path(str(asset["source_name"]))
        path = (source / relative).resolve()
        if not path.is_relative_to(source.resolve()) or not path.is_file():
            raise SystemExit(f"invalid source path: {relative}")
        return path.read_bytes()
    if source.is_file() and zipfile.is_zipfile(source):
        with zipfile.ZipFile(source) as archive:
            return archive.read(str(asset["source_name"]))
    raise SystemExit(f"source must be a directory or ZIP: {source}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reviewed", type=Path, action="append", required=True)
    parser.add_argument("--source", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", default="detection-split-v1")
    args = parser.parse_args()
    if len(args.reviewed) != len(args.source):
        raise SystemExit("each reviewed manifest requires one matching source")

    inputs = []
    eligible = []
    seen_assets = set()
    for manifest_path, source_path in zip(args.reviewed, args.source, strict=True):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("status") != "human_review_applied":
            raise SystemExit(f"reviewed manifest has unexpected status: {manifest_path}")
        inputs.append(
            {
                "reviewed_manifest": manifest_path.name,
                "reviewed_manifest_sha256": digest(manifest_path),
                "source": source_path.name,
            }
        )
        for asset in manifest["assets"]:
            if not asset["training_eligible"]:
                continue
            if asset["asset_id"] in seen_assets:
                raise SystemExit(f"duplicate asset ID: {asset['asset_id']}")
            seen_assets.add(asset["asset_id"])
            boxes = asset.get("selected_boxes", [])
            if not boxes:
                raise SystemExit(f"eligible asset has no boxes: {asset['asset_id']}")
            content = source_content(asset, source_path)
            if hashlib.sha256(content).hexdigest() != asset["source_sha256"]:
                raise SystemExit(f"source hash mismatch: {asset['asset_id']}")
            eligible.append({**asset, "_content": content})

    by_class: dict[str, list[dict[str, object]]] = defaultdict(list)
    for asset in eligible:
        by_class[str(asset.get("class_id", "polyommatus-eros"))].append(asset)
    assignments = {}
    for class_id, assets in sorted(by_class.items()):
        ordered = ranked(assets, f"{args.seed}:{class_id}")
        if len(ordered) < 3:
            raise SystemExit(f"class has fewer than three eligible images: {class_id}")
        validation_count = max(1, round(len(ordered) * 0.15))
        test_count = max(1, round(len(ordered) * 0.15))
        if validation_count + test_count >= len(ordered):
            validation_count = test_count = 1
        for index, asset in enumerate(ordered):
            if index < validation_count:
                split = "validation"
            elif index < validation_count + test_count:
                split = "test"
            else:
                split = "train"
            assignments[asset["asset_id"]] = split

    images_directory = args.output / "images"
    images_directory.mkdir(parents=True, exist_ok=True)
    records = []
    clamped_box_count = 0
    for asset in sorted(eligible, key=lambda item: item["asset_id"]):
        content = asset.pop("_content")
        suffix = Path(str(asset["source_name"])).suffix.lower() or ".jpg"
        filename = f'{asset["asset_id"]}{suffix}'
        destination = images_directory / filename
        destination.write_bytes(content)
        with Image.open(destination) as image:
            width, height = image.size
        boxes = []
        for selected in asset["selected_boxes"]:
            x1, y1, x2, y2 = [float(value) for value in selected["box_xyxy"]]
            overflow = max(0.0, -x1, -y1, x2 - width, y2 - height)
            if overflow > 1.0:
                raise SystemExit(f"box outside image: {asset['asset_id']}")
            if overflow > 0:
                clamped_box_count += 1
            x1, y1 = max(0.0, x1), max(0.0, y1)
            x2, y2 = min(float(width), x2), min(float(height), y2)
            if not (x1 < x2 and y1 < y2):
                raise SystemExit(f"box has no positive area: {asset['asset_id']}")
            boxes.append({"xyxy": [x1, y1, x2, y2], "label": 1})
        records.append(
            {
                "asset_id": asset["asset_id"],
                "class_id": asset.get("class_id", "polyommatus-eros"),
                "split": assignments[asset["asset_id"]],
                "image_path": f"images/{filename}",
                "image_sha256": hashlib.sha256(content).hexdigest(),
                "width": width,
                "height": height,
                "boxes": boxes,
            }
        )

    split_counts = defaultdict(int)
    box_counts = defaultdict(int)
    for record in records:
        split_counts[record["split"]] += 1
        box_counts[record["split"]] += len(record["boxes"])
    output = {
        "schema_version": "0.1.0",
        "status": "reviewed_detection_dataset",
        "task": "one-class-lepidoptera-detection",
        "foreground_label": {"index": 1, "name": "Lepidoptera"},
        "split": {
            "seed": args.seed,
            "method": "per-source-class ascending SHA-256 rank; validation then test then train",
            "image_counts": dict(sorted(split_counts.items())),
            "box_counts": dict(sorted(box_counts.items())),
        },
        "inputs": inputs,
        "subpixel_clamped_box_count": clamped_box_count,
        "records": records,
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "dataset-manifest.json").write_text(
        json.dumps(output, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"RESULT OK images={len(records)} boxes={sum(len(x['boxes']) for x in records)} "
        + " ".join(f"{key}={value}" for key, value in sorted(split_counts.items()))
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
