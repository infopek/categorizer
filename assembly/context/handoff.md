# Categorizer Handoff

## Current lifecycle phase

Requirements and planning are merged. Task decomposition is drafted on `ai/task-split-planning-20260712` and pending task-backlog pull-request review.

## Source of truth

- `project_workspace.json`
- `assembly/intake/project_intake.json` after guided intake
- `assembly/requirements/REQUIREMENTS.md`

## Next action

Validate and review the task batches, canonical backlog, collaboration state, and slot readiness; then commit, push, open, and merge the task-decomposition pull request. After merge, open Dispatch from the merged repository files.

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

The merged planning package remains the architectural source for this draft decomposition.

## Draft task decomposition

- `assembly/generated/task_batch_index.json`
- `assembly/generated/task_batches/`
- `assembly/generated/task_backlog.json`
- `assembly/generated/collaboration_state.json`
- `assembly/generated/slots_db.json`

The draft contains 5 batches and 31 tasks. No task is assigned, claimed, in progress, or complete. Contract Steward and ML Engine Builder have dependency-free entry tasks; later lanes remain gated by task dependencies.
