from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_FILES = [
    ROOT / "generated" / "project_spec.json",
    ROOT / "generated" / "repo_plan.json",
    ROOT / "generated" / "task_backlog.json",
    ROOT / "generated" / "task_batch_index.json",
    ROOT / "generated" / "agent_prompts.json",
    ROOT / "generated" / "slots_db.json",
    ROOT / "generated" / "review_manifest.json",
    ROOT / "contracts" / "project_spec.schema.json",
    ROOT / "contracts" / "repo_plan.schema.json",
    ROOT / "contracts" / "task.schema.json",
    ROOT / "contracts" / "task_batch_index.schema.json",
    ROOT / "contracts" / "task_batch.schema.json",
    ROOT / "contracts" / "slot.schema.json",
    ROOT / "contracts" / "agent_prompt.schema.json",
    ROOT / "contracts" / "collaboration_state.schema.json",
    ROOT / "contracts" / "api_contract.openapi.yaml",
]


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def discover_json_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*.json") if path.is_file())


def format_rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def load_optional_jsonschema():
    try:
        import jsonschema  # type: ignore
    except ImportError:
        return None
    return jsonschema


def load_optional_yaml():
    try:
        import yaml  # type: ignore
    except ImportError:
        return None
    return yaml


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def expect_type(value: Any, expected_type: type | tuple[type, ...], label: str) -> None:
    expect(isinstance(value, expected_type), f"{label} must be {expected_type}, got {type(value)}")


def expect_non_empty_string(value: Any, label: str) -> None:
    expect_type(value, str, label)
    expect(bool(value.strip()), f"{label} must be a non-empty string")


def expect_string_array(value: Any, label: str, min_items: int = 0) -> None:
    expect_type(value, list, label)
    expect(len(value) >= min_items, f"{label} must contain at least {min_items} item(s)")
    for index, item in enumerate(value):
        expect_non_empty_string(item, f"{label}[{index}]")


def expect_object_keys(value: Any, label: str, required: set[str], optional: set[str] | None = None) -> None:
    optional = optional or set()
    expect_type(value, dict, label)
    keys = set(value.keys())
    missing = required - keys
    unexpected = keys - required - optional
    expect(not missing, f"{label} is missing required key(s): {sorted(missing)}")
    expect(not unexpected, f"{label} has unexpected key(s): {sorted(unexpected)}")


def validate_repo_unit(value: Any, label: str) -> None:
    expect_object_keys(value, label, {"name", "purpose", "contains", "depends_on"})
    expect_non_empty_string(value["name"], f"{label}.name")
    expect_non_empty_string(value["purpose"], f"{label}.purpose")
    expect_string_array(value["contains"], f"{label}.contains")
    expect_string_array(value["depends_on"], f"{label}.depends_on")


def validate_project_spec(value: Any, label: str) -> None:
    expect_object_keys(
        value,
        label,
        {
            "project_name",
            "product_summary",
            "safety_boundaries",
            "repo_split",
            "domain_model",
            "frontend_screens",
            "backend_services",
            "core_engine_responsibilities",
            "verification_tasks",
            "starter_prompts",
        },
    )
    expect_non_empty_string(value["project_name"], f"{label}.project_name")

    summary = value["product_summary"]
    expect_object_keys(summary, f"{label}.product_summary", {"goal", "users", "non_goals"})
    expect_non_empty_string(summary["goal"], f"{label}.product_summary.goal")
    expect_string_array(summary["users"], f"{label}.product_summary.users", min_items=1)
    expect_string_array(summary["non_goals"], f"{label}.product_summary.non_goals")

    expect_string_array(value["safety_boundaries"], f"{label}.safety_boundaries", min_items=1)

    repo_split = value["repo_split"]
    expect_type(repo_split, list, f"{label}.repo_split")
    expect(len(repo_split) >= 1, f"{label}.repo_split must contain at least 1 item")
    for index, item in enumerate(repo_split):
        validate_repo_unit(item, f"{label}.repo_split[{index}]")

    domain_model = value["domain_model"]
    expect_type(domain_model, list, f"{label}.domain_model")
    expect(len(domain_model) >= 1, f"{label}.domain_model must contain at least 1 item")
    for index, item in enumerate(domain_model):
        entry_label = f"{label}.domain_model[{index}]"
        expect_object_keys(item, entry_label, {"name", "fields"}, {"relations"})
        expect_non_empty_string(item["name"], f"{entry_label}.name")
        expect_string_array(item["fields"], f"{entry_label}.fields")
        if "relations" in item:
            expect_string_array(item["relations"], f"{entry_label}.relations")

    screens = value["frontend_screens"]
    expect_type(screens, list, f"{label}.frontend_screens")
    expect(len(screens) >= 1, f"{label}.frontend_screens must contain at least 1 item")
    for index, item in enumerate(screens):
        entry_label = f"{label}.frontend_screens[{index}]"
        expect_object_keys(item, entry_label, {"name", "renders_from"}, {"notes"})
        expect_non_empty_string(item["name"], f"{entry_label}.name")
        expect_string_array(item["renders_from"], f"{entry_label}.renders_from")
        if "notes" in item:
            expect_non_empty_string(item["notes"], f"{entry_label}.notes")

    services = value["backend_services"]
    expect_type(services, list, f"{label}.backend_services")
    expect(len(services) >= 1, f"{label}.backend_services must contain at least 1 item")
    for index, item in enumerate(services):
        entry_label = f"{label}.backend_services[{index}]"
        expect_object_keys(item, entry_label, {"name", "responsibility"}, {"inputs", "outputs"})
        expect_non_empty_string(item["name"], f"{entry_label}.name")
        expect_non_empty_string(item["responsibility"], f"{entry_label}.responsibility")
        if "inputs" in item:
            expect_string_array(item["inputs"], f"{entry_label}.inputs")
        if "outputs" in item:
            expect_string_array(item["outputs"], f"{entry_label}.outputs")

    expect_string_array(
        value["core_engine_responsibilities"],
        f"{label}.core_engine_responsibilities",
        min_items=1,
    )
    expect_string_array(value["verification_tasks"], f"{label}.verification_tasks", min_items=1)

    prompts = value["starter_prompts"]
    required_prompts = {
        "intake_interviewer",
        "planning_agent",
        "contract_steward",
        "frontend_builder",
        "backend_builder",
        "core_engine_builder",
        "red_team_verifier",
    }
    expect_object_keys(prompts, f"{label}.starter_prompts", required_prompts)
    for key in required_prompts:
        expect_non_empty_string(prompts[key], f"{label}.starter_prompts.{key}")


def validate_repo_plan(value: Any, label: str) -> None:
    expect_object_keys(value, label, {"project_name", "repos"})
    expect_non_empty_string(value["project_name"], f"{label}.project_name")
    repos = value["repos"]
    expect_type(repos, list, f"{label}.repos")
    expect(len(repos) >= 1, f"{label}.repos must contain at least 1 item")
    for index, item in enumerate(repos):
        entry_label = f"{label}.repos[{index}]"
        expect_object_keys(
            item,
            entry_label,
            {"name", "purpose", "contains", "depends_on", "excludes"},
        )
        expect_non_empty_string(item["name"], f"{entry_label}.name")
        expect_non_empty_string(item["purpose"], f"{entry_label}.purpose")
        expect_string_array(item["contains"], f"{entry_label}.contains")
        expect_string_array(item["depends_on"], f"{entry_label}.depends_on")
        expect_string_array(item["excludes"], f"{entry_label}.excludes")


def validate_task(value: Any, label: str) -> None:
    expect_object_keys(
        value,
        label,
        {
            "id",
            "title",
            "summary",
            "owner_role",
            "repo_target",
            "depends_on",
            "inputs",
            "outputs",
            "acceptance_criteria",
            "verification",
        },
        {
            "risk_tags",
            "notes",
            "lane",
            "allowed_areas",
            "handoff_notes",
            "status",
            "priority",
            "milestone",
            "blocks",
            "objective",
            "context",
            "implementation_notes",
            "proof_required",
            "edge_cases",
            "non_goals",
            "estimated_size",
        },
    )
    expect_non_empty_string(value["id"], f"{label}.id")
    expect(
        re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9\-]*", value["id"]) is not None,
        f"{label}.id must match ^[A-Za-z0-9][A-Za-z0-9\\-]*$",
    )
    expect_non_empty_string(value["title"], f"{label}.title")
    expect_non_empty_string(value["summary"], f"{label}.summary")
    if "objective" in value:
        expect_non_empty_string(value["objective"], f"{label}.objective")
    if "context" in value:
        expect_non_empty_string(value["context"], f"{label}.context")
    expect_non_empty_string(value["owner_role"], f"{label}.owner_role")
    if "lane" in value:
        expect_non_empty_string(value["lane"], f"{label}.lane")
    expect_non_empty_string(value["repo_target"], f"{label}.repo_target")
    if "status" in value:
        expect(
            value["status"] in {"draft", "ready", "in_progress", "blocked", "review", "done", "rejected"},
            f"{label}.status must be one of the allowed task states",
        )
    if "priority" in value:
        expect(value["priority"] in {"P0", "P1", "P2", "P3"}, f"{label}.priority must be P0, P1, P2, or P3")
    if "milestone" in value:
        expect_non_empty_string(value["milestone"], f"{label}.milestone")
    if "allowed_areas" in value:
        expect_string_array(value["allowed_areas"], f"{label}.allowed_areas")
    expect_string_array(value["depends_on"], f"{label}.depends_on")
    if "blocks" in value:
        expect_string_array(value["blocks"], f"{label}.blocks")
    expect_string_array(value["inputs"], f"{label}.inputs")
    expect_string_array(value["outputs"], f"{label}.outputs")
    if "implementation_notes" in value:
        expect_string_array(value["implementation_notes"], f"{label}.implementation_notes")
    expect_string_array(value["acceptance_criteria"], f"{label}.acceptance_criteria", min_items=1)
    expect_string_array(value["verification"], f"{label}.verification", min_items=1)
    if "proof_required" in value:
        expect_string_array(value["proof_required"], f"{label}.proof_required")
    if "edge_cases" in value:
        expect_string_array(value["edge_cases"], f"{label}.edge_cases")
    if "non_goals" in value:
        expect_string_array(value["non_goals"], f"{label}.non_goals")
    if "estimated_size" in value:
        expect(value["estimated_size"] in {"S", "M", "L"}, f"{label}.estimated_size must be S, M, or L")
    if "risk_tags" in value:
        expect_string_array(value["risk_tags"], f"{label}.risk_tags")
    if "notes" in value:
        expect_non_empty_string(value["notes"], f"{label}.notes")
    if "handoff_notes" in value:
        expect_non_empty_string(value["handoff_notes"], f"{label}.handoff_notes")


def validate_task_batch_index(value: Any, label: str) -> None:
    expect_object_keys(value, label, {"schema_version", "source_plan_path", "batching_strategy", "batches"})
    expect_non_empty_string(value["schema_version"], f"{label}.schema_version")
    expect_non_empty_string(value["source_plan_path"], f"{label}.source_plan_path")
    expect(
        value["batching_strategy"] in {"owner_role", "repo_target", "lane", "milestone", "mixed"},
        f"{label}.batching_strategy must be an allowed strategy",
    )
    expect_type(value["batches"], list, f"{label}.batches")

    batch_ids: set[str] = set()
    task_ids: set[str] = set()
    for index, batch in enumerate(value["batches"]):
        batch_label = f"{label}.batches[{index}]"
        expect_object_keys(
            batch,
            batch_label,
            {
                "batch_id",
                "owner_role",
                "repo_targets",
                "lanes",
                "milestones",
                "expected_task_count",
                "expected_task_ids",
                "depends_on_batches",
                "output_path",
                "status",
            },
            {"notes"},
        )
        expect_non_empty_string(batch["batch_id"], f"{batch_label}.batch_id")
        expect(
            re.fullmatch(r"[a-z0-9][a-z0-9\-]*", batch["batch_id"]) is not None,
            f"{batch_label}.batch_id must match ^[a-z0-9][a-z0-9\\-]*$",
        )
        expect(batch["batch_id"] not in batch_ids, f"{batch_label}.batch_id must be unique")
        batch_ids.add(batch["batch_id"])
        expect_non_empty_string(batch["owner_role"], f"{batch_label}.owner_role")
        expect_string_array(batch["repo_targets"], f"{batch_label}.repo_targets")
        expect_string_array(batch["lanes"], f"{batch_label}.lanes")
        expect_string_array(batch["milestones"], f"{batch_label}.milestones")
        expect_type(batch["expected_task_count"], int, f"{batch_label}.expected_task_count")
        expect(batch["expected_task_count"] >= 0, f"{batch_label}.expected_task_count must be >= 0")
        expect_string_array(batch["expected_task_ids"], f"{batch_label}.expected_task_ids")
        expect(
            len(batch["expected_task_ids"]) == batch["expected_task_count"],
            f"{batch_label}.expected_task_ids length must match expected_task_count",
        )
        for task_id in batch["expected_task_ids"]:
            expect(task_id not in task_ids, f"{batch_label}.expected_task_ids contains duplicate task id: {task_id}")
            task_ids.add(task_id)
        expect_string_array(batch["depends_on_batches"], f"{batch_label}.depends_on_batches")
        expect_non_empty_string(batch["output_path"], f"{batch_label}.output_path")
        expect(
            batch["output_path"] == f"generated/task_batches/{batch['batch_id']}.json",
            f"{batch_label}.output_path must match batch_id",
        )
        expect(
            batch["status"] in {"planned", "generated", "validated", "accepted", "rejected"},
            f"{batch_label}.status must be an allowed status",
        )
        if "notes" in batch:
            expect_non_empty_string(batch["notes"], f"{batch_label}.notes")

    for index, batch in enumerate(value["batches"]):
        for dependency in batch["depends_on_batches"]:
            expect(
                dependency in batch_ids,
                f"{label}.batches[{index}].depends_on_batches refers to unknown batch: {dependency}",
            )


def validate_slot(value: Any, label: str) -> None:
    expect_object_keys(
        value,
        label,
        {
            "slot_id",
            "role",
            "status",
            "inputs",
            "outputs",
            "allowed_actions",
            "verification_requirements",
        },
        {"notes"},
    )
    expect_non_empty_string(value["slot_id"], f"{label}.slot_id")
    expect(re.fullmatch(r"[a-z0-9\-]+", value["slot_id"]) is not None, f"{label}.slot_id must match ^[a-z0-9\\-]+$")
    expect_non_empty_string(value["role"], f"{label}.role")
    expect(
        value["status"] in {"planned", "ready", "active", "blocked", "review", "complete"},
        f"{label}.status must be one of the allowed slot states",
    )
    expect_string_array(value["inputs"], f"{label}.inputs")
    expect_string_array(value["outputs"], f"{label}.outputs")
    expect_string_array(value["allowed_actions"], f"{label}.allowed_actions", min_items=1)
    expect_string_array(
        value["verification_requirements"],
        f"{label}.verification_requirements",
        min_items=1,
    )
    if "notes" in value:
        expect_non_empty_string(value["notes"], f"{label}.notes")


def validate_agent_prompt_set(value: Any, label: str) -> None:
    expect_object_keys(value, label, {"project_name", "prompts"})
    expect_non_empty_string(value["project_name"], f"{label}.project_name")
    prompts = value["prompts"]
    expect_type(prompts, list, f"{label}.prompts")
    expect(len(prompts) >= 1, f"{label}.prompts must contain at least 1 item")
    for index, item in enumerate(prompts):
        entry_label = f"{label}.prompts[{index}]"
        expect_object_keys(
            item,
            entry_label,
            {
                "prompt_id",
                "role",
                "target_repo",
                "allowed_files",
                "forbidden_files",
                "input_context_required",
                "task_boundaries",
                "output_required",
                "verification_required",
            },
        )
        expect_non_empty_string(item["prompt_id"], f"{entry_label}.prompt_id")
        expect(
            re.fullmatch(r"[a-z0-9\-]+", item["prompt_id"]) is not None,
            f"{entry_label}.prompt_id must match ^[a-z0-9\\-]+$",
        )
        expect_non_empty_string(item["role"], f"{entry_label}.role")
        expect_non_empty_string(item["target_repo"], f"{entry_label}.target_repo")
        expect_string_array(item["allowed_files"], f"{entry_label}.allowed_files")
        expect_string_array(item["forbidden_files"], f"{entry_label}.forbidden_files")
        expect_string_array(
            item["input_context_required"],
            f"{entry_label}.input_context_required",
            min_items=1,
        )
        expect_string_array(item["task_boundaries"], f"{entry_label}.task_boundaries", min_items=1)
        expect_string_array(item["output_required"], f"{entry_label}.output_required", min_items=1)
        expect_string_array(
            item["verification_required"],
            f"{entry_label}.verification_required",
            min_items=1,
        )


def validate_without_jsonschema(path: Path, payload: Any) -> list[str]:
    rel = format_rel(path)
    messages: list[str] = []

    if rel == "generated/project_spec.json":
        validate_project_spec(payload, rel)
        return [f"SCHEMA OK   {rel} -> contracts/project_spec.schema.json (builtin)"]
    if rel == "generated/repo_plan.json":
        validate_repo_plan(payload, rel)
        return [f"SCHEMA OK   {rel} -> contracts/repo_plan.schema.json (builtin)"]
    if rel == "generated/agent_prompts.json":
        validate_agent_prompt_set(payload, rel)
        return [f"SCHEMA OK   {rel} -> contracts/agent_prompt.schema.json (builtin)"]
    if rel == "generated/task_batch_index.json":
        validate_task_batch_index(payload, rel)
        return [f"SCHEMA OK   {rel} -> contracts/task_batch_index.schema.json (builtin)"]
    if rel == "examples/coc-base-builder/generated-repo-plan.json":
        validate_repo_plan(payload, rel)
        return [f"SCHEMA OK   {rel} -> contracts/repo_plan.schema.json (builtin)"]
    if rel in {"generated/task_backlog.json", "examples/coc-base-builder/generated-task-backlog.json"}:
        expect_type(payload, list, rel)
        for index, item in enumerate(payload):
            validate_task(item, f"{rel}[{index}]")
            messages.append(f"SCHEMA OK   {rel}[{index}] -> contracts/task.schema.json (builtin)")
        return messages
    if rel == "generated/slots_db.json":
        expect_type(payload, list, rel)
        for index, item in enumerate(payload):
            validate_slot(item, f"{rel}[{index}]")
            messages.append(f"SCHEMA OK   {rel}[{index}] -> contracts/slot.schema.json (builtin)")
        return messages

    return []


def validate_with_schema(
    path: Path,
    payload: Any,
    schemas: dict[str, Any],
    jsonschema_module: Any,
) -> list[str]:
    rel = format_rel(path)

    direct_map = {
        "generated/project_spec.json": "contracts/project_spec.schema.json",
        "generated/repo_plan.json": "contracts/repo_plan.schema.json",
        "generated/agent_prompts.json": "contracts/agent_prompt.schema.json",
        "examples/coc-base-builder/generated-repo-plan.json": "contracts/repo_plan.schema.json",
    }
    element_map = {
        "generated/task_backlog.json": "contracts/task.schema.json",
        "generated/task_batch_index.json": "contracts/task_batch_index.schema.json",
        "generated/slots_db.json": "contracts/slot.schema.json",
        "examples/coc-base-builder/generated-task-backlog.json": "contracts/task.schema.json",
    }

    if rel in direct_map:
        schema = schemas[direct_map[rel]]
        jsonschema_module.validate(instance=payload, schema=schema)
        return [f"SCHEMA OK   {rel} -> {direct_map[rel]}"]

    if rel in element_map:
        if not isinstance(payload, list):
            raise jsonschema_module.ValidationError("Expected a JSON array.")
        schema = schemas[element_map[rel]]
        messages = []
        for index, item in enumerate(payload):
            jsonschema_module.validate(instance=item, schema=schema)
            messages.append(
                f"SCHEMA OK   {rel}[{index}] -> {element_map[rel]}"
            )
        return messages

    if rel.startswith("generated/task_batches/") and rel.endswith(".json"):
        schema = schemas["contracts/task_batch.schema.json"]
        jsonschema_module.validate(instance=payload, schema=schema)
        return [f"SCHEMA OK   {rel} -> contracts/task_batch.schema.json"]

    return []


def collect_string_values(value: Any) -> list[str]:
    values: list[str] = []

    if isinstance(value, str):
        values.append(value)
    elif isinstance(value, list):
        for item in value:
            values.extend(collect_string_values(item))
    elif isinstance(value, dict):
        for item in value.values():
            values.extend(collect_string_values(item))

    return values


def path_exists_or_pattern(path_value: str) -> bool:
    path = ROOT / path_value
    if path.exists():
        return True
    if any(char in path_value for char in "*?[]"):
        return any(candidate.is_file() for candidate in ROOT.glob(path_value))
    if path_value.endswith("/"):
        return path.is_dir()
    return False


def validate_required_files() -> int:
    missing_count = 0
    for path in REQUIRED_FILES:
        if not path.exists():
            print(f"MISSING FAIL {format_rel(path)}")
            missing_count += 1
    return missing_count


def validate_yaml_contract() -> int:
    yaml_module = load_optional_yaml()
    rel = "contracts/api_contract.openapi.yaml"
    path = ROOT / rel

    if yaml_module is None:
        print(f"YAML SKIP {rel} (PyYAML not installed)")
        return 0

    try:
        with path.open("r", encoding="utf-8") as handle:
            yaml_module.safe_load(handle)
    except OSError as exc:
        print(f"YAML FAIL {rel}: {exc}")
        return 1
    except Exception as exc:
        print(f"YAML FAIL {rel}: {exc}")
        return 1

    print(f"YAML OK   {rel}")
    return 0


def run_consistency_checks(parsed_payloads: dict[Path, Any]) -> list[str]:
    messages: list[str] = []
    warnings: list[str] = []

    project_spec = parsed_payloads[ROOT / "generated" / "project_spec.json"]
    repo_plan = parsed_payloads[ROOT / "generated" / "repo_plan.json"]
    task_backlog = parsed_payloads[ROOT / "generated" / "task_backlog.json"]
    agent_prompts = parsed_payloads[ROOT / "generated" / "agent_prompts.json"]
    slots_db = parsed_payloads[ROOT / "generated" / "slots_db.json"]

    repo_names = {repo["name"] for repo in repo_plan["repos"]}
    task_ids = {task["id"] for task in task_backlog}

    for index, task in enumerate(task_backlog):
        expect(
            task["repo_target"] in repo_names,
            f"generated/task_backlog.json[{index}].repo_target must exist in generated/repo_plan.json",
        )
        for dependency in task["depends_on"]:
            expect(
                dependency in task_ids,
                f"generated/task_backlog.json[{index}].depends_on entry must refer to an existing task id: {dependency}",
            )

    for index, prompt in enumerate(agent_prompts["prompts"]):
        expect(
            prompt["target_repo"] in repo_names,
            f"generated/agent_prompts.json[{index}].target_repo must exist in generated/repo_plan.json",
        )

    for index, screen in enumerate(project_spec["frontend_screens"]):
        for render_path in screen["renders_from"]:
            expect(
                path_exists_or_pattern(render_path),
                f"generated/project_spec.json.frontend_screens[{index}].renders_from path must exist: {render_path}",
            )

    for repo_index, repo in enumerate(repo_plan["repos"]):
        for contains_path in repo["contains"]:
            expect(
                path_exists_or_pattern(contains_path),
                f"generated/repo_plan.json.repos[{repo_index}].contains path must exist or match a documented pattern: {contains_path}",
            )

    forbidden_strings = {
        "seed-web-placeholder",
        "web/static-docs-or-dashboard-placeholder",
    }
    generated_files = [
        ROOT / "generated" / "project_spec.json",
        ROOT / "generated" / "repo_plan.json",
        ROOT / "generated" / "task_backlog.json",
        ROOT / "generated" / "agent_prompts.json",
        ROOT / "generated" / "slots_db.json",
    ]
    for path in generated_files:
        rel = format_rel(path)
        string_values = collect_string_values(parsed_payloads[path])
        for forbidden in forbidden_strings:
            expect(
                forbidden not in string_values,
                f"{rel} must not reference stale value: {forbidden}",
            )

    screens = project_spec["frontend_screens"]
    screen_names = {screen["name"] for screen in screens}
    expected_screen_names = {
        "Overview",
        "Repo Split",
        "Task Backlog",
        "Prompt Pack",
        "Slot Board",
        "Contracts and Verification",
    }
    expect(
        expected_screen_names.issubset(screen_names),
        "generated/project_spec.json.frontend_screens must include the actual viewer pages, including Slot Board",
    )

    slot_screen = next(screen for screen in screens if screen["name"] == "Slot Board")
    expect(
        "generated/slots_db.json" in slot_screen["renders_from"],
        "generated/project_spec.json Slot Board screen must render from generated/slots_db.json",
    )

    messages.append("CONSISTENCY OK generated task repo_target values map to generated/repo_plan.json")
    messages.append("CONSISTENCY OK generated task depends_on values refer to existing task ids")
    messages.append("CONSISTENCY OK generated prompt target_repo values map to generated/repo_plan.json")
    messages.append("CONSISTENCY OK generated frontend_screens renders_from paths exist")
    messages.append("CONSISTENCY OK generated repo_plan contains paths exist or match documented patterns")
    messages.append("CONSISTENCY OK generated artifacts contain no stale web placeholder references")
    messages.append("CONSISTENCY OK generated frontend_screens includes the actual viewer pages, including Slot Board")

    prompt_roles = {prompt["role"] for prompt in agent_prompts["prompts"]}
    for slot in slots_db:
        if slot["role"] not in prompt_roles:
            warnings.append(f"CONSISTENCY WARN slot role has no matching generated prompt role: {slot['role']}")

    return messages + warnings


def main() -> int:
    missing_required = validate_required_files()
    if missing_required:
        print(f"RESULT FAIL missing_required={missing_required}")
        return 1

    json_files = discover_json_files(ROOT)
    if not json_files:
        print("No JSON files found.")
        return 1

    jsonschema_module = load_optional_jsonschema()
    schemas: dict[str, Any] = {}

    parse_failures = 0
    schema_failures = 0
    yaml_failures = 0
    parsed_payloads: dict[Path, Any] = {}

    for path in json_files:
        rel = format_rel(path)
        try:
            payload = load_json(path)
        except json.JSONDecodeError as exc:
            parse_failures += 1
            print(
                f"PARSE FAIL {rel}: {exc.msg} "
                f"(line {exc.lineno}, column {exc.colno})"
            )
            continue
        except OSError as exc:
            parse_failures += 1
            print(f"PARSE FAIL {rel}: {exc}")
            continue

        parsed_payloads[path] = payload
        print(f"JSON OK     {rel}")

        if rel.startswith("contracts/"):
            schemas[rel] = payload

    if jsonschema_module is None:
        for path, payload in parsed_payloads.items():
            rel = format_rel(path)
            try:
                messages = validate_without_jsonschema(path, payload)
                for message in messages:
                    print(message)
            except ValueError as exc:
                schema_failures += 1
                print(f"SCHEMA FAIL {rel}: {exc}")
    else:
        for path, payload in parsed_payloads.items():
            rel = format_rel(path)
            try:
                messages = validate_with_schema(
                    path=path,
                    payload=payload,
                    schemas=schemas,
                    jsonschema_module=jsonschema_module,
                )
                for message in messages:
                    print(message)
            except jsonschema_module.ValidationError as exc:
                schema_failures += 1
                detail = exc.message
                location = " -> ".join(str(part) for part in exc.absolute_path)
                if location:
                    print(f"SCHEMA FAIL {rel}: {detail} at {location}")
                else:
                    print(f"SCHEMA FAIL {rel}: {detail}")

    if parse_failures or schema_failures:
        print(
            f"RESULT FAIL parse_failures={parse_failures} "
            f"schema_failures={schema_failures}"
        )
        return 1

    yaml_failures += validate_yaml_contract()
    if yaml_failures:
        print(
            f"RESULT FAIL parse_failures={parse_failures} "
            f"schema_failures={schema_failures} "
            f"yaml_failures={yaml_failures}"
        )
        return 1

    consistency_failures = 0
    try:
        for message in run_consistency_checks(parsed_payloads):
            print(message)
    except ValueError as exc:
        consistency_failures += 1
        print(f"CONSISTENCY FAIL {exc}")

    if consistency_failures:
        print(
            f"RESULT FAIL parse_failures={parse_failures} "
            f"schema_failures={schema_failures} "
            f"consistency_failures={consistency_failures}"
        )
        return 1

    print(
        f"RESULT OK   parsed={len(parsed_payloads)} "
        f"schema_validated={'jsonschema' if jsonschema_module else 'builtin'}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
