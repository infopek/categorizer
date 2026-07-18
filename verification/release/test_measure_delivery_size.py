from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


PATH = Path(__file__).with_name("measure_delivery_size.py")
SPEC = importlib.util.spec_from_file_location("measure_delivery_size", PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ParseTotalSizeTest(unittest.TestCase):
    def test_parses_bundletool_csv(self) -> None:
        self.assertEqual(MODULE.parse_total_size("MIN,MAX\n123,456\n"), (123, 456))

    def test_rejects_invalid_values(self) -> None:
        with self.assertRaisesRegex(ValueError, "invalid bundletool size values"):
            MODULE.parse_total_size("MIN,MAX\nsmall,large\n")

    def test_rejects_unexpected_shape(self) -> None:
        with self.assertRaisesRegex(ValueError, "unexpected bundletool size output"):
            MODULE.parse_total_size("SIZE\n123\n")


if __name__ == "__main__":
    unittest.main()
