import { escapeHtml, initializeViewerPage, renderCardGrid, renderChipRow, renderList } from "./viewer-layout.js";

initializeViewerPage({
  pageId: "prompts",
  eyebrow: "Agent Prompts",
  title: "Prompt Pack",
  description: "Generated role prompts rendered directly from the canonical prompt set.",
  requiredKeys: ["agentPrompts"],
  renderContent(container, data) {
    const promptSet = data.agentPrompts;

    container.innerHTML = `
      <div class="stack">
        <div class="card">
          <h2>${escapeHtml(promptSet.project_name)}</h2>
          <p class="muted">Every prompt card below renders from <code>generated/agent_prompts.json</code>.</p>
        </div>

        ${renderCardGrid(
          promptSet.prompts
            .map(
              (prompt) => `
                <article class="card">
                  <div class="chip-row">
                    <span class="chip">${escapeHtml(prompt.prompt_id)}</span>
                    <span class="chip">${escapeHtml(prompt.target_repo)}</span>
                  </div>
                  <h3>${escapeHtml(prompt.role)}</h3>
                  <div class="card-grid two-up">
                    <div>
                      <h4>Allowed Files</h4>
                      ${renderList(prompt.allowed_files)}
                    </div>
                    <div>
                      <h4>Forbidden Files</h4>
                      ${renderList(prompt.forbidden_files)}
                    </div>
                    <div>
                      <h4>Input Context Required</h4>
                      ${renderList(prompt.input_context_required)}
                    </div>
                    <div>
                      <h4>Task Boundaries</h4>
                      ${renderList(prompt.task_boundaries)}
                    </div>
                    <div>
                      <h4>Output Required</h4>
                      ${renderList(prompt.output_required)}
                    </div>
                    <div>
                      <h4>Verification Required</h4>
                      ${renderList(prompt.verification_required)}
                    </div>
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
