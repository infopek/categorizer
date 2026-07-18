#!/usr/bin/env python3
"""Export and verify the pinned 163-class Figshare MaxViT-T checkpoint."""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import zipfile
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torchvision
from onnxruntime.quantization import QuantType, quantize_dynamic
from onnxruntime.transformers.float16 import convert_float_to_float16
from PIL import Image
from torchvision.transforms import v2

EXPECTED_CHECKPOINT_SHA256 = "ac3cf138930a8b6f52dcb064ff44ace39b701d3412812e457930463073e5eca0"
INPUT_SIZE = 224
CLASS_COUNT = 163
OPSET = 18
ATOL = 1e-4
RTOL = 1e-4


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def ranking(values: np.ndarray) -> list[int]:
    return np.lexsort((np.arange(values.size), -values)).tolist()


def topologically_sort(graph: onnx.GraphProto) -> None:
    available = {value.name for value in graph.input} | {value.name for value in graph.initializer}
    pending = list(graph.node)
    ordered = []
    while pending:
        ready = [node for node in pending if all(not name or name in available for name in node.input)]
        if not ready:
            raise SystemExit("optimized ONNX graph cannot be topologically sorted")
        for node in ready:
            ordered.append(node)
            available.update(node.output)
            pending.remove(node)
    del graph.node[:]
    graph.node.extend(ordered)


def fixtures(sample_archive: Path | None) -> list[tuple[str, torch.Tensor]]:
    generator = torch.Generator().manual_seed(20260717)
    items = [
        ("zeros", torch.zeros(3, INPUT_SIZE, INPUT_SIZE)),
        ("ones", torch.ones(3, INPUT_SIZE, INPUT_SIZE)),
        ("random-seed-20260717", torch.rand(3, INPUT_SIZE, INPUT_SIZE, generator=generator)),
    ]
    if sample_archive is None:
        return items
    transform = v2.Compose(
        [
            v2.Resize(INPUT_SIZE),
            v2.CenterCrop(INPUT_SIZE),
            v2.ToImage(),
            v2.ToDtype(torch.float32, scale=True),
            v2.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
        ]
    )
    with zipfile.ZipFile(sample_archive) as archive:
        for name in sorted(archive.namelist()):
            if name.lower().endswith((".jpg", ".jpeg", ".png")):
                with archive.open(name) as source:
                    image = Image.open(source).convert("RGB")
                    items.append((f"archive:{name}", transform(image)))
    return items


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--class-map", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sample-archive", type=Path)
    optimization = parser.add_mutually_exclusive_group()
    optimization.add_argument("--dynamic-int8", action="store_true")
    optimization.add_argument("--float16", action="store_true")
    parser.add_argument("--common-names", type=Path, default=Path("ml/catalog/lepidoptera-common-names.json"))
    args = parser.parse_args()

    checkpoint_hash = sha256(args.checkpoint)
    if checkpoint_hash != EXPECTED_CHECKPOINT_SHA256:
        raise SystemExit("checkpoint SHA-256 does not match the pinned artifact")
    class_map = json.loads(args.class_map.read_text(encoding="utf-8"))
    common_names = json.loads(args.common_names.read_text(encoding="utf-8"))
    common_by_id = {item["class_id"]: item["common_name"] for item in common_names["classes"]}
    classes = class_map.get("classes", [])
    if class_map.get("checkpoint_sha256") != checkpoint_hash:
        raise SystemExit("class map belongs to a different checkpoint")
    if len(classes) != CLASS_COUNT or [item["index"] for item in classes] != list(range(CLASS_COUNT)):
        raise SystemExit("class map must contain contiguous indices 0..162")

    state = torch.load(args.checkpoint, map_location="cpu", weights_only=True)
    model = torchvision.models.maxvit_t(weights=None, num_classes=CLASS_COUNT)
    model.load_state_dict(state, strict=True)
    model.eval()
    args.output.mkdir(parents=True, exist_ok=True)
    model_path = args.output / "model.onnx"
    sample = torch.zeros(1, 3, INPUT_SIZE, INPUT_SIZE)
    if args.dynamic_int8 or args.float16:
        float_path = args.output / "model.float.onnx"
        torch.onnx.export(
            model, (sample,), float_path, input_names=["images"], output_names=["logits"],
            opset_version=OPSET, dynamo=False, external_data=False,
        )
        if args.dynamic_int8:
            quantize_dynamic(float_path, model_path, weight_type=QuantType.QInt8)
        else:
            float_graph = onnx.load(float_path)
            half_graph = convert_float_to_float16(float_graph, keep_io_types=True)
            topologically_sort(half_graph.graph)
            onnx.save(half_graph, model_path)
        float_session = ort.InferenceSession(str(float_path), providers=["CPUExecutionProvider"])
    else:
        program = torch.onnx.export(
            model, (sample,), input_names=["images"], output_names=["logits"],
            opset_version=OPSET, dynamo=True, external_data=False,
        )
        program.save(model_path, external_data=False)
        float_session = None
    graph = onnx.load(model_path)
    onnx.checker.check_model(graph)
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    if session.get_inputs()[0].name != "images" or session.get_inputs()[0].shape != [1, 3, 224, 224]:
        raise SystemExit("ONNX input contract mismatch")
    if session.get_outputs()[0].name != "logits" or session.get_outputs()[0].shape != [1, 163]:
        raise SystemExit("ONNX output contract mismatch")

    maximum_absolute = 0.0
    maximum_relative = 0.0
    complete_rankings_equal = True
    top_five_equal = True
    top_one_equal_count = 0
    top_five_set_equal_count = 0
    fixture_count = 0
    with torch.inference_mode():
        for _, tensor in fixtures(args.sample_archive):
            batch = tensor.unsqueeze(0)
            expected = model(batch).numpy()[0]
            if float_session is not None:
                expected = float_session.run(["logits"], {"images": batch.numpy()})[0][0]
            actual = session.run(["logits"], {"images": batch.numpy()})[0][0]
            difference = np.abs(expected - actual)
            maximum_absolute = max(maximum_absolute, float(difference.max()))
            maximum_relative = max(
                maximum_relative,
                float((difference / np.maximum(np.abs(expected), 1e-12)).max()),
            )
            expected_ranking = ranking(expected)
            actual_ranking = ranking(actual)
            complete_rankings_equal &= expected_ranking == actual_ranking
            top_five_equal &= expected_ranking[:5] == actual_ranking[:5]
            top_one_equal_count += expected_ranking[0] == actual_ranking[0]
            top_five_set_equal_count += set(expected_ranking[:5]) == set(actual_ranking[:5])
            fixture_count += 1
    if not (args.dynamic_int8 or args.float16) and (maximum_absolute > ATOL or not top_five_equal):
        raise SystemExit("PyTorch/ONNX fixture equivalence failed")

    model_size_mib = model_path.stat().st_size / 1048576
    operators = sorted({(node.domain or "ai.onnx", node.op_type) for node in graph.graph.node})
    report = {
        "schema_version": "1.0.0",
        "candidate_status": "optimized_fixture_comparison_held_out_pending" if (args.dynamic_int8 or args.float16) else "fixture_equivalence_passed_held_out_pending",
        "architecture": "torchvision.maxvit_t",
        "class_count": CLASS_COUNT,
        "checkpoint_sha256": checkpoint_hash,
        "class_map_sha256": sha256(args.class_map),
        "model_sha256": sha256(model_path),
        "model_size_bytes": model_path.stat().st_size,
        "model_size_mib": model_size_mib,
        "size_gate": {"optimization_target_mib": 80, "hard_maximum_mib": 150, "hard_gate_passed": model_size_mib <= 150},
        "input": {"name": "images", "shape": [1, 3, 224, 224], "type": "float32", "layout": "NCHW"},
        "preprocessing": {"resize_short_edge": 224, "center_crop": 224, "color": "RGB", "scale": "uint8_to_0_1", "mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225]},
        "output": {"name": "logits", "shape": [1, 163], "ranking": "descending_logit_then_ascending_index"},
        "equivalence": {"reference": "legacy_float_onnx" if (args.dynamic_int8 or args.float16) else "pytorch", "fixture_count": fixture_count, "atol": ATOL, "rtol": RTOL, "maximum_absolute_difference": maximum_absolute, "maximum_relative_difference": maximum_relative, "top_one_agreement": top_one_equal_count / fixture_count, "top_five_set_agreement": top_five_set_equal_count / fixture_count, "top_five_rankings_equal": top_five_equal, "complete_rankings_equal": complete_rankings_equal},
        "quantization": {"mode": "dynamic_int8_weights" if args.dynamic_int8 else ("float16_weights_float32_io" if args.float16 else "none"), "held_out_acceptance_required": args.dynamic_int8 or args.float16},
        "held_out_evaluation": {"status": "pending", "reason": "upstream test split and full image dataset have not been acquired"},
        "compatibility": {"onnx_checker": "passed", "opset": OPSET, "onnxruntime": ort.__version__, "providers": session.get_providers(), "operators": [{"domain": domain, "type": name} for domain, name in operators]},
        "runtime": {"python": platform.python_version(), "torch": torch.__version__, "torchvision": torchvision.__version__},
    }
    (args.output / "equivalence-report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    runtime_classes = []
    for item in classes:
        words = item["scientific_name"].split(maxsplit=1)
        runtime_classes.append(
            {
                "index": item["index"],
                "class_id": item["class_id"],
                "make": words[0],
                "model": words[1] if len(words) > 1 else words[0],
                "generation_label": "",
                "approximate_year_range": "",
                "display_name": common_by_id.get(item["class_id"]) or item["display_name"],
                "scientific_name": item["scientific_name"],
            }
        )
    runtime_class_map = {
        "schema_version": "1.0.0",
        "category_id": "lepidoptera",
        "catalog_id": class_map["catalog_id"],
        "class_count": CLASS_COUNT,
        "classes": runtime_classes,
    }
    runtime_class_path = args.output / "class-map.json"
    runtime_class_path.write_text(json.dumps(runtime_class_map, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    notice = args.output / "NOTICE.candidate.txt"
    notice.write_text(
        "Experimental Lepidoptera classifier candidate; held-out qualification remains pending. "
        "Source checkpoint and training dataset provenance are recorded in repository ML documentation.\n",
        encoding="utf-8",
    )
    manifest = {
        "schema_version": "1.0.0",
        "bundle_id": f"lepidoptera-maxvit-t-{checkpoint_hash[:12]}",
        "model_version": "lepidoptera-maxvit-t-163-dynamic-int8-pilot" if args.dynamic_int8 else ("lepidoptera-maxvit-t-163-fp16-pilot" if args.float16 else "lepidoptera-maxvit-t-163-pilot"),
        "artifact_status": "experimental_held_out_pending",
        "model": {"filename": model_path.name, "format": "ONNX", "sha256": sha256(model_path), "size_bytes": model_path.stat().st_size},
        "class_map": {"filename": runtime_class_path.name, "schema_version": "1.0.0", "catalog_id": class_map["catalog_id"], "class_count": CLASS_COUNT, "sha256": sha256(runtime_class_path), "common_names_sha256": sha256(args.common_names)},
        "input": {"tensor_name": "images", "element_type": "float32", "layout": "NCHW", "shape": [1, 3, INPUT_SIZE, INPUT_SIZE], "color_order": "RGB"},
        "preprocessing": {
            "resize": {"mode": "shorter_side", "shorter_side": INPUT_SIZE, "interpolation": "bilinear"},
            "crop": {"mode": "center", "width": INPUT_SIZE, "height": INPUT_SIZE},
            "normalization": {"mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225]},
        },
        "output": {"tensor_name": "logits", "element_type": "float32", "shape": [1, CLASS_COUNT], "class_axis": 1, "semantics": "logits", "score_transform": "softmax", "ranking": "descending_score_then_ascending_class_index", "top_k": 5},
        "licenses": {"model_spdx_expression": "LicenseRef-Experimental-Qualification-Pending", "notice_files": [notice.name]},
    }
    (args.output / "model-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"RESULT OK fixtures={fixture_count} max_abs={maximum_absolute:.8g} size_mib={model_size_mib:.2f} hard_size_gate={model_size_mib <= 150}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
