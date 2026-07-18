#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import torch
import torchvision


CHECKPOINT_SHA256 = "ac3cf138930a8b6f52dcb064ff44ace39b701d3412812e457930463073e5eca0"
CLASS_COUNT = 163
TENSOR_SIZE = 3 * 224 * 224


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fixture(name: str, fill: float, overrides: dict[int, float]) -> tuple[str, torch.Tensor, dict[str, object]]:
    values = torch.full((TENSOR_SIZE,), fill, dtype=torch.float32)
    for index, value in overrides.items():
        values[index] = value
    recipe = {
        "name": name,
        "fill": fill,
        "overrides": [{"index": index, "value": value} for index, value in sorted(overrides.items())],
    }
    return name, values.reshape(1, 3, 224, 224), recipe


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if sha256(args.checkpoint) != CHECKPOINT_SHA256:
        raise SystemExit("checkpoint SHA-256 mismatch")

    model = torchvision.models.maxvit_t(weights=None, num_classes=CLASS_COUNT)
    model.load_state_dict(torch.load(args.checkpoint, map_location="cpu", weights_only=True), strict=True)
    model.eval()
    fixtures = [
        fixture("zeros", 0.0, {}),
        fixture("ones", 1.0, {}),
        fixture("negative-ones", -1.0, {}),
        fixture("sparse", 0.0, {0: 1.0, 50176: -1.0, 100352: 0.5, TENSOR_SIZE - 1: -0.5}),
    ]
    output = []
    with torch.inference_mode():
        for _, tensor, recipe in fixtures:
            logits = model(tensor)[0]
            ranking = sorted(range(CLASS_COUNT), key=lambda index: (-float(logits[index]), index))
            output.append({**recipe, "expected_logits": logits.tolist(), "expected_top_five": ranking[:5]})
    payload = {
        "schema_version": "1.0.0",
        "reference_runtime": {"torch": torch.__version__, "torchvision": torchvision.__version__},
        "checkpoint_sha256": CHECKPOINT_SHA256,
        "input_shape": [1, 3, 224, 224],
        "absolute_tolerance": 0.0001,
        "fixtures": output,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
