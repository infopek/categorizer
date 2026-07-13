from __future__ import annotations

import json
import sys
from pathlib import Path


PATH = Path(__file__).with_name("benchmark-device.json")


def main() -> int:
    payload = json.loads(PATH.read_text(encoding="utf-8"))
    required = {
        "schema_version",
        "decision_status",
        "accepted_at",
        "access_confirmed_by",
        "manufacturer",
        "product_name",
        "model",
        "ram_gb",
        "android_version",
        "vendor_ui",
        "runtime_capture_required",
        "acceptance_gate",
    }
    missing = required - set(payload)
    errors: list[str] = []
    if missing:
        errors.append(f"missing fields: {sorted(missing)}")
    if payload.get("decision_status") != "accepted":
        errors.append("device decision must be accepted")
    if not isinstance(payload.get("ram_gb"), int) or payload.get("ram_gb", 0) < 4:
        errors.append("benchmark device must have at least 4 GB RAM")
    gate = payload.get("acceptance_gate", {})
    if gate.get("statistic") != "median" or gate.get("maximum_ms_exclusive") != 2000:
        errors.append("acceptance gate must be median inference below 2000 ms")
    if not payload.get("runtime_capture_required"):
        errors.append("runtime capture list must not be empty")

    if errors:
        for error in errors:
            print(f"ERROR {error}")
        print(f"RESULT FAIL errors={len(errors)}")
        return 1

    print(
        "RESULT OK "
        f"model={payload['model']} android={payload['android_version']} "
        f"ram_gb={payload['ram_gb']} median_gate_ms={gate['maximum_ms_exclusive']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
