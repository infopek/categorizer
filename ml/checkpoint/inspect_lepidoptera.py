#!/usr/bin/env python3
"""Fail-closed structural inspection for the pinned Lepidoptera checkpoint."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import torch
import torchvision

EXPECTED_SHA256 = "ac3cf138930a8b6f52dcb064ff44ace39b701d3412812e457930463073e5eca0"
EXPECTED_BYTES = 122_783_842


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument(
        "--class-map",
        type=Path,
        default=Path("ml/catalog/lepidoptera-checkpoint-class-map.json"),
    )
    parser.add_argument(
        "--catalog",
        type=Path,
        default=Path("ml/catalog/mvp-lepidoptera-catalog.json"),
    )
    args = parser.parse_args()
    class_map = json.loads(args.class_map.read_text(encoding="utf-8"))
    if args.checkpoint.stat().st_size != EXPECTED_BYTES:
        raise SystemExit("checkpoint byte count does not match pinned artifact")
    if digest(args.checkpoint) != EXPECTED_SHA256:
        raise SystemExit("checkpoint SHA-256 does not match pinned artifact")
    if class_map.get("checkpoint_sha256") != EXPECTED_SHA256:
        raise SystemExit("class map belongs to a different checkpoint")
    classes = class_map.get("classes", [])
    if len(classes) != 163 or [item["index"] for item in classes] != list(range(163)):
        raise SystemExit("class map must contain contiguous indices 0..162")
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    if {item["class_id"] for item in classes} != {
        item["class_id"] for item in catalog.get("classes", [])
    }:
        raise SystemExit("class map and accepted catalog contain different class IDs")
    state = torch.load(args.checkpoint, map_location="cpu", weights_only=True)
    output_shape = tuple(state["classifier.5.weight"].shape)
    if output_shape != (163, 512):
        raise SystemExit(f"unexpected classifier shape: {output_shape}")
    model = torchvision.models.maxvit_t(weights=None, num_classes=163)
    model.load_state_dict(state, strict=True)
    model.eval()
    with torch.inference_mode():
        output = model(torch.zeros(1, 3, 224, 224))
    if tuple(output.shape) != (1, 163) or not torch.isfinite(output).all():
        raise SystemExit("invalid checkpoint inference output")
    print(
        "RESULT OK checkpoint=maxvit_t classes=163 strict_load=true "
        f"parameters={sum(item.numel() for item in model.parameters())}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
