from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


PATH = Path(__file__).with_name("run_cold_benchmark.py")
SPEC = importlib.util.spec_from_file_location("run_cold_benchmark", PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ParseResultTest(unittest.TestCase):
    def test_parses_one_result(self) -> None:
        result = MODULE.parse_result(
            "I/System.out: BENCHMARK_COLD_RESULT fixture_ms=1.2 copy_ms=3.4 "
            "session_ms=5.6 inference_ms=7.8 total_ms=18.0"
        )
        self.assertEqual(result["session_ms"], 5.6)
        self.assertEqual(result["total_ms"], 18.0)

    def test_rejects_missing_result(self) -> None:
        with self.assertRaisesRegex(ValueError, "found 0"):
            MODULE.parse_result("unrelated log")

    def test_rejects_multiple_results(self) -> None:
        line = (
            "BENCHMARK_COLD_RESULT fixture_ms=1 copy_ms=2 session_ms=3 "
            "inference_ms=4 total_ms=10"
        )
        with self.assertRaisesRegex(ValueError, "found 2"):
            MODULE.parse_result(f"{line}\n{line}")

    def test_summarizes_with_nearest_rank_percentiles(self) -> None:
        summary = MODULE.summarize([float(value) for value in range(1, 11)])
        self.assertEqual(summary["median"], 5.5)
        self.assertEqual(summary["p90"], 9.0)
        self.assertEqual(summary["p95"], 10.0)
        self.assertEqual(summary["minimum"], 1.0)
        self.assertEqual(summary["maximum"], 10.0)


if __name__ == "__main__":
    unittest.main()
