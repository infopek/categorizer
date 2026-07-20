#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=200)
    args = parser.parse_args()
    if args.batch_size < 1:
        raise SystemExit("batch size must be positive")
    source = json.loads(args.manifest.read_text(encoding="utf-8"))
    if source.get("status") != "unreviewed_detection_pilot_sample":
        raise SystemExit("unexpected sample status")
    assets = sorted(source.get("assets", []), key=lambda item: hashlib.sha256(item["asset_id"].encode()).digest())
    args.output.mkdir(parents=True, exist_ok=True)
    batch_count = (len(assets) + args.batch_size - 1) // args.batch_size
    index = {
        "schema_version": "0.1.0",
        "status": "unreviewed_detection_batch_index",
        "source_manifest": str(args.manifest),
        "source_manifest_sha256": hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
        "asset_count": len(assets),
        "batch_size": args.batch_size,
        "batches": [],
    }
    for batch_index in range(batch_count):
        selected = assets[batch_index * args.batch_size : (batch_index + 1) * args.batch_size]
        payload = {
            **source,
            "selection": {
                **source.get("selection", {}),
                "batch_index": batch_index,
                "batch_count": batch_count,
                "batch_method": "ascending SHA-256 rank of asset ID",
            },
            "assets": selected,
        }
        filename = f"batch-{batch_index:03d}.json"
        path = args.output / filename
        path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        index["batches"].append(
            {"index": batch_index, "filename": filename, "asset_count": len(selected), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        )
    (args.output / "index.json").write_text(json.dumps(index, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"RESULT OK assets={len(assets)} batches={batch_count} batch_size={args.batch_size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
