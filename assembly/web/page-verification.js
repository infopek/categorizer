import { CONTRACT_FILES, formatError, isFileProtocol, loadTextFile } from "./viewer-data.js";
import { escapeHtml, initializeViewerPage, renderCardGrid, renderChipRow, renderList } from "./viewer-layout.js";

const CONTRACT_ENTRIES = [
  { id: "projectSpecSchema", path: "contracts/project_spec.schema.json", kind: "json" },
  { id: "repoPlanSchema", path: "contracts/repo_plan.schema.json", kind: "json" },
  { id: "taskSchema", path: "contracts/task.schema.json", kind: "json" },
  { id: "taskBatchIndexSchema", path: "contracts/task_batch_index.schema.json", kind: "json" },
  { id: "taskBatchSchema", path: "contracts/task_batch.schema.json", kind: "json" },
  { id: "slotSchema", path: "contracts/slot.schema.json", kind: "json" },
  { id: "agentPromptSchema", path: "contracts/agent_prompt.schema.json", kind: "json" },
  { id: "collaborationStateSchema", path: "contracts/collaboration_state.schema.json", kind: "json" },
  { id: "apiContract", path: "contracts/api_contract.openapi.yaml", kind: "text" },
];

initializeViewerPage({
  pageId: "verification",
  eyebrow: "Verification",
  title: "Verification Rules And Proof Requirements",
  description: "Read-only verification view assembled from the generated project spec, backlog, prompts, and slots.",
  requiredKeys: ["projectSpec", "taskBacklog", "agentPrompts", "slotsDb"],
  extraSourceFiles: CONTRACT_ENTRIES.map((entry) => entry.path),
  helperNote:
    "Generated JSON files can still be loaded with the local file picker. Contract files are fetched from repository paths separately and are most reliable when the repo root is served with python -m http.server 8000.",
  renderContent(container, data) {
    const taskGroups = groupTaskVerification(data.taskBacklog);
    const promptCards = data.agentPrompts.prompts.map(
      (prompt) => `
        <article class="card">
          <div class="chip-row">
            <span class="chip">${escapeHtml(prompt.role)}</span>
            <span class="chip">${escapeHtml(prompt.target_repo)}</span>
          </div>
          <h3>${escapeHtml(prompt.prompt_id)}</h3>
          <h4>Verification Required</h4>
          ${renderList(prompt.verification_required)}
          <h4>Task Boundaries</h4>
          ${renderList(prompt.task_boundaries)}
        </article>
      `,
    );

    const slotCards = data.slotsDb.map(
      (slot) => `
        <article class="card">
          <div class="chip-row">
            <span class="chip">${escapeHtml(slot.role)}</span>
            <span class="chip status-${escapeHtml(slot.status)}">${escapeHtml(slot.status)}</span>
          </div>
          <h3>${escapeHtml(slot.slot_id)}</h3>
          <h4>Verification Requirements</h4>
          ${renderList(slot.verification_requirements)}
        </article>
      `,
    );

    container.innerHTML = `
      <div class="stack">
        ${renderCardGrid(
          `
            <article class="card">
              <h2>Source Of Truth Notes</h2>
              <p class="muted">This page renders verification expectations from generated JSON plus raw contract files and keeps the frontend read-only.</p>
              <h3>Generated Artifacts</h3>
              ${renderList([
                "generated/project_spec.json defines top-level verification tasks and scope boundaries.",
                "generated/task_backlog.json defines task acceptance criteria and proof checks.",
                "generated/agent_prompts.json defines role-level verification requirements.",
                "generated/slots_db.json defines slot verification requirements.",
              ])}
              <h3>Contracts</h3>
              ${renderList(CONTRACT_ENTRIES.map((entry) => `${entry.path} is rendered read-only as raw contract content.`))}
            </article>
            <article class="card">
              <h2>Project Verification Rules</h2>
              <h3>Verification Tasks</h3>
              ${renderList(data.projectSpec.verification_tasks)}
              <h3>Safety Boundaries</h3>
              ${renderList(data.projectSpec.safety_boundaries)}
            </article>
          `,
          "two-up",
        )}

        <section class="card">
          <div class="section-heading">
            <h2>Backlog Proof Requirements</h2>
            <span class="chip">${data.taskBacklog.length} task${data.taskBacklog.length === 1 ? "" : "s"}</span>
          </div>
          <div class="stack">
            ${taskGroups
              .map(
                ([repoTarget, tasks]) => `
                  <article class="card inset-card">
                    <div class="section-heading">
                      <div>
                        <h3>${escapeHtml(repoTarget)}</h3>
                        <p class="muted">Acceptance criteria and verification steps grouped by existing <code>repo_target</code>.</p>
                      </div>
                      <span class="chip">${tasks.length} task${tasks.length === 1 ? "" : "s"}</span>
                    </div>
                    <div class="stack">
                      ${tasks
                        .map(
                          (task) => `
                            <div class="card inset-card">
                              <div class="chip-row">
                                <span class="chip">${escapeHtml(task.id)}</span>
                                <span class="chip">${escapeHtml(task.owner_role)}</span>
                              </div>
                              <h4>${escapeHtml(task.title)}</h4>
                              <div class="card-grid two-up">
                                <div>
                                  <h5>Acceptance Criteria</h5>
                                  ${renderList(task.acceptance_criteria)}
                                </div>
                                <div>
                                  <h5>Verification</h5>
                                  ${renderList(task.verification)}
                                </div>
                              </div>
                            </div>
                          `,
                        )
                        .join("")}
                    </div>
                  </article>
                `,
              )
              .join("")}
          </div>
        </section>

        <section class="card">
          <h2>Prompt Verification Requirements</h2>
          ${renderCardGrid(promptCards.join(""), "two-up")}
        </section>

        <section class="card">
          <h2>Slot Verification Requirements</h2>
          ${renderCardGrid(slotCards.join(""), "three-up")}
        </section>

        <section class="card">
          <div class="section-heading">
            <div>
              <h2>Contracts</h2>
              <p class="muted">Each contract is shown read-only with its source path and raw content.</p>
            </div>
            <span id="contractLoadSummary" class="chip">Loading contracts...</span>
          </div>
          <div id="contractsContent" class="stack"></div>
        </section>
      </div>
    `;

    void renderContracts(container.querySelector("#contractsContent"), container.querySelector("#contractLoadSummary"));
  },
});

function groupTaskVerification(tasks) {
  const groups = new Map();

  for (const task of tasks) {
    if (!groups.has(task.repo_target)) {
      groups.set(task.repo_target, []);
    }
    groups.get(task.repo_target).push(task);
  }

  return Array.from(groups.entries());
}

async function renderContracts(target, summaryChip) {
  if (!target || !summaryChip) {
    return;
  }

  const results = await Promise.all(CONTRACT_ENTRIES.map((entry) => loadContractEntry(entry)));
  const failures = results.filter((result) => result.status === "error").length;

  summaryChip.textContent = failures
    ? `${failures} contract load failure${failures === 1 ? "" : "s"}`
    : `${results.length} contracts loaded`;
  summaryChip.className = failures ? "chip status-blocked" : "chip status-ready";

  target.innerHTML = results.map((result) => renderContractCard(result)).join("");
}

async function loadContractEntry(entry) {
  try {
    const raw = await loadTextFile(CONTRACT_FILES[entry.id]);

    if (entry.kind === "json") {
      try {
        JSON.parse(raw);
        return { ...entry, status: "success", parseStatus: "JSON parse OK", raw };
      } catch (error) {
        return { ...entry, status: "error", parseStatus: `JSON parse failed: ${formatError(error)}`, raw };
      }
    }

    return { ...entry, status: "success", parseStatus: "Raw text rendered", raw };
  } catch (error) {
    const detail = isFileProtocol()
      ? `${formatError(error)} Serve the repo root with python -m http.server 8000 to load contract files.`
      : formatError(error);

    return { ...entry, status: "error", parseStatus: detail, raw: "" };
  }
}

function renderContractCard(result) {
  const statusClass = result.status === "success" ? "status success" : "status error";
  const body = result.raw
    ? `<pre class="code-block">${escapeHtml(result.raw)}</pre>`
    : '<p class="muted">No contract content available.</p>';

  return `
    <article class="card inset-card">
      <div class="section-heading">
        <div>
          <h3>${escapeHtml(result.path)}</h3>
          <p class="${statusClass}">${escapeHtml(result.parseStatus)}</p>
        </div>
        <span class="chip">${escapeHtml(result.kind === "json" ? "JSON schema" : "YAML draft")}</span>
      </div>
      ${body}
    </article>
  `;
}
