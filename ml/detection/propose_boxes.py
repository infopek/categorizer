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
            f'<button data-status="accepted" data-indices="{index}">Use #{index + 1}</button>'
            for index in range(len(proposals))
        )
        cards.append(
            f'''<article data-id="{asset_id}"><img src="rendered/{asset_id}.jpg" loading="lazy">
            <h3>{html.escape(str(item["source_name"]))}</h3><p>{summary}</p>
            {proposal_buttons}<button data-status="accepted" data-indices="all">Use all</button>
            <button data-status="needs_adjustment">Adjust</button><button data-status="multiple_subjects">Multiple subjects</button>
            <button data-status="rejected">Reject proposals</button><button data-status="not_visible">No visible subject</button>
            <output>pending</output></article>'''
        )
    return '''<!doctype html><meta charset="utf-8"><title>Lepidoptera box review</title>
    <style>body{font:18px system-ui;margin:16px}header{position:sticky;top:0;background:white;padding:12px;z-index:2}
    main{display:grid;grid-template-columns:repeat(auto-fill,minmax(360px,1fr));gap:14px}article{border:2px solid #aaa;border-radius:12px;padding:10px}
    img{width:100%;height:300px;object-fit:contain;background:#222}button{font-size:17px;padding:12px;margin:4px}output{display:block;font-weight:bold;padding:6px}</style>
    <header><button id="export">Export decisions.json</button> <span id="progress"></span></header><main>''' + "".join(cards) + '''</main>
    <script>const decisions={};const cards=[...document.querySelectorAll('article')];function update(){document.querySelector('#progress').textContent=`${Object.keys(decisions).length}/${cards.length} reviewed`}
    document.querySelectorAll('article button').forEach(b=>b.onclick=()=>{const c=b.closest('article');let indices=[];if(b.dataset.indices==='all'){indices=[...c.querySelectorAll('button[data-indices]')].filter(x=>x.dataset.indices!=='all').map(x=>Number(x.dataset.indices))}else if(b.dataset.indices!==undefined){indices=[Number(b.dataset.indices)]}decisions[c.dataset.id]={status:b.dataset.status,proposal_indices:indices};c.querySelector('output').textContent=b.textContent;c.style.borderColor='#16803a';update()});
    document.querySelector('#export').onclick=()=>{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(decisions,null,2)],{type:'application/json'}));a.download='detection-decisions.json';a.click()};update()</script>'''


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--threshold", type=float, default=0.25)
    parser.add_argument("--text-threshold", type=float, default=0.20)
    parser.add_argument("--nms-iou", type=float, default=0.50)
    parser.add_argument("--maximum-proposals", type=int, default=10)
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    if not 0 < args.threshold < 1 or not 0 < args.text_threshold < 1 or not 0 < args.nms_iou < 1:
        raise SystemExit("thresholds must be between zero and one")
    if args.maximum_proposals < 1:
        raise SystemExit("maximum proposals must be positive")

    args.output.mkdir(parents=True, exist_ok=True)
    rendered = args.output / "rendered"
    rendered.mkdir(exist_ok=True)
    processor = AutoProcessor.from_pretrained(MODEL_ID, revision=MODEL_REVISION)
    model = AutoModelForZeroShotObjectDetection.from_pretrained(
        MODEL_ID,
        revision=MODEL_REVISION,
        use_safetensors=True,
    ).eval()
    annotations: list[dict[str, object]] = []
    with zipfile.ZipFile(args.archive) as archive:
        names = sorted(
            name
            for name in archive.namelist()
            if name.lower().endswith((".jpg", ".jpeg", ".png"))
        )
        if args.limit is not None:
            names = names[: args.limit]
        for position, name in enumerate(names, 1):
            content = archive.read(name)
            image = Image.open(BytesIO(content)).convert("RGB")
            original_width, original_height = image.size
            inputs = processor(images=image, text=[PROMPT], return_tensors="pt")
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
                    "width": original_width,
                    "height": original_height,
                    "proposals": proposals,
                    "inference_seconds": round(elapsed, 3),
                }
            )
            print(f"PROGRESS {position}/{len(names)} proposals={len(proposals)} seconds={elapsed:.2f}", flush=True)

    manifest = {
        "schema_version": "0.1.0",
        "status": "teacher_proposals_pending_human_review",
        "archive": {"filename": args.archive.name, "sha256": digest(args.archive)},
        "teacher": {
            "model_id": MODEL_ID,
            "revision": MODEL_REVISION,
            "license": "Apache-2.0",
            "prompt": PROMPT,
            "box_threshold": args.threshold,
            "text_threshold": args.text_threshold,
            "nms_iou": args.nms_iou,
            "maximum_proposals": args.maximum_proposals,
        },
        "assets": annotations,
    }
    (args.output / "proposals.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (args.output / "review.html").write_text(review_page(annotations), encoding="utf-8")
    print(f"RESULT OK assets={len(annotations)} output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
