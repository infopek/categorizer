import unittest
from ml.evaluation.evaluate import ranked
class ExportPolicyTests(unittest.TestCase):
 def test_tied_rank_policy_is_stable(self):self.assertEqual(ranked([1.0,1.0,0.0]),[0,1,2])
 def test_rank_change_is_detectable(self):self.assertNotEqual(ranked([1.0,0.9]),ranked([0.9,1.0]))
if __name__=="__main__":unittest.main()
