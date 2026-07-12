import {
  isWorkspaceContext,
  loadProjectContext,
  workspaceDisplayPath,
  workspaceFetchPath,
} from "./project-workspace.js";

export const DATA_FILES = {
  projectSpec: "../generated/project_spec.json",
  repoPlan: "../generated/repo_plan.json",
  taskBacklog: "../generated/task_backlog.json",
  taskBatchIndex: "../generated/task_batch_index.json",
  collaborationState: "../generated/collaboration_state.json",
  agentPrompts: "../generated/agent_prompts.json",
  slotsDb: "../generated/slots_db.json",
  planningRunsIndex: "../generated/planning_runs_index.json",
};

export const CONTRACT_FILES = {
  projectSpecSchema: "../contracts/project_spec.schema.json",
  repoPlanSchema: "../contracts/repo_plan.schema.json",
  taskSchema: "../contracts/task.schema.json",
  taskBatchIndexSchema: "../contracts/task_batch_index.schema.json",
  taskBatchSchema: "../contracts/task_batch.schema.json",
  taskRunSchema: "../contracts/task_run.schema.json",
  slotSchema: "../contracts/slot.schema.json",
  agentPromptSchema: "../contracts/agent_prompt.schema.json",
  collaborationStateSchema: "../contracts/collaboration_state.schema.json",
  projectRegistrySchema: "../contracts/project_registry.schema.json",
  projectWorkspaceSchema: "../contracts/project_workspace.schema.json",
  apiContract: "../contracts/api_contract.openapi.yaml",
};

export const FILE_NAMES = {
  projectSpec: "project_spec.json",
  repoPlan: "repo_plan.json",
  taskBacklog: "task_backlog.json",
  taskBatchIndex: "task_batch_index.json",
  collaborationState: "collaboration_state.json",
  agentPrompts: "agent_prompts.json",
  slotsDb: "slots_db.json",
  planningRunsIndex: "planning_runs_index.json",
};

const WORKSPACE_GENERATED_KEYS = {
  projectSpec: "project_spec",
  repoPlan: "repo_plan",
  taskBacklog: "task_backlog",
  taskBatchIndex: "task_batch_index",
  collaborationState: "collaboration_state",
  agentPrompts: "agent_prompts",
  slotsDb: "slots_db",
  planningRunsIndex: "planning_runs_index",
};

export async function resolveViewerProjectContext() {
  return loadProjectContext();
}

export async function loadGeneratedState(requiredKeys, projectContext) {
  const entries = await Promise.all(
    requiredKeys.map(async (key) => {
      const path = dataFileFetchPath(key, projectContext);
      const response = await fetch(path, { cache: "no-store" });

      if (!response.ok) {
        throw new Error(`Failed to fetch ${path}: ${response.status} ${response.statusText}`);
      }

      try {
        return [key, await response.json()];
      } catch (error) {
        throw new Error(`Failed to parse ${path}: ${formatError(error)}`);
      }
    }),
  );

  return {
    ...Object.fromEntries(entries),
    __projectContext: projectContext,
  };
}

export async function loadLocalState(requiredKeys, files, projectContext = null) {
  const fileMap = new Map(Array.from(files ?? []).map((file) => [file.name, file]));

  for (const key of requiredKeys) {
    const fileName = FILE_NAMES[key];
    if (!fileMap.has(fileName)) {
      throw new Error(`Missing required file: ${fileName}`);
    }
  }

  const entries = await Promise.all(
    requiredKeys.map(async (key) => {
      const fileName = FILE_NAMES[key];
      const raw = await fileMap.get(fileName).text();

      try {
        return [key, JSON.parse(raw)];
      } catch (error) {
        throw new Error(`Failed to parse ${fileName}: ${formatError(error)}`);
      }
    }),
  );

  return {
    ...Object.fromEntries(entries),
    __projectContext: projectContext,
  };
}

export async function loadTextFile(path) {
  const response = await fetch(path, { cache: "no-store" });

  if (!response.ok) {
    throw new Error(`Failed to fetch ${path}: ${response.status} ${response.statusText}`);
  }

  return response.text();
}

export function sourceFilesForKeys(requiredKeys, projectContext = null) {
  return requiredKeys.map((key) => dataFileDisplayPath(key, projectContext));
}

export function sourceFileForExtra(path, projectContext = null) {
  if (isWorkspaceContext(projectContext) && path.startsWith("generated/")) {
    return workspaceDisplayPath(projectContext, path);
  }

  return path;
}

export function dataFileFetchPath(key, projectContext = null) {
  if (isWorkspaceContext(projectContext)) {
    const workspacePath = workspaceGeneratedPath(key, projectContext);
    if (workspacePath) {
      return workspaceFetchPath(projectContext, workspacePath);
    }
  }

  return DATA_FILES[key];
}

export function dataFileDisplayPath(key, projectContext = null) {
  if (isWorkspaceContext(projectContext)) {
    const workspacePath = workspaceGeneratedPath(key, projectContext);
    if (workspacePath) {
      return workspaceDisplayPath(projectContext, workspacePath);
    }
  }

  return `generated/${FILE_NAMES[key]}`;
}

function workspaceGeneratedPath(key, projectContext) {
  const generatedKey = WORKSPACE_GENERATED_KEYS[key];
  if (!generatedKey) {
    return null;
  }

  const configuredPath = projectContext.workspace?.paths?.generated?.[generatedKey];
  if (configuredPath) {
    return configuredPath;
  }

  return `generated/${FILE_NAMES[key]}`;
}

export function isFileProtocol() {
  return window.location.protocol === "file:";
}

export function formatError(error) {
  return error instanceof Error ? error.message : String(error);
}
