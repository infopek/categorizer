import { escapeHtml, initializeViewerPage, renderCardGrid, renderChipRow, renderList } from "./viewer-layout.js";

initializeViewerPage({
  pageId: "slots",
  eyebrow: "Agent Slot Board",
  title: "Slots",
  description: "Generated slot records grouped by current status from the canonical slot database.",
  requiredKeys: ["slotsDb"],
  renderContent(container, data) {
    const slots = data.slotsDb;
    const statusOrder = ["active", "ready", "planned", "review", "blocked", "complete"];
    const groups = new Map(statusOrder.map((status) => [status, []]));

    for (const slot of slots) {
      if (!groups.has(slot.status)) {
        groups.set(slot.status, []);
      }
      groups.get(slot.status).push(slot);
    }

    container.innerHTML = `
      <div class="stack">
        ${Array.from(groups.entries())
          .filter(([, groupSlots]) => groupSlots.length > 0)
          .map(([status, groupSlots]) => renderSlotGroup(status, groupSlots))
          .join("")}
      </div>
    `;
  },
});

function renderSlotGroup(status, slots) {
  return `
    <section class="card">
      <div class="section-heading">
        <h2>${escapeHtml(status)}</h2>
        <span class="chip status-${escapeHtml(status)}">${slots.length} slot${slots.length === 1 ? "" : "s"}</span>
      </div>
      ${renderCardGrid(slots.map((slot) => renderSlotCard(slot)).join(""), "three-up")}
    </section>
  `;
}

function renderSlotCard(slot) {
  return `
    <article class="card inset-card">
      <div class="chip-row">
        <span class="chip">${escapeHtml(slot.role)}</span>
        <span class="chip status-${escapeHtml(slot.status)}">${escapeHtml(slot.status)}</span>
      </div>
      <h3>${escapeHtml(slot.slot_id)}</h3>
      ${slot.notes ? `<p class="muted">${escapeHtml(slot.notes)}</p>` : ""}
      <h4>Inputs</h4>
      ${renderList(slot.inputs)}
      <h4>Outputs</h4>
      ${renderList(slot.outputs)}
      <h4>Allowed Actions</h4>
      ${renderList(slot.allowed_actions)}
      <h4>Verification Requirements</h4>
      ${renderList(slot.verification_requirements)}
    </article>
  `;
}
