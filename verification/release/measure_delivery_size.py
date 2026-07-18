from __future__ import annotations

import argparse
import csv
import io
import json
import subprocess
import sys
import tempfile
from pathlib import Path


BUDGETS = Path(__file__).with_name("resource-budgets.json")


def parse_total_size(output: str) -> tuple[int, int]:
    rows = list(csv.DictReader(io.StringIO(output.strip())))
    if len(rows) != 1 or set(rows[0]) != {"MIN", "MAX"}:
        raise ValueError(f"unexpected bundletool size output: {output!r}")
    try:
        return int(rows[0]["MIN"]), int(rows[0]["MAX"])
    except (TypeError, ValueError) as error:
        raise ValueError(f"invalid bundletool size values: {output!r}") from error


def bundletool(jar: Path, *args: str) -> str:
    return subprocess.run(
        ["java", "-jar", str(jar), *args],
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def measure(jar: Path, aab: Path, adb: Path) -> dict[str, int | float | str]:
    with tempfile.TemporaryDirectory(prefix="categorizer-delivery-") as directory:
        temporary = Path(directory)
        device_spec = temporary / "device-spec.json"
        apks = temporary / "candidate.apks"
        bundletool(jar, "get-device-spec", f"--adb={adb}", f"--output={device_spec}")
        bundletool(
            jar,
            "build-apks",
            f"--bundle={aab}",
            f"--output={apks}",
            f"--device-spec={device_spec}",
        )
        minimum, maximum = parse_total_size(
            bundletool(jar, "get-size", "total", f"--apks={apks}", f"--device-spec={device_spec}"),
        )

    budget = json.loads(BUDGETS.read_text(encoding="utf-8"))["budgets"]["play_compressed_download"]
    target_bytes = int(budget["optimization_target_maximum"] * 1_000_000)
    hard_gate_bytes = int(budget["hard_gate_maximum"] * 1_000_000)
    return {
        "minimum_download_bytes": minimum,
        "maximum_download_bytes": maximum,
        "maximum_download_mb": round(maximum / 1_000_000, 2),
        "optimization_target_mb": budget["optimization_target_maximum"],
        "optimization_target_result": "pass" if maximum <= target_bytes else "miss",
        "hard_gate_mb": budget["hard_gate_maximum"],
        "hard_gate_result": "pass" if maximum <= hard_gate_bytes else "fail",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Estimate device-specific compressed AAB delivery size.")
    parser.add_argument("--bundletool", required=True, type=Path)
    parser.add_argument("--aab", required=True, type=Path)
    parser.add_argument("--adb", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = measure(args.bundletool, args.aab, args.adb)
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 1 if result["hard_gate_result"] == "fail" else 0


if __name__ == "__main__":
    sys.exit(main())
