#!/usr/bin/env python3
"""Fetch reproducible English vernacular-name suggestions from the GBIF backbone."""
from __future__ import annotations

import argparse
import json
import urllib.parse
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def request(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.load(response)


def fetch(item: dict) -> dict:
    name = item["scientific_name"]
    match_url = "https://api.gbif.org/v1/species/match?" + urllib.parse.urlencode({"name": name})
    match = request(match_url)
    key = match.get("usageKey")
    if key is None or match.get("matchType") == "NONE":
        return {"class_id": item["class_id"], "scientific_name": name, "common_name": None, "gbif_match_url": match_url}
    names_url = f"https://api.gbif.org/v1/species/{key}/vernacularNames?limit=100"
    names = [
        value["vernacularName"].strip()
        for value in request(names_url).get("results", [])
        if value.get("language") == "eng" and value.get("vernacularName", "").strip()
    ]
    counts = Counter(name.casefold() for name in names)
    selected_key = min(counts, key=lambda value: (-counts[value], value)) if counts else None
    selected = next((name for name in names if name.casefold() == selected_key), None)
    return {
        "class_id": item["class_id"],
        "scientific_name": name,
        "common_name": selected,
        "gbif_usage_key": key,
        "gbif_match_url": match_url,
        "gbif_vernacular_names_url": names_url,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--class-map", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = json.loads(args.class_map.read_text(encoding="utf-8"))
    with ThreadPoolExecutor(max_workers=12) as executor:
        entries = list(executor.map(fetch, source["classes"]))
    output = {
        "schema_version": "1.0.0",
        "status": "generated_vernacular_name_suggestions",
        "source": "GBIF Species API",
        "source_documentation": "https://techdocs.gbif.org/en/openapi/v1/species",
        "selection": "most frequent case-insensitive English record; alphabetical tie-break",
        "class_count": len(entries),
        "common_name_count": sum(item["common_name"] is not None for item in entries),
        "classes": entries,
    }
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"RESULT OK classes={len(entries)} common_names={output['common_name_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
