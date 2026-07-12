import {
  formatError,
  isFileProtocol,
  loadGeneratedState,
  loadLocalState,
  resolveViewerProjectContext,
  sourceFileForExtra,
  sourceFilesForKeys,
} from "./viewer-data.js";
import {
  buildProjectSelectionUrl,
  isWorkspaceContext,
  projectUrlForPage,
  renderProjectLabel,
} from "./project-workspace.js";

const NAV_ITEMS = [
  { id: "overview", label: "Overview", href: "index.html" },
  { id: "repos", label: "Repos", href: "repos.html" },
  { id: "backlog", label: "Backlog", href: "backlog.html" },
  { id: "assignments", label: "Assignments", href: "assignments.html" },
  { id: "dispatch", label: "Dispatch", href: "dispatch.html" },
  { id: "task-batches", label: "Task Batches", href: "task-batches.html" },
  { id: "prompts", label: "Prompts", href: "prompts.html" },
  { id: "slots", label: "Slots", href: "slots.html" },
  { id: "planning-runs", label: "Planning Runs", href: "planning-runs.html" },
  { id: "verification", label: "Verification", href: "verification.html" },
];

export function initializeViewerPage(config) {
  const app = document.getElementById("app");
  if (!app) {
    throw new Error('Missing required root element: #app');
  }

  void initializeAsync(app, config);
}

async function initializeAsync(app, config) {
  const {
    pageId,
    eyebrow,
    title,
    description,
    requiredKeys,
    extraSourceFiles = [],
    helperNote = "",
    renderContent,
  } = config;

  let projectContext;
  try {
    projectContext = await resolveViewerProjectContext();
  } catch (error) {
    renderInitializationFailure(app, config, error);
    return;
  }

  const sourceFiles = [
    ...sourceFilesForKeys(requiredKeys, projectContext),
    ...extraSourceFiles.map((path) => sourceFileForExtra(path, projectContext)),
  ];

  app.innerHTML = `
    <header class="hero shell-width">
      <div class="hero-copy">
        <p class="eyebrow">${escapeHtml(eyebrow)}</p>
        <h1>${escapeHtml(title)}</h1>
        <p class="lede">${escapeHtml(description)}</p>
        <nav class="site-nav" aria-label="Viewer pages">
          ${NAV_ITEMS.map((item) => renderNavLink(item, pageId, projectContext)).join("")}
        </nav>
      </div>
      <div class="hero-actions">
        ${renderProjectSelector(projectContext)}
        <button id="reloadButton" type="button">Reload Generated State</button>
        <label class="file-button" for="filePicker">Load Local JSON Files</label>
        <input id="filePicker" type="file" accept=".json" multiple>
      </div>
    </header>

    <main class="layout shell-width">
      <section class="panel status-panel">
        <h2>Status</h2>
        <p id="statusMessage" class="status loading">Loading generated state...</p>
        <p class="source-note">
          This page renders generated state only. It does not edit or invent task, repo, prompt, slot, planning-run, or contract structure.
        </p>
        <p class="source-note">
          Project mode:
          <strong>${escapeHtml(renderProjectLabel(projectContext))}</strong>
          ${renderProjectSource(projectContext)}.
        </p>
        <p class="source-note">
          Source of truth for this page:
          ${sourceFiles.map((path) => `<code>${escapeHtml(path)}</code>`).join(", ")}.
        </p>
        <p class="helper">
          Serve the repository root with <code>python -m http.server 8000</code>.
          Standalone product repositories may place this viewer under <code>assembly/web/</code> with a repository-root <code>project_workspace.json</code>.
          Registry URLs use <code>?project=&lt;project-id&gt;</code>; explicit standalone manifests may use <code>?workspace=&lt;relative-json-path&gt;</code>.
          If the page is opened with <code>file://</code> and fetch is blocked, load the required JSON files with the button above.
        </p>
        ${helperNote ? `<p class="helper">${helperNote}</p>` : ""}
        <p class="helper">
          Required local files for this page: ${sourceFiles.map((path) => `<code>${escapeHtml(path)}</code>`).join(", ")}.
        </p>
      </section>

      <section class="panel">
        <div id="pageContent"></div>
      </section>
    </main>
  `;

  const statusMessage = document.getElementById("statusMessage");
  const pageContent = document.getElementById("pageContent");
  const reloadButton = document.getElementById("reloadButton");
  const filePicker = document.getElementById("filePicker");
  const projectPicker = document.getElementById("projectPicker");

  projectPicker?.addEventListener("change", (event) => {
    window.location.href = buildProjectSelectionUrl(event.target.value);
  });

  reloadButton.addEventListener("click", () => {
    void fetchAndRender();
  });

  filePicker.addEventListener("change", async (event) => {
    try {
      setStatus(statusMessage, "Loading local JSON files...", "loading");
      clearContent(pageContent);
      const data = await loadLocalState(requiredKeys, event.target.files, projectContext);
      renderContent(pageContent, data, projectContext);
      setStatus(statusMessage, "Loaded generated state from local files.", "success");
    } catch (error) {
      renderFailure(pageContent, statusMessage, error, requiredKeys, projectContext);
    } finally {
      filePicker.value = "";
    }
  });

  void fetchAndRender();

  async function fetchAndRender() {
    try {
      setStatus(statusMessage, "Loading generated state...", "loading");
      clearContent(pageContent);
      const data = await loadGeneratedState(requiredKeys, projectContext);
      renderContent(pageContent, data, projectContext);
      setStatus(statusMessage, `Loaded generated state from ${sourceFiles.join(", ")}.`, "success");
    } catch (error) {
      renderFailure(pageContent, statusMessage, error, requiredKeys, projectContext);
    }
  }
}

function renderInitializationFailure(app, config, error) {
  const pageId = config.pageId ?? "";
  app.innerHTML = `
    <header class="hero shell-width">
      <div class="hero-copy">
        <p class="eyebrow">${escapeHtml(config.eyebrow ?? "Viewer")}</p>
        <h1>${escapeHtml(config.title ?? "Unable to load viewer")}</h1>
        <p class="lede">${escapeHtml(config.description ?? "")}</p>
        <nav class="site-nav" aria-label="Viewer pages">
          ${NAV_ITEMS.map((item) => renderNavLink(item, pageId, null)).join("")}
        </nav>
      </div>
    </header>
    <main class="layout shell-width">
      <section class="panel">
        <div class="card error-card">
          <h2>Unable to load project workspace</h2>
          <p>${escapeHtml(formatError(error))}</p>
          <p class="helper">
            Check the standalone <code>project_workspace.json</code>, an explicit <code>?workspace=...</code> value,
            or <code>projects/index.json</code> and the requested <code>?project=...</code> value.
          </p>
        </div>
      </section>
    </main>
  `;
}

function renderFailure(pageContent, statusMessage, error, requiredKeys, projectContext) {
  const fileList = sourceFilesForKeys(requiredKeys, projectContext)
    .map((path) => `<code>${escapeHtml(path)}</code>`)
    .join(", ");

  const hint = isFileProtocol()
    ? 'Fetch is likely blocked under <code>file://</code>. Use <code>python -m http.server 8000</code> from the repository root or load the required files manually.'
    : isWorkspaceContext(projectContext)
      ? 'Check that the selected project workspace exists and its configured generated JSON files exist.'
      : 'Check that the generated JSON files exist and contain valid JSON.';

  setStatus(statusMessage, formatError(error), "error");
  pageContent.innerHTML = `
    <div class="card error-card">
      <h3>Unable to render this page</h3>
      <p>${escapeHtml(formatError(error))}</p>
      <p class="helper">This page requires ${fileList}.</p>
      <p class="helper">${hint}</p>
    </div>
  `;
}

function renderProjectSource(projectContext) {
  if (projectContext.mode === "project") {
    return `from registry workspace <code>${escapeHtml(projectContext.workspaceRootPath)}</code>`;
  }

  if (projectContext.mode === "standalone") {
    return `from standalone manifest <code>${escapeHtml(projectContext.workspacePath)}</code>`;
  }

  return "from root generated files";
}

function renderProjectSelector(projectContext) {
  if (projectContext.mode === "standalone") {
    return `
      <div class="project-switcher" aria-label="Project selection">
        <span class="project-switcher-label">Project</span>
        <strong>${escapeHtml(renderProjectLabel(projectContext))}</strong>
      </div>
    `;
  }

  const projects = projectContext.projects ?? [];
  const selected = projectContext.mode === "project" ? projectContext.projectId : "";

  if (!projects.length) {
    return `
      <div class="project-switcher" aria-label="Project selection">
        <span class="project-switcher-label">Project</span>
        <strong>Root Generated State</strong>
      </div>
    `;
  }

  return `
    <label class="project-switcher" for="projectPicker">
      <span class="project-switcher-label">Project</span>
      <select id="projectPicker">
        <option value="" ${selected ? "" : "selected"}>Root Generated State</option>
        ${projects.map((project) => `
          <option value="${escapeHtml(project.project_id)}" ${project.project_id === selected ? "selected" : ""}>
            ${escapeHtml(project.name)} (${escapeHtml(project.project_id)})
          </option>
        `).join("")}
      </select>
    </label>
  `;
}

function renderNavLink(item, pageId, projectContext) {
  const className = item.id === pageId ? "nav-link active" : "nav-link";
  return `<a class="${className}" href="${projectUrlForPage(item.href, projectContext)}">${escapeHtml(item.label)}</a>`;
}

export function renderList(items, emptyLabel = "None") {
  if (!items || items.length === 0) {
    return `<p class="muted">${escapeHtml(emptyLabel)}</p>`;
  }

  return `<ul class="list">${items.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul>`;
}

export function renderChipRow(items, emptyLabel = "None") {
  if (!items || items.length === 0) {
    return `<p class="muted">${escapeHtml(emptyLabel)}</p>`;
  }

  return `<div class="chip-row">${items.map((item) => `<span class="chip">${escapeHtml(item)}</span>`).join("")}</div>`;
}

export function renderKeyValueRows(rows) {
  return `
    <dl class="meta">
      ${rows
        .map(
          (row) => `
            <div>
              <dt>${escapeHtml(row.label)}</dt>
              <dd>${row.value}</dd>
            </div>
          `,
        )
        .join("")}
    </dl>
  `;
}

export function renderCardGrid(cardsMarkup, className = "") {
  const suffix = className ? ` ${className}` : "";
  return `<div class="card-grid${suffix}">${cardsMarkup}</div>`;
}

export function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function clearContent(pageContent) {
  pageContent.innerHTML = "";
}

function setStatus(statusMessage, message, kind) {
  statusMessage.textContent = message;
  statusMessage.className = `status ${kind}`;
}
