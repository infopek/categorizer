from pathlib import Path
import unittest
from ml.training.train import load_inputs
class TrainingBoundaryTests(unittest.TestCase):
 def test_training_source_excludes_test_rows(self):
  source=Path("ml/training/train.py").read_text();self.assertIn('by_split={"train":[],"validation":[]}',source);self.assertIn('if name=="test":continue',source)
 def test_missing_inputs_fail_closed(self):
  with self.assertRaises(FileNotFoundError):load_inputs(Path("missing"),Path("missing"),Path("missing"),Path("missing"))
if __name__=="__main__":unittest.main()
