import math, unittest
from ml.evaluation.evaluate import calculate, ranked
class EvaluationMetricTests(unittest.TestCase):
 def test_known_rankings_ties_and_fewer_than_five_classes(self):
  classes=["a","b","c"]
  records=[{"asset_id":"a1","target_index":0,"logits":[2,1,0]},{"asset_id":"b1","target_index":1,"logits":[1,1,0]},{"asset_id":"c1","target_index":2,"logits":[0,1,2]}]
  result=calculate(records,classes);self.assertEqual(ranked([1,1,0]),[0,1,2]);self.assertAlmostEqual(result["aggregate"]["top_one_accuracy"],2/3);self.assertEqual(result["aggregate"]["top_five_accuracy"],1);self.assertEqual(result["confusion_matrix"]["counts"],[[1,0,0],[1,0,0],[0,0,1]])
 def test_missing_class_support_fails(self):
  with self.assertRaisesRegex(ValueError,"insufficient") :calculate([{"asset_id":"a","target_index":0,"logits":[1,0]}],["a","b"])
 def test_non_finite_logits_fail(self):
  with self.assertRaisesRegex(ValueError,"NaN/Inf"):ranked([math.nan,0])
if __name__=="__main__":unittest.main()
