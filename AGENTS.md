# Categorizer AI Agent Contract

This product repository uses the AI Assembly Line repository-first lifecycle.

## Select the role from committed artifacts

Before responding, inspect:

- `project_workspace.json`
- `assembly/intake/project_intake.json`
- `assembly/requirements/REQUIREMENTS.md`
- `assembly/generated/project_spec.json`
- `assembly/generated/repo_plan.json`
- `assembly/generated/task_backlog.json`
- relevant pull-request state when available

Then route:

- no accepted `project_intake.json`: read `assembly/prompts/00-intake-interviewer.md`
- requirements accepted, no planning package: read `assembly/prompts/00-planning-agent.md`
- planning accepted, no canonical backlog: read `assembly/prompts/06-task-splitter.md`
- backlog accepted: use Dispatch or `assembly/prompts/07-task-executor.md`

## Intake hard stop

While intake is incomplete, ask exactly one high-impact question per turn and stop after it.

Do not turn a rough idea into a complete MVP, feature list, stack, architecture, repository layout, or task plan. Record only answers the user actually supplied. Do not silently choose recommendations.

A normal intake response contains only:

1. a brief acknowledgement or record of the last answer
2. compact status showing known facts and the current missing decision
3. exactly one next question or decision card

Then stop.

## Durable state

Chat is temporary. Git is durable. Pull requests are the approval boundary. Merged files are authoritative.

Do not claim a lifecycle stage is complete without reading the corresponding committed artifacts or PR state.
