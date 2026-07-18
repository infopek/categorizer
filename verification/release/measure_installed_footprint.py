from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any


BUDGETS = Path(__file__).with_name("resource-budgets.json")


def parse_diskstats(text: str, package: str) -> dict[str, int]:
    fields = {
        "package_names": "Package Names",
        "code_bytes": "App Sizes",
        "data_bytes": "App Data Sizes",
        "cache_bytes": "Cache Sizes",
    }
    values: dict[str, list[Any]] = {}
    for key, label in fields.items():
        line = next((line for line in text.splitlines() if line.startswith(f"{label}: ")), None)
        if line is None:
            raise ValueError(f"dumpsys diskstats is missing {label}")
        values[key] = json.loads(line.split(": ", 1)[1])

    package_names = values.pop("package_names")
    if package not in package_names:
        raise ValueError(f"package is absent from dumpsys diskstats: {package}")
    index = package_names.index(package)
    if any(index >= len(items) for items in values.values()):
        raise ValueError("dumpsys diskstats package arrays have inconsistent lengths")
    return {key: int(items[index]) for key, items in values.items()}


def parse_du_kib(text: str) -> int:
    try:
        return int(text.strip().split(maxsplit=1)[0])
    except (IndexError, ValueError) as error:
        raise ValueError(f"unexpected du output: {text!r}") from error


def run(adb: str, *args: str) -> str:
    return subprocess.run(
        [adb, *args],
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def measure_debuggable_package(adb: str, package: str) -> dict[str, int]:
    package_paths = run(adb, "shell", "pm", "path", package).strip().splitlines()
    if not package_paths:
        raise ValueError(f"package is not installed: {package}")
    base_path = next((line.removeprefix("package:") for line in package_paths if line.endswith("/base.apk")), None)
    if base_path is None:
        raise ValueError(f"package has no base APK: {package}")
    code_directory = base_path.rsplit("/", 1)[0]
    code_bytes = parse_du_kib(run(adb, "shell", "du", "-sk", code_directory)) * 1024
    private_bytes = parse_du_kib(run(adb, "shell", "run-as", package, "du", "-sk", ".")) * 1024
    cache_bytes = parse_du_kib(run(adb, "shell", "run-as", package, "du", "-sk", "cache")) * 1024
    return {
        "code_bytes": code_bytes,
        "data_bytes": max(0, private_bytes - cache_bytes),
        "cache_bytes": cache_bytes,
    }


def measure(adb: str, package: str) -> dict[str, Any]:
    diskstats = run(adb, "shell", "dumpsys", "diskstats")
    try:
        sizes = parse_diskstats(diskstats, package)
        source = "adb shell dumpsys diskstats"
    except ValueError as diskstats_error:
        try:
            sizes = measure_debuggable_package(adb, package)
            source = "adb shell du plus run-as du (debuggable build fallback)"
        except (subprocess.CalledProcessError, ValueError) as fallback_error:
            raise ValueError(f"{diskstats_error}; fallback failed: {fallback_error}") from fallback_error
    total_bytes = sum(sizes.values())
    budgets = json.loads(BUDGETS.read_text(encoding="utf-8"))["budgets"]["installed_footprint"]
    mib = 1_048_576
    target_bytes = int(budgets["optimization_target_maximum"] * mib)
    hard_gate_bytes = int(budgets["hard_gate_maximum"] * mib)
    return {
        "package": package,
        **sizes,
        "total_bytes": total_bytes,
        "total_mib": round(total_bytes / mib, 2),
        "optimization_target_mib": budgets["optimization_target_maximum"],
        "optimization_target_result": "pass" if total_bytes <= target_bytes else "miss",
        "hard_gate_mib": budgets["hard_gate_maximum"],
        "hard_gate_result": "pass" if total_bytes <= hard_gate_bytes else "fail",
        "measurement_source": source,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Measure an installed Android package footprint.")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default="com.infopek.categorizer")
    args = parser.parse_args()
    try:
        result = measure(args.adb, args.package)
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 1 if result["hard_gate_result"] == "fail" else 0


if __name__ == "__main__":
    sys.exit(main())
