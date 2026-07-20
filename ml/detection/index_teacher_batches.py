#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import html
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch-index", type=Path, required=True)
    parser.add_argument("--proposals-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    batch_index = json.loads(args.batch_index.read_text(encoding="utf-8"))
    records = []
    all_ids: set[str] = set()
    total_assets = 0
    total_proposals = 0
    without_proposals = 0
    for batch in batch_index["batches"]:
        name = Path(batch["filename"]).stem
        source_path = args.batch_index.parent / batch["filename"]
        proposal_path = args.proposals_root / name / "proposals.json"
        review_path = args.proposals_root / name / "review.html"
        reviewed_path = args.proposals_root / name / "reviewed-annotations.json"
        source = json.loads(source_path.read_text(encoding="utf-8"))
        proposals = json.loads(proposal_path.read_text(encoding="utf-8"))
        source_ids = {item["asset_id"] for item in source["assets"]}
        proposal_ids = {item["sample_asset_id"] for item in proposals["assets"]}
        if source_ids != proposal_ids or len(proposals["assets"]) != batch["asset_count"]:
            raise SystemExit(f"teacher batch identity mismatch: {name}")
        if all_ids & proposal_ids:
            raise SystemExit(f"teacher batch contains duplicate assets: {name}")
        all_ids.update(proposal_ids)
        batch_proposals = sum(len(item["proposals"]) for item in proposals["assets"])
        batch_without = sum(not item["proposals"] for item in proposals["assets"])
        total_assets += len(proposals["assets"])
        total_proposals += batch_proposals
        without_proposals += batch_without
        records.append(
            {
                "index": batch["index"],
                "name": name,
                "asset_count": len(proposals["assets"]),
                "proposal_count": batch_proposals,
                "without_proposals": batch_without,
                "proposal_manifest_sha256": hashlib.sha256(proposal_path.read_bytes()).hexdigest(),
                "review_path": str(review_path.relative_to(args.output.parent)),
                "review_complete": reviewed_path.is_file(),
            }
        )
    if total_assets != batch_index["asset_count"]:
        raise SystemExit("teacher index asset total mismatch")
    payload = {
        "schema_version": "0.1.0",
        "status": "teacher_proposals_pending_batched_human_review",
        "asset_count": total_assets,
        "proposal_count": total_proposals,
        "without_proposals": without_proposals,
        "batch_count": len(records),
        "completed_batch_count": sum(record["review_complete"] for record in records),
        "batches": records,
    }
    args.output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    def review_cell(record: dict[str, object]) -> str:
        if record["review_complete"]:
            return "✓ Applied"
        return f'<a href="{html.escape(str(record["review_path"]))}">Open review</a>'

    rows = "".join(
        f'<tr><td>{record["index"] + 1}</td><td>{record["asset_count"]}</td>'
        f'<td>{record["proposal_count"]}</td><td>{record["without_proposals"]}</td>'
        f'<td>{review_cell(record)}</td></tr>'
        for record in records
    )
    dashboard = f'''<!doctype html><meta charset="utf-8"><title>Detection review batches</title>
    <style>body{{font:20px system-ui;max-width:900px;margin:30px auto;padding:16px}}table{{border-collapse:collapse;width:100%}}td,th{{padding:12px;border:1px solid #aaa}}a{{display:inline-block;padding:10px 16px;background:#1769aa;color:white;border-radius:8px;text-decoration:none}}</style>
    <h1>Detection review</h1><p>{total_assets} images · {total_proposals} proposed boxes · {without_proposals} without proposals</p>
    <table><thead><tr><th>Batch</th><th>Images</th><th>Boxes</th><th>No proposal</th><th></th></tr></thead><tbody>{rows}</tbody></table>'''
    args.output.with_name("review-index.html").write_text(dashboard, encoding="utf-8")
    print(f"RESULT OK assets={total_assets} proposals={total_proposals} without_proposals={without_proposals} batches={len(records)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
