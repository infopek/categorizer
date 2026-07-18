#!/usr/bin/env python3
"""Collect individually licensed Commons candidates for frozen detection evaluation."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from ml.dataset.collect_wikimedia import (
    download_with_backoff,
    https_url,
    license_info,
    plain,
    search_pages,
)

QUERIES = {
    "positive": ("butterfly flower", "butterfly garden", "butterfly nature", "moth tree", "moth outdoor", "moth wall"),
    "hard_negative": ("bee flower", "flower garden", "wildflower meadow", "tree bark closeup", "fallen leaves", "lichen tree"),
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--per-query", type=int, default=10)
    parser.add_argument("--per-status", type=int, default=35)
    parser.add_argument("--search-limit", type=int, default=80)
    parser.add_argument("--max-bytes", type=int, default=20 * 1024 * 1024)
    args = parser.parse_args()
    if args.per_query < 1 or args.per_status < 1 or args.search_limit < args.per_query:
        raise SystemExit("search limit must be at least the positive per-query count")

    args.output.mkdir(parents=True, exist_ok=True)
    manifest_path = args.output / "sample-manifest.json"
    assets = []
    seen_pages: set[int] = set()
    for expected_status, queries in QUERIES.items():
        status_start = len(assets)
        for query in queries:
            accepted = 0
            for page in search_pages(f"{query} filetype:bitmap", args.search_limit):
                if accepted >= args.per_query:
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
                license_id, license_url = approved
                description_url = info["descriptionurl"]
                author = plain(metadata.get("Artist", {}).get("value")) or "Unknown Commons contributor"
                assets.append(
                    {
                        "asset_id": asset_id,
                        "class_id": expected_status,
                        "expected_status": expected_status,
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
                accepted += 1
                manifest = {
                    "schema_version": "0.1.0",
                    "status": "unreviewed_detection_pilot_sample",
                    "purpose": "frozen_cluttered_hard_negative_evaluation",
                    "source": {"name": "Wikimedia Commons", "license_policy": "individual allowlisted license"},
                    "selection": {
                        "queries": QUERIES,
                        "per_query": args.per_query,
                        "per_status": args.per_status,
                        "search_limit": args.search_limit,
                    },
                    "assets": assets,
                }
                manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            print(f"QUERY status={expected_status} query={query!r} accepted={accepted}", flush=True)
            if len(assets) - status_start >= args.per_status:
                break
        if len(assets) - status_start < args.per_status:
            raise SystemExit(f"too few licensed candidates for status: {expected_status}")
    print(f"RESULT OK assets={len(assets)} manifest={manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
