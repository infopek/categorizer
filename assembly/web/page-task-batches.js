import { escapeHtml, initializeViewerPage, renderCardGrid, renderChipRow, renderKeyValueRows, renderList } from "./viewer-layout.js";
import { workspaceDisplayPath, workspaceFetchPath } from "./project-workspace.js";

initializeViewerPage({
  pageId: "task-batches",
  eyebrow: "Task Batch Workflow",
  title: "Task Batches",
  description: "Read-only view of task batch index files, generated batch files, and dependency graph readiness.",
  requiredKeys: ["taskBatchIndex"],
  helperNote: "Batch files are fetched from each batch output_path. Serve the repo root locally for automatic batch loading.",
  renderContent(container, data, projectContext) {
    const context = projectContext ?? data.__projectContext;
    const index = data.taskBatchIndex;
    const batches = Array.isArray(index.batches) ? index.batches : [];
    const batchSummaryId = "batchLoadSummary";
    const batchContentId = "batchContent";
    const taskBatchIndexPath = context?.mode === "project"
      ? workspaceDisplayPath(context, context.workspace?.paths?.generated?.task_batch_index ?? "generated/task_batch_index.json")
      : "generated/task_batch_index.json";
    const taskBatchesDir = context?.mode === "project"
      ? workspaceDisplayPath(context, context.workspace?.paths?.generated?.task_batches_dir ?? "generated/task_batches")
      : "generated/task_batches";

    container.innerHTML = `
      <div class="stack">
        <div class="card">
          <div class="section-heading">
            <div>
              <h2>Task Batch Index</h2>
              <p class="muted">Use this page after generating <code>${escapeHtml(taskBatchIndexPath)}</code> with the Task Splitter prompt.</p>
            </div>
            <span id="${batchSummaryId}" class="chip">Loading batch files...</span>
          </div>
          ${renderKeyValueRows([
            { label: "Project", value: escapeHtml(projectLabel(context)) },
            { label: "Batch Index", value: `<code>${escapeHtml(taskBatchIndexPath)}</code>` },
            { label: "Task Batches Dir", value: `<code>${escapeHtml(taskBatchesDir)}</code>` },
            { label: "Schema Version", value: `<code>${escapeHtml(index.schema_version ?? "unknown")}</code>` },
            { label: "Source Plan", value: `<code>${escapeHtml(index.source_plan_path ?? "unknown")}</code>` },
            { label: "Batching Strategy", value: `<code>${escapeHtml(index.batching_strategy ?? "unknown")}</code>` },
            { label: "Batches", value: escapeHtml(String(batches.length)) },
            { label: "Expected Tasks", value: escapeHtml(String(sumExpectedTasks(batches))) },
          ])}
        </div>

        <article class="card">
          <h3>Copy/Paste Flow</h3>
          ${renderList([
            "Paste prompts/06-task-splitter.md into a web AI with MODE: guided and the accepted generated plan.",
            `Save the first returned JSON block as ${taskBatchIndexPath}.`,
            "Reply continue, next, or sounds good to generate one batch file at a time.",
            `Save each returned JSON block under ${taskBatchesDir}/<batch_id>.json.`,
            "Run python tools/validate_task_batches.py to validate root seed files, or project workspace validation for project-scoped files.",
          ])}
        </article>

        <div id="${batchContentId}" class="stack"></div>
      </div>
    `;

    void renderBatchFiles(
      container.querySelector(`#${batchContentId}`),
      container.querySelector(`#${batchSummaryId}`),
      batches,
      context,
    );
  },
});

async function renderBatchFiles(target, summaryChip, batches, projectContext) {
  if (!target || !summaryChip) {
    return;
  }

  const results = await Promise.all(batches.map((batch) => loadBatch(batch, projectContext)));
  const loaded = results.filter((result) => result.status === "loaded");
  const missing = results.filter((result) => result.status !== "loaded");
  const graph = analyzeGraph(batches, loaded);

  summaryChip.textContent = `${loaded.length}/${batches.length} batch files loaded`;
  summaryChip.className = missing.length ? "chip status-planned" : "chip status-complete";

  target.innerHTML = `
    ${renderGraphCard(graph)}
    ${batches.length === 0 ? '<p class="muted">No task batches are listed yet.</p>' : renderBatchCards(results)}
  `;
}

async function loadBatch(batch, projectContext) {
  const path = batch.output_path;
  if (!path) {
    return { batch, status: "error", error: "Missing output_path", payload: null, displayPath: "missing-output-path" };
  }

  const fetchPath = projectContext?.mode === "project" ? workspaceFetchPath(projectContext, path) : `../${path}`;
  const displayPath = projectContext?.mode === "project" ? workspaceDisplayPath(projectContext, path) : path;

  try {
    const response = await fetch(fetchPath, { cache: "no-store" });
    if (!response.ok) {
      return { batch, status: "missing", error: `${response.status} ${response.statusText}`, payload: null, displayPath };
    }
    return { batch, status: "loaded", error: "", payload: await response.json(), displayPath };
  } catch (error) {
    return { batch, status: "error", error: error instanceof Error ? error.message : String(error), payload: null, displayPath };
  }
}

function projectLabel(context) {
  return context?.mode === "project" ? `${context.projectName} (${context.projectId})` : "Root Generated State";
}

function analyzeGraph(batches, loadedResults) {
  const batchOrder = new Map(batches.map((batch, index) => [batch.batch_id, index]));
  const tasks = new Map();
  const taskToBatch = new Map();
  const errors = [];

  for (const result of loadedResults) {
    const batchId = result.batch.batch_id;
    const payload = result.payload;
    if (!payload || payload.batch_id !== batchId || !Array.isArray(payload.tasks)) {
      errors.push(`${batchId}: batch file shape does not match expected task batch object.`);
      continue;
    }

    for (const task of payload.tasks) {
      if (!task.id) {
        errors.push(`${batchId}: task is missing id.`);
        continue;
      }
      if (tasks.has(task.id)) {
        errors.push(`Duplicate task id: ${task.id}.`);
        continue;
      }
      tasks.set(task.id, task);
      taskToBatch.set(task.id, batchId);
    }
  }

  for (const [taskId, task] of tasks.entries()) {
    for (const dependency of task.depends_on ?? []) {
      if (!tasks.has(dependency)) {
        errors.push(`${taskId} depends on unknown task ${dependency}.`);
        continue;
      }
      const dependencyBatch = taskToBatch.get(dependency);
      const taskBatch = taskToBatch.get(taskId);
      if (batchOrder.get(dependencyBatch) > batchOrder.get(taskBatch)) {
        errors.push(`${taskId} depends on ${dependency}, which is in a later batch.`);
      }
    }

    for (const blocked of task.blocks ?? []) {
      if (!tasks.has(blocked)) {
        errors.push(`${taskId} blocks unknown task ${blocked}.`);
      }
    }
  }

  const cycleError = findCycle(tasks);
  if (cycleError) {
    errors.push(cycleError);
  }

  return {
    loadedBatchCount: loadedResults.length,
    taskCount: tasks.size,
    nodes: buildGraphNodes(tasks, taskToBatch, batchOrder),
    edges: buildGraphEdges(tasks),
    errors,
  };
}

function buildGraphNodes(tasks, taskToBatch, batchOrder) {
  return Array.from(tasks.values())
    .map((task) => ({
      id: task.id,
      title: task.title ?? task.summary ?? task.id,
      status: task.status ?? "draft",
      ownerRole: task.owner_role ?? "unknown",
      repoTarget: task.repo_target ?? "unknown",
      lane: task.lane ?? "unassigned",
      batchId: taskToBatch.get(task.id) ?? "unknown-batch",
      dependsOn: Array.isArray(task.depends_on) ? task.depends_on : [],
      blocks: Array.isArray(task.blocks) ? task.blocks : [],
    }))
    .sort((left, right) => {
      const batchDelta = (batchOrder.get(left.batchId) ?? 9999) - (batchOrder.get(right.batchId) ?? 9999);
      return batchDelta || left.id.localeCompare(right.id);
    });
}

function buildGraphEdges(tasks) {
  const edges = [];

  for (const [taskId, task] of tasks.entries()) {
    for (const dependency of task.depends_on ?? []) {
      if (tasks.has(dependency)) {
        edges.push({ from: dependency, to: taskId, kind: "depends_on" });
      }
    }

    for (const blocked of task.blocks ?? []) {
      if (tasks.has(blocked)) {
        edges.push({ from: taskId, to: blocked, kind: "blocks" });
      }
    }
  }

  return edges.sort((left, right) => `${left.from}:${left.to}:${left.kind}`.localeCompare(`${right.from}:${right.to}:${right.kind}`));
}

function findCycle(tasks) {
  const indegree = new Map(Array.from(tasks.keys()).map((taskId) => [taskId, 0]));
  const outgoing = new Map(Array.from(tasks.keys()).map((taskId) => [taskId, []]));

  for (const [taskId, task] of tasks.entries()) {
    for (const dependency of task.depends_on ?? []) {
      if (!tasks.has(dependency)) {
        continue;
      }
      outgoing.get(dependency).push(taskId);
      indegree.set(taskId, indegree.get(taskId) + 1);
    }
  }

  const queue = Array.from(indegree.entries())
    .filter(([, degree]) => degree === 0)
    .map(([taskId]) => taskId)
    .sort();
  let visited = 0;

  while (queue.length) {
    const taskId = queue.shift();
    visited += 1;
    for (const dependent of outgoing.get(taskId)) {
      indegree.set(dependent, indegree.get(dependent) - 1);
      if (indegree.get(dependent) === 0) {
        queue.push(dependent);
        queue.sort();
      }
    }
  }

  return visited === tasks.size ? "" : "Task dependency graph contains a cycle.";
}

function renderGraphCard(graph) {
  const ok = graph.errors.length === 0;
  return `
    <article class="card">
      <div class="section-heading">
        <div>
          <h2>Graph Readiness</h2>
          <p class="muted">Checks loaded batch files for dependency references, blocks references, cycles, and topological batch order.</p>
        </div>
        <span class="chip ${ok ? "status-complete" : "status-blocked"}">${ok ? "graph ok" : "graph issues"}</span>
      </div>
      ${renderKeyValueRows([
        { label: "Loaded Batches", value: escapeHtml(String(graph.loadedBatchCount)) },
        { label: "Loaded Tasks", value: escapeHtml(String(graph.taskCount)) },
        { label: "Graph Edges", value: escapeHtml(String(graph.edges.length)) },
        { label: "Issues", value: escapeHtml(String(graph.errors.length)) },
      ])}
      ${renderDependencyGraph(graph)}
      <h3>Issues</h3>
      ${renderList(graph.errors, "No graph issues in loaded batches")}
    </article>
  `;
}

function renderDependencyGraph(graph) {
  if (!graph.nodes.length) {
    return `
      <h3>Dependency Graph</h3>
      <p class="muted">No loaded task nodes yet. Generate at least one batch file to populate the graph.</p>
    `;
  }

  const groups = groupNodesByBatch(graph.nodes);
  return `
    <h3>Dependency Graph</h3>
    <p class="muted">Each card is a task node. Batch columns preserve the generated topological order; dependency and blocking edges are listed below.</p>
    <div class="dependency-graph" aria-label="Task dependency graph">
      <div class="graph-columns">
        ${groups.map((group) => renderGraphColumn(group)).join("")}
      </div>
    </div>
    <h4>Edges</h4>
    ${renderGraphEdges(graph.edges)}
  `;
}

function groupNodesByBatch(nodes) {
  const groups = [];
  const byBatch = new Map();

  for (const node of nodes) {
    if (!byBatch.has(node.batchId)) {
      const group = { batchId: node.batchId, nodes: [] };
      byBatch.set(node.batchId, group);
      groups.push(group);
    }
    byBatch.get(node.batchId).nodes.push(node);
  }

  return groups;
}

function renderGraphColumn(group) {
  return `
    <section class="graph-column">
      <div class="graph-column-heading">
        <h4>${escapeHtml(group.batchId)}</h4>
        <span class="chip">${group.nodes.length} node${group.nodes.length === 1 ? "" : "s"}</span>
      </div>
      <div class="graph-node-stack">
        ${group.nodes.map((node) => renderGraphNode(node)).join("")}
      </div>
    </section>
  `;
}

function renderGraphNode(node) {
  return `
    <article class="graph-node ${taskStatusClass(node.status)}">
      <div class="graph-node-title">
        <strong>${escapeHtml(node.id)}</strong>
        <span class="chip ${taskStatusClass(node.status)}">${escapeHtml(node.status)}</span>
      </div>
      <p>${escapeHtml(node.title)}</p>
      <div class="graph-node-meta">
        <span>${escapeHtml(node.ownerRole)}</span>
        <span>${escapeHtml(node.repoTarget)}</span>
        <span>${escapeHtml(node.lane)}</span>
      </div>
      <div class="graph-node-links">
        <span>Depends on</span>
        ${renderInlineIds(node.dependsOn, "none")}
        <span>Blocks</span>
        ${renderInlineIds(node.blocks, "none")}
      </div>
    </article>
  `;
}

function renderGraphEdges(edges) {
  if (!edges.length) {
    return '<p class="muted">No dependency or blocking edges in loaded tasks.</p>';
  }

  return `
    <div class="graph-edge-list">
      ${edges.map((edge) => `
        <div class="graph-edge">
          <code>${escapeHtml(edge.from)}</code>
          <span>${edge.kind === "blocks" ? "blocks" : "feeds"}</span>
          <code>${escapeHtml(edge.to)}</code>
          <span class="chip">${escapeHtml(edge.kind)}</span>
        </div>
      `).join("")}
    </div>
  `;
}

function renderInlineIds(ids, emptyText) {
  if (!ids.length) {
    return `<span class="muted">${escapeHtml(emptyText)}</span>`;
  }

  return `<span class="inline-id-list">${ids.map((id) => `<code>${escapeHtml(id)}</code>`).join("")}</span>`;
}

function taskStatusClass(status) {
  const safeStatus = String(status ?? "draft").replace(/[^a-z0-9_-]/gi, "").toLowerCase();
  return `status-${safeStatus || "draft"}`;
}

function renderBatchCards(results) {
  return renderCardGrid(
    results.map((result) => renderBatchCard(result)).join(""),
    "two-up",
  );
}

function renderBatchCard(result) {
  const batch = result.batch;
  const payload = result.payload;
  const tasks = payload && Array.isArray(payload.tasks) ? payload.tasks : [];
  const statusClass = result.status === "loaded" ? "status-complete" : "status-planned";

  return `
    <article class="card">
      <div class="section-heading">
        <div>
          <h3>${escapeHtml(batch.batch_id ?? "unknown-batch")}</h3>
          <p class="muted"><code>${escapeHtml(result.displayPath ?? batch.output_path ?? "missing-output-path")}</code></p>
        </div>
        <span class="chip ${statusClass}">${escapeHtml(result.status)}</span>
      </div>

      ${renderKeyValueRows([
        { label: "Owner Role", value: escapeHtml(batch.owner_role ?? "unknown") },
        { label: "Expected Tasks", value: escapeHtml(String(batch.expected_task_count ?? 0)) },
        { label: "Loaded Tasks", value: escapeHtml(String(tasks.length)) },
        { label: "Status", value: `<code>${escapeHtml(batch.status ?? "unknown")}</code>` },
      ])}

      <h4>Repo Targets</h4>
      ${renderChipRow(batch.repo_targets ?? [], "No repo targets")}
      <h4>Lanes</h4>
      ${renderChipRow(batch.lanes ?? [], "No lanes")}
      <h4>Milestones</h4>
      ${renderList(batch.milestones ?? [], "No milestones")}
      <h4>Depends On Batches</h4>
      ${renderList(batch.depends_on_batches ?? [], "No batch dependencies")}
      <h4>Expected Task IDs</h4>
      ${renderChipRow(batch.expected_task_ids ?? [], "No expected task IDs")}
      ${result.error ? `<p class="helper">Batch file detail: ${escapeHtml(result.error)}</p>` : ""}
    </article>
  `;
}

function sumExpectedTasks(batches) {
  return batches.reduce((total, batch) => total + Number(batch.expected_task_count ?? 0), 0);
}
