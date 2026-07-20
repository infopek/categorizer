#!/usr/bin/env python3
"""Propose reviewable Lepidoptera boxes from a source archive."""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import time
import zipfile
from io import BytesIO
from pathlib import Path

import torch
from PIL import Image, ImageDraw
from torchvision.ops import nms
from transformers import AutoModelForZeroShotObjectDetection, AutoProcessor

MODEL_ID = "IDEA-Research/grounding-dino-tiny"
MODEL_REVISION = "a2bb814dd30d776dcf7e30523b00659f4f141c71"
PROMPT = ["a butterfly", "a moth"]
COLORS = ["#00ff66", "#ffcc00", "#00ccff", "#ff55cc", "#ff6633", "#cc99ff"]


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def identifier(name: str, content: bytes) -> str:
    return hashlib.sha256(name.encode() + b"\0" + content).hexdigest()[:20]


def source_items(
    archive_path: Path | None,
    sample_manifest_path: Path | None,
    sample_root: Path | None,
    limit: int | None,
    class_id: str | None = None,
    excluded_sample_ids: set[str] | None = None,
) -> tuple[dict[str, object], list[tuple[str, bytes, dict[str, object]]]]:
    if archive_path is not None:
        with zipfile.ZipFile(archive_path) as archive:
            names = sorted(
                name
                for name in archive.namelist()
                if name.lower().endswith((".jpg", ".jpeg", ".png"))
            )
            if limit is not None:
                names = names[:limit]
            items = [(name, archive.read(name), {}) for name in names]
        return (
            {"kind": "archive", "filename": archive_path.name, "sha256": digest(archive_path)},
            items,
        )
    if sample_manifest_path is None:
        raise ValueError("one source is required")
    sample = json.loads(sample_manifest_path.read_text(encoding="utf-8"))
    if sample.get("status") != "unreviewed_detection_pilot_sample":
        raise SystemExit("sample manifest has an unexpected status")
    root = sample_root or sample_manifest_path.parent
    assets = sorted(sample.get("assets", []), key=lambda item: (item["class_id"], item["local_path"]))
    if class_id is not None:
        assets = [asset for asset in assets if asset["class_id"] == class_id]
    if excluded_sample_ids:
        assets = [asset for asset in assets if asset["asset_id"] not in excluded_sample_ids]
    if limit is not None:
        assets = assets[:limit]
    items = []
    for asset in assets:
        path = (root / asset["local_path"]).resolve()
        if not path.is_relative_to(root.resolve()) or not path.is_file():
            raise SystemExit(f"sample asset path is invalid: {asset['local_path']}")
        content = path.read_bytes()
        if hashlib.sha256(content).hexdigest() != asset["sha256"] or len(content) != asset["bytes"]:
            raise SystemExit(f"sample asset identity mismatch: {asset['asset_id']}")
        items.append(
            (
                asset["local_path"],
                content,
                {
                    "sample_asset_id": asset["asset_id"],
                    "class_id": asset["class_id"],
                    "figshare_file_id": asset["figshare_file_id"],
                    "archive_name": asset["archive_name"],
                    "archive_member": asset["member"],
                },
            )
        )
    return (
        {
            "kind": "sample_manifest",
            "filename": sample_manifest_path.name,
            "sha256": digest(sample_manifest_path),
            "source": sample["source"],
            "selection": sample["selection"],
        },
        items,
    )


def review_page(annotations: list[dict[str, object]]) -> str:
    cards = []
    for item in annotations:
        asset_id = html.escape(str(item["asset_id"]))
        proposals = item["proposals"]
        summary = " · ".join(
            f'{html.escape(str(box["label"]))} {float(box["score"]):.3f}'
            for box in proposals
        ) or "no proposal"
        proposal_buttons = "".join(
            f'<button data-status="accepted" data-indices="{index}">Use only #{index + 1}</button>'
            for index in range(len(proposals))
        )
        proposal_checks = "".join(
            f'<label><input type="checkbox" data-proposal-index="{index}"> #{index + 1}</label>'
            for index in range(len(proposals))
        )
        cards.append(
            f'''<article data-id="{asset_id}"><img src="rendered/{asset_id}.jpg" loading="lazy">
            <h3>{html.escape(str(item["source_name"]))}</h3><p>{summary}</p>
            <button class="approve" data-status="accepted" data-indices="all">✓ Approve AI boxes (A)</button>{proposal_buttons}
            <div class="combine"><strong>Combine individual boxes:</strong> {proposal_checks}
            <button class="approve-selected" data-status="accepted" data-indices="selected">Approve selected (Enter)</button></div>
            <button data-status="needs_adjustment">Adjust (X)</button><button data-status="multiple_subjects">Missing subjects (M)</button>
            <button data-status="rejected">Wrong boxes (R)</button><button data-status="not_visible">No butterfly/moth (N)</button>
            <output>pending</output></article>'''
        )
    return '''<!doctype html><meta charset="utf-8"><title>Lepidoptera box review</title>
    <style>body{font:20px system-ui;margin:16px}header{position:sticky;top:0;background:white;padding:12px;z-index:2;border-bottom:2px solid #ddd}
    main{display:block}article{max-width:850px;margin:24px auto;border:4px solid #aaa;border-radius:16px;padding:12px;scroll-margin-top:100px}
    img{width:100%;height:620px;object-fit:contain;background:#222}button{font-size:19px;font-weight:650;padding:15px;margin:5px;border-radius:10px;cursor:pointer}.approve{display:block;width:100%;font-size:23px;background:#20a34a;color:white;border:0}.combine{padding:12px;margin:8px 0;background:#eef5ff;border-radius:12px}.combine label{display:inline-block;font-size:22px;padding:10px}.combine input{width:24px;height:24px}.approve-selected{background:#1769aa;color:white;border:0}output{display:block;font-weight:bold;padding:8px}</style>
    <header><button id="export">Export this batch</button> <strong id="progress"></strong> <span>Shortcuts: A all · 1–9 only · Shift+1–9 toggle combination · Enter approve selected · R wrong · N none · M missing · X adjust</span></header><main>''' + "".join(cards) + '''</main>
    <script>const storageKey=`categorizer-detection-review:${location.pathname}`;const decisions=JSON.parse(localStorage.getItem(storageKey)||'{}');const cards=[...document.querySelectorAll('article')];
    function update(){document.querySelector('#progress').textContent=`${Object.keys(decisions).length}/${cards.length} reviewed`;localStorage.setItem(storageKey,JSON.stringify(decisions))}
    function next(c){const i=cards.indexOf(c);const remaining=cards.slice(i+1).find(x=>!decisions[x.dataset.id])||cards.find(x=>!decisions[x.dataset.id]);if(remaining)remaining.scrollIntoView({behavior:'auto',block:'center'})}
    function decide(b){const c=b.closest('article');let indices=[];if(b.dataset.indices==='all'){indices=[...c.querySelectorAll('button[data-indices]')].filter(x=>/^[0-9]+$/.test(x.dataset.indices)).map(x=>Number(x.dataset.indices))}else if(b.dataset.indices==='selected'){indices=[...c.querySelectorAll('input[data-proposal-index]:checked')].map(x=>Number(x.dataset.proposalIndex));if(!indices.length){c.querySelector('output').textContent='Select at least one box';return}}else if(b.dataset.indices!==undefined){indices=[Number(b.dataset.indices)]}decisions[c.dataset.id]={status:b.dataset.status,proposal_indices:indices};c.querySelector('output').textContent=`${b.textContent} ${indices.length?`(#${indices.map(x=>x+1).join(', #')})`:''}`;c.style.borderColor='#16803a';update();next(c)}
    document.querySelectorAll('article button').forEach(b=>b.onclick=()=>decide(b));cards.forEach(c=>{if(decisions[c.dataset.id]){c.style.borderColor='#16803a';c.querySelector('output').textContent='reviewed (saved)'}});
    document.onkeydown=e=>{if(['INPUT','TEXTAREA'].includes(e.target.tagName))return;const c=cards.find(x=>{const r=x.getBoundingClientRect();return r.top<innerHeight*.65&&r.bottom>innerHeight*.35})||cards.find(x=>!decisions[x.dataset.id]);if(!c)return;const key=e.key.toLowerCase();if(e.shiftKey&&/^[1-9]$/.test(key)){const box=c.querySelector(`input[data-proposal-index="${Number(key)-1}"]`);if(box){e.preventDefault();box.checked=!box.checked}return}const map={a:'button.approve',r:'button[data-status="rejected"]',n:'button[data-status="not_visible"]',m:'button[data-status="multiple_subjects"]',x:'button[data-status="needs_adjustment"]',enter:'button.approve-selected'};const selector=/^[1-9]$/.test(key)?`button[data-indices="${Number(key)-1}"]`:map[key];const b=selector&&c.querySelector(selector);if(b){e.preventDefault();decide(b)}};
    document.querySelector('#export').onclick=()=>{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(decisions,null,2)],{type:'application/json'}));a.download=`${location.pathname.split('/').slice(-2,-1)[0]}-decisions.json`;a.click()};update()</script>'''


def main() -> int:
    parser = argparse.ArgumentParser()
    sources = parser.add_mutually_exclusive_group(required=True)
    sources.add_argument("--archive", type=Path)
    sources.add_argument("--sample-manifest", type=Path)
    parser.add_argument("--sample-root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--threshold", type=float, default=0.25)
    parser.add_argument("--text-threshold", type=float, default=0.20)
    parser.add_argument("--nms-iou", type=float, default=0.50)
    parser.add_argument("--maximum-proposals", type=int, default=10)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--class-id")
    parser.add_argument("--exclude-proposals", type=Path)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if not 0 < args.threshold < 1 or not 0 < args.text_threshold < 1 or not 0 < args.nms_iou < 1:
        raise SystemExit("thresholds must be between zero and one")
    if args.maximum_proposals < 1:
        raise SystemExit("maximum proposals must be positive")

    excluded_sample_ids: set[str] = set()
    if args.exclude_proposals:
        excluded = json.loads(args.exclude_proposals.read_text(encoding="utf-8"))
        excluded_sample_ids = {
            str(asset["sample_asset_id"])
            for asset in excluded.get("assets", [])
            if asset.get("sample_asset_id") is not None
        }
    args.output.mkdir(parents=True, exist_ok=True)
    rendered = args.output / "rendered"
    rendered.mkdir(exist_ok=True)
    source, items = source_items(
        args.archive,
        args.sample_manifest,
        args.sample_root,
        args.limit,
        args.class_id,
        excluded_sample_ids,
    )
    processor = AutoProcessor.from_pretrained(MODEL_ID, revision=MODEL_REVISION)
    device = torch.device(args.device)
    model = AutoModelForZeroShotObjectDetection.from_pretrained(
        MODEL_ID,
        revision=MODEL_REVISION,
        use_safetensors=True,
    ).to(device).eval()
    annotations: list[dict[str, object]] = []
    for position, (name, content, provenance) in enumerate(items, 1):
            image = Image.open(BytesIO(content)).convert("RGB")
            original_width, original_height = image.size
            inputs = processor(images=image, text=[PROMPT], return_tensors="pt").to(device)
            started = time.monotonic()
            with torch.inference_mode():
                outputs = model(**inputs)
            elapsed = time.monotonic() - started
            result = processor.post_process_grounded_object_detection(
                outputs,
                inputs.input_ids,
                threshold=args.threshold,
                text_threshold=args.text_threshold,
                target_sizes=[image.size[::-1]],
            )[0]
            kept = nms(result["boxes"], result["scores"], args.nms_iou)[: args.maximum_proposals].tolist()
            boxes = result["boxes"][kept].tolist()
            scores = result["scores"][kept].tolist()
            labels = [result["text_labels"][index] for index in kept]
            proposals = [
                {
                    "box_xyxy": [round(float(value), 3) for value in box],
                    "label": label,
                    "score": round(float(score), 6),
                }
                for box, score, label in zip(boxes, scores, labels, strict=True)
            ]
            asset_id = identifier(name, content)
            drawing = ImageDraw.Draw(image)
            for proposal_index, proposal in enumerate(proposals):
                box = proposal["box_xyxy"]
                color = COLORS[proposal_index % len(COLORS)]
                drawing.rectangle(box, outline=color, width=max(3, min(image.size) // 150))
                drawing.text((box[0] + 4, box[1] + 4), f'#{proposal_index + 1} {proposal["score"]:.2f}', fill=color, stroke_width=2, stroke_fill="black")
            image.thumbnail((1600, 1600))
            image.save(rendered / f"{asset_id}.jpg", quality=88)
            annotations.append(
                {
                    "asset_id": asset_id,
                    "source_name": name,
                    "source_sha256": hashlib.sha256(content).hexdigest(),
                    **provenance,
                    "width": original_width,
                    "height": original_height,
                    "proposals": proposals,
                    "inference_seconds": round(elapsed, 3),
                }
            )
            print(f"PROGRESS {position}/{len(items)} proposals={len(proposals)} seconds={elapsed:.2f}", flush=True)

    manifest = {
        "schema_version": "0.1.0",
        "status": "teacher_proposals_pending_human_review",
        "source": source,
        "teacher": {
            "model_id": MODEL_ID,
            "revision": MODEL_REVISION,
            "license": "Apache-2.0",
            "prompt": PROMPT,
            "box_threshold": args.threshold,
            "text_threshold": args.text_threshold,
            "nms_iou": args.nms_iou,
            "maximum_proposals": args.maximum_proposals,
            "device": str(device),
        },
        "assets": annotations,
    }
    (args.output / "proposals.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (args.output / "review.html").write_text(review_page(annotations), encoding="utf-8")
    print(f"RESULT OK assets={len(annotations)} output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
