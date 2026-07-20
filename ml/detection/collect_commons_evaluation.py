#!/usr/bin/env python3
"""Collect individually licensed Commons candidates for frozen detection evaluation."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from ml.dataset.collect_wikimedia import (
    category_file_pages,
    download_with_backoff,
    https_url,
    license_info,
    plain,
    search_pages,
)

CATEGORY_ROOTS = {
    "training": {
        "positive": ("Butterflies", "Moths"),
        "hard_negative": ("Bees", "Hoverflies", "Dragonflies", "Flowers", "Leaves", "Tree bark"),
    },
    "hard-negative-mining": {
        "hard_negative": (
            "Bees", "Hoverflies", "Wasps", "Dragonflies", "Damselflies", "Beetles",
            "Flowers", "Leaves", "Tree bark", "Lichens", "Spiders", "Birds",
        ),
    },
    "targeted-final": {
        "positive": ("Butterflies", "Moths"),
        "hard_negative": (
            "Bees", "Hoverflies", "Dragonflies", "Damselflies", "Beetles", "Flowers",
            "Leaves", "Tree bark", "Lichens", "Spiders",
        ),
    },
}

PROFILES = {
    "evaluation": {
        "positive": ("butterfly flower", "butterfly garden", "butterfly nature", "moth tree", "moth outdoor", "moth wall"),
        "hard_negative": ("bee flower", "flower garden", "wildflower meadow", "tree bark closeup", "fallen leaves", "lichen tree"),
    },
    "remediation": {
        "positive": ("multiple butterflies", "butterfly group", "butterflies flower", "small butterfly meadow", "moths light", "two butterflies"),
        "hard_negative": ("bee closeup", "dragonfly", "flower closeup", "dry leaves", "tree bark lichen", "bird flower"),
    },
    "training": {
        "positive": (
            "butterfly flower", "butterfly garden", "butterfly meadow", "butterfly forest",
            "butterfly wildlife", "butterfly nature", "butterfly leaf", "butterfly bark",
            "butterfly camouflage", "butterfly wings closed", "butterfly underside",
            "butterfly flying", "butterfly distant", "small butterfly flower",
            "small butterfly meadow", "tiny butterfly", "butterfly background",
            "butterfly partially hidden", "butterfly foliage", "butterfly grass",
            "multiple butterflies", "butterfly group", "two butterflies", "butterflies puddling",
            "butterflies flower", "butterfly swarm", "moth outdoor", "moth tree", "moth bark",
            "moth wall", "moth grass", "moth foliage", "moth camouflage", "moth flying",
            "moth at light", "small moth", "multiple moths", "two moths", "moths group",
            "lepidoptera habitat",
        ),
        "hard_negative": (
            "bee flower", "bee closeup", "bumblebee flower", "hoverfly flower",
            "dragonfly", "damselfly", "beetle flower", "grasshopper leaf",
            "flower closeup", "wildflower meadow", "flower garden", "orchid closeup",
            "fallen leaves", "dry leaves", "leaf closeup", "tree bark closeup",
            "tree bark lichen", "lichen tree", "bird flower", "spider flower",
        ),
    },
    "hard-negative-mining": {
        "hard_negative": (
            "bee on flower", "bumblebee flower", "hoverfly closeup", "wasp flower",
            "dragonfly leaf", "damselfly grass", "beetle flower", "grasshopper leaf",
            "spider flower", "bird flower", "flower closeup", "wildflower meadow",
            "orchid closeup", "autumn leaves", "dry leaves", "leaf closeup",
            "tree bark closeup", "tree bark lichen", "lichen closeup", "mushroom forest",
            "seed pod closeup", "feather closeup", "camouflage insect", "garden foliage",
        ),
    },
    "targeted-final": {
        "positive": (
            "single small butterfly foliage", "single butterfly wildflower meadow",
            "single butterfly camouflage", "single moth tree bark", "single moth foliage",
            "distant butterfly flower", "partially hidden butterfly leaf", "small moth outdoor",
        ),
        "hard_negative": (
            "bee flower", "hoverfly flower", "dragonfly leaf", "damselfly grass",
            "beetle flower", "spider flower", "flower closeup", "wildflower meadow",
            "dry leaves", "leaf closeup", "tree bark closeup", "lichen closeup",
            "seed pod closeup", "feather closeup", "garden foliage",
        ),
    },
}

QUERY_SLICES = {
    "small": ("small ", "tiny ", " distant", " background"),
    "multiple": ("multiple ", " group", "two ", "puddling", "swarm"),
    "occluded": ("camouflage", "hidden", "foliage", "bark", "grass", "wings closed", "underside"),
}


def selection_slice(status: str, query: str) -> str:
    if status == "hard_negative":
        return "hard_negative"
    for name, markers in QUERY_SLICES.items():
        if any(marker in query for marker in markers):
            return name
    return "ordinary"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--per-query", type=int, default=10)
    parser.add_argument("--per-status", type=int, default=35)
    parser.add_argument("--positive-count", type=int)
    parser.add_argument("--hard-negative-count", type=int)
    parser.add_argument("--search-limit", type=int, default=80)
    parser.add_argument("--max-bytes", type=int, default=20 * 1024 * 1024)
    parser.add_argument("--profile", choices=tuple(PROFILES), default="evaluation")
    parser.add_argument("--exclude-manifest", type=Path, action="append", default=[])
    parser.add_argument("--delay", type=float, default=0.25)
    parser.add_argument("--category-depth", type=int, default=2)
    args = parser.parse_args()
    if args.per_query < 1 or args.per_status < 1 or args.search_limit < args.per_query:
        raise SystemExit("search limit must be at least the positive per-query count")

    args.output.mkdir(parents=True, exist_ok=True)
    manifest_path = args.output / "sample-manifest.json"
    if manifest_path.exists():
        existing_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        assets = existing_manifest.get("assets", [])
    else:
        assets = []
    seen_pages: set[int] = set()
    seen_hashes = {item["sha256"] for item in assets}
    seen_pages.update(int(item["figshare_file_id"]) for item in assets)
    exclusions = []
    for path in args.exclude_manifest:
        excluded = json.loads(path.read_text(encoding="utf-8"))
        page_ids = {int(item["figshare_file_id"]) for item in excluded["assets"]}
        seen_pages.update(page_ids)
        exclusions.append({"filename": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "asset_count": len(page_ids)})
    queries_by_status = PROFILES[args.profile]
    quotas = {
        "positive": args.positive_count or args.per_status,
        "hard_negative": args.hard_negative_count or args.per_status,
    }
    for expected_status, queries in queries_by_status.items():
        category_queries = tuple(
            f"category:{name}" for name in CATEGORY_ROOTS.get(args.profile, {}).get(expected_status, ())
        )
        queries = category_queries + queries
        status_count = sum(item["expected_status"] == expected_status for item in assets)
        for query in queries:
            if status_count >= quotas[expected_status]:
                break
            accepted = 0
            query_limit = min(args.per_query, quotas[expected_status] - status_count)
            pages = (
                category_file_pages([query.removeprefix("category:")], args.category_depth)
                if query.startswith("category:")
                else search_pages(f"{query} filetype:bitmap", args.search_limit)
            )
            for page in pages:
                if accepted >= query_limit:
                    break
                page_id = int(page["pageid"])
                if page_id in seen_pages:
                    continue
                info = (page.get("imageinfo") or [{}])[0]
                metadata = info.get("extmetadata", {})
                approved = license_info(metadata)
                mime = info.get("mime")
                if approved is None or mime not in {"image/jpeg", "image/png", "image/webp"}:
                    continue
                extension = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}[mime]
                asset_id = f"commons-{page_id}"
                relative = Path(expected_status) / f"{asset_id}{extension}"
                destination = args.output / relative
                destination.parent.mkdir(exist_ok=True)
                try:
                    digest = download_with_backoff(info.get("thumburl") or info["url"], destination, args.max_bytes)
                    with Image.open(destination) as image:
                        image.verify()
                        width, height = image.size
                except Exception as error:
                    destination.unlink(missing_ok=True)
                    print(f"SKIP page={page_id} error={error}", flush=True)
                    continue
                if digest in seen_hashes:
                    destination.unlink(missing_ok=True)
                    seen_pages.add(page_id)
                    continue
                license_id, license_url = approved
                description_url = info["descriptionurl"]
                author = plain(metadata.get("Artist", {}).get("value")) or "Unknown Commons contributor"
                assets.append(
                    {
                        "asset_id": asset_id,
                        "class_id": expected_status,
                        "expected_status": expected_status,
                        "selection_slice": selection_slice(expected_status, query),
                        "query": query,
                        "local_path": relative.as_posix(),
                        "sha256": digest,
                        "bytes": destination.stat().st_size,
                        "width": width,
                        "height": height,
                        "figshare_file_id": page_id,
                        "archive_name": "Wikimedia Commons",
                        "member": page["title"],
                        "original_source_url": info["url"],
                        "description_url": description_url,
                        "author": author,
                        "license_id": license_id,
                        "license_url": https_url(metadata.get("LicenseUrl", {}).get("value"), license_url),
                        "attribution": f'{page["title"]} — {author} — {license_id} — {description_url}',
                        "retrieved_at": datetime.now(timezone.utc).isoformat(),
                    }
                )
                seen_pages.add(page_id)
                seen_hashes.add(digest)
                accepted += 1
                status_count += 1
                manifest = {
                    "schema_version": "0.1.0",
                    "status": "unreviewed_detection_pilot_sample",
                    "purpose": (
                        "frozen_cluttered_hard_negative_evaluation"
                        if args.profile == "evaluation"
                        else "detector_false_positive_mining_candidates"
                        if args.profile == "hard-negative-mining"
                        else "detection_remediation_training_candidates"
                    ),
                    "source": {"name": "Wikimedia Commons", "license_policy": "individual allowlisted license"},
                    "selection": {
                        "profile": args.profile,
                        "queries": queries_by_status,
                        "per_query": args.per_query,
                        "per_status": args.per_status,
                        "quotas": quotas,
                        "search_limit": args.search_limit,
                        "exclusions": exclusions,
                    },
                    "assets": assets,
                }
                manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
                time.sleep(args.delay)
            print(f"QUERY status={expected_status} query={query!r} accepted={accepted}", flush=True)
        if status_count < quotas[expected_status]:
            raise SystemExit(f"too few licensed candidates for status: {expected_status}")
    print(f"RESULT OK assets={len(assets)} manifest={manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
