#!/usr/bin/env python3
"""Rank licensed negative candidates by detector confidence for human review."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import torch
import torch.nn.functional as functional
from PIL import Image, ImageDraw
from torchvision.models.detection import ssdlite320_mobilenet_v3_large
from torchvision.transforms.functional import pil_to_tensor

from ml.detection.propose_boxes import COLORS, review_page
from ml.detection.train_ssdlite import configure_detection_geometry


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample-manifest", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--score-threshold", type=float, default=0.25)
    parser.add_argument("--maximum-candidates", type=int, default=500)
    parser.add_argument("--maximum-proposals", type=int, default=5)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if not 0 < args.score_threshold < 1:
        raise SystemExit("score threshold must be between zero and one")

    sample = json.loads(args.sample_manifest.read_text(encoding="utf-8"))
    run = json.loads(args.run.read_text(encoding="utf-8"))
    geometry = run["detection_geometry"]
    device = torch.device(args.device)
    model = ssdlite320_mobilenet_v3_large(weights=None, weights_backbone=None, num_classes=2)
    configure_detection_geometry(model, int(geometry["input_size"]), geometry["anchor_scales"])
    model.load_state_dict(torch.load(args.checkpoint, map_location="cpu", weights_only=True))
    model.to(device).eval()

    ranked = []
    assets = sample.get("assets", [])
    for position, asset in enumerate(assets, 1):
        path = args.sample_manifest.parent / asset["local_path"]
        if digest(path) != asset["sha256"]:
            raise SystemExit(f"sample identity mismatch: {asset['asset_id']}")
        with Image.open(path) as source:
            image = pil_to_tensor(source.convert("RGB")).float().div(255)
            original_width, original_height = source.size
        height, width = image.shape[-2:]
        if max(height, width) > 640:
            scale = 640 / max(height, width)
            image = functional.interpolate(
                image[None], scale_factor=scale, mode="bilinear", align_corners=False, antialias=True
            )[0]
        with torch.inference_mode():
            prediction = model([image.to(device)])[0]
        keep = (prediction["scores"] >= args.score_threshold).nonzero().flatten()[: args.maximum_proposals]
        if len(keep):
            x_scale = original_width / image.shape[-1]
            y_scale = original_height / image.shape[-2]
            proposals = []
            for index in keep.tolist():
                box = prediction["boxes"][index].detach().cpu().tolist()
                proposals.append(
                    {
                        "box_xyxy": [
                            round(box[0] * x_scale, 3), round(box[1] * y_scale, 3),
                            round(box[2] * x_scale, 3), round(box[3] * y_scale, 3),
                        ],
                        "label": "Lepidoptera",
                        "score": round(float(prediction["scores"][index]), 6),
                    }
                )
            ranked.append((proposals[0]["score"], asset, proposals, original_width, original_height))
        if position % 50 == 0 or position == len(assets):
            print(f"PROGRESS {position}/{len(assets)} detections={len(ranked)}", flush=True)

    selected = sorted(ranked, key=lambda item: (-item[0], item[1]["asset_id"]))[: args.maximum_candidates]
    args.output.mkdir(parents=True, exist_ok=True)
    rendered = args.output / "rendered"
    rendered.mkdir(exist_ok=True)
    annotations = []
    for _, asset, proposals, width, height in selected:
        source_path = args.sample_manifest.parent / asset["local_path"]
        with Image.open(source_path) as source:
            image = source.convert("RGB")
        drawing = ImageDraw.Draw(image)
        for index, proposal in enumerate(proposals):
            color = COLORS[index % len(COLORS)]
            drawing.rectangle(proposal["box_xyxy"], outline=color, width=max(3, min(image.size) // 150))
            drawing.text(
                (proposal["box_xyxy"][0] + 4, proposal["box_xyxy"][1] + 4),
                f'#{index + 1} {proposal["score"]:.2f}', fill=color, stroke_width=2, stroke_fill="black",
            )
        image.thumbnail((1600, 1600))
        review_id = hashlib.sha256((asset["asset_id"] + "\0" + asset["sha256"]).encode()).hexdigest()[:20]
        image.save(rendered / f"{review_id}.jpg", quality=88)
        annotations.append(
            {
                "asset_id": review_id,
                "sample_asset_id": asset["asset_id"],
                "source_name": asset["local_path"],
                "source_sha256": asset["sha256"],
                "class_id": "hard_negative",
                "width": width,
                "height": height,
                "proposals": proposals,
            }
        )

    output = {
        "schema_version": "0.1.0",
        "status": "teacher_proposals_pending_human_review",
        "source": {
            "kind": "sample_manifest",
            "filename": args.sample_manifest.name,
            "sha256": digest(args.sample_manifest),
        },
        "teacher": {
            "kind": "false_positive_mining",
            "checkpoint_sha256": digest(args.checkpoint),
            "run_sha256": digest(args.run),
            "score_threshold": args.score_threshold,
            "detection_geometry": geometry,
        },
        "assets": annotations,
    }
    (args.output / "proposals.json").write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (args.output / "review.html").write_text(review_page(annotations), encoding="utf-8")
    print(f"RESULT OK scanned={len(assets)} detected={len(ranked)} review={len(annotations)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
