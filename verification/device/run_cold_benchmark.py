from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from math import ceil
from statistics import mean, median


RESULT = re.compile(
    r"BENCHMARK_COLD_RESULT fixture_ms=(?P<fixture_ms>[0-9.]+) "
    r"copy_ms=(?P<copy_ms>[0-9.]+) session_ms=(?P<session_ms>[0-9.]+) "
    r"inference_ms=(?P<inference_ms>[0-9.]+) total_ms=(?P<total_ms>[0-9.]+)"
)


def parse_result(logcat: str) -> dict[str, float]:
    matches = list(RESULT.finditer(logcat))
    if len(matches) != 1:
        raise ValueError(f"expected one cold benchmark result, found {len(matches)}")
    return {key: float(value) for key, value in matches[0].groupdict().items()}


def adb(executable: str, *args: str) -> str:
    return subprocess.run(
        [executable, *args], check=True, capture_output=True, text=True
    ).stdout


def summarize(values: list[float]) -> dict[str, float]:
    ordered = sorted(values)
    percentile = lambda quantile: ordered[ceil(quantile * len(ordered)) - 1]
    return {
        "median": median(ordered),
        "p90": percentile(0.90),
        "p95": percentile(0.95),
        "minimum": ordered[0],
        "maximum": ordered[-1],
        "mean": mean(ordered),
    }


def run_trials(executable: str, count: int) -> dict[str, object]:
    samples: list[dict[str, float]] = []
    for trial in range(1, count + 1):
        adb(executable, "logcat", "-c")
        adb(executable, "shell", "am", "force-stop", "com.infopek.categorizer")
        output = adb(
            executable,
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            "categorizer.inference.MaxVitDeviceBenchmarkTest#benchmarkColdSessionAndInference",
            "com.infopek.categorizer.test/androidx.test.runner.AndroidJUnitRunner",
        )
        if "OK (1 test)" not in output:
            raise ValueError(f"cold trial {trial} failed: {output.strip()}")
        samples.append(parse_result(adb(executable, "logcat", "-d", "-v", "brief")))
        print(f"COLD_PROGRESS {trial}/{count}", file=sys.stderr)
    metrics = samples[0].keys()
    return {
        "trial_count": count,
        "samples_ms": samples,
        "summary_ms": {metric: summarize([sample[metric] for sample in samples]) for metric in metrics},
        "failures": [],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run independent Android cold inference trials.")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--trials", type=int, default=10)
    args = parser.parse_args()
    if args.trials < 1:
        parser.error("--trials must be positive")
    try:
        result = run_trials(args.adb, args.trials)
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
