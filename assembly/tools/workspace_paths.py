from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class WorkspacePaths:
    workspace_root: Path
    manifest_path: Path | None
    task_batch_index: Path
    task_batches_dir: Path
    task_batches_dir_rel: str
    task_backlog: Path
    collaboration_state: Path
    slots_db: Path

    def resolve_repo_path(self, relative_path: str) -> Path:
        return self.workspace_root / normalize_relative_path(relative_path)

    def display(self, path: Path) -> str:
        try:
            return path.relative_to(self.workspace_root).as_posix()
        except ValueError:
            return path.as_posix()


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def normalize_relative_path(value: str) -> str:
    raw = str(value).replace("\\", "/").strip()
    if not raw or raw.startswith("/") or re.match(r"^[A-Za-z]:", raw):
        raise ValueError(f"invalid repository-relative path: {value}")
    path = raw.rstrip("/")
    parts = Path(path).parts
    if ".." in parts:
        raise ValueError(f"invalid repository-relative path: {value}")
    return path


def discover_workspace_paths(tool_root: Path, explicit_manifest: str | None = None) -> WorkspacePaths:
    manifest_path = resolve_manifest(tool_root, explicit_manifest)
    if manifest_path is None:
        return root_seed_paths(tool_root)

    payload = load_json(manifest_path)
    generated = payload.get("paths", {}).get("generated", {})
    required = {
        "task_batch_index",
        "task_batches_dir",
        "task_backlog",
        "collaboration_state",
        "slots_db",
    }
    missing = sorted(key for key in required if not generated.get(key))
    if missing:
        raise ValueError(f"{manifest_path.as_posix()} missing generated path keys: {missing}")

    workspace_root = manifest_path.parent
    task_batches_dir_rel = normalize_relative_path(generated["task_batches_dir"])
    return WorkspacePaths(
        workspace_root=workspace_root,
        manifest_path=manifest_path,
        task_batch_index=workspace_root / normalize_relative_path(generated["task_batch_index"]),
        task_batches_dir=workspace_root / task_batches_dir_rel,
        task_batches_dir_rel=task_batches_dir_rel,
        task_backlog=workspace_root / normalize_relative_path(generated["task_backlog"]),
        collaboration_state=workspace_root / normalize_relative_path(generated["collaboration_state"]),
        slots_db=workspace_root / normalize_relative_path(generated["slots_db"]),
    )


def resolve_manifest(tool_root: Path, explicit_manifest: str | None) -> Path | None:
    if explicit_manifest:
        candidate = Path(explicit_manifest).expanduser().resolve()
        if not candidate.is_file():
            raise ValueError(f"workspace manifest not found: {explicit_manifest}")
        return candidate

    candidates = [
        tool_root / "project_workspace.json",
        tool_root.parent / "project_workspace.json",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    return None


def root_seed_paths(tool_root: Path) -> WorkspacePaths:
    generated = tool_root / "generated"
    return WorkspacePaths(
        workspace_root=tool_root,
        manifest_path=None,
        task_batch_index=generated / "task_batch_index.json",
        task_batches_dir=generated / "task_batches",
        task_batches_dir_rel="generated/task_batches",
        task_backlog=generated / "task_backlog.json",
        collaboration_state=generated / "collaboration_state.json",
        slots_db=generated / "slots_db.json",
    )
