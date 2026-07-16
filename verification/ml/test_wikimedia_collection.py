import unittest
from ml.dataset.collect_wikimedia import candidate_count,https_url,license_info,plain,safe_id
class WikimediaCollectionTests(unittest.TestCase):
 def test_only_accepted_license_ids_map(self):
  self.assertEqual("CC-BY-4.0",license_info({"License":{"value":"cc-by-4.0"}})[0]);self.assertIsNone(license_info({"License":{"value":"cc-by-sa-4.0"}}));self.assertIsNone(license_info({"License":{"value":"unknown"}}))
 def test_html_is_removed_from_author(self):self.assertEqual("Jane Doe",plain('<a href="x">Jane Doe</a>'))
 def test_asset_ids_are_stable_and_safe(self):self.assertEqual("commons-42-file-toyota-corolla-jpg",safe_id(42,"File:Toyota Corolla.jpg"))
 def test_protocol_relative_license_url_becomes_https(self):self.assertEqual("https://creativecommons.org/x",https_url("//creativecommons.org/x","fallback"))
 def test_rejected_assets_do_not_fill_candidate_target(self):
  assets=[{"class_id":"car-a","review_status":"approved"},{"class_id":"car-a","review_status":"pending"},{"class_id":"car-a","review_status":"rejected"},{"class_id":"car-b","review_status":"approved"}]
  self.assertEqual(2,candidate_count(assets,"car-a"))
if __name__=="__main__":unittest.main()
