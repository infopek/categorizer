#!/usr/bin/env python3
"""Apply explicit human decisions to teacher-proposed detection boxes."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ALLOWED_STATUSES = {
    "accepted",
    "needs_adjustment",
    "multiple_subjects",
    "rejected",
    "not_visible",
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--proposals", type=Path, required=True)
    parser.add_argument("--decisions", type=Path, required=True)
    parser.add_argument("--reviewer", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--allow-unknown-decisions", action="store_true")
    args = parser.parse_args()

    manifest = json.loads(args.proposals.read_text(encoding="utf-8"))
    decisions = json.loads(args.decisions.read_text(encoding="utf-8"))
    assets = manifest.get("assets", [])
    known = {item["asset_id"]: item for item in assets}
    if len(known) != len(assets):
        raise SystemExit("proposal manifest contains duplicate asset IDs")
    unknown = sorted(set(decisions) - set(known))
    missing = sorted(set(known) - set(decisions))
    if missing or (unknown and not args.allow_unknown_decisions):
        raise SystemExit(f"decision identity mismatch: unknown={unknown} missing={missing}")

    status_counts: dict[str, int] = {}
    selected_count = 0
    reviewed_assets = []
    for asset in assets:
        decision = decisions[asset["asset_id"]]
        status = decision.get("status")
        indices = decision.get("proposal_indices")
        if status not in ALLOWED_STATUSES or not isinstance(indices, list):
            raise SystemExit(f"invalid decision for {asset['asset_id']}")
        if len(indices) != len(set(indices)) or any(
            not isinstance(index, int) or index < 0 or index >= len(asset["proposals"])
            for index in indices
        ):
            raise SystemExit(f"invalid proposal indices for {asset['asset_id']}")
        if status == "accepted" and not indices:
            raise SystemExit(f"accepted decision has no selected box for {asset['asset_id']}")
        if status != "accepted" and indices:
            raise SystemExit(f"non-accepted decision selects boxes for {asset['asset_id']}")
        selected = [asset["proposals"][index] for index in indices]
        status_counts[status] = status_counts.get(status, 0) + 1
        selected_count += len(selected)
        reviewed_assets.append(
            {
                **asset,
                "review": {
                    "status": status,
                    "reviewer": args.reviewer,
                    "selected_proposal_indices": indices,
                },
                "selected_boxes": selected,
                "training_eligible": status == "accepted",
            }
        )

    output = {
        **manifest,
        "schema_version": "0.2.0",
        "status": "human_review_applied",
        "review": {
            "reviewer": args.reviewer,
            "proposal_manifest_sha256": digest(args.proposals),
            "decisions_sha256": digest(args.decisions),
            "status_counts": dict(sorted(status_counts.items())),
            "selected_box_count": selected_count,
            "ignored_unknown_decision_count": len(unknown),
        },
        "assets": reviewed_assets,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"RESULT OK assets={len(reviewed_assets)} selected_boxes={selected_count} "
        + " ".join(f"{key}={value}" for key, value in sorted(status_counts.items()))
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
