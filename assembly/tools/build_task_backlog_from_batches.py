from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import validate_task_batches
import validate_seed
from workspace_paths import WorkspacePaths, discover_workspace_paths


ROOT = Path(__file__).resolve().parents[1]


def usage() -> int:
    print("Usage: python tools/build_task_backlog_from_batches.py [project_workspace.json]")
    return 1


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")


def build_backlog(index: dict[str, Any], paths: WorkspacePaths) -> list[dict[str, Any]]:
    validate_task_batches.validate_batch_index(
        index,
        expected_batches_dir=paths.task_batches_dir_rel,
        label=paths.display(paths.task_batch_index),
    )
    validate_seed.expect(
        len(index["batches"]) > 0,
        f"task_batch_index.batches is empty; refusing to overwrite {paths.display(paths.task_backlog)}",
    )

    all_tasks: dict[str, dict[str, Any]] = {}
    task_to_batch: dict[str, str] = {}

    for batch in index["batches"]:
        batch_path = paths.resolve_repo_path(batch["output_path"])
        validate_seed.expect(
            batch_path.is_file(),
            f"missing task batch file: {batch['output_path']}",
        )
        payload = load_json(batch_path)
        validate_task_batches.validate_task_batch(
            batch_path,
            payload,
            batch,
            paths.display(batch_path),
        )

        for task in payload["tasks"]:
            task_id = task["id"]
            validate_seed.expect(task_id not in all_tasks, f"duplicate task id across batches: {task_id}")
            all_tasks[task_id] = task
            task_to_batch[task_id] = batch["batch_id"]

    ordered_task_ids = validate_task_batches.topological_order(all_tasks)
    validate_task_batches.validate_batch_order(index, task_to_batch, all_tasks)
    return [all_tasks[task_id] for task_id in ordered_task_ids]


def main(argv: list[str]) -> int:
    if len(argv) > 2:
        return usage()

    explicit_manifest = argv[1] if len(argv) == 2 else None
    try:
        paths = discover_workspace_paths(ROOT, explicit_manifest)
        if not paths.task_batch_index.is_file():
            print(f"MISSING FAIL {paths.display(paths.task_batch_index)}")
            return 1

        index = load_json(paths.task_batch_index)
        backlog = build_backlog(index, paths)
        write_json(paths.task_backlog, backlog)
    except Exception as exc:
        print(f"RESULT FAIL {exc}")
        return 1

    mode = "workspace" if paths.manifest_path else "root-seed"
    print(f"RESULT OK   mode={mode} wrote {paths.display(paths.task_backlog)} tasks={len(backlog)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
