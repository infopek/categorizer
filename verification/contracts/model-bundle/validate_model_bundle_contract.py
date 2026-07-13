from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[3]
FIXTURES = Path(__file__).with_name("fixtures")
MANIFEST_SCHEMA_PATH = ROOT / "models" / "model-manifest.schema.json"
CLASS_MAP_SCHEMA_PATH = ROOT / "models" / "class-map.schema.json"


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def schema_errors(instance: dict[str, Any], schema: dict[str, Any]) -> list[str]:
    validator = Draft202012Validator(schema)
    return [
        f"{'/'.join(str(part) for part in error.absolute_path) or '<root>'}: {error.message}"
        for error in sorted(validator.iter_errors(instance), key=lambda item: list(item.absolute_path))
    ]


def validate_class_map(class_map: dict[str, Any]) -> list[str]:
    errors = schema_errors(class_map, load_json(CLASS_MAP_SCHEMA_PATH))
    classes = class_map.get("classes")
    if not isinstance(classes, list):
        return errors

    if class_map.get("class_count") != len(classes):
        errors.append("class_count does not equal the classes array length")

    indices = [item.get("index") for item in classes if isinstance(item, dict)]
    if indices != list(range(len(classes))):
        errors.append("class indices must be contiguous and match array order")

    class_ids = [item.get("class_id") for item in classes if isinstance(item, dict)]
    if len(class_ids) != len(set(class_ids)):
        errors.append("class_id values must be unique")

    labels = [
        item.get("display_name", "").casefold()
        for item in classes
        if isinstance(item, dict) and isinstance(item.get("display_name"), str)
    ]
    if len(labels) != len(set(labels)):
        errors.append("display_name values must be unique ignoring case")
    return errors


def validate_manifest(
    manifest: dict[str, Any],
    class_map: dict[str, Any],
    class_map_path: Path,
    model_path: Path | None,
) -> list[str]:
    errors = schema_errors(manifest, load_json(MANIFEST_SCHEMA_PATH))
    class_ref = manifest.get("class_map")
    if isinstance(class_ref, dict):
        if class_ref.get("filename") != class_map_path.name:
            errors.append("class_map filename does not match the supplied file")
        if class_ref.get("schema_version") != class_map.get("schema_version"):
            errors.append("class-map schema version does not match the supplied file")
        if class_ref.get("catalog_id") != class_map.get("catalog_id"):
            errors.append("catalog_id does not match the supplied class map")
        if class_ref.get("class_count") != class_map.get("class_count"):
            errors.append("class_count does not match the supplied class map")
        if class_ref.get("sha256") != sha256(class_map_path):
            errors.append("class-map SHA-256 does not match the supplied file bytes")

    output = manifest.get("output")
    if isinstance(output, dict) and isinstance(class_ref, dict):
        shape = output.get("shape")
        axis = output.get("class_axis")
        if isinstance(shape, list) and isinstance(axis, int):
            if axis >= len(shape):
                errors.append("output class_axis is outside the output shape")
            elif shape[axis] != class_ref.get("class_count"):
                errors.append("output class-axis length does not equal class_count")
        if output.get("top_k", 0) > class_ref.get("class_count", 0):
            errors.append("top_k cannot exceed class_count")
        if output.get("semantics") == "logits" and output.get("score_transform") != "softmax":
            errors.append("logit output requires softmax")
        if output.get("semantics") == "probabilities" and output.get("score_transform") != "identity":
            errors.append("probability output requires identity score transform")

    input_contract = manifest.get("input")
    preprocessing = manifest.get("preprocessing")
    if isinstance(input_contract, dict) and isinstance(preprocessing, dict):
        shape = input_contract.get("shape")
        crop = preprocessing.get("crop")
        if isinstance(shape, list) and len(shape) == 4 and isinstance(crop, dict):
            expected = [crop.get("height"), crop.get("width")]
            actual = shape[2:4] if input_contract.get("layout") == "NCHW" else shape[1:3]
            if actual != expected:
                errors.append("input spatial shape does not match crop dimensions")
            channel_count = shape[1] if input_contract.get("layout") == "NCHW" else shape[3]
            if channel_count != 3:
                errors.append("input channel dimension must be three")

    status = manifest.get("artifact_status")
    model_ref = manifest.get("model")
    if status in {"release_candidate", "released"} and model_path is None:
        errors.append("release candidate and released manifests require a supplied ONNX file")
    if status in {"release_candidate", "released"}:
        licenses = manifest.get("licenses", {})
        for notice_name in licenses.get("notice_files", []):
            notice_path = class_map_path.parent / notice_name
            if not notice_path.is_file():
                errors.append(f"required notice file is missing: {notice_name}")
    if model_path is not None and isinstance(model_ref, dict):
        if model_ref.get("filename") != model_path.name:
            errors.append("model filename does not match the supplied ONNX file")
        if model_ref.get("size_bytes") != model_path.stat().st_size:
            errors.append("model size does not match the supplied ONNX file")
        if model_ref.get("sha256") != sha256(model_path):
            errors.append("model SHA-256 does not match the supplied ONNX file")

    return errors


def validate_bundle(
    manifest_path: Path,
    class_map_path: Path,
    model_path: Path | None = None,
) -> list[str]:
    class_map = load_json(class_map_path)
    manifest = load_json(manifest_path)
    return validate_class_map(class_map) + validate_manifest(
        manifest, class_map, class_map_path, model_path
    )


def run_fixture_suite() -> int:
    valid = FIXTURES / "valid"
    invalid = FIXTURES / "invalid"
    cases = [
        (
            "valid_bundle",
            valid / "model-manifest.json",
            valid / "class-map.json",
            True,
        ),
        (
            "missing_required_manifest_field",
            invalid / "missing-field-manifest.json",
            valid / "class-map.json",
            False,
        ),
        (
            "class_map_hash_mismatch",
            invalid / "hash-mismatch-manifest.json",
            valid / "class-map.json",
            False,
        ),
        (
            "duplicate_class_id",
            valid / "model-manifest.json",
            invalid / "duplicate-class-map.json",
            False,
        ),
        (
            "reordered_class_indices",
            valid / "model-manifest.json",
            invalid / "reordered-class-map.json",
            False,
        ),
    ]
    failures = 0
    for name, manifest_path, class_map_path, should_pass in cases:
        errors = validate_bundle(manifest_path, class_map_path)
        passed = not errors
        if passed != should_pass:
            failures += 1
            print(f"FAIL {name} expected_pass={should_pass} errors={errors}")
        else:
            outcome = "accepted" if passed else "rejected"
            print(f"PASS {name} {outcome} errors={len(errors)}")

    if failures:
        print(f"RESULT FAIL cases={len(cases)} failures={failures}")
        return 1
    print(f"RESULT OK cases={len(cases)} positive=1 negative={len(cases) - 1}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", nargs="?", type=Path)
    parser.add_argument("class_map", nargs="?", type=Path)
    parser.add_argument("--model", type=Path)
    args = parser.parse_args()

    if args.manifest is None and args.class_map is None:
        return run_fixture_suite()
    if args.manifest is None or args.class_map is None:
        parser.error("manifest and class_map must be supplied together")

    errors = validate_bundle(args.manifest, args.class_map, args.model)
    for error in errors:
        print(f"ERROR {error}")
    if errors:
        print(f"RESULT FAIL errors={len(errors)}")
        return 1
    print("RESULT OK bundle contract valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
