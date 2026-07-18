#!/usr/bin/env python3
"""Train and evaluate the one-class SSDLite detection pilot."""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import time
from pathlib import Path

import torch
import torchvision
from PIL import Image
from torch.utils.data import DataLoader, Dataset
from torchvision.models import MobileNet_V3_Large_Weights
from torchvision.models.detection import ssdlite320_mobilenet_v3_large
from torchvision.transforms.functional import pil_to_tensor


class DetectionDataset(Dataset):
    def __init__(self, root: Path, records: list[dict], augment: bool = False) -> None:
        self.root = root
        self.records = records
        self.augment = augment

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, index: int):
        record = self.records[index]
        with Image.open(self.root / record["image_path"]) as source:
            image = pil_to_tensor(source.convert("RGB")).float().div(255)
        boxes = torch.tensor([box["xyxy"] for box in record["boxes"]], dtype=torch.float32)
        if self.augment and torch.rand(()) < 0.5:
            image = image.flip(-1)
            width = image.shape[-1]
            boxes[:, [0, 2]] = width - boxes[:, [2, 0]]
        target = {
            "boxes": boxes,
            "labels": torch.ones(len(boxes), dtype=torch.int64),
            "image_id": torch.tensor(index),
        }
        return image, target


def collate(batch):
    return tuple(zip(*batch, strict=True))


def iou(left: torch.Tensor, right: torch.Tensor) -> torch.Tensor:
    top_left = torch.maximum(left[:, None, :2], right[None, :, :2])
    bottom_right = torch.minimum(left[:, None, 2:], right[None, :, 2:])
    intersection = (bottom_right - top_left).clamp(min=0).prod(2)
    left_area = (left[:, 2:] - left[:, :2]).prod(1)[:, None]
    right_area = (right[:, 2:] - right[:, :2]).prod(1)[None, :]
    return intersection / (left_area + right_area - intersection).clamp(min=1e-8)


def counts(prediction: dict, target: dict, score_threshold: float) -> tuple[int, int, int]:
    predicted = prediction["boxes"][prediction["scores"] >= score_threshold].cpu()
    truth = target["boxes"].cpu()
    matched_truth: set[int] = set()
    true_positive = 0
    if len(predicted) and len(truth):
        overlaps = iou(predicted, truth)
        for prediction_index in range(len(predicted)):
            value, truth_index = overlaps[prediction_index].max(0)
            selected = int(truth_index)
            if float(value) >= 0.5 and selected not in matched_truth:
                matched_truth.add(selected)
                true_positive += 1
    return true_positive, len(predicted) - true_positive, len(truth) - true_positive


@torch.inference_mode()
def predict(model, loader, device) -> list[tuple[dict, dict]]:
    model.eval()
    result = []
    for images, targets in loader:
        outputs = model([image.to(device) for image in images])
        result.extend(zip(outputs, targets, strict=True))
    return result


def metrics(predictions: list[tuple[dict, dict]], threshold: float) -> dict[str, float | int]:
    true_positive = false_positive = false_negative = 0
    for prediction, target in predictions:
        tp, fp, fn = counts(prediction, target, threshold)
        true_positive += tp
        false_positive += fp
        false_negative += fn
    precision = true_positive / max(1, true_positive + false_positive)
    recall = true_positive / max(1, true_positive + false_negative)
    return {
        "score_threshold": threshold,
        "iou_threshold": 0.5,
        "true_positive": true_positive,
        "false_positive": false_positive,
        "false_negative": false_negative,
        "precision": precision,
        "recall": recall,
        "f1": 2 * precision * recall / max(1e-8, precision + recall),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=0.002)
    parser.add_argument("--seed", type=int, default=20260718)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)
    manifest_path = args.dataset / "dataset-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("status") != "reviewed_detection_dataset":
        raise SystemExit("dataset manifest is not a reviewed detection dataset")
    records = manifest["records"]
    datasets = {
        split: DetectionDataset(args.dataset, [r for r in records if r["split"] == split], split == "train")
        for split in ("train", "validation", "test")
    }
    generator = torch.Generator().manual_seed(args.seed)
    loaders = {
        split: DataLoader(
            dataset,
            batch_size=args.batch_size,
            shuffle=split == "train",
            drop_last=split == "train",
            generator=generator,
            num_workers=args.workers,
            collate_fn=collate,
            pin_memory=args.device.startswith("cuda"),
        )
        for split, dataset in datasets.items()
    }
    device = torch.device(args.device)
    model = ssdlite320_mobilenet_v3_large(
        weights=None,
        weights_backbone=MobileNet_V3_Large_Weights.IMAGENET1K_V1,
        num_classes=2,
    ).to(device)
    optimizer = torch.optim.SGD(
        [parameter for parameter in model.parameters() if parameter.requires_grad],
        lr=args.learning_rate,
        momentum=0.9,
        weight_decay=5e-4,
    )
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=args.epochs)
    history = []
    started = time.time()
    for epoch in range(1, args.epochs + 1):
        model.train()
        total_loss = 0.0
        batches = 0
        for images, targets in loaders["train"]:
            images = [image.to(device) for image in images]
            targets = [{key: value.to(device) for key, value in target.items()} for target in targets]
            losses = model(images, targets)
            loss = sum(losses.values())
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()
            total_loss += float(loss.detach())
            batches += 1
        scheduler.step()
        entry = {"epoch": epoch, "mean_training_loss": total_loss / batches, "learning_rate": scheduler.get_last_lr()[0]}
        history.append(entry)
        print(f"epoch={epoch}/{args.epochs} loss={entry['mean_training_loss']:.5f} lr={entry['learning_rate']:.6g}", flush=True)

    validation_predictions = predict(model, loaders["validation"], device)
    candidates = [metrics(validation_predictions, threshold / 100) for threshold in range(5, 96, 5)]
    selected = max(candidates, key=lambda item: (item["f1"], item["recall"], item["score_threshold"]))
    test_metrics = metrics(predict(model, loaders["test"], device), float(selected["score_threshold"]))
    args.output.mkdir(parents=True, exist_ok=True)
    checkpoint = args.output / "ssdlite320-mobilenet-v3-large.pt"
    torch.save(model.state_dict(), checkpoint)
    report = {
        "schema_version": "0.1.0",
        "status": "experimental_detection_training_run",
        "architecture": "ssdlite320_mobilenet_v3_large",
        "classes": {"background": 0, "Lepidoptera": 1},
        "initialization": {
            "detector": "random",
            "backbone": "torchvision MobileNet_V3_Large_Weights.IMAGENET1K_V1",
            "distribution_approval": "not granted; pretrained-weight provenance requires separate review",
        },
        "dataset_manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        "dataset_split": manifest["split"],
        "seed": args.seed,
        "device": str(device),
        "torch": torch.__version__,
        "torchvision": torchvision.__version__,
        "duration_seconds": time.time() - started,
        "parameters": vars(args) | {"dataset": str(args.dataset), "output": str(args.output)},
        "training_history": history,
        "validation_threshold_candidates": candidates,
        "selected_validation_metrics": selected,
        "test_metrics_at_frozen_threshold": test_metrics,
        "checkpoint": checkpoint.name,
    }
    (args.output / "run.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("RESULT OK " + json.dumps(test_metrics, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
