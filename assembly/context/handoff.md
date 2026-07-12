# Categorizer Handoff

## Current lifecycle phase

Guided intake is accepted; requirements/bootstrap artifacts are ready for review.

## Source of truth

- `project_workspace.json`
- `assembly/intake/project_intake.json` after guided intake
- `assembly/requirements/REQUIREMENTS.md`

## Next action

Validate and review the requirements/bootstrap branch, commit and push it, then open and merge the requirements pull request. After merge, start a fresh Planning Agent context from the merged repository files.

## Accepted direction

- Android-first offline car recognition for casual users.
- Local-only personal album with camera and gallery input.
- Kotlin Multiplatform, Compose Multiplatform, ONNX Runtime, and a PyTorch-to-ONNX model pipeline.
- Approximately 100 to 200 supported car models, evaluated against top-five accuracy and device-latency gates.

## Non-blocking planning inputs

- Select the licensed datasets, pretrained weights, and exact initial catalog.
- Select the representative mid-range Android benchmark device.
- Establish the bundled application and model size budget.
