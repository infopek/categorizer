from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
COLLECT_SPEC = importlib.util.spec_from_file_location(
    "collect_commons_evaluation", ROOT / "ml/detection/collect_commons_evaluation.py"
)
assert COLLECT_SPEC and COLLECT_SPEC.loader
COLLECT = importlib.util.module_from_spec(COLLECT_SPEC)
COLLECT_SPEC.loader.exec_module(COLLECT)


class DetectionCampaignTest(unittest.TestCase):
    def test_selection_slices_cover_campaign_priorities(self) -> None:
        self.assertEqual(COLLECT.selection_slice("positive", "small butterfly meadow"), "small")
        self.assertEqual(COLLECT.selection_slice("positive", "multiple butterflies"), "multiple")
        self.assertEqual(COLLECT.selection_slice("positive", "moth camouflage"), "occluded")
        self.assertEqual(COLLECT.selection_slice("positive", "butterfly flower"), "ordinary")
        self.assertEqual(COLLECT.selection_slice("hard_negative", "bee flower"), "hard_negative")

    def test_batching_is_complete_and_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = root / "sample.json"
            manifest.write_text(
                json.dumps(
                    {
                        "schema_version": "0.1.0",
                        "status": "unreviewed_detection_pilot_sample",
                        "source": {},
                        "selection": {},
                        "assets": [{"asset_id": f"asset-{index}"} for index in range(5)],
                    }
                ),
                encoding="utf-8",
            )
            output = root / "batches"
            command = [
                "python3",
                str(ROOT / "ml/detection/batch_sample_manifest.py"),
                "--manifest",
                str(manifest),
                "--output",
                str(output),
                "--batch-size",
                "2",
            ]
            subprocess.run(command, check=True, capture_output=True)
            first = (output / "index.json").read_bytes()
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual(first, (output / "index.json").read_bytes())
            index = json.loads(first)
            self.assertEqual(index["asset_count"], 5)
            self.assertEqual([item["asset_count"] for item in index["batches"]], [2, 2, 1])


if __name__ == "__main__":
    unittest.main()
