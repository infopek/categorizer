#!/usr/bin/env python3
"""Evaluate an SSDLite checkpoint on reviewed positives and hard negatives."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

import torch
from torch.utils.data import DataLoader
from torchvision.models.detection import ssdlite320_mobilenet_v3_large

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from ml.detection.train_ssdlite import DetectionDataset, collate, metrics, predict  # noqa: E402


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reviewed", type=Path, required=True)
    parser.add_argument("--sample-manifest", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--score-threshold", type=float, default=0.25)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if not 0 < args.score_threshold < 1:
        raise SystemExit("score threshold must be between zero and one")

    reviewed = json.loads(args.reviewed.read_text(encoding="utf-8"))
    sample = json.loads(args.sample_manifest.read_text(encoding="utf-8"))
    if reviewed.get("status") != "human_review_applied":
        raise SystemExit("reviewed manifest has an unexpected status")
    samples = {item["asset_id"]: item for item in sample["assets"]}
    records = []
    excluded = {}
    for asset in reviewed["assets"]:
        status = asset["review"]["status"]
        if status not in {"accepted", "not_visible"}:
            excluded[status] = excluded.get(status, 0) + 1
            continue
        source = samples[asset["sample_asset_id"]]
        path = args.sample_manifest.parent / source["local_path"]
        if digest(path) != source["sha256"] or source["sha256"] != asset["source_sha256"]:
            raise SystemExit(f"source identity mismatch: {asset['asset_id']}")
        records.append(
            {
                "asset_id": asset["asset_id"],
                "image_path": source["local_path"],
                "width": asset["width"],
                "height": asset["height"],
                "boxes": [{"xyxy": box["box_xyxy"], "label": 1} for box in asset["selected_boxes"]],
                "review_status": status,
                "license_id": source["license_id"],
            }
        )
    dataset = DetectionDataset(args.sample_manifest.parent, records)
    loader = DataLoader(dataset, batch_size=args.batch_size, num_workers=args.workers, collate_fn=collate)
    device = torch.device(args.device)
    model = ssdlite320_mobilenet_v3_large(weights=None, weights_backbone=None, num_classes=2)
    model.load_state_dict(torch.load(args.checkpoint, map_location="cpu", weights_only=True))
    model.to(device)
    if device.type == "cuda":
        torch.cuda.synchronize()
    started = time.perf_counter()
    predictions = predict(model, loader, device)
    if device.type == "cuda":
        torch.cuda.synchronize()
    elapsed = time.perf_counter() - started

    positives = [(prediction, target) for record, (prediction, target) in zip(records, predictions, strict=True) if record["boxes"]]
    negatives = [prediction for record, (prediction, _) in zip(records, predictions, strict=True) if not record["boxes"]]
    positive_metrics = metrics(positives, args.score_threshold)
    negative_detections = [int((prediction["scores"] >= args.score_threshold).sum()) for prediction in negatives]
    by_subject_count = {}
    for name, wanted in (("single", 1), ("multiple", 2)):
        subset = [pair for record, pair in zip(records, predictions, strict=True) if len(record["boxes"]) == wanted or (wanted == 2 and len(record["boxes"]) > 1)]
        if subset:
            by_subject_count[name] = metrics(subset, args.score_threshold)
    by_size = {}
    for name, lower, upper in (("small", 0, 0.01), ("medium", 0.01, 0.1), ("large", 0.1, float("inf"))):
        subset = []
        for record, pair in zip(records, predictions, strict=True):
            ratios = [((box["xyxy"][2] - box["xyxy"][0]) * (box["xyxy"][3] - box["xyxy"][1])) / (record["width"] * record["height"]) for box in record["boxes"]]
            if ratios and all(lower <= ratio < upper for ratio in ratios):
                subset.append(pair)
        if subset:
            by_size[name] = metrics(subset, args.score_threshold)
    report = {
        "schema_version": "0.1.0",
        "status": "frozen_detection_evaluation",
        "reviewed_manifest_sha256": digest(args.reviewed),
        "sample_manifest_sha256": digest(args.sample_manifest),
        "checkpoint_sha256": digest(args.checkpoint),
        "score_threshold": args.score_threshold,
        "included": {"positive_images": len(positives), "hard_negative_images": len(negatives)},
        "excluded": excluded,
        "positive_localization": positive_metrics,
        "hard_negatives": {
            "false_positives": sum(negative_detections),
            "false_positives_per_image": sum(negative_detections) / max(1, len(negative_detections)),
            "no_detection_rate": sum(value == 0 for value in negative_detections) / max(1, len(negative_detections)),
        },
        "by_subject_count": by_subject_count,
        "by_relative_subject_area": by_size,
        "inference": {"device": str(device), "total_seconds": elapsed, "mean_milliseconds_per_image": elapsed * 1000 / len(records)},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("RESULT OK " + json.dumps(report["positive_localization"], sort_keys=True))
    print("HARD_NEGATIVES " + json.dumps(report["hard_negatives"], sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
