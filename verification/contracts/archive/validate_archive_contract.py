from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
import stat
import struct
import sys
import tempfile
import warnings
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

from jsonschema import Draft202012Validator


HERE = Path(__file__).resolve().parent
SCHEMA_PATH = HERE / "album-archive.schema.json"
FIXTURES = HERE / "fixtures"
MAX_MANIFEST_BYTES = 10 * 1024 * 1024
MAX_IMAGE_BYTES = 50 * 1024 * 1024
MAX_TOTAL_IMAGE_BYTES = 10 * 1024 * 1024 * 1024
MEDIA_EXTENSIONS = {
    "image/jpeg": {".jpg", ".jpeg"},
    "image/png": {".png"},
    "image/webp": {".webp"},
}


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def safe_member_path(value: str) -> bool:
    if not value or "\\" in value or "\x00" in value or len(value) > 240:
        return False
    path = PurePosixPath(value)
    if path.is_absolute() or ":" in path.parts[0]:
        return False
    return all(part not in {"", ".", ".."} for part in path.parts)


def schema_errors(manifest: Any) -> list[str]:
    schema = load_json(SCHEMA_PATH)
    validator = Draft202012Validator(schema)
    return [
        f"{'/'.join(str(part) for part in error.absolute_path) or '<root>'}: {error.message}"
        for error in sorted(validator.iter_errors(manifest), key=lambda item: list(item.absolute_path))
    ]


def png_chunk_types(payload: bytes) -> list[str]:
    if not payload.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("fixture payload is not a PNG")
    chunks: list[str] = []
    position = 8
    while position < len(payload):
        if position + 12 > len(payload):
            raise ValueError("fixture PNG has a truncated chunk")
        size = struct.unpack(">I", payload[position : position + 4])[0]
        end = position + 12 + size
        if end > len(payload):
            raise ValueError("fixture PNG chunk exceeds payload")
        chunks.append(payload[position + 4 : position + 8].decode("ascii"))
        position = end
    return chunks


def semantic_errors(manifest: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    entries = manifest.get("entries")
    images = manifest.get("image_files")
    if not isinstance(entries, list) or not isinstance(images, list):
        return errors

    if manifest.get("entry_count") != len(entries):
        errors.append("entry_count does not match entries")
    if manifest.get("image_count") != len(images):
        errors.append("image_count does not match image_files")

    entry_ids = [entry.get("entry_id") for entry in entries if isinstance(entry, dict)]
    if len(entry_ids) != len(set(entry_ids)):
        errors.append("entry_id values must be unique")
    entry_image_ids = [entry.get("image_id") for entry in entries if isinstance(entry, dict)]
    if len(entry_image_ids) != len(set(entry_image_ids)):
        errors.append("entry image_id references must be unique")

    image_ids = [image.get("image_id") for image in images if isinstance(image, dict)]
    if len(image_ids) != len(set(image_ids)):
        errors.append("image_id values must be unique")
    paths = [image.get("relative_path") for image in images if isinstance(image, dict)]
    if len(paths) != len(set(paths)):
        errors.append("image relative paths must be unique")
    if set(entry_image_ids) != set(image_ids):
        errors.append("entries and image_files must form a one-to-one image mapping")

    total_size = 0
    for image in images:
        if not isinstance(image, dict):
            continue
        relative_path = image.get("relative_path")
        if isinstance(relative_path, str):
            if not safe_member_path(relative_path) or not relative_path.startswith("images/"):
                errors.append(f"unsafe declared image path: {relative_path}")
            expected_extensions = MEDIA_EXTENSIONS.get(image.get("media_type"), set())
            if PurePosixPath(relative_path).suffix.lower() not in expected_extensions:
                errors.append(f"media type does not match extension: {relative_path}")
        size = image.get("size_bytes")
        if isinstance(size, int):
            total_size += size

    if total_size > MAX_TOTAL_IMAGE_BYTES:
        errors.append("total declared image bytes exceed 10 GiB")

    for entry in entries:
        if not isinstance(entry, dict):
            continue
        created = entry.get("created_at_epoch_ms")
        updated = entry.get("updated_at_epoch_ms")
        if isinstance(created, int) and isinstance(updated, int) and updated < created:
            errors.append(f"entry updated time precedes creation: {entry.get('entry_id')}")
    return errors


def validate_archive(path: Path) -> list[str]:
    errors: list[str] = []
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            if len(names) != len(set(names)):
                errors.append("duplicate ZIP member names")
            for info in infos:
                if info.filename != "manifest.json" and not safe_member_path(info.filename):
                    errors.append(f"unsafe archive member path: {info.filename}")
                unix_mode = info.external_attr >> 16
                if unix_mode and stat.S_ISLNK(unix_mode):
                    errors.append(f"symbolic link member is forbidden: {info.filename}")

            manifest_infos = [info for info in infos if info.filename == "manifest.json"]
            if len(manifest_infos) != 1:
                errors.append("archive must contain exactly one manifest.json")
                return errors
            if manifest_infos[0].file_size > MAX_MANIFEST_BYTES:
                errors.append("manifest.json exceeds 10 MiB")
                return errors

            try:
                manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
            except (
                KeyError,
                RuntimeError,
                UnicodeDecodeError,
                json.JSONDecodeError,
                zipfile.BadZipFile,
            ) as error:
                errors.append(f"manifest.json is unreadable: {error}")
                return errors

            errors.extend(schema_errors(manifest))
            if not isinstance(manifest, dict):
                return errors
            errors.extend(semantic_errors(manifest))
            images = manifest.get("image_files")
            if not isinstance(images, list):
                return errors

            declared_paths = {
                image.get("relative_path")
                for image in images
                if isinstance(image, dict) and isinstance(image.get("relative_path"), str)
            }
            actual_paths = {name for name in names if name != "manifest.json"}
            for missing in sorted(declared_paths - actual_paths):
                errors.append(f"missing declared image: {missing}")
            for extra in sorted(actual_paths - declared_paths):
                errors.append(f"undeclared archive member: {extra}")

            observed_total = 0
            info_by_name = {info.filename: info for info in infos}
            declared_member_infos = [
                info for info in infos if info.filename in declared_paths
            ]
            if any(info.file_size > MAX_IMAGE_BYTES for info in declared_member_infos):
                errors.append("observed image member exceeds 50 MiB")
            if sum(info.file_size for info in declared_member_infos) > MAX_TOTAL_IMAGE_BYTES:
                errors.append("total observed image bytes exceed 10 GiB")
            for image in images:
                if not isinstance(image, dict):
                    continue
                relative_path = image.get("relative_path")
                if not isinstance(relative_path, str) or relative_path not in info_by_name:
                    continue
                info = info_by_name[relative_path]
                if info.compress_type != zipfile.ZIP_STORED:
                    errors.append(f"image member must use ZIP_STORED: {relative_path}")
                if info.file_size > MAX_IMAGE_BYTES:
                    continue
                try:
                    digest = hashlib.sha256()
                    payload_size = 0
                    with archive.open(info) as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                            payload_size += len(chunk)
                            digest.update(chunk)
                except (RuntimeError, zipfile.BadZipFile) as error:
                    errors.append(f"image member is corrupt: {relative_path}: {error}")
                    continue
                observed_total += payload_size
                if image.get("size_bytes") != payload_size:
                    errors.append(f"image size mismatch: {relative_path}")
                if image.get("sha256") != digest.hexdigest():
                    errors.append(f"image SHA-256 mismatch: {relative_path}")
            if observed_total > MAX_TOTAL_IMAGE_BYTES:
                errors.append("total observed image bytes exceed 10 GiB")
    except (OSError, zipfile.BadZipFile) as error:
        errors.append(f"archive is not a readable ZIP: {error}")
    return errors


def write_fixture_archive(
    output: Path,
    manifest: dict[str, Any],
    image_path: str,
    image_payload: bytes,
    mutation: str | None,
) -> None:
    include_image = mutation != "missing_image_file"
    duplicate_member = mutation == "duplicate_zip_member"
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(
            "manifest.json",
            json.dumps(manifest, indent=2, sort_keys=True).encode("utf-8"),
            compress_type=zipfile.ZIP_DEFLATED,
        )
        if include_image:
            archive.writestr(image_path, image_payload, compress_type=zipfile.ZIP_STORED)
        if duplicate_member:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr(image_path, image_payload, compress_type=zipfile.ZIP_STORED)


def mutated_fixture(base: dict[str, Any], mutation: str) -> tuple[dict[str, Any], str]:
    manifest = copy.deepcopy(base)
    image_path = manifest["image_files"][0]["relative_path"]
    if mutation == "checksum_mismatch":
        manifest["image_files"][0]["sha256"] = "0" * 64
    elif mutation == "duplicate_entry_id":
        manifest["entries"].append(copy.deepcopy(manifest["entries"][0]))
        manifest["entry_count"] = 2
    elif mutation == "incompatible_version":
        manifest["archive_schema_version"] = "2.0.0"
    elif mutation == "path_traversal":
        image_path = "../escape.png"
        manifest["image_files"][0]["relative_path"] = image_path
    elif mutation not in {"missing_image_file", "duplicate_zip_member"}:
        raise ValueError(f"unknown fixture mutation: {mutation}")
    return manifest, image_path


def run_fixture_suite() -> int:
    valid_dir = FIXTURES / "valid"
    base_manifest = load_json(valid_dir / "archive-manifest.json")
    payload_record = load_json(valid_dir / "image-payload.json")
    image_payload = base64.b64decode(payload_record["data"], validate=True)
    cases = load_json(FIXTURES / "invalid" / "cases.json")["cases"]
    failures = 0
    chunks = png_chunk_types(image_payload)
    if chunks != ["IHDR", "IDAT", "IEND"]:
        failures += 1
        print(f"FAIL fixture_image_metadata unexpected_chunks={chunks}")
    else:
        print("PASS fixture_image_metadata chunks=IHDR,IDAT,IEND")

    with tempfile.TemporaryDirectory(prefix="categorizer-archive-contract-") as temp:
        temp_dir = Path(temp)
        valid_path = temp_dir / "valid.zip"
        write_fixture_archive(
            valid_path,
            base_manifest,
            payload_record["relative_path"],
            image_payload,
            None,
        )
        valid_errors = validate_archive(valid_path)
        if valid_errors:
            failures += 1
            print(f"FAIL valid_archive errors={valid_errors}")
        else:
            print("PASS valid_archive accepted errors=0")

        empty_manifest = copy.deepcopy(base_manifest)
        empty_manifest["archive_id"] = "contract-fixture-empty-archive"
        empty_manifest["entry_count"] = 0
        empty_manifest["image_count"] = 0
        empty_manifest["entries"] = []
        empty_manifest["image_files"] = []
        empty_path = temp_dir / "empty.zip"
        write_fixture_archive(empty_path, empty_manifest, "", b"", "missing_image_file")
        empty_errors = validate_archive(empty_path)
        if empty_errors:
            failures += 1
            print(f"FAIL empty_archive errors={empty_errors}")
        else:
            print("PASS empty_archive accepted errors=0")

        for case in cases:
            mutation = case["mutation"]
            manifest, image_path = mutated_fixture(base_manifest, mutation)
            archive_path = temp_dir / f"{case['name']}.zip"
            write_fixture_archive(
                archive_path,
                manifest,
                image_path,
                image_payload,
                mutation,
            )
            errors = validate_archive(archive_path)
            expected = case["expected_error"]
            if not errors or not any(expected in error for error in errors):
                failures += 1
                print(
                    f"FAIL {case['name']} expected_error={expected!r} errors={errors}"
                )
            else:
                print(f"PASS {case['name']} rejected errors={len(errors)}")

    total = 2 + len(cases)
    if failures:
        print(f"RESULT FAIL cases={total} failures={failures}")
        return 1
    print(f"RESULT OK cases={total} positive=2 negative={len(cases)}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", nargs="?", type=Path)
    args = parser.parse_args()
    if args.archive is None:
        return run_fixture_suite()

    errors = validate_archive(args.archive)
    for error in errors:
        print(f"ERROR {error}")
    if errors:
        print(f"RESULT FAIL errors={len(errors)}")
        return 1
    print("RESULT OK archive contract valid; no mutation performed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
