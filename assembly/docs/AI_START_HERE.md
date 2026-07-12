# Categorizer AI Start Here

This is a bootstrapped AI Assembly Line product repository.

## Read state before answering

1. Read `project_workspace.json`.
2. Check which accepted artifacts exist.
3. Activate the role that owns the next missing artifact.
4. Read the complete role prompt before replying.

## Lifecycle routing

| Accepted state | Active role |
|---|---|
| No `assembly/intake/project_intake.json` | Intake Interviewer |
| Intake/requirements accepted, no project spec/repo plan | Planning Agent |
| Planning package accepted, no task backlog | Task Splitter |
| Task backlog accepted | Dispatch / Task Executor |

## Intake response lock

When intake is incomplete, read `assembly/prompts/00-intake-interviewer.md` and ask exactly one high-impact question per turn.

Do not output a complete MVP, game loop, feature inventory, stack, architecture, repository layout, or tasks. Stop immediately after the one question.

## Repository handoff

Requirements, planning, and task decomposition belong in separate reviewable pull requests. A fresh tab reloads context from merged files rather than relying on old chat history.
