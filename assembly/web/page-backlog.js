import { escapeHtml, initializeViewerPage, renderChipRow, renderList } from "./viewer-layout.js";

initializeViewerPage({
  pageId: "backlog",
  eyebrow: "Task Backlog",
  title: "Backlog By Repo Target",
  description: "Generated tasks grouped by the existing repo_target values in the canonical backlog.",
  requiredKeys: ["repoPlan", "taskBacklog"],
  renderContent(container, data) {
    const repoOrder = data.repoPlan.repos.map((repo) => repo.name);
    const repoMeta = new Map(data.repoPlan.repos.map((repo) => [repo.name, repo]));
    const groups = new Map(repoOrder.map((name) => [name, []]));

    for (const task of data.taskBacklog) {
      if (!groups.has(task.repo_target)) {
        groups.set(task.repo_target, []);
      }
      groups.get(task.repo_target).push(task);
    }

    container.innerHTML = `
      <div class="stack">
        ${Array.from(groups.entries())
          .map(([repoName, tasks]) => renderTaskGroup(repoName, tasks, repoMeta.get(repoName)))
          .join("")}
      </div>
    `;
  },
});

function renderTaskGroup(repoName, tasks, repoMeta) {
  return `
    <section class="card">
      <div class="section-heading">
        <div>
          <h2>${escapeHtml(repoName)}</h2>
          <p class="muted">${repoMeta ? escapeHtml(repoMeta.purpose) : "Repo target appears in the generated backlog but not in the generated repo plan."}</p>
        </div>
        <span class="chip">${tasks.length} task${tasks.length === 1 ? "" : "s"}</span>
      </div>

      ${
        tasks.length
          ? `<div class="stack">${tasks.map((task) => renderTaskCard(task)).join("")}</div>`
          : '<p class="muted">No tasks currently generated for this repo target.</p>'
      }
    </section>
  `;
}

function renderTaskCard(task) {
  return `
    <article class="card inset-card">
      <div class="chip-row">
        <span class="chip">${escapeHtml(task.id)}</span>
        <span class="chip">${escapeHtml(task.owner_role)}</span>
      </div>
      <h3>${escapeHtml(task.title)}</h3>
      <p>${escapeHtml(task.summary)}</p>
      <div class="card-grid two-up">
        <div>
          <h4>Depends On</h4>
          ${renderList(task.depends_on)}
        </div>
        <div>
          <h4>Inputs</h4>
          ${renderList(task.inputs)}
        </div>
        <div>
          <h4>Outputs</h4>
          ${renderList(task.outputs)}
        </div>
        <div>
          <h4>Risk Tags</h4>
          ${task.risk_tags && task.risk_tags.length ? renderChipRow(task.risk_tags) : '<p class="muted">None</p>'}
        </div>
      </div>
      <div class="card-grid two-up">
        <div>
          <h4>Acceptance Criteria</h4>
          ${renderList(task.acceptance_criteria)}
        </div>
        <div>
          <h4>Verification</h4>
          ${renderList(task.verification)}
        </div>
      </div>
    </article>
  `;
}
