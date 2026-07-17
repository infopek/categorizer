# Categorizer Handoff

## Current lifecycle phase

An accepted product-scope amendment moves the MVP category from cars to Austrian
butterflies and moths. The requirements amendment is pending pull-request review;
the merged car-oriented planning package and task backlog must be amended afterward.

## Source of truth

- `project_workspace.json`
- `assembly/intake/project_intake.json` after guided intake
- `assembly/requirements/REQUIREMENTS.md`

## Next action

Review and merge the Lepidoptera requirements amendment. Then create a fresh planning
run that preserves category-neutral application foundations, moves car recognition to
post-MVP, and replaces the ML/catalog tasks with the accepted Lepidoptera source and
checkpoint evaluation path.

## Accepted direction

- Android-first offline butterfly and moth recognition for casual users and nature enthusiasts.
- Local-only personal album with camera and gallery input.
- Kotlin Multiplatform, Compose Multiplatform, ONNX Runtime, and a PyTorch-to-ONNX model pipeline.
- The 163 sufficiently represented Austrian Lepidoptera classes from the accepted Figshare source, evaluated against top-five accuracy and device-latency gates.
- The prepared 200-class car catalog and local car dataset are preserved for post-MVP work.

## Non-blocking planning inputs

- Verify the pinned Figshare checkpoint's artifact provenance and exact class order.
- Evaluate whether MaxViT-T can satisfy ONNX, size, memory, and Galaxy S20 FE latency gates or requires distillation.
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
