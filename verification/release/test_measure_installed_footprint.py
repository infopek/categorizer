from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("measure_installed_footprint.py")
SPEC = importlib.util.spec_from_file_location("measure_installed_footprint", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ParseDiskstatsTest(unittest.TestCase):
    def test_extracts_aligned_package_sizes(self) -> None:
        output = """Package Names: [\"other\",\"com.infopek.categorizer\"]
App Sizes: [1,200]
App Data Sizes: [2,30]
Cache Sizes: [3,4]
"""
        self.assertEqual(
            MODULE.parse_diskstats(output, "com.infopek.categorizer"),
            {"code_bytes": 200, "data_bytes": 30, "cache_bytes": 4},
        )

    def test_rejects_missing_package(self) -> None:
        output = """Package Names: [\"other\"]
App Sizes: [1]
App Data Sizes: [2]
Cache Sizes: [3]
"""
        with self.assertRaisesRegex(ValueError, "package is absent"):
            MODULE.parse_diskstats(output, "com.infopek.categorizer")

    def test_parses_du_kib(self) -> None:
        self.assertEqual(MODULE.parse_du_kib("12345\t/data/app/example\n"), 12345)

    def test_rejects_invalid_du_output(self) -> None:
        with self.assertRaisesRegex(ValueError, "unexpected du output"):
            MODULE.parse_du_kib("permission denied")

    def test_rejects_inconsistent_arrays(self) -> None:
        output = """Package Names: [\"other\",\"com.infopek.categorizer\"]
App Sizes: [1]
App Data Sizes: [2,3]
Cache Sizes: [4,5]
"""
        with self.assertRaisesRegex(ValueError, "inconsistent lengths"):
            MODULE.parse_diskstats(output, "com.infopek.categorizer")


if __name__ == "__main__":
    unittest.main()
