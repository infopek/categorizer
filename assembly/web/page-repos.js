import { escapeHtml, initializeViewerPage, renderCardGrid, renderChipRow, renderKeyValueRows, renderList } from "./viewer-layout.js";

initializeViewerPage({
  pageId: "repos",
  eyebrow: "Repository Split",
  title: "Repo Ownership",
  description: "Read-only view of the generated repository plan and contract-defined ownership boundaries.",
  requiredKeys: ["repoPlan"],
  renderContent(container, data) {
    const repoPlan = data.repoPlan;

    container.innerHTML = `
      <div class="stack">
        <div class="card">
          <h2>${escapeHtml(repoPlan.project_name)}</h2>
          <p class="muted">Each card renders directly from <code>generated/repo_plan.json</code>.</p>
        </div>

        ${renderCardGrid(
          repoPlan.repos
            .map(
              (repo) => `
                <article class="card">
                  <div class="stack">
                    <div>
                      <h3>${escapeHtml(repo.name)}</h3>
                      <p>${escapeHtml(repo.purpose)}</p>
                    </div>
                    ${renderKeyValueRows([
                      { label: "Contains", value: renderList(repo.contains) },
                      { label: "Depends On", value: repo.depends_on.length ? renderChipRow(repo.depends_on) : '<p class="muted">None</p>' },
                      { label: "Excludes", value: renderList(repo.excludes) },
                    ])}
                  </div>
                </article>
              `,
            )
            .join(""),
          "two-up",
        )}
      </div>
    `;
  },
});
