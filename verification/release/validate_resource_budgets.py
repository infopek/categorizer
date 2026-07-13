from __future__ import annotations

import json
import sys
from pathlib import Path


PATH = Path(__file__).with_name("resource-budgets.json")


def main() -> int:
    payload = json.loads(PATH.read_text(encoding="utf-8"))
    errors: list[str] = []
    required_metrics = {
        "bundled_onnx_file": "MiB",
        "play_compressed_download": "MB",
        "installed_footprint": "MiB",
        "peak_runtime_pss": "MiB",
        "inference_working_memory": "MiB",
    }

    if payload.get("decision_status") not in {
        "proposed_pending_human_acceptance",
        "accepted",
    }:
        errors.append("decision_status must be proposed_pending_human_acceptance or accepted")
    if payload.get("decision_status") == "accepted":
        if not payload.get("accepted_at"):
            errors.append("accepted decision must record accepted_at")
        if not payload.get("accepted_by"):
            errors.append("accepted decision must record accepted_by")

    budgets = payload.get("budgets", {})
    for name, expected_unit in required_metrics.items():
        budget = budgets.get(name)
        if not isinstance(budget, dict):
            errors.append(f"missing budget: {name}")
            continue
        target = budget.get("optimization_target_maximum")
        hard_gate = budget.get("hard_gate_maximum")
        if budget.get("unit") != expected_unit:
            errors.append(f"{name} unit must be {expected_unit}")
        if not isinstance(target, (int, float)) or target <= 0:
            errors.append(f"{name} optimization target must be positive")
        if not isinstance(hard_gate, (int, float)) or hard_gate <= 0:
            errors.append(f"{name} hard gate must be positive")
        if isinstance(target, (int, float)) and isinstance(hard_gate, (int, float)):
            if target > hard_gate:
                errors.append(f"{name} optimization target cannot exceed its hard gate")

    context = payload.get("external_context", {})
    download = budgets.get("play_compressed_download", {})
    if download.get("hard_gate_maximum", float("inf")) > context.get(
        "google_play_base_module_limit_mb", 0
    ):
        errors.append("download hard gate exceeds recorded Google Play base-module limit")
    if not payload.get("evidence_directory"):
        errors.append("evidence_directory must be recorded")

    if errors:
        for error in errors:
            print(f"ERROR {error}")
        print(f"RESULT FAIL errors={len(errors)}")
        return 1

    print(
        "RESULT OK "
        f"status={payload['decision_status']} metrics={len(required_metrics)} "
        f"download_hard_gate_mb={download['hard_gate_maximum']} "
        f"peak_pss_hard_gate_mib={budgets['peak_runtime_pss']['hard_gate_maximum']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
