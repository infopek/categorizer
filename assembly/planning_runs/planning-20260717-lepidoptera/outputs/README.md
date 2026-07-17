# Outputs

Save the planning artifacts for this run in this folder.

Expected files:

- `project_spec.json`
- `repo_plan.json`
- `agent_prompts.json`
- `slots_db.json`

The final `task_backlog.json` is intentionally not a planning-run output in the normal repository-first workflow.

Validate with:

```powershell
python tools\validate_planning_run.py <path-to-this-folder>
```
