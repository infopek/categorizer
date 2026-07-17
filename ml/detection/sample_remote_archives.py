#!/usr/bin/env python3
"""Sample Figshare ZIP members using HTTP ranges instead of full downloads."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import urllib.request
import zipfile
from pathlib import Path

from PIL import Image

ARTICLE_API = "https://api.figshare.com/v2/articles/29135618"
IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png")


class HTTPRangeReader(io.RawIOBase):
    def __init__(self, url: str, size: int, block_size: int = 4 * 1024 * 1024):
        self.url = url
        self.size = size
        self.block_size = block_size
        self.position = 0
        self.cache_start = 0
        self.cache = b""
        self.transferred_bytes = 0
        self.requests = 0

    def readable(self) -> bool:
        return True

    def seekable(self) -> bool:
        return True

    def tell(self) -> int:
        return self.position

    def seek(self, offset: int, whence: int = io.SEEK_SET) -> int:
        if whence == io.SEEK_SET:
            position = offset
        elif whence == io.SEEK_CUR:
            position = self.position + offset
        elif whence == io.SEEK_END:
            position = self.size + offset
        else:
            raise ValueError("invalid seek mode")
        if position < 0:
            raise ValueError("negative seek position")
        self.position = min(position, self.size)
        return self.position

    def read(self, length: int = -1) -> bytes:
        if self.position >= self.size:
            return b""
        if length is None or length < 0:
            length = self.size - self.position
        length = min(length, self.size - self.position)
        if not (
            self.cache_start <= self.position
            and self.position + length <= self.cache_start + len(self.cache)
        ):
            start = self.position
            end = min(self.size, max(start + length, start + self.block_size)) - 1
            request = urllib.request.Request(self.url, headers={"Range": f"bytes={start}-{end}"})
            with urllib.request.urlopen(request, timeout=120) as response:
                if response.status != 206:
                    raise OSError(f"server ignored byte range: HTTP {response.status}")
                self.cache = response.read()
            self.cache_start = start
            self.transferred_bytes += len(self.cache)
            self.requests += 1
        offset = self.position - self.cache_start
        value = self.cache[offset : offset + length]
        self.position += len(value)
        return value


def load_json_url(url: str) -> dict[str, object]:
    with urllib.request.urlopen(url, timeout=120) as response:
        return json.load(response)


def ranked(values: list[str], seed: str) -> list[str]:
    return sorted(values, key=lambda value: hashlib.sha256(f"{seed}\0{value}".encode()).digest())


def safe_name(name: str) -> str:
    path = Path(name)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError(f"unsafe ZIP member: {name}")
    return path.name


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--class-map",
        type=Path,
        default=Path("ml/catalog/lepidoptera-checkpoint-class-map.json"),
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--class-count", type=int, default=12)
    parser.add_argument("--images-per-class", type=int, default=5)
    parser.add_argument("--seed", default="detection-pilot-v1")
    args = parser.parse_args()
    if args.class_count < 1 or args.images_per_class < 1:
        raise SystemExit("sample counts must be positive")

    class_map = json.loads(args.class_map.read_text(encoding="utf-8"))
    classes = class_map["classes"]
    selected_ids = set(
        ranked([item["class_id"] for item in classes], args.seed)[: args.class_count]
    )
    selected_classes = [item for item in classes if item["class_id"] in selected_ids]
    article = load_json_url(ARTICLE_API)
    remote_files = {item["name"]: item for item in article["files"]}
    args.output.mkdir(parents=True, exist_ok=True)
    assets = []
    archive_records = []
    for class_position, item in enumerate(selected_classes, 1):
        archive_name = f'{item["source_folder"]}.ZIP'
        remote = remote_files.get(archive_name)
        if remote is None:
            raise SystemExit(f"Figshare article is missing {archive_name}")
        reader = HTTPRangeReader(remote["download_url"], int(remote["size"]))
        with zipfile.ZipFile(reader) as archive:
            members = sorted(
                entry.filename
                for entry in archive.infolist()
                if not entry.is_dir() and entry.filename.lower().endswith(IMAGE_SUFFIXES)
            )
            chosen = ranked(members, f'{args.seed}:{item["class_id"]}')[: args.images_per_class]
            if len(chosen) < args.images_per_class:
                raise SystemExit(f"{archive_name} has too few images")
            class_directory = args.output / item["class_id"]
            class_directory.mkdir(exist_ok=True)
            for member in chosen:
                content = archive.read(member)
                with Image.open(io.BytesIO(content)) as image:
                    image.verify()
                    width, height = image.size
                filename = f"{hashlib.sha256(member.encode()).hexdigest()[:12]}-{safe_name(member)}"
                destination = class_directory / filename
                destination.write_bytes(content)
                assets.append(
                    {
                        "asset_id": hashlib.sha256(
                            f'{remote["id"]}\0{member}'.encode()
                        ).hexdigest()[:20],
                        "class_id": item["class_id"],
                        "source_folder": item["source_folder"],
                        "figshare_file_id": remote["id"],
                        "archive_name": archive_name,
                        "member": member,
                        "local_path": destination.relative_to(args.output).as_posix(),
                        "sha256": hashlib.sha256(content).hexdigest(),
                        "bytes": len(content),
                        "width": width,
                        "height": height,
                    }
                )
        archive_records.append(
            {
                "class_id": item["class_id"],
                "figshare_file_id": remote["id"],
                "name": archive_name,
                "source_bytes": remote["size"],
                "md5": remote["supplied_md5"],
                "range_requests": reader.requests,
                "transferred_bytes": reader.transferred_bytes,
            }
        )
        print(
            f"PROGRESS {class_position}/{len(selected_classes)} class={item['class_id']} "
            f"images={args.images_per_class} transferred={reader.transferred_bytes}",
            flush=True,
        )

    manifest = {
        "schema_version": "0.1.0",
        "status": "unreviewed_detection_pilot_sample",
        "source": {
            "figshare_article_id": article["id"],
            "doi": article["doi"],
            "license": article["license"]["name"],
            "api_url": ARTICLE_API,
        },
        "selection": {
            "seed": args.seed,
            "class_count": len(selected_classes),
            "images_per_class": args.images_per_class,
            "method": "ascending SHA-256 rank of class IDs and ZIP member names",
        },
        "archives": archive_records,
        "assets": assets,
    }
    (args.output / "sample-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"RESULT OK classes={len(selected_classes)} assets={len(assets)} "
        f"source_bytes={sum(int(x['source_bytes']) for x in archive_records)} "
        f"transferred_bytes={sum(int(x['transferred_bytes']) for x in archive_records)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
