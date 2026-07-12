# Web Viewer

This directory contains the static multi-page read-only viewer for generated planning and execution-coordination state.

Serve the repository root locally with:

`python -m http.server 8000`

## Supported workspace modes

### Standalone product repository — recommended for real projects

A product repository may contain:

```text
project_workspace.json
assembly/
  web/
  generated/
  contracts/
  prompts/
```

Open:

`http://localhost:8000/assembly/web/`

The viewer automatically checks for a repository-root `project_workspace.json` from the common `assembly/web/` layout. The manifest paths may point at files such as:

```text
assembly/generated/project_spec.json
assembly/generated/repo_plan.json
assembly/generated/task_backlog.json
assembly/generated/collaboration_state.json
```

An explicit relative manifest may also be requested:

`dispatch.html?workspace=../../project_workspace.json`

### Registry mode — optional multi-project workspace

The viewer can load:

```text
projects/index.json
projects/<project-id>/project_workspace.json
```

Open:

`http://localhost:8000/web/dispatch.html?project=<project-id>`

Changing project in the shared selector preserves the current viewer page.

### Root seed mode

When no standalone workspace or registered project is available, the viewer falls back to:

```text
generated/*.json
```

This keeps the AI Assembly Line seed/demo repository usable.

If the browser blocks `file://` fetches, use the page-level file picker to load the required generated JSON files.

## Page roles

Dispatch is the primary task-pickup surface. Assignments and Task Batches remain useful, but they have narrower roles:

- `web/dispatch.html`: primary "what can I do next?" page. It renders topological task waves from the configured task backlog, overlays execution state from collaboration state, colors tasks by availability, and generates one-task execution context for a fresh AI chat.
- `web/assignments.html`: audit/status page for task ownership, execution status, notes, proof references, and collaboration-state helpers.
- `web/task-batches.html`: generation/validation page for guided task splitting, task batch index readiness, generated batch files, and dependency graph checks.

The remaining viewer pages are:

- `web/index.html`: project overview
- `web/repos.html`: repository split and ownership
- `web/backlog.html`: backlog grouped by `repo_target`
- `web/prompts.html`: agent prompt pack
- `web/slots.html`: slot board
- `web/planning-runs.html`: planning-run scaffold and output completeness
- `web/verification.html`: verification rules, proof requirements, and raw contract rendering

See `docs/VIEWER_PAGE_ROLES.md` for the page-consolidation decision.

## Shared files

- `web/project-workspace.js`: standalone/registry/root workspace selection and path resolution
- `web/viewer-data.js`: generated JSON loading and file-picker fallback
- `web/viewer-layout.js`: shared shell, navigation, status handling, project label/selector, and helpers
- `web/page-*.js`: page-specific rendering only
- `web/page-dispatch-keyboard.js`: keyboard helper for Enter/Space task-node activation on Dispatch
- `web/viewer.css`: shared styling

## Still intentionally absent

The viewer does not add:

- editing as persisted browser state
- backend APIs
- authentication
- realtime sync
- direct task claiming
- file writes
- frontend-only task, repo, prompt, slot, planning-run, execution, or contract models
