#!/usr/bin/env python3
"""Export a trained SSDLite candidate to ONNX and verify runtime equivalence."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn.functional as functional
from PIL import Image
from torchvision.models.detection import ssdlite320_mobilenet_v3_large
from torchvision.transforms.functional import pil_to_tensor

from ml.detection.train_ssdlite import configure_detection_geometry


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ExportWrapper(torch.nn.Module):
    def __init__(self, model: torch.nn.Module) -> None:
        super().__init__()
        self.model = model

    def forward(self, images: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        output = self.model([images[0]])[0]
        return output["boxes"], output["scores"]


def image_tensor(path: Path, size: int) -> torch.Tensor:
    with Image.open(path) as source:
        tensor = pil_to_tensor(source.convert("RGB")).float().div(255)
    return functional.interpolate(
        tensor[None], size=(size, size), mode="bilinear", align_corners=False, antialias=True
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fixture-count", type=int, default=5)
    parser.add_argument("--atol", type=float, default=1e-3)
    args = parser.parse_args()

    run = json.loads(args.run.read_text(encoding="utf-8"))
    geometry = run["detection_geometry"]
    input_size = int(geometry["input_size"])
    threshold = float(run["selected_validation_metrics"]["score_threshold"])
    model = ssdlite320_mobilenet_v3_large(weights=None, weights_backbone=None, num_classes=2)
    configure_detection_geometry(model, input_size, geometry["anchor_scales"])
    model.load_state_dict(torch.load(args.checkpoint, map_location="cpu", weights_only=True))
    wrapper = ExportWrapper(model).eval()

    args.output.mkdir(parents=True, exist_ok=True)
    model_path = args.output / "detector.onnx"
    torch.onnx.export(
        wrapper,
        torch.zeros(1, 3, input_size, input_size),
        model_path,
        input_names=["images"],
        output_names=["boxes", "scores"],
        opset_version=18,
        dynamo=False,
        dynamic_axes={"boxes": {0: "detections"}, "scores": {0: "detections"}},
    )
    exported = onnx.load(model_path)
    onnx.checker.check_model(exported)

    dataset_manifest = args.dataset / "dataset-manifest.json"
    dataset = json.loads(dataset_manifest.read_text(encoding="utf-8"))
    fixtures = sorted(
        (record for record in dataset["records"] if record["split"] == "test"),
        key=lambda record: record["asset_id"],
    )[: args.fixture_count]
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    comparisons = []
    maximum_delta = 0.0
    with torch.inference_mode():
        for record in fixtures:
            tensor = image_tensor(args.dataset / record["image_path"], input_size)
            torch_boxes, torch_scores = wrapper(tensor)
            ort_boxes, ort_scores = session.run(None, {"images": tensor.numpy()})
            box_delta = float(np.max(np.abs(torch_boxes.numpy() - ort_boxes), initial=0.0))
            score_delta = float(np.max(np.abs(torch_scores.numpy() - ort_scores), initial=0.0))
            maximum_delta = max(maximum_delta, box_delta, score_delta)
            comparisons.append(
                {
                    "asset_id": record["asset_id"],
                    "detections": len(ort_scores),
                    "maximum_box_delta": box_delta,
                    "maximum_score_delta": score_delta,
                }
            )
    if maximum_delta > args.atol:
        raise SystemExit(f"ONNX equivalence failed: maximum delta {maximum_delta} exceeds {args.atol}")

    report = {
        "schema_version": "0.1.0",
        "status": "candidate_not_distribution_approved",
        "architecture": run["architecture"],
        "model": {"filename": model_path.name, "sha256": digest(model_path), "size_bytes": model_path.stat().st_size},
        "source": {
            "checkpoint_sha256": digest(args.checkpoint),
            "run_sha256": digest(args.run),
            "dataset_manifest_sha256": digest(dataset_manifest),
            "initialization": run["initialization"],
        },
        "input": {"name": "images", "shape": [1, 3, input_size, input_size], "element_type": "float32", "layout": "NCHW", "color_order": "RGB", "value_scale": "uint8_to_0_1"},
        "outputs": {"boxes": {"name": "boxes", "format": "xyxy"}, "scores": {"name": "scores"}},
        "postprocessing": {"score_threshold": threshold, "foreground": "Lepidoptera"},
        "equivalence": {"absolute_tolerance": args.atol, "maximum_delta": maximum_delta, "fixtures": comparisons},
        "distribution_blocker": "TorchVision COCO detector initialization provenance is not approved for distribution.",
    }
    (args.output / "detector-manifest.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"RESULT OK bytes={model_path.stat().st_size} fixtures={len(fixtures)} maximum_delta={maximum_delta:.8f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
