from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
TASK_BACKLOG_PATH = ROOT / "generated" / "task_backlog.json"
COLLABORATION_STATE_PATH = ROOT / "generated" / "collaboration_state.json"
TASK_RUNS_DIR = ROOT / "generated" / "task_runs"

VALID_ASSIGNMENT_STATUSES = {"unclaimed", "claimed", "in_progress", "review", "done", "blocked", "released"}
VALID_ACTOR_KINDS = {"human", "web_ai", "local_ai", "service"}
VALID_RUN_STATUSES = {"review", "done", "blocked", "failed"}


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def expect_string(value: Any, label: str, allow_empty: bool = False) -> None:
    expect(isinstance(value, str), f"{label} must be a string")
    if not allow_empty:
        expect(bool(value.strip()), f"{label} must be a non-empty string")


def expect_list(value: Any, label: str) -> None:
    expect(isinstance(value, list), f"{label} must be a list")


def validate_actor(actor: Any, label: str) -> str:
    expect(isinstance(actor, dict), f"{label} must be an object")
    expect_string(actor.get("actor_id"), f"{label}.actor_id")
    expect_string(actor.get("display_name"), f"{label}.display_name")
    expect(actor.get("kind") in VALID_ACTOR_KINDS, f"{label}.kind must be a valid actor kind")
    expect(actor.get("status") in {"active", "inactive", "blocked"}, f"{label}.status must be a valid actor status")
    return actor["actor_id"]


def validate_assignment(assignment: Any, label: str, task_ids: set[str], actor_ids: set[str]) -> str:
    expect(isinstance(assignment, dict), f"{label} must be an object")
    expect_string(assignment.get("task_id"), f"{label}.task_id")
    task_id = assignment["task_id"]
    expect(task_id in task_ids, f"{label}.task_id must refer to generated/task_backlog.json: {task_id}")
    expect(assignment.get("status") in VALID_ASSIGNMENT_STATUSES, f"{label}.status must be a valid assignment status")
    expect_string(assignment.get("updated_at"), f"{label}.updated_at")

    assigned_to = assignment.get("assigned_to")
    if assigned_to is not None:
        expect_string(assigned_to, f"{label}.assigned_to")
        expect(assigned_to in actor_ids, f"{label}.assigned_to must refer to actors[].actor_id: {assigned_to}")
    elif assignment.get("status") not in {"unclaimed", "released"}:
        raise ValueError(f"{label}.assigned_to is required unless status is unclaimed or released")

    proof = assignment.get("proof", [])
    expect_list(proof, f"{label}.proof")
    for proof_index, proof_ref in enumerate(proof):
        proof_label = f"{label}.proof[{proof_index}]"
        expect(isinstance(proof_ref, dict), f"{proof_label} must be an object")
        expect_string(proof_ref.get("kind"), f"{proof_label}.kind")
        expect_string(proof_ref.get("summary"), f"{proof_label}.summary")
        path_value = proof_ref.get("path")
        if path_value:
            expect_string(path_value, f"{proof_label}.path")

    return task_id


def validate_task_run(path: Path, payload: Any, task_ids: set[str], actor_ids: set[str]) -> list[str]:
    label = rel(path)
    expect(isinstance(payload, dict), f"{label} must be an object")
    expect_string(payload.get("task_id"), f"{label}.task_id")
    expect(payload["task_id"] in task_ids, f"{label}.task_id must refer to generated/task_backlog.json")
    expect_string(payload.get("run_id"), f"{label}.run_id")
    expect_string(payload.get("actor_id"), f"{label}.actor_id")
    expect(payload["actor_id"] in actor_ids, f"{label}.actor_id must refer to collaboration actors")
    expect(payload.get("status") in VALID_RUN_STATUSES, f"{label}.status must be a valid task run status")
    expect_string(payload.get("implementation_summary"), f"{label}.implementation_summary")
    expect_list(payload.get("files_changed"), f"{label}.files_changed")
    expect_list(payload.get("verification"), f"{label}.verification")
    expect_list(payload.get("proof"), f"{label}.proof")
    expect_list(payload.get("blockers"), f"{label}.blockers")
    expect_string(payload.get("updated_at"), f"{label}.updated_at")
    return [f"TASK RUN OK {label}"]


def main() -> int:
    try:
        task_backlog = load_json(TASK_BACKLOG_PATH)
        state = load_json(COLLABORATION_STATE_PATH)

        expect_list(task_backlog, rel(TASK_BACKLOG_PATH))
        expect(isinstance(state, dict), f"{rel(COLLABORATION_STATE_PATH)} must be an object")

        task_ids = {task["id"] for task in task_backlog if isinstance(task, dict) and "id" in task}
        expect(len(task_ids) == len(task_backlog), "generated/task_backlog.json must contain unique task ids")

        expect_list(state.get("actors"), "collaboration_state.actors")
        actor_ids = set()
        for index, actor in enumerate(state["actors"]):
            actor_id = validate_actor(actor, f"collaboration_state.actors[{index}]")
            expect(actor_id not in actor_ids, f"duplicate actor_id: {actor_id}")
            actor_ids.add(actor_id)

        expect_list(state.get("task_assignments"), "collaboration_state.task_assignments")
        assigned_task_ids = set()
        for index, assignment in enumerate(state["task_assignments"]):
            task_id = validate_assignment(assignment, f"collaboration_state.task_assignments[{index}]", task_ids, actor_ids)
            expect(task_id not in assigned_task_ids, f"duplicate task assignment for task_id: {task_id}")
            assigned_task_ids.add(task_id)

        messages = [
            f"COLLAB OK actors={len(actor_ids)} assignments={len(assigned_task_ids)} unclaimed={len(task_ids - assigned_task_ids)}"
        ]

        if TASK_RUNS_DIR.is_dir():
            for path in sorted(TASK_RUNS_DIR.glob("*.json")):
                messages.extend(validate_task_run(path, load_json(path), task_ids, actor_ids))

        for message in messages:
            print(message)
    except Exception as exc:
        print(f"RESULT FAIL {exc}")
        return 1

    print("RESULT OK   collaboration state is internally consistent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
