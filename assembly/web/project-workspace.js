const PROJECT_REGISTRY_URL = "../projects/index.json";
const STANDALONE_WORKSPACE_CANDIDATES = [
  "../../project_workspace.json",
  "../project_workspace.json",
];

const ROOT_PROJECT_CONTEXT = Object.freeze({
  mode: "root",
  projectId: null,
  projectName: "Root Generated State",
  projectStatus: "seed",
  workspacePath: null,
  workspaceRootPath: "",
  workspaceRootUrl: "../",
  taskRunPathPrefix: "generated/task_runs/",
  registry: null,
  workspace: null,
  project: null,
  projects: [],
  requestedProjectId: "",
  requestedWorkspaceUrl: "",
});

export async function loadProjectContext() {
  const requestedProjectId = getRequestedProjectId();
  const requestedWorkspaceUrl = getRequestedWorkspaceUrl();

  if (requestedWorkspaceUrl) {
    return loadStandaloneWorkspace(requestedWorkspaceUrl, { requestedWorkspaceUrl });
  }

  if (requestedProjectId) {
    return loadRegistryWorkspace(requestedProjectId);
  }

  const standalone = await findStandaloneWorkspace();
  if (standalone) {
    return buildStandaloneContext(standalone.payload, standalone.url, {
      requestedWorkspaceUrl: "",
    });
  }

  const registryResult = await fetchOptionalJson(PROJECT_REGISTRY_URL);
  if (!registryResult.ok) {
    return { ...ROOT_PROJECT_CONTEXT };
  }

  return chooseRegistryContext(registryResult.payload, "");
}

export function isWorkspaceContext(projectContext) {
  return Boolean(projectContext?.workspace && projectContext.mode !== "root");
}

export function projectUrlForPage(pageHref, projectContext) {
  if (!projectContext) {
    return pageHref;
  }

  if (projectContext.mode === "project" && projectContext.projectId) {
    return appendQuery(pageHref, "project", projectContext.projectId);
  }

  if (projectContext.mode === "standalone" && projectContext.requestedWorkspaceUrl) {
    return appendQuery(pageHref, "workspace", projectContext.requestedWorkspaceUrl);
  }

  return pageHref;
}

export function renderProjectLabel(projectContext) {
  if (!projectContext || projectContext.mode === "root") {
    return "Root Generated State";
  }

  return `${projectContext.projectName} (${projectContext.projectId})`;
}

export function workspaceDisplayPath(projectContext, relativePath) {
  const cleanRelative = trimSlashes(relativePath);
  if (!isWorkspaceContext(projectContext)) {
    return cleanRelative;
  }

  return joinPath(projectContext.workspaceRootPath, cleanRelative);
}

export function workspaceFetchPath(projectContext, relativePath) {
  const cleanRelative = trimSlashes(relativePath);
  if (!isWorkspaceContext(projectContext)) {
    return `../${cleanRelative}`;
  }

  if (projectContext.mode === "project" && cleanRelative.startsWith("projects/")) {
    return `../${cleanRelative}`;
  }

  return joinUrl(projectContext.workspaceRootUrl, cleanRelative);
}

export function getRequestedProjectId() {
  return new URLSearchParams(window.location.search).get("project")?.trim() ?? "";
}

export function getRequestedWorkspaceUrl() {
  const value = new URLSearchParams(window.location.search).get("workspace")?.trim() ?? "";
  if (!value) {
    return "";
  }

  if (
    value.includes("://")
    || value.startsWith("/")
    || value.startsWith("\\")
    || value.includes("\\")
    || !value.endsWith(".json")
  ) {
    throw new Error("The workspace query parameter must be a relative JSON path.");
  }

  return value;
}

export function buildProjectSelectionUrl(projectId) {
  const pageName = window.location.pathname.split("/").pop() || "index.html";
  const params = new URLSearchParams(window.location.search);
  params.delete("workspace");

  if (projectId) {
    params.set("project", projectId);
  } else {
    params.delete("project");
  }

  const query = params.toString();
  return `${pageName}${query ? `?${query}` : ""}${window.location.hash}`;
}

async function loadRegistryWorkspace(requestedProjectId) {
  const registryResult = await fetchOptionalJson(PROJECT_REGISTRY_URL);
  if (!registryResult.ok) {
    throw new Error(`Project "${requestedProjectId}" was requested, but projects/index.json could not be loaded: ${registryResult.error}`);
  }

  return chooseRegistryContext(registryResult.payload, requestedProjectId);
}

async function chooseRegistryContext(registry, requestedProjectId) {
  const projects = Array.isArray(registry.projects) ? registry.projects : [];
  const project = chooseProject(projects, registry.default_project_id, requestedProjectId);

  if (!project) {
    if (requestedProjectId) {
      throw new Error(`Project "${requestedProjectId}" was not found in projects/index.json.`);
    }
    return { ...ROOT_PROJECT_CONTEXT, registry, projects };
  }

  const workspaceUrl = `../${trimSlashes(project.workspace_path)}`;
  const workspace = await fetchJson(workspaceUrl, `project workspace for ${project.project_id}`);
  const workspaceRootPath = project.workspace_path.replace(/project_workspace\.json$/, "");
  const workspaceRootUrl = `../${workspaceRootPath}`;

  validateWorkspaceIdentity(workspace, project.project_id, workspaceUrl);

  const taskRunsDir = workspace.paths?.generated?.task_runs_dir ?? "generated/task_runs";
  return {
    mode: "project",
    projectId: project.project_id,
    projectName: project.name,
    projectStatus: project.status,
    workspacePath: project.workspace_path,
    workspaceRootPath,
    workspaceRootUrl,
    taskRunPathPrefix: joinPath(workspaceRootPath, ensureTrailingSlash(taskRunsDir)),
    registry,
    workspace,
    project,
    projects,
    requestedProjectId,
    requestedWorkspaceUrl: "",
  };
}

async function loadStandaloneWorkspace(workspaceUrl, options) {
  const workspace = await fetchJson(workspaceUrl, "standalone project workspace");
  return buildStandaloneContext(workspace, workspaceUrl, options);
}

async function findStandaloneWorkspace() {
  for (const url of STANDALONE_WORKSPACE_CANDIDATES) {
    const result = await fetchOptionalJson(url);
    if (result.ok && looksLikeWorkspace(result.payload)) {
      return { ...result, url };
    }
  }
  return null;
}

function buildStandaloneContext(workspace, workspaceUrl, options) {
  validateWorkspaceIdentity(workspace, workspace.project_id, workspaceUrl);
  const workspaceRootUrl = directoryUrl(workspaceUrl);
  const taskRunsDir = workspace.paths?.generated?.task_runs_dir ?? "generated/task_runs";

  return {
    mode: "standalone",
    projectId: workspace.project_id,
    projectName: workspace.name,
    projectStatus: workspace.status,
    workspacePath: workspaceUrl,
    workspaceRootPath: "",
    workspaceRootUrl,
    taskRunPathPrefix: ensureTrailingSlash(trimSlashes(taskRunsDir)),
    registry: null,
    workspace,
    project: {
      project_id: workspace.project_id,
      name: workspace.name,
      status: workspace.status,
      default_view: workspace.default_view,
      workspace_path: workspaceUrl,
    },
    projects: [],
    requestedProjectId: "",
    requestedWorkspaceUrl: options.requestedWorkspaceUrl ?? "",
  };
}

function validateWorkspaceIdentity(workspace, expectedProjectId, workspaceUrl) {
  if (!looksLikeWorkspace(workspace)) {
    throw new Error(`Workspace at ${workspaceUrl} is missing project_id, name, or paths.generated.`);
  }

  if (expectedProjectId && workspace.project_id !== expectedProjectId) {
    throw new Error(`Expected workspace id "${expectedProjectId}", but ${workspaceUrl} contains "${workspace.project_id}".`);
  }
}

function looksLikeWorkspace(payload) {
  return Boolean(
    payload
    && typeof payload === "object"
    && typeof payload.project_id === "string"
    && typeof payload.name === "string"
    && payload.paths
    && typeof payload.paths === "object"
    && payload.paths.generated
    && typeof payload.paths.generated === "object"
  );
}

function chooseProject(projects, defaultProjectId, requestedProjectId) {
  if (requestedProjectId) {
    return projects.find((project) => project.project_id === requestedProjectId) ?? null;
  }

  if (defaultProjectId) {
    const defaultProject = projects.find((project) => project.project_id === defaultProjectId);
    if (defaultProject) {
      return defaultProject;
    }
  }

  return projects.find((project) => project.status === "active") ?? projects[0] ?? null;
}

async function fetchOptionalJson(url) {
  try {
    const response = await fetch(url, { cache: "no-store" });
    if (!response.ok) {
      return { ok: false, payload: null, error: `${response.status} ${response.statusText}` };
    }
    return { ok: true, payload: await response.json(), error: "" };
  } catch (error) {
    return { ok: false, payload: null, error: formatError(error) };
  }
}

async function fetchJson(url, label) {
  const response = await fetch(url, { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`Failed to fetch ${label} at ${url}: ${response.status} ${response.statusText}`);
  }

  try {
    return await response.json();
  } catch (error) {
    throw new Error(`Failed to parse ${label} at ${url}: ${formatError(error)}`);
  }
}

function appendQuery(pageHref, key, value) {
  const [pathAndQuery, hash = ""] = pageHref.split("#");
  const [path, existingQuery = ""] = pathAndQuery.split("?");
  const params = new URLSearchParams(existingQuery);
  params.set(key, value);
  const query = params.toString();
  return `${path}${query ? `?${query}` : ""}${hash ? `#${hash}` : ""}`;
}

function directoryUrl(fileUrl) {
  const lastSlash = fileUrl.lastIndexOf("/");
  return lastSlash >= 0 ? fileUrl.slice(0, lastSlash + 1) : "./";
}

function joinUrl(root, relativePath) {
  return `${ensureTrailingSlash(root)}${trimSlashes(relativePath)}`;
}

function joinPath(root, relativePath) {
  const cleanRoot = trimSlashes(root);
  const cleanRelative = trimSlashes(relativePath);
  if (!cleanRoot) {
    return cleanRelative;
  }
  if (!cleanRelative) {
    return `${cleanRoot}/`;
  }
  return `${cleanRoot}/${cleanRelative}`;
}

function ensureTrailingSlash(value) {
  return value.endsWith("/") ? value : `${value}/`;
}

function trimSlashes(value) {
  return String(value ?? "").replace(/^\/+|\/+$/g, "");
}

function formatError(error) {
  return error instanceof Error ? error.message : String(error);
}
