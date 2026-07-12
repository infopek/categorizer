from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLANNING_RUNS_DIR = ROOT / "planning_runs"
REQUIRED_OUTPUTS = [
    "project_spec.json",
    "repo_plan.json",
    "agent_prompts.json",
    "slots_db.json",
]


def usage() -> int:
    print("Usage: python tools/init_planning_run.py <run-slug>")
    return 1


def validate_slug(run_slug: str) -> None:
    if re.fullmatch(r"[a-z0-9][a-z0-9\-]*", run_slug) is None:
        raise ValueError("run-slug must match ^[a-z0-9][a-z0-9\\-]*$")


def default_input_idea() -> str:
    return (
        "# Accepted Requirements Input\n\n"
        "Replace this text with repository paths and a concise summary of the merged requirements.\n"
        "Planning should read the accepted project intake and requirements from the repository.\n"
        "Do not use this file to override merged requirements.\n"
    ).strip()


def planning_prompt(run_slug: str, input_idea: str) -> str:
    output_list = "\n".join(f"- `{name}`" for name in REQUIRED_OUTPUTS)
    return f"""# Planning Run Prompt

You are producing a repository-first planning run for the `ai-assembly-line` workflow.

## Run Slug

`{run_slug}`

## Preconditions

- The product repository is initialized.
- The requirements/bootstrap PR is merged.
- `project_workspace.json` and accepted intake/requirements files exist.
- Repository files, not chat history, are the source of truth.

## Goal

Convert the merged requirements into a safe, structured planning package and proposed planning PR.

Generated outputs are drafts until the planning PR is reviewed and merged.

## Accepted Requirements Reference

{input_idea}

## Required Output Files

Return exactly these planning artifact types:

{output_list}

## Deliberate Separation

Do not produce:

- `task_batch_index.json`
- task batch files
- `task_backlog.json`
- task assignments or task claims

Final task decomposition belongs to a fresh Task Splitter context after the planning PR is merged.

## Output Requirements

- `project_spec.json` describes the accepted product, boundaries, domain model, screens, responsibilities, interfaces, and verification strategy.
- `repo_plan.json` defines repository/module ownership and allowed areas.
- `agent_prompts.json` defines role-level boundaries tied to the repo plan.
- `slots_db.json` defines planned role/lane slots and verification expectations; task readiness may be updated later by Task Splitter.

## Constraints

- Produce planning artifacts only.
- Do not implement software.
- Do not add hidden workflow state.
- Keep outputs human-reviewable and machine-readable.
- Keep project names, repo targets, role prompts, and slots internally consistent.
- Preserve unresolved questions rather than inventing answers.

## Response Format

When repository write access is available, write the files to the paths declared by `project_workspace.json`, validate them, and open a planning PR.

When repository write access is unavailable, return each file in its own fenced code block with the exact repository-root-relative path immediately above it.

Use valid JSON for all four files.
"""


def review_template() -> str:
    return """# Review

## Outcome

- [ ] Accepted
- [ ] Needs revision
- [ ] Rejected

## Review Notes

- Requirements alignment:
- Architecture and module boundaries:
- Repo/prompt/slot coherence:
- Verification strategy:
- Open questions:
- Task decomposition correctly deferred:
- Reviewer decision rationale:
"""


def outputs_readme() -> str:
    expected = "\n".join(f"- `{name}`" for name in REQUIRED_OUTPUTS)
    return f"""# Outputs

Save the planning artifacts for this run in this folder.

Expected files:

{expected}

The final `task_backlog.json` is intentionally not a planning-run output in the normal repository-first workflow.

Validate with:

```powershell
python tools\\validate_planning_run.py <path-to-this-folder>
```
"""


def write_if_missing(path: Path, content: str) -> str:
    if path.exists():
        return "exists"
    path.write_text(content.rstrip() + "\n", encoding="utf-8")
    return "created"


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        return usage()

    run_slug = argv[1]
    try:
        validate_slug(run_slug)
    except ValueError as exc:
        print(f"ERROR {exc}")
        return 1

    run_dir = PLANNING_RUNS_DIR / run_slug
    outputs_dir = run_dir / "outputs"
    run_dir.mkdir(parents=True, exist_ok=True)
    outputs_dir.mkdir(parents=True, exist_ok=True)

    input_path = run_dir / "input-idea.md"
    input_status = write_if_missing(input_path, default_input_idea())
    accepted_input = input_path.read_text(encoding="utf-8").strip()

    planning_prompt_path = run_dir / "planning-run.md"
    planning_prompt_path.write_text(planning_prompt(run_slug, accepted_input).rstrip() + "\n", encoding="utf-8")

    review_status = write_if_missing(run_dir / "review-notes.md", review_template())
    outputs_status = write_if_missing(outputs_dir / "README.md", outputs_readme())

    print(f"RUN DIR   {run_dir.relative_to(ROOT).as_posix()}")
    print(f"INPUT     {input_status} {input_path.relative_to(ROOT).as_posix()}")
    print(f"PROMPT    updated {planning_prompt_path.relative_to(ROOT).as_posix()}")
    print(f"REVIEW    {review_status} {run_dir.joinpath('review-notes.md').relative_to(ROOT).as_posix()}")
    print(f"OUTPUTS   {outputs_status} {outputs_dir.joinpath('README.md').relative_to(ROOT).as_posix()}")
    print("NEXT      reference merged requirements, rerun, create planning artifacts, validate, and open a planning PR")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
