from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import validate_seed


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_OUTPUTS = {
    "project_spec.json": "contracts/project_spec.schema.json",
    "repo_plan.json": "contracts/repo_plan.schema.json",
    "agent_prompts.json": "contracts/agent_prompt.schema.json",
    "slots_db.json": "contracts/slot.schema.json",
}


def usage() -> int:
    print("Usage: python tools/validate_planning_run.py <run-dir-or-outputs-dir>")
    return 1


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def display_path(path: Path) -> str:
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def resolve_outputs_dir(path_arg: str) -> Path:
    candidate = Path(path_arg).resolve()
    if not candidate.exists() or not candidate.is_dir():
        raise ValueError(f"run or outputs directory not found: {path_arg}")
    if candidate.name == "outputs":
        return candidate
    outputs_dir = candidate / "outputs"
    if outputs_dir.exists() and outputs_dir.is_dir():
        return outputs_dir
    return candidate


def validate_required_outputs(outputs_dir: Path) -> int:
    missing_count = 0
    for file_name in REQUIRED_OUTPUTS:
        path = outputs_dir / file_name
        if not path.exists():
            print(f"MISSING FAIL {display_path(path)}")
            missing_count += 1
    return missing_count


def validate_builtin_payloads(outputs_dir: Path, payloads: dict[str, Any]) -> list[str]:
    messages: list[str] = []

    validate_seed.validate_project_spec(payloads["project_spec.json"], f"{outputs_dir.name}/project_spec.json")
    messages.append("SCHEMA OK   project_spec.json -> contracts/project_spec.schema.json (builtin)")

    validate_seed.validate_repo_plan(payloads["repo_plan.json"], f"{outputs_dir.name}/repo_plan.json")
    messages.append("SCHEMA OK   repo_plan.json -> contracts/repo_plan.schema.json (builtin)")

    validate_seed.validate_agent_prompt_set(payloads["agent_prompts.json"], f"{outputs_dir.name}/agent_prompts.json")
    messages.append("SCHEMA OK   agent_prompts.json -> contracts/agent_prompt.schema.json (builtin)")

    slots_db = payloads["slots_db.json"]
    validate_seed.expect_type(slots_db, list, f"{outputs_dir.name}/slots_db.json")
    for index, slot in enumerate(slots_db):
        validate_seed.validate_slot(slot, f"{outputs_dir.name}/slots_db.json[{index}]")
        messages.append(f"SCHEMA OK   slots_db.json[{index}] -> contracts/slot.schema.json (builtin)")

    return messages


def validate_jsonschema_payloads(outputs_dir: Path, payloads: dict[str, Any]) -> list[str]:
    jsonschema_module = validate_seed.load_optional_jsonschema()
    if jsonschema_module is None:
        return validate_builtin_payloads(outputs_dir, payloads)

    schemas = {
        schema_path: load_json(ROOT / schema_path)
        for schema_path in REQUIRED_OUTPUTS.values()
    }

    messages: list[str] = []
    jsonschema_module.validate(payloads["project_spec.json"], schemas["contracts/project_spec.schema.json"])
    messages.append("SCHEMA OK   project_spec.json -> contracts/project_spec.schema.json")

    jsonschema_module.validate(payloads["repo_plan.json"], schemas["contracts/repo_plan.schema.json"])
    messages.append("SCHEMA OK   repo_plan.json -> contracts/repo_plan.schema.json")

    jsonschema_module.validate(payloads["agent_prompts.json"], schemas["contracts/agent_prompt.schema.json"])
    messages.append("SCHEMA OK   agent_prompts.json -> contracts/agent_prompt.schema.json")

    slots_db = payloads["slots_db.json"]
    validate_seed.expect_type(slots_db, list, f"{outputs_dir.name}/slots_db.json")
    for index, slot in enumerate(slots_db):
        jsonschema_module.validate(slot, schemas["contracts/slot.schema.json"])
        messages.append(f"SCHEMA OK   slots_db.json[{index}] -> contracts/slot.schema.json")

    return messages


def run_consistency_checks(payloads: dict[str, Any]) -> list[str]:
    project_spec = payloads["project_spec.json"]
    repo_plan = payloads["repo_plan.json"]
    agent_prompts = payloads["agent_prompts.json"]
    slots_db = payloads["slots_db.json"]

    repo_names = {repo["name"] for repo in repo_plan["repos"]}

    validate_seed.expect(
        project_spec["project_name"] == repo_plan["project_name"] == agent_prompts["project_name"],
        "project_name must match across project_spec.json, repo_plan.json, and agent_prompts.json",
    )

    for index, prompt in enumerate(agent_prompts["prompts"]):
        validate_seed.expect(
            prompt["target_repo"] in repo_names,
            f"agent_prompts.json[{index}].target_repo must exist in repo_plan.json",
        )

    prompt_roles = {prompt["role"] for prompt in agent_prompts["prompts"]}
    warnings = []
    for slot in slots_db:
        if slot["role"] not in prompt_roles:
            warnings.append(f"CONSISTENCY WARN slot role has no matching prompt role: {slot['role']}")

    return [
        "CONSISTENCY OK project_name aligns across primary planning artifacts",
        "CONSISTENCY OK prompt target_repo values map to repo_plan.json",
        "SEPARATION OK planning run does not require task_backlog.json",
        *warnings,
    ]


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        return usage()

    try:
        outputs_dir = resolve_outputs_dir(argv[1])
    except ValueError as exc:
        print(f"ERROR {exc}")
        return 1

    missing_required = validate_required_outputs(outputs_dir)
    if missing_required:
        print(f"RESULT FAIL missing_required={missing_required}")
        return 1

    payloads: dict[str, Any] = {}
    parse_failures = 0

    for file_name in REQUIRED_OUTPUTS:
        path = outputs_dir / file_name
        try:
            payloads[file_name] = load_json(path)
            print(f"JSON OK     {display_path(path)}")
        except json.JSONDecodeError as exc:
            parse_failures += 1
            print(f"PARSE FAIL {display_path(path)}: {exc.msg} (line {exc.lineno}, column {exc.colno})")
        except OSError as exc:
            parse_failures += 1
            print(f"PARSE FAIL {display_path(path)}: {exc}")

    if parse_failures:
        print(f"RESULT FAIL parse_failures={parse_failures}")
        return 1

    try:
        for message in validate_jsonschema_payloads(outputs_dir, payloads):
            print(message)
    except Exception as exc:
        print(f"SCHEMA FAIL {display_path(outputs_dir)}: {exc}")
        print("RESULT FAIL schema_failures=1")
        return 1

    try:
        for message in run_consistency_checks(payloads):
            print(message)
    except ValueError as exc:
        print(f"CONSISTENCY FAIL {exc}")
        print("RESULT FAIL consistency_failures=1")
        return 1

    print("RESULT OK   planning_run_outputs_valid task_backlog_deferred=true")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
