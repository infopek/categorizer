import { escapeHtml } from "./viewer-layout.js";

const STATUSES = ["unclaimed", "claimed", "in_progress", "review", "done", "blocked", "released"];
let mounted = false;

installStyles();
new MutationObserver(() => void mount()).observe(document.body, { childList: true, subtree: true });
void mount();

async function mount() {
  if (mounted) return;
  const stack = document.querySelector("#pageContent .stack");
  if (!stack) return;
  mounted = true;

  const panel = document.createElement("section");
  panel.className = "card assignment-tools-panel";
  panel.innerHTML = `<h3>Copy/Paste Execution Tools</h3><p class="helper">Loading generated files...</p>`;
  stack.insertBefore(panel, stack.children[1] ?? null);

  try {
    const [tasks, state] = await Promise.all([
      fetchJson("../generated/task_backlog.json"),
      fetchJson("../generated/collaboration_state.json"),
    ]);
    render(panel, Array.isArray(tasks) ? tasks : [], state && typeof state === "object" ? state : {});
  } catch (error) {
    panel.innerHTML = `
      <h3>Copy/Paste Execution Tools</h3>
      <p class="helper">Could not auto-load generated files: ${escapeHtml(error instanceof Error ? error.message : String(error))}</p>
      <p class="muted">Serve the repo root with <code>python -m http.server 8000</code>, then open <code>http://localhost:8000/web/assignments.html</code>.</p>
    `;
  }
}

async function fetchJson(path) {
  const response = await fetch(path, { cache: "no-store" });
  if (!response.ok) throw new Error(`${path}: ${response.status} ${response.statusText}`);
  return response.json();
}

function render(panel, tasks, state) {
  const actors = Array.isArray(state.actors) ? state.actors : [];
  panel.innerHTML = `
    <div class="section-heading">
      <div>
        <h3>Copy/Paste Execution Tools</h3>
        <p class="muted">Generate executor prompts and replacement <code>generated/collaboration_state.json</code> previews. Nothing is written automatically.</p>
      </div>
      <span class="chip status-planned">static helper</span>
    </div>
    <div class="builder-grid">
      <label>Task
        <select id="toolTask">${tasks.map((task) => `<option value="${escapeHtml(task.id)}">${escapeHtml(task.id)} - ${escapeHtml(task.title ?? task.summary ?? "Untitled")}</option>`).join("")}</select>
      </label>
      <label>Actor
        <select id="toolActor"><option value="">Unassigned</option>${actors.map((actor) => `<option value="${escapeHtml(actor.actor_id)}">${escapeHtml(actor.display_name ?? actor.actor_id)} (${escapeHtml(actor.kind ?? "unknown")})</option>`).join("")}</select>
      </label>
      <label>Status
        <select id="toolStatus">${STATUSES.map((status) => `<option value="${escapeHtml(status)}">${escapeHtml(status)}</option>`).join("")}</select>
      </label>
      <label class="wide-field">Notes
        <textarea id="toolNotes" rows="3" placeholder="Short assignment note"></textarea>
      </label>
      <label class="wide-field">Proof path
        <input id="toolProofPath" type="text" placeholder="generated/task_runs/<task_id>.json">
      </label>
      <label class="wide-field">Proof summary
        <textarea id="toolProofSummary" rows="3" placeholder="Optional proof summary"></textarea>
      </label>
    </div>
    <div class="button-row">
      <button id="copyPrompt" type="button">Copy Executor Prompt</button>
      <button id="previewState" type="button">Preview State JSON</button>
      <button id="copyState" type="button">Copy State JSON</button>
      <button id="downloadState" type="button">Download State JSON</button>
    </div>
    <p id="toolMessage" class="helper">Select a task to start.</p>
    <pre id="toolPreview" class="code-block">No generated preview yet.</pre>
  `;
  wire(panel, tasks, state, actors);
}

function wire(panel, tasks, state, actors) {
  const q = (id) => panel.querySelector(id);
  const taskSelect = q("#toolTask");
  const actorSelect = q("#toolActor");
  const statusSelect = q("#toolStatus");
  const notesInput = q("#toolNotes");
  const proofPathInput = q("#toolProofPath");
  const proofSummaryInput = q("#toolProofSummary");
  const message = q("#toolMessage");
  const preview = q("#toolPreview");
  const tasksById = new Map(tasks.map((task) => [task.id, task]));
  const actorsById = new Map(actors.map((actor) => [actor.actor_id, actor]));
  const assignments = new Map((Array.isArray(state.task_assignments) ? state.task_assignments : []).map((item) => [item.task_id, item]));

  const task = () => tasksById.get(taskSelect.value) ?? tasks[0] ?? null;

  function fill() {
    const selected = task();
    if (!selected) return;
    const existing = assignments.get(selected.id);
    actorSelect.value = actorsById.has(existing?.assigned_to) ? existing.assigned_to : "";
    statusSelect.value = existing?.status ?? "claimed";
    notesInput.value = existing?.notes ?? "";
    proofPathInput.value = `generated/task_runs/${selected.id}.json`;
    proofSummaryInput.value = "";
    preview.textContent = "No generated preview yet.";
    message.textContent = `Selected ${selected.id}.`;
  }

  function assignmentFor(selected) {
    const now = new Date().toISOString();
    const actor = actorSelect.value ? actorsById.get(actorSelect.value) : null;
    const previous = assignments.get(selected.id) ?? {};
    const status = statusSelect.value;
    const unassigned = !actor || status === "unclaimed" || status === "released";
    const assignment = {
      task_id: selected.id,
      assigned_to: unassigned ? null : actor.actor_id,
      assignee_type: unassigned ? "unassigned" : actor.kind,
      assignee_label: unassigned ? "Unassigned" : actor.display_name ?? actor.actor_id,
      status,
      updated_at: now,
    };
    if (!unassigned) assignment.claimed_at = previous.claimed_at ?? now;
    const notes = notesInput.value.trim() || previous.notes;
    if (notes) assignment.notes = notes;
    const proof = Array.isArray(previous.proof) ? clone(previous.proof) : [];
    const proofPath = proofPathInput.value.trim();
    const proofSummary = proofSummaryInput.value.trim();
    if (proofPath || proofSummary) {
      proof.push({
        kind: "task_run",
        ...(proofPath ? { path: proofPath } : {}),
        summary: proofSummary || `Proof recorded for ${selected.id}.`,
        status: "needs_review",
        recorded_at: now,
      });
    }
    if (proof.length) assignment.proof = proof;
    return assignment;
  }

  function stateText() {
    const selected = task();
    const payload = clone(state);
    payload.schema_version = payload.schema_version ?? "0.1.0";
    payload.workspace_id = payload.workspace_id ?? "default-workspace";
    payload.actors = Array.isArray(payload.actors) ? payload.actors : [];
    payload.task_assignments = Array.isArray(payload.task_assignments) ? payload.task_assignments : [];
    payload.task_claims = Array.isArray(payload.task_claims) ? payload.task_claims : [];
    payload.artifact_submissions = Array.isArray(payload.artifact_submissions) ? payload.artifact_submissions : [];
    payload.reviews = Array.isArray(payload.reviews) ? payload.reviews : [];
    payload.audit_events = Array.isArray(payload.audit_events) ? payload.audit_events : [];
    if (selected) {
      const nextAssignment = assignmentFor(selected);
      const index = payload.task_assignments.findIndex((item) => item.task_id === selected.id);
      if (index >= 0) payload.task_assignments[index] = nextAssignment;
      else payload.task_assignments.push(nextAssignment);
      payload.task_assignments.sort((left, right) => left.task_id.localeCompare(right.task_id));
    }
    const text = `${JSON.stringify(payload, null, 2)}\n`;
    preview.textContent = text;
    return text;
  }

  taskSelect.addEventListener("change", fill);
  fill();

  q("#copyPrompt")?.addEventListener("click", async () => {
    const selected = task();
    if (!selected) return;
    await copyText(executorPrompt(selected, assignmentFor(selected)));
    message.textContent = `Copied executor prompt for ${selected.id}.`;
  });
  q("#previewState")?.addEventListener("click", () => {
    stateText();
    message.textContent = "Generated collaboration_state.json preview.";
  });
  q("#copyState")?.addEventListener("click", async () => {
    await copyText(stateText());
    message.textContent = "Copied collaboration_state.json preview.";
  });
  q("#downloadState")?.addEventListener("click", () => {
    downloadText("collaboration_state.json", stateText());
    message.textContent = "Downloaded collaboration_state.json preview.";
  });
}

function executorPrompt(task, assignment) {
  return `Use prompts/07-task-executor.md for this exact task. Work only inside the task boundaries and return exactly one fenced json code block matching contracts/task_run.schema.json.

Save returned report to: generated/task_runs/${task.id}.json
After review, add a proof reference for that run to generated/collaboration_state.json.

Assignment metadata:
${JSON.stringify(assignment ?? {}, null, 2)}

Task JSON:
${JSON.stringify(task, null, 2)}
`;
}

async function copyText(text) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.style.position = "absolute";
  textarea.style.left = "-9999px";
  document.body.append(textarea);
  textarea.select();
  document.execCommand("copy");
  textarea.remove();
}

function downloadText(filename, text) {
  const blob = new Blob([text], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function installStyles() {
  const style = document.createElement("style");
  style.textContent = `
    .builder-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin: 14px 0; }
    .builder-grid label { display: grid; gap: 6px; color: var(--muted); line-height: 1.45; }
    .builder-grid input, .builder-grid select, .builder-grid textarea { width: 100%; border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px; font: inherit; color: var(--ink); background: #fffcf5; }
    .wide-field { grid-column: span 3; }
    .button-row { display: flex; flex-wrap: wrap; gap: 10px; margin: 12px 0; }
    @media (max-width: 820px) { .builder-grid { grid-template-columns: 1fr; } .wide-field { grid-column: span 1; } }
  `;
  document.head.append(style);
}

function clone(value) {
  return JSON.parse(JSON.stringify(value ?? {}));
}
