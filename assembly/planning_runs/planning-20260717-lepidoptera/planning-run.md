# Planning Run Prompt

You are producing a repository-first planning run for the `ai-assembly-line` workflow.

## Run Slug

`planning-20260717-lepidoptera`

## Preconditions

- The product repository is initialized.
- The requirements/bootstrap PR is merged.
- `project_workspace.json` and accepted intake/requirements files exist.
- Repository files, not chat history, are the source of truth.

## Goal

Convert the merged requirements into a safe, structured planning package and proposed planning PR.

Generated outputs are drafts until the planning PR is reviewed and merged.

## Accepted Requirements Reference

# Accepted Requirements Input

Replace this text with repository paths and a concise summary of the merged requirements.
Planning should read the accepted project intake and requirements from the repository.
Do not use this file to override merged requirements.

## Required Output Files

Return exactly these planning artifact types:

- `project_spec.json`
- `repo_plan.json`
- `agent_prompts.json`
- `slots_db.json`

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
