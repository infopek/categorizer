import { escapeHtml, initializeViewerPage, renderCardGrid, renderChipRow, renderList } from "./viewer-layout.js";

initializeViewerPage({
  pageId: "overview",
  eyebrow: "Read-Only Planning State Viewer",
  title: "AI Assembly Line",
  description: "Overview of the generated project specification and phase 0 scope boundaries.",
  requiredKeys: ["projectSpec"],
  renderContent(container, data) {
    const projectSpec = data.projectSpec;

    container.innerHTML = `
      <div class="stack">
        <div class="card">
          <h2>${escapeHtml(projectSpec.project_name)}</h2>
          <p>${escapeHtml(projectSpec.product_summary.goal)}</p>
        </div>

        ${renderCardGrid(
          `
            <article class="card">
              <h3>Users</h3>
              ${renderChipRow(projectSpec.product_summary.users)}
            </article>
            <article class="card">
              <h3>Non-Goals</h3>
              ${renderList(projectSpec.product_summary.non_goals)}
            </article>
          `,
          "two-up",
        )}

        ${renderCardGrid(
          `
            <article class="card">
              <h3>Safety Boundaries</h3>
              ${renderList(projectSpec.safety_boundaries)}
            </article>
            <article class="card">
              <h3>Verification Tasks</h3>
              ${renderList(projectSpec.verification_tasks)}
            </article>
          `,
          "two-up",
        )}

        <article class="card">
          <h3>Core Engine Responsibilities</h3>
          ${renderList(projectSpec.core_engine_responsibilities)}
        </article>

        ${renderCardGrid(
          projectSpec.frontend_screens
            .map(
              (screen) => `
                <article class="card">
                  <h3>${escapeHtml(screen.name)}</h3>
                  <p class="muted">${escapeHtml(screen.notes)}</p>
                  <h4>Renders From</h4>
                  ${renderList(screen.renders_from)}
                </article>
              `,
            )
            .join(""),
          "three-up",
        )}

        ${renderCardGrid(
          projectSpec.domain_model
            .map(
              (entry) => `
                <article class="card">
                  <h3>${escapeHtml(entry.name)}</h3>
                  <h4>Fields</h4>
                  ${renderList(entry.fields)}
                  <h4>Relations</h4>
                  ${renderList(entry.relations)}
                </article>
              `,
            )
            .join(""),
          "three-up",
        )}
      </div>
    `;
  },
});
