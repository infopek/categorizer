# Categorizer Handoff

## Current lifecycle phase

Requirements are merged. Planning run `planning-20260712` is drafted on `ai/planning-20260712` and pending planning pull-request review.

## Source of truth

- `project_workspace.json`
- `assembly/intake/project_intake.json` after guided intake
- `assembly/requirements/REQUIREMENTS.md`

## Next action

Validate and review the planning package, then commit, push, open, and merge the planning pull request. After merge, start a fresh Task Splitter context from the merged repository files.

## Accepted direction

- Android-first offline car recognition for casual users.
- Local-only personal album with camera and gallery input.
- Kotlin Multiplatform, Compose Multiplatform, ONNX Runtime, and a PyTorch-to-ONNX model pipeline.
- Approximately 100 to 200 supported car models, evaluated against top-five accuracy and device-latency gates.

## Non-blocking planning inputs

- Select the licensed datasets, pretrained weights, and exact initial catalog.
- Select the representative mid-range Android benchmark device.
- Establish the bundled application and model size budget.

## Draft planning package

- `assembly/generated/project_spec.json`
- `assembly/generated/repo_plan.json`
- `assembly/generated/agent_prompts.json`
- `assembly/generated/slots_db.json`
- `assembly/planning_runs/planning-20260712/`

Final task decomposition remains deliberately deferred until this package is reviewed and merged.
