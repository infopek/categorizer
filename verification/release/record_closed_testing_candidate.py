from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aab", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    verification = subprocess.run(
        ["jarsigner", "-verify", str(args.aab)],
        check=False,
        capture_output=True,
        text=True,
    )
    if verification.returncode != 0 or "jar verified" not in verification.stdout.lower():
        raise SystemExit("AAB upload signature verification failed")

    with zipfile.ZipFile(args.aab) as archive:
        names = set(archive.namelist())
        signature_files = sorted(
            name for name in names if name.startswith("META-INF/") and name.endswith((".RSA", ".DSA", ".EC"))
        )
        if not signature_files:
            raise SystemExit("AAB contains no upload signature block")
        manifest = json.loads(archive.read("base/assets/recognition/model-manifest.json"))
        model = archive.read("base/assets/recognition/model.onnx")
        class_map = archive.read("base/assets/recognition/class-map.json")
        if sha256_bytes(model) != manifest["model"]["sha256"]:
            raise SystemExit("embedded model hash does not match its manifest")
        if sha256_bytes(class_map) != manifest["class_map"]["sha256"]:
            raise SystemExit("embedded class-map hash does not match its manifest")

    git_commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True
    ).stdout.strip()
    payload = {
        "schema_version": "0.1.0",
        "status": "signed_candidate_pending_play_upload",
        "recorded_at": datetime.now(timezone.utc).isoformat(),
        "git_commit": git_commit,
        "application": {
            "application_id": "com.infopek.categorizer",
            "version_code": 1,
            "version_name": "0.1.0",
        },
        "aab": {
            "path": args.aab.as_posix(),
            "size_bytes": args.aab.stat().st_size,
            "sha256": sha256_file(args.aab),
            "upload_signature_verified": True,
            "signature_block_count": len(signature_files),
        },
        "recognition_bundle": {
            "model_version": manifest["model_version"],
            "model_sha256": manifest["model"]["sha256"],
            "model_size_bytes": manifest["model"]["size_bytes"],
            "class_map_sha256": manifest["class_map"]["sha256"],
            "class_count": manifest["class_map"]["class_count"],
            "license_expression": manifest["licenses"]["model_spdx_expression"],
        },
        "known_blockers": [
            "Google Play developer identity verification and upload are pending.",
            "Independent held-out accuracy cannot be reproduced because publisher split indices are unavailable.",
            "The bundled manifest remains qualification-pending and must not be represented as a passed independent accuracy gate.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"RESULT OK aab_sha256={payload['aab']['sha256']} bytes={payload['aab']['size_bytes']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
