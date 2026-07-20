#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def materialize(source: Path, destination: Path) -> None:
    if destination.exists():
        return
    try:
        os.link(source, destination)
    except OSError:
        shutil.copy2(source, destination)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample-manifest", type=Path, action="append", required=True)
    parser.add_argument("--reviewed-dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    reviewed_manifest_path = args.reviewed_dataset / "dataset-manifest.json"
    reviewed_manifest = json.loads(reviewed_manifest_path.read_text(encoding="utf-8"))
    reviewed_records = reviewed_manifest.get("records", [])
    reviewed_hashes: set[str] = set()
    for record in reviewed_records:
        path = args.reviewed_dataset / record["image_path"]
        if not path.is_file() or sha256(path) != record["image_sha256"]:
            raise SystemExit(f"reviewed dataset identity mismatch: {record['asset_id']}")
        reviewed_hashes.add(record["image_sha256"])

    args.output.mkdir(parents=True, exist_ok=True)
    image_root = args.output / "images"
    image_root.mkdir(exist_ok=True)
    assets = []
    seen_hashes = set(reviewed_hashes)
    duplicate_count = 0
    input_records = []
    for manifest_path in args.sample_manifest:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("status") != "unreviewed_detection_pilot_sample":
            raise SystemExit(f"unexpected sample status: {manifest_path}")
        accepted = 0
        source_metadata = manifest.get("source", {})
        for asset in manifest.get("assets", []):
            source = manifest_path.parent / asset["local_path"]
            if not source.is_file() or source.stat().st_size != asset["bytes"] or sha256(source) != asset["sha256"]:
                raise SystemExit(f"sample identity mismatch: {asset['asset_id']}")
            if asset["sha256"] in seen_hashes:
                duplicate_count += 1
                continue
            suffix = source.suffix.lower() if source.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"} else ".img"
            relative = Path("images") / f'{asset["sha256"]}{suffix}'
            materialize(source, args.output / relative)
            assets.append(
                {
                    **asset,
                    "local_path": relative.as_posix(),
                    "figshare_file_id": asset.get("figshare_file_id", 0),
                    "archive_name": asset.get("archive_name", "Wikimedia Commons"),
                    "member": asset.get("member", asset["local_path"]),
                    "license_id": asset.get("license_id", source_metadata.get("license")),
                    "description_url": asset.get(
                        "description_url",
                        f'https://doi.org/{source_metadata["doi"]}' if source_metadata.get("doi") else None,
                    ),
                }
            )
            seen_hashes.add(asset["sha256"])
            accepted += 1
        input_records.append(
            {
                "path": str(manifest_path),
                "sha256": sha256(manifest_path),
                "source_assets": len(manifest.get("assets", [])),
                "accepted_unique_assets": accepted,
            }
        )

    sample_manifest = {
        "schema_version": "0.1.0",
        "status": "unreviewed_detection_pilot_sample",
        "source": {"kind": "combined_licensed_campaign", "inputs": input_records},
        "selection": {
            "method": "union by SHA-256 excluding the existing reviewed dataset",
            "duplicate_count": duplicate_count,
            "reviewed_dataset_sha256": sha256(reviewed_manifest_path),
        },
        "assets": assets,
    }
    sample_path = args.output / "sample-manifest.json"
    sample_path.write_text(json.dumps(sample_manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    campaign = {
        "schema_version": "0.1.0",
        "status": "collected_pending_teacher_proposals",
        "existing_reviewed_images": len(reviewed_records),
        "new_unreviewed_images": len(assets),
        "combined_images": len(reviewed_records) + len(assets),
        "duplicate_images_excluded": duplicate_count,
        "species_in_new_figshare_samples": len({item["class_id"] for item in assets if item["class_id"] not in {"positive", "hard_negative"}}),
        "sample_manifest_sha256": sha256(sample_path),
    }
    (args.output / "campaign.json").write_text(json.dumps(campaign, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"RESULT OK reviewed={len(reviewed_records)} new={len(assets)} combined={campaign['combined_images']} duplicates={duplicate_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
