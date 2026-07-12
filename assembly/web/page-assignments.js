import { escapeHtml, initializeViewerPage, renderCardGrid, renderChipRow, renderKeyValueRows, renderList } from "./viewer-layout.js";
import { isWorkspaceContext, workspaceDisplayPath } from "./project-workspace.js";

initializeViewerPage({
  pageId: "assignments",
  eyebrow: "Execution Coordination",
  title: "Task Assignments",
  description: "Read-only ownership, execution status, and proof view over the selected task backlog and file-based collaboration state.",
  requiredKeys: ["taskBacklog", "collaborationState"],
  extraSourceFiles: ["contracts/collaboration_state.schema.json", "contracts/task_run.schema.json", "generated/task_runs/*.json"],
  helperNote: "Unclaimed tasks are derived from the selected task_backlog.json when no matching task_assignments entry exists.",
  renderContent(container, data, projectContext) {
    const tasks = Array.isArray(data.taskBacklog) ? data.taskBacklog : [];
    const state = data.collaborationState && typeof data.collaborationState === "object" ? data.collaborationState : {};
    const actors = Array.isArray(state.actors) ? state.actors : [];
    const assignmentMap = buildAssignmentMap(state.task_assignments);
    const rows = tasks.map((task) => buildTaskView(task, assignmentMap.get(task.id), actors));
    const summary = summarizeRows(rows);
    const context = projectContext ?? data.__projectContext;
    const configuredCollaborationPath = context?.workspace?.paths?.generated?.collaboration_state
      ?? "generated/collaboration_state.json";
    const collaborationPath = isWorkspaceContext(context)
      ? workspaceDisplayPath(context, configuredCollaborationPath)
      : "generated/collaboration_state.json";

    container.innerHTML = `
      <div class="stack">
        <section class="card">
          <div class="section-heading">
            <div>
              <h2>Collaboration State</h2>
              <p class="muted">This page overlays execution ownership on top of the selected generated task backlog. It does not claim, edit, or lock tasks.</p>
            </div>
            <span class="chip ${summary.blocked ? "status-blocked" : "status-complete"}">${summary.blocked ? "blocked tasks" : "ready to assign"}</span>
          </div>
          ${renderKeyValueRows([
            { label: "Project", value: escapeHtml(projectLabel(context)) },
            { label: "Collaboration Path", value: `<code>${escapeHtml(collaborationPath)}</code>` },
            { label: "Schema Version", value: `<code>${escapeHtml(state.schema_version ?? "unknown")}</code>` },
            { label: "Workspace", value: `<code>${escapeHtml(state.workspace_id ?? "unknown")}</code>` },
            { label: "Actors", value: escapeHtml(String(actors.length)) },
            { label: "Tasks", value: escapeHtml(String(tasks.length)) },
            { label: "Unclaimed", value: escapeHtml(String(summary.unclaimed)) },
            { label: "In Progress", value: escapeHtml(String(summary.in_progress)) },
            { label: "Review", value: escapeHtml(String(summary.review)) },
            { label: "Done", value: escapeHtml(String(summary.done)) },
            { label: "Blocked", value: escapeHtml(String(summary.blocked)) },
          ])}
        </section>

        <section class="card">
          <h3>Actors</h3>
          ${actors.length ? renderActorCards(actors) : '<p class="muted">No collaboration actors are defined yet.</p>'}
        </section>

        <section class="card">
          <h3>Task Ownership</h3>
          ${rows.length ? renderTaskCards(rows) : '<p class="muted">No tasks exist in the selected task backlog yet.</p>'}
        </section>
      </div>
    `;
  },
});

function projectLabel(context) {
  return isWorkspaceContext(context) ? `${context.projectName} (${context.projectId})` : "Root Generated State";
}

function buildAssignmentMap(assignments) {
  const map = new Map();
  if (!Array.isArray(assignments)) {
    return map;
  }

  for (const assignment of assignments) {
    if (assignment && assignment.task_id) {
      map.set(assignment.task_id, assignment);
    }
  }
  return map;
}

function buildTaskView(task, assignment, actors) {
  const actor = assignment?.assigned_to ? actors.find((candidate) => candidate.actor_id === assignment.assigned_to) : null;
  return {
    id: task.id ?? "unknown-task",
    title: task.title ?? task.summary ?? task.id ?? "Untitled task",
    ownerRole: task.owner_role ?? "unknown role",
    repoTarget: task.repo_target ?? "unknown repo",
    lane: task.lane ?? "unassigned lane",
    planningStatus: task.status ?? "not set",
    executionStatus: assignment?.status ?? "unclaimed",
    assignedTo: actor?.display_name ?? assignment?.assignee_label ?? assignment?.assigned_to ?? "Unassigned",
    assigneeType: assignment?.assignee_type ?? actor?.kind ?? "unassigned",
    claimedAt: assignment?.claimed_at ?? "",
    updatedAt: assignment?.updated_at ?? "",
    proof: Array.isArray(assignment?.proof) ? assignment.proof : [],
    notes: assignment?.notes ?? "",
    dependsOn: Array.isArray(task.depends_on) ? task.depends_on : [],
    outputs: Array.isArray(task.outputs) ? task.outputs : [],
    verification: Array.isArray(task.verification) ? task.verification : [],
  };
}

function summarizeRows(rows) {
  return rows.reduce(
    (summary, row) => {
      summary.total += 1;
      const key = row.executionStatus;
      summary[key] = (summary[key] ?? 0) + 1;
      return summary;
    },
    {
      total: 0,
      unclaimed: 0,
      claimed: 0,
      in_progress: 0,
      review: 0,
      done: 0,
      blocked: 0,
      released: 0,
    },
  );
}

function renderActorCards(actors) {
  return renderCardGrid(
    actors.map((actor) => `
      <article class="card inset-card">
        <div class="section-heading">
          <div>
            <h4>${escapeHtml(actor.display_name ?? actor.actor_id ?? "Unknown actor")}</h4>
            <p class="muted"><code>${escapeHtml(actor.actor_id ?? "missing-actor-id")}</code></p>
          </div>
          <span class="chip ${statusClass(actor.status)}">${escapeHtml(actor.status ?? "unknown")}</span>
        </div>
        ${renderKeyValueRows([
          { label: "Kind", value: `<code>${escapeHtml(actor.kind ?? "unknown")}</code>` },
          { label: "Notes", value: escapeHtml(actor.notes ?? "") },
        ])}
      </article>
    `).join(""),
    "three-up",
  );
}

function renderTaskCards(rows) {
  return renderCardGrid(rows.map((row) => renderTaskCard(row)).join(""), "two-up");
}

function renderTaskCard(row) {
  return `
    <article class="card">
      <div class="section-heading">
        <div>
          <h4>${escapeHtml(row.id)}</h4>
          <p class="muted">${escapeHtml(row.title)}</p>
        </div>
        <span class="chip ${statusClass(row.executionStatus)}">${escapeHtml(row.executionStatus)}</span>
      </div>
      ${renderKeyValueRows([
        { label: "Assigned To", value: escapeHtml(row.assignedTo) },
        { label: "Assignee Type", value: `<code>${escapeHtml(row.assigneeType)}</code>` },
        { label: "Planning Status", value: `<code>${escapeHtml(row.planningStatus)}</code>` },
        { label: "Owner Role", value: escapeHtml(row.ownerRole) },
        { label: "Repo Target", value: escapeHtml(row.repoTarget) },
        { label: "Lane", value: escapeHtml(row.lane) },
        { label: "Claimed At", value: row.claimedAt ? `<code>${escapeHtml(row.claimedAt)}</code>` : '<span class="muted">not claimed</span>' },
        { label: "Updated At", value: row.updatedAt ? `<code>${escapeHtml(row.updatedAt)}</code>` : '<span class="muted">not updated</span>' },
      ])}
      <h4>Proof</h4>
      ${renderProof(row.proof)}
      <h4>Depends On</h4>
      ${renderChipRow(row.dependsOn, "No dependencies")}
      <h4>Expected Outputs</h4>
      ${renderList(row.outputs, "No outputs listed")}
      <h4>Verification</h4>
      ${renderList(row.verification, "No verification listed")}
      ${row.notes ? `<p class="helper">${escapeHtml(row.notes)}</p>` : ""}
    </article>
  `;
}

function renderProof(proof) {
  if (!proof.length) {
    return '<p class="muted">No proof submitted yet.</p>';
  }

  return `
    <div class="stack">
      ${proof.map((item) => `
        <div class="card inset-card">
          <div class="section-heading">
            <strong>${escapeHtml(item.kind ?? "proof")}</strong>
            <span class="chip ${statusClass(item.status ?? "needs_review")}">${escapeHtml(item.status ?? "needs_review")}</span>
          </div>
          <p>${escapeHtml(item.summary ?? "No proof summary provided.")}</p>
          ${item.path ? `<p class="muted"><code>${escapeHtml(item.path)}</code></p>` : ""}
        </div>
      `).join("")}
    </div>
  `;
}

function statusClass(status) {
  const safeStatus = String(status ?? "unknown").replace(/[^a-z0-9_-]/gi, "").toLowerCase();
  return `status-${safeStatus || "unknown"}`;
}
