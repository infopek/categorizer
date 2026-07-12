from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
PLANNING_RUNS_DIR = ROOT / "planning_runs"
OUTPUT_PATH = ROOT / "generated" / "planning_runs_index.json"

REQUIRED_OUTPUTS = [
    "project_spec.json",
    "repo_plan.json",
    "agent_prompts.json",
    "slots_db.json",
]

DEFERRED_OUTPUTS = [
    "task_batch_index.json",
    "task_backlog.json",
]

SCAFFOLD_FILES = {
    "input_idea": "input-idea.md",
    "planning_prompt": "planning-run.md",
    "review_notes": "review-notes.md",
}


def posix(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def build_run_entry(run_dir: Path) -> dict[str, Any]:
    outputs_dir = run_dir / "outputs"
    outputs = {name: (outputs_dir / name).is_file() for name in REQUIRED_OUTPUTS}
    deferred_outputs = {name: (outputs_dir / name).is_file() for name in DEFERRED_OUTPUTS}

    scaffold_presence = {
        key: (run_dir / filename).is_file()
        for key, filename in SCAFFOLD_FILES.items()
    }
    scaffold_presence["outputs_dir"] = outputs_dir.is_dir()

    missing_scaffold = [
        filename
        for key, filename in SCAFFOLD_FILES.items()
        if not scaffold_presence[key]
    ]
    if not scaffold_presence["outputs_dir"]:
        missing_scaffold.append("outputs/")

    missing_outputs = [name for name, present in outputs.items() if not present]
    present_outputs = [name for name, present in outputs.items() if present]

    if missing_scaffold:
        status = "invalid_missing_scaffold"
    elif not present_outputs:
        status = "draft_missing_outputs"
    elif missing_outputs:
        status = "draft_partial_outputs"
    else:
        status = "outputs_present"

    return {
        "slug": run_dir.name,
        "path": posix(run_dir),
        "status": status,
        "has_input_idea": scaffold_presence["input_idea"],
        "has_planning_prompt": scaffold_presence["planning_prompt"],
        "has_review_notes": scaffold_presence["review_notes"],
        "has_outputs_dir": scaffold_presence["outputs_dir"],
        "outputs": outputs,
        "missing_outputs": missing_outputs,
        "deferred_outputs": deferred_outputs,
        "missing_scaffold": missing_scaffold,
        "task_decomposition_deferred": True,
    }


def discover_runs() -> list[dict[str, Any]]:
    if not PLANNING_RUNS_DIR.exists():
        return []

    runs: list[dict[str, Any]] = []
    for child in sorted(PLANNING_RUNS_DIR.iterdir(), key=lambda path: path.name):
        if not child.is_dir():
            continue
        if child.name.startswith(".") or child.name == "__pycache__":
            continue
        runs.append(build_run_entry(child))
    return runs


def main() -> int:
    runs = discover_runs()
    index = {
        "schema_version": "0.1.0",
        "generated_by": "tools/build_planning_runs_index.py",
        "planning_runs_path": "planning_runs",
        "required_outputs": REQUIRED_OUTPUTS,
        "deferred_to_task_splitter": DEFERRED_OUTPUTS,
        "runs": runs,
    }

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"WROTE {posix(OUTPUT_PATH)}")
    print(f"RESULT OK runs={len(runs)} task_decomposition_deferred=true")
    for run in runs:
        print(f"RUN {run['slug']} status={run['status']} missing_outputs={len(run['missing_outputs'])}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
