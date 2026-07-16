from pathlib import Path
import unittest
from ml.training.fetch_pretrained import SHA256,URL,digest
from ml.training.train import build_model,load_inputs
class TrainingBoundaryTests(unittest.TestCase):
 def test_training_source_excludes_test_rows(self):
  source=Path("ml/training/train.py").read_text();self.assertIn('by_split={"train":[],"validation":[]}',source);self.assertIn('if name=="test":continue',source)
 def test_missing_inputs_fail_closed(self):
  with self.assertRaises(FileNotFoundError):load_inputs(Path("missing"),Path("missing"),Path("missing"),Path("missing"))
 def test_approved_weight_identity_is_pinned(self):
  self.assertEqual("46d2c063b18125884c48937afa4c49e18128869e52e8db96df48bf0a4d7ff697",SHA256);self.assertIn("1824797e7887cbec1990e4adbd6675960a36c589",URL)
 def test_unknown_architecture_fails_closed(self):
  with self.assertRaisesRegex(ValueError,"unsupported architecture"):build_model({"architecture":"unknown"},2)
if __name__=="__main__":unittest.main()
