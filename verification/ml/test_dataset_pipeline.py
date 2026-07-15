from __future__ import annotations
import hashlib, random, tempfile, unittest
from pathlib import Path
from PIL import Image
from ml.dataset.pipeline import split, validate

CATALOG=Path("ml/catalog/mvp-car-catalog.json")

class DatasetPipelineTest(unittest.TestCase):
 def fixture(self,root:Path):
  assets=[]
  for class_id,offset in (("toyota-corolla",0),("honda-civic",40)):
   for index in range(6):
    name=f"{class_id}-{index}.png";image=Image.new("RGB",(32,32))
    rng=random.Random(offset+index);image.putdata([(rng.randrange(256),rng.randrange(256),rng.randrange(256)) for _ in range(1024)]);image.save(root/name)
    assets.append({"asset_id":name[:-4],"class_id":class_id,"local_path":name,"source":"local_fixture","original_source_url":"https://example.invalid/source/"+name,"description_url":"https://example.invalid/page/"+name,"author":"Fixture author","license_id":"LicenseRef-LocalFixture","license_url":"https://example.invalid/license","attribution":"Synthetic test fixture","retrieved_at":"2026-07-15T00:00:00Z","sha256":hashlib.sha256((root/name).read_bytes()).hexdigest(),"label_reviewer":"fixture-reviewer","review_status":"approved","duplicate_group":"dup-"+name,"split_group":"vehicle-"+name})
  return {"schema_version":"1.0.0","catalog_id":"cars-mvp-150-v1","assets":assets}
 def test_same_seed_reproduces_split_and_reports_every_class(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);usable,report=validate(self.fixture(root),root,CATALOG)
   self.assertEqual(split(usable,"seed"),split(usable,"seed"));self.assertEqual(report["per_class"],{"honda-civic":6,"toyota-corolla":6})
   assigned=split(usable,"seed");self.assertEqual({"train","validation","test"},{assigned[(x["class_id"],x["split_group"])] for x,_ in usable if x["class_id"]=="toyota-corolla"})
 def test_hash_license_catalog_and_near_duplicate_leakage_fail_closed(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);manifest=self.fixture(root);manifest["assets"][1]["local_path"]=manifest["assets"][0]["local_path"];manifest["assets"][1]["sha256"]=manifest["assets"][0]["sha256"]
   with self.assertRaisesRegex(ValueError,"duplicate"):validate(manifest,root,CATALOG)
   manifest=self.fixture(root);manifest["assets"][0]["license_id"]="Unknown"
   with self.assertRaisesRegex(ValueError,"unapproved"):validate(manifest,root,CATALOG)
 def test_exclusions_are_reported_not_silently_dropped(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);manifest=self.fixture(root);manifest["assets"][0]["review_status"]="rejected";manifest["assets"][0]["exclusion_reason"]="ambiguous_generation";usable,report=validate(manifest,root,CATALOG)
   self.assertEqual(11,len(usable));self.assertEqual({"ambiguous_generation":1},report["exclusion_reasons"])

if __name__=="__main__":unittest.main()
