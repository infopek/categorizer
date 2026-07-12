import { escapeHtml, initializeViewerPage, renderList } from "./viewer-layout.js";

const LOCKED_STATUSES = new Set(["claimed", "in_progress"]);
const REVIEW_STATUSES = new Set(["review"]);
const DONE_STATUSES = new Set(["done"]);
const BLOCKED_STATUSES = new Set(["blocked"]);
const ACTIVE_CLAIM_STATUSES = new Set(["claimed", "in_progress"]);
const FILTERS = ["all", "available", "waiting", "locked", "blocked", "done"];

initializeViewerPage({
  pageId: "dispatch",
  eyebrow: "Execution Cockpit",
  title: "Dispatch",
  description: "Pick the next available task, copy one complete execution context, and start a focused AI task chat.",
  requiredKeys: ["taskBacklog", "collaborationState"],
  extraSourceFiles: ["prompts/07-task-executor.md", "contracts/task_run.schema.json"],
  helperNote: "Dispatch is the primary task-pickup screen. It is still static/read-only: it does not claim, lock, edit, or write files.",
  renderContent(container, data, projectContext) {
    const tasks = Array.isArray(data.taskBacklog) ? data.taskBacklog : [];
    const state = data.collaborationState && typeof data.collaborationState === "object" ? data.collaborationState : {};
    const model = buildDispatchModel(tasks, state, projectContext ?? data.__projectContext);

    container.innerHTML = renderDispatchShell(model);
    bindDispatchControls(container, model);
  },
});

function buildDispatchModel(tasks, state, projectContext) {
  const actors = Array.isArray(state.actors) ? state.actors : [];
  const actorMap = new Map(actors.map((actor) => [actor.actor_id, actor]));
  const assignmentMap = buildAssignmentMap(state.task_assignments);
  const claimMap = buildActiveClaimMap(state.task_claims);
  const taskMap = new Map(tasks.map((task) => [task.id, task]));
  const layers = computeTopologicalLayers(tasks);
  const taskRunPathPrefix = projectContext?.taskRunPathPrefix ?? "generated/task_runs/";
  const projectLabel = projectContext?.mode === "project"
    ? `${projectContext.projectName} (${projectContext.projectId})`
    : "Root Generated State";
  const workspaceLabel = projectContext?.mode === "project" ? projectContext.workspaceRootPath : "root generated/";

  const rawRows = tasks.map((task) => {
    const assignment = assignmentMap.get(task.id);
    const activeClaim = claimMap.get(task.id);
    const actorId = assignment?.assigned_to ?? activeClaim?.actor_id ?? "";
    const actor = actorId ? actorMap.get(actorId) : null;

    return {
      task,
      assignment,
      activeClaim,
      actor,
      id: task.id ?? "unknown-task",
      title: task.title ?? task.summary ?? task.id ?? "Untitled task",
      summary: task.summary ?? "",
      ownerRole: task.owner_role ?? "unknown role",
      repoTarget: task.repo_target ?? "unknown repo",
      lane: task.lane ?? "unassigned lane",
      planningStatus: task.status ?? "not set",
      executionStatus: assignment?.status ?? claimStatusToAssignmentStatus(activeClaim?.status) ?? "unclaimed",
      assignedTo: actor?.display_name ?? assignment?.assignee_label ?? assignment?.assigned_to ?? activeClaim?.actor_id ?? "Unassigned",
      assigneeType: assignment?.assignee_type ?? actor?.kind ?? (activeClaim ? "unknown" : "unassigned"),
      dependsOn: Array.isArray(task.depends_on) ? task.depends_on : [],
      outputs: Array.isArray(task.outputs) ? task.outputs : [],
      verification: Array.isArray(task.verification) ? task.verification : [],
      acceptanceCriteria: Array.isArray(task.acceptance_criteria) ? task.acceptance_criteria : [],
      proof: Array.isArray(assignment?.proof) ? assignment.proof : [],
      notes: assignment?.notes ?? activeClaim?.notes ?? "",
      updatedAt: assignment?.updated_at ?? activeClaim?.claimed_at ?? activeClaim?.released_at ?? "",
    };
  });

  const rowsById = new Map(rawRows.map((row) => [row.id, row]));
  const rows = rawRows.map((row) => classifyRow(row, rowsById, taskMap));
  const classifiedRowsById = new Map(rows.map((row) => [row.id, row]));
  const summary = summarizeRows(rows);
  const defaultTaskId = rows.find((row) => row.dispatchStatus === "available")?.id ?? rows[0]?.id ?? "";

  return {
    actors,
    state,
    tasks,
    layers,
    rows,
    rowsById: classifiedRowsById,
    summary,
    defaultTaskId,
    projectContext,
    projectLabel,
    workspaceLabel,
    taskRunPathPrefix,
  };
}

function buildAssignmentMap(assignments) {
  const map = new Map();
  if (!Array.isArray(assignments)) {
    return map;
  }

  for (const assignment of assignments) {
    if (assignment?.task_id) {
      map.set(assignment.task_id, assignment);
    }
  }

  return map;
}

function buildActiveClaimMap(claims) {
  const map = new Map();
  if (!Array.isArray(claims)) {
    return map;
  }

  for (const claim of claims) {
    if (claim?.task_id && ACTIVE_CLAIM_STATUSES.has(claim.status)) {
      map.set(claim.task_id, claim);
    }
  }

  return map;
}

function claimStatusToAssignmentStatus(status) {
  if (!status) {
    return null;
  }

  if (status === "submitted") {
    return "review";
  }

  if (status === "released") {
    return "released";
  }

  return status;
}

function classifyRow(row, rowsById, taskMap) {
  const missingDependencies = row.dependsOn.filter((dependencyId) => !taskMap.has(dependencyId));
  const dependencyRows = row.dependsOn.map((dependencyId) => rowsById.get(dependencyId)).filter(Boolean);
  const blockedDependencies = dependencyRows.filter((dependency) => BLOCKED_STATUSES.has(dependency.executionStatus));
  const unfinishedDependencies = dependencyRows.filter((dependency) => !isDone(dependency));

  let dispatchStatus = "available";
  let visualStatus = "available";
  let dispatchReason = "All dependencies are done and the task is free to take.";
  let unavailableDetails = [];

  if (isDone(row)) {
    dispatchStatus = "done";
    visualStatus = "done";
    dispatchReason = "Done. Review proof before using this as dependency context.";
  } else if (BLOCKED_STATUSES.has(row.executionStatus) || missingDependencies.length || blockedDependencies.length) {
    dispatchStatus = "blocked";
    visualStatus = "blocked";
    if (missingDependencies.length) {
      dispatchReason = `Missing dependency reference(s): ${missingDependencies.join(", ")}.`;
    } else if (blockedDependencies.length) {
      dispatchReason = `Blocked by dependency task(s): ${blockedDependencies.map((dependency) => dependency.id).join(", ")}.`;
    } else {
      dispatchReason = row.notes ? `Blocked: ${row.notes}` : "Task is explicitly blocked.";
    }
    unavailableDetails = buildBlockedDetails(row, missingDependencies, blockedDependencies);
  } else if (LOCKED_STATUSES.has(row.executionStatus)) {
    dispatchStatus = "locked";
    visualStatus = "locked";
    dispatchReason = `Locked by ${row.assignedTo} (${row.executionStatus}).`;
    unavailableDetails = [
      `actor: ${row.assignedTo}`,
      `status: ${row.executionStatus}`,
      row.updatedAt ? `updated: ${row.updatedAt}` : "updated: not recorded",
    ];
  } else if (REVIEW_STATUSES.has(row.executionStatus)) {
    dispatchStatus = "waiting";
    visualStatus = "waiting";
    dispatchReason = "In review; wait for proof acceptance before taking follow-up work.";
    unavailableDetails = [
      `review actor/status: ${row.assignedTo} / ${row.executionStatus}`,
      row.updatedAt ? `updated: ${row.updatedAt}` : "updated: not recorded",
    ];
  } else if (unfinishedDependencies.length) {
    dispatchStatus = "waiting";
    visualStatus = "waiting";
    dispatchReason = `Waiting for dependency task(s): ${unfinishedDependencies.map((dependency) => dependency.id).join(", ")}.`;
    unavailableDetails = unfinishedDependencies.map(
      (dependency) => `${dependency.id} - ${dependency.title} - ${dependency.executionStatus}`,
    );
  }

  return {
    ...row,
    dependencyRows,
    missingDependencies,
    blockedDependencies,
    unfinishedDependencies,
    dispatchStatus,
    visualStatus,
    dispatchReason,
    unavailableDetails,
  };
}

function buildBlockedDetails(row, missingDependencies, blockedDependencies) {
  const details = [];

  if (missingDependencies.length) {
    details.push(`missing dependencies: ${missingDependencies.join(", ")}`);
  }

  for (const dependency of blockedDependencies) {
    details.push(`${dependency.id} - ${dependency.title} - ${dependency.dispatchReason}`);
  }

  if (BLOCKED_STATUSES.has(row.executionStatus)) {
    details.push(row.notes ? `blocked note: ${row.notes}` : "assignment status is blocked");
  }

  return details;
}

function isDone(row) {
  return DONE_STATUSES.has(row.executionStatus) || row.planningStatus === "done";
}

function computeTopologicalLayers(tasks) {
  const taskMap = new Map(tasks.map((task) => [task.id, task]));
  const indegree = new Map(tasks.map((task) => [task.id, 0]));
  const dependents = new Map(tasks.map((task) => [task.id, []]));

  for (const task of tasks) {
    const dependencies = Array.isArray(task.depends_on) ? task.depends_on : [];
    for (const dependencyId of dependencies) {
      if (!taskMap.has(dependencyId)) {
        continue;
      }

      indegree.set(task.id, (indegree.get(task.id) ?? 0) + 1);
      dependents.get(dependencyId).push(task.id);
    }
  }

  const remaining = new Set(tasks.map((task) => task.id));
  let ready = tasks.filter((task) => (indegree.get(task.id) ?? 0) === 0).map((task) => task.id);
  const layers = [];

  while (ready.length) {
    const layerIds = ready.filter((taskId) => remaining.has(taskId));
    if (!layerIds.length) {
      break;
    }

    layers.push({ label: `Wave ${layers.length + 1}`, taskIds: layerIds, cyclic: false });
    const next = [];

    for (const taskId of layerIds) {
      remaining.delete(taskId);
      for (const dependentId of dependents.get(taskId) ?? []) {
        indegree.set(dependentId, (indegree.get(dependentId) ?? 0) - 1);
        if ((indegree.get(dependentId) ?? 0) === 0) {
          next.push(dependentId);
        }
      }
    }

    ready = next;
  }

  if (remaining.size) {
    layers.push({ label: "Unsorted / cyclic", taskIds: Array.from(remaining), cyclic: true });
  }

  return layers;
}

function summarizeRows(rows) {
  return rows.reduce(
    (summary, row) => {
      summary.total += 1;
      summary[row.dispatchStatus] = (summary[row.dispatchStatus] ?? 0) + 1;
      return summary;
    },
    {
      total: 0,
      available: 0,
      waiting: 0,
      locked: 0,
      blocked: 0,
      done: 0,
    },
  );
}

function renderDispatchShell(model) {
  return `
    <div class="dispatch-cockpit">
      <section class="dispatch-intro">
        <div>
          <p class="eyebrow">Static execution cockpit</p>
          <h2>Pick one task and launch a fresh AI chat</h2>
          <p class="muted">Green cards are ready now. Click a task, copy the full execution context, and save the returned report as <code>${escapeHtml(model.taskRunPathPrefix)}&lt;task_id&gt;.json</code>.</p>
          <p class="helper">Project: <strong>${escapeHtml(model.projectLabel)}</strong> · Workspace: <code>${escapeHtml(model.workspaceLabel)}</code></p>
        </div>
        <span class="chip status-available">${escapeHtml(String(model.summary.available))} ready now</span>
      </section>

      <section class="dispatch-summary" aria-label="Dispatch state summary">
        ${renderSummaryButton("available", "Available", model.summary.available)}
        ${renderSummaryButton("waiting", "Waiting", model.summary.waiting)}
        ${renderSummaryButton("locked", "Locked", model.summary.locked)}
        ${renderSummaryButton("blocked", "Blocked", model.summary.blocked)}
        ${renderSummaryButton("done", "Done", model.summary.done)}
        ${renderSummaryButton("all", "Total", model.summary.total)}
      </section>

      <section class="dispatch-workspace">
        <div class="dispatch-board card">
          <div class="section-heading dispatch-board-heading">
            <div>
              <h3>Task waves</h3>
              <p class="muted">Topological order: earlier waves unblock later waves.</p>
            </div>
            <div class="chip-row dispatch-filter-row" id="dispatchFilters">
              ${FILTERS.map((filter) => renderFilterButton(filter)).join("")}
            </div>
          </div>
          <div id="dispatchGraph" class="dependency-graph dispatch-graph"></div>
        </div>

        <aside id="dispatchDetail" class="dispatch-detail card"></aside>
      </section>
    </div>
  `;
}

function renderSummaryButton(filter, label, value) {
  return `
    <button type="button" class="dispatch-summary-button status-${escapeHtml(filter)}" data-filter="${escapeHtml(filter)}">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(String(value))}</strong>
    </button>
  `;
}

function renderFilterButton(filter) {
  return `<button type="button" class="dispatch-filter-button" data-filter="${escapeHtml(filter)}">${escapeHtml(filter)}</button>`;
}

function bindDispatchControls(container, model) {
  let selectedTaskId = model.defaultTaskId;
  let activeFilter = "available";
  const graph = container.querySelector("#dispatchGraph");
  const detail = container.querySelector("#dispatchDetail");

  container.addEventListener("click", async (event) => {
    const copyButton = event.target.closest("[data-copy-kind]");
    if (copyButton) {
      const selectedRow = model.rowsById.get(selectedTaskId);
      if (!selectedRow) {
        return;
      }

      const text = copyButton.dataset.copyKind === "task" ? JSON.stringify(selectedRow.task, null, 2) : buildExecutionContext(selectedRow, model);
      await copyText(text, copyButton);
      return;
    }

    const filterButton = event.target.closest("[data-filter]");
    if (filterButton) {
      activeFilter = filterButton.dataset.filter;
      render();
      return;
    }

    const node = event.target.closest("[data-task-id]");
    if (node) {
      selectedTaskId = node.dataset.taskId;
      render();
    }
  });

  function render() {
    graph.innerHTML = renderGraph(model, activeFilter, selectedTaskId);
    detail.innerHTML = renderTaskDetail(model.rowsById.get(selectedTaskId), model);
    for (const button of container.querySelectorAll("[data-filter]")) {
      button.classList.toggle("active", button.dataset.filter === activeFilter);
      button.setAttribute("aria-pressed", String(button.dataset.filter === activeFilter));
    }
  }

  render();
}

function renderGraph(model, activeFilter, selectedTaskId) {
  if (!model.rows.length) {
    return '<p class="muted">No tasks exist in the selected task backlog yet.</p>';
  }

  const columns = model.layers.map((layer) => {
    const rows = layer.taskIds
      .map((taskId) => model.rowsById.get(taskId))
      .filter(Boolean)
      .filter((row) => activeFilter === "all" || row.dispatchStatus === activeFilter);

    return `
      <section class="graph-column dispatch-wave ${layer.cyclic ? "error-card" : ""}">
        <div class="graph-column-heading">
          <h4>${escapeHtml(layer.label)}</h4>
          <span class="chip">${escapeHtml(String(rows.length))}</span>
        </div>
        <div class="graph-node-stack">
          ${rows.length ? rows.map((row) => renderGraphNode(row, selectedTaskId)).join("") : '<p class="muted dispatch-empty-wave">No tasks for this filter.</p>'}
        </div>
      </section>
    `;
  });

  return `<div class="graph-columns dispatch-columns">${columns.join("")}</div>`;
}

function renderGraphNode(row, selectedTaskId) {
  const selectedClass = row.id === selectedTaskId ? " selected" : "";
  const selectedLabel = row.id === selectedTaskId ? '<span class="chip status-active">selected</span>' : "";
  return `
    <article class="graph-node dispatch-task-card status-${escapeHtml(row.visualStatus)}${selectedClass}" data-task-id="${escapeHtml(row.id)}" tabindex="0" aria-label="${escapeHtml(`${row.id}: ${row.dispatchStatus}`)}">
      <div class="graph-node-title">
        <strong>${escapeHtml(row.id)}</strong>
        <span class="chip status-${escapeHtml(row.visualStatus)}">${escapeHtml(row.dispatchStatus)}</span>
      </div>
      <p class="dispatch-task-title">${escapeHtml(row.title)}</p>
      <div class="graph-node-meta">
        <span>${escapeHtml(row.ownerRole)} / ${escapeHtml(row.repoTarget)}</span>
        <span>${escapeHtml(row.dispatchReason)}</span>
        ${selectedLabel}
      </div>
    </article>
  `;
}

function renderTaskDetail(row, model) {
  if (!row) {
    return '<p class="muted">Select a task to see dispatch context.</p>';
  }

  return `
    <div class="dispatch-detail-actions">
      <button type="button" class="dispatch-primary-action" data-copy-kind="context">Copy Full Execution Context</button>
      <button type="button" class="dispatch-secondary-action" data-copy-kind="task">Copy Task JSON</button>
    </div>

    <div class="dispatch-detail-heading">
      <div>
        <p class="eyebrow">Selected task</p>
        <h3>${escapeHtml(row.id)}</h3>
        <p class="muted">${escapeHtml(row.title)}</p>
      </div>
      <span class="chip status-${escapeHtml(row.visualStatus)}">${escapeHtml(row.dispatchStatus)}</span>
    </div>

    <section class="dispatch-state-explain status-${escapeHtml(row.visualStatus)}">
      <h4>${escapeHtml(stateExplanationTitle(row))}</h4>
      <p>${escapeHtml(row.dispatchReason)}</p>
      ${renderUnavailableDetails(row)}
    </section>

    <div class="dispatch-facts">
      ${renderFact("Project", model.projectLabel)}
      ${renderFact("Owner", row.ownerRole)}
      ${renderFact("Repo", row.repoTarget)}
      ${renderFact("Execution", row.executionStatus)}
      ${renderFact("Assignee", row.assignedTo)}
      ${renderFact("Task run path", taskRunPath(model, row.id), true)}
    </div>

    <h4>Dependencies</h4>
    ${renderDependencyStatus(row)}

    <h4>Acceptance Criteria</h4>
    ${renderList(row.acceptanceCriteria, "No acceptance criteria listed")}

    <h4>Expected Outputs</h4>
    ${renderList(row.outputs, "No outputs listed")}

    <h4>Verification</h4>
    ${renderList(row.verification, "No verification listed")}

    <details class="dispatch-json-preview">
      <summary>Task JSON preview</summary>
      <pre class="code-block">${escapeHtml(JSON.stringify(row.task, null, 2))}</pre>
    </details>
  `;
}

function renderFact(label, value, code = false) {
  return `
    <div class="dispatch-fact">
      <dt>${escapeHtml(label)}</dt>
      <dd>${code ? `<code>${escapeHtml(value)}</code>` : escapeHtml(value)}</dd>
    </div>
  `;
}

function stateExplanationTitle(row) {
  if (row.dispatchStatus === "available") {
    return "Ready to launch";
  }

  if (row.dispatchStatus === "waiting") {
    return REVIEW_STATUSES.has(row.executionStatus) ? "Waiting for review" : "Waiting on dependencies";
  }

  if (row.dispatchStatus === "locked") {
    return "Locked by active work";
  }

  if (row.dispatchStatus === "blocked") {
    return "Blocked";
  }

  return "Completed";
}

function renderUnavailableDetails(row) {
  if (row.dispatchStatus === "available") {
    return '<p class="helper">This is the happy path: copy the context and start one fresh AI task chat.</p>';
  }

  if (row.dispatchStatus === "done") {
    return row.proof.length
      ? `<div class="helper">Proof recorded: ${escapeHtml(summarizeProof(row.proof))}</div>`
      : '<p class="helper">No proof reference is recorded on this assignment yet.</p>';
  }

  if (!row.unavailableDetails.length) {
    return "";
  }

  return `
    <ul class="list">
      ${row.unavailableDetails.map((detail) => `<li>${escapeHtml(detail)}</li>`).join("")}
    </ul>
  `;
}

function renderDependencyStatus(row) {
  const dependencyRows = row.dependsOn.map((dependencyId) => row.dependencyRows.find((dependency) => dependency.id === dependencyId));
  const knownRows = dependencyRows.filter(Boolean);
  const missingRows = row.missingDependencies;

  if (!knownRows.length && !missingRows.length) {
    return '<p class="muted">No dependencies. This task can stand alone once unclaimed.</p>';
  }

  return `
    <div class="dispatch-dependency-list">
      ${knownRows.map((dependency) => renderDependencyCard(dependency)).join("")}
      ${missingRows.map((dependencyId) => `
        <article class="dispatch-dependency-card status-blocked">
          <strong>${escapeHtml(dependencyId)}</strong>
          <span class="chip status-blocked">missing</span>
          <p class="muted">This dependency ID is referenced but not present in the backlog.</p>
        </article>
      `).join("")}
    </div>
  `;
}

function renderDependencyCard(row) {
  return `
    <article class="dispatch-dependency-card status-${escapeHtml(row.visualStatus)}">
      <div class="graph-node-title">
        <strong>${escapeHtml(row.id)}</strong>
        <span class="chip status-${escapeHtml(row.visualStatus)}">${escapeHtml(row.dispatchStatus)}</span>
      </div>
      <p>${escapeHtml(row.title)}</p>
      <p class="muted">${escapeHtml(row.dispatchReason)}</p>
      ${row.proof.length ? renderProofSummary(row.proof) : '<p class="muted">No proof reference recorded.</p>'}
    </article>
  `;
}

function renderProofSummary(proofItems) {
  return `
    <ul class="list">
      ${proofItems.map((item) => `<li>${escapeHtml(proofLabel(item))}</li>`).join("")}
    </ul>
  `;
}

function proofLabel(item) {
  const bits = [item.kind, item.status, item.path, item.summary].filter(Boolean);
  return bits.length ? bits.join(" / ") : "proof";
}

function buildExecutionContext(row, model) {
  const dependencyRows = row.dependsOn.map((dependencyId) => model.rowsById.get(dependencyId)).filter(Boolean);
  const missingDependencySummary = row.missingDependencies.length
    ? `\nMissing dependency reference(s): ${row.missingDependencies.join(", ")}`
    : "";
  const dependencySummary = dependencyRows.length
    ? dependencyRows.map((dependency) => formatDependencyContext(dependency)).join("\n")
    : "- No dependencies.";
  const projectContext = model.projectContext;
  const projectBlock = projectContext?.mode === "project"
    ? `Project: ${projectContext.projectName}\nProject ID: ${projectContext.projectId}\nWorkspace: ${projectContext.workspaceRootPath}`
    : "Project: Root Generated State\nProject ID: root\nWorkspace: generated/";

  return `# AI Assembly Line - One Task Execution Context

You are executing exactly one task from the AI Assembly Line backlog.

${projectBlock}

Use the task executor rules from:
prompts/07-task-executor.md

Hard rules:
- Work only on the selected task.
- Do not broaden scope.
- Respect dependencies, allowed files, outputs, non-goals, and verification.
- Do not mark done without accepted proof.
- If required context is missing, return blocked with a clear blocker.
- Return one task_run JSON object only.

Expected output path:
${taskRunPath(model, row.id)}

Dispatch status: ${row.dispatchStatus}
Reason: ${row.dispatchReason}
Assigned to: ${row.assignedTo} (${row.assigneeType})
Updated at: ${row.updatedAt || "not recorded"}

## Selected Task JSON

${JSON.stringify(row.task, null, 2)}

## Current Assignment Metadata

${JSON.stringify(row.assignment ?? { task_id: row.id, status: "unclaimed" }, null, 2)}

## Active Claim Metadata

${JSON.stringify(row.activeClaim ?? { task_id: row.id, status: "none" }, null, 2)}

## Dependency Summary

${dependencySummary}${missingDependencySummary}

## Dependency Proof Summaries

${dependencyRows.length ? dependencyRows.map((dependency) => `- ${dependency.id}: ${summarizeProof(dependency.proof)}`).join("\n") : "- No dependency proof required."}

## Required Return Shape

Return exactly one JSON object compatible with contracts/task_run.schema.json:

{
  "schema_version": "0.1.0",
  "task_id": "${row.id}",
  "run_id": "<unique-run-id>",
  "actor_id": "<your-actor-id>",
  "status": "review",
  "implementation_summary": ["<what changed>"],
  "files_changed": [],
  "verification": [],
  "proof": [],
  "blockers": [],
  "notes": [],
  "updated_at": "<ISO-8601 timestamp>"
}

Use status "review" when implementation appears complete but still needs human review.
Use status "blocked" if required context or dependencies are missing.
Do not mark "done" without accepted proof.
`;
}

function taskRunPath(model, taskId) {
  return `${model.taskRunPathPrefix}${taskId}.json`;
}

function formatDependencyContext(dependency) {
  return [
    `- ${dependency.id}: ${dependency.dispatchStatus}`,
    `  title: ${dependency.title}`,
    `  execution_status: ${dependency.executionStatus}`,
    `  reason: ${dependency.dispatchReason}`,
    `  proof: ${summarizeProof(dependency.proof)}`,
  ].join("\n");
}

function summarizeProof(proofItems) {
  if (!proofItems.length) {
    return "no proof recorded";
  }

  return proofItems.map((item) => proofLabel(item)).join("; ");
}

async function copyText(text, button) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      fallbackCopy(text);
    }
    flashButton(button, "Copied");
  } catch (error) {
    fallbackCopy(text);
    flashButton(button, "Copied");
  }
}

function fallbackCopy(text) {
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "");
  textarea.style.position = "absolute";
  textarea.style.left = "-9999px";
  document.body.appendChild(textarea);
  textarea.select();
  document.execCommand("copy");
  document.body.removeChild(textarea);
}

function flashButton(button, label) {
  const original = button.textContent;
  button.textContent = label;
  window.setTimeout(() => {
    button.textContent = original;
  }, 1200);
}
