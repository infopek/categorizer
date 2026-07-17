import unittest
from ml.dataset.collect_wikimedia import candidate_count,category_names,https_url,license_info,plain,relevant_category,safe_id
class WikimediaCollectionTests(unittest.TestCase):
 def test_only_accepted_license_ids_map(self):
  self.assertEqual("CC-BY-4.0",license_info({"License":{"value":"cc-by-4.0"}})[0]);self.assertIsNone(license_info({"License":{"value":"cc-by-sa-4.0"}}));self.assertIsNone(license_info({"License":{"value":"unknown"}}))
 def test_html_is_removed_from_author(self):self.assertEqual("Jane Doe",plain('<a href="x">Jane Doe</a>'))
 def test_asset_ids_are_stable_and_safe(self):self.assertEqual("commons-42-file-toyota-corolla-jpg",safe_id(42,"File:Toyota Corolla.jpg"))
 def test_protocol_relative_license_url_becomes_https(self):self.assertEqual("https://creativecommons.org/x",https_url("//creativecommons.org/x","fallback"))
 def test_rejected_assets_do_not_fill_candidate_target(self):
  assets=[{"class_id":"car-a","review_status":"approved"},{"class_id":"car-a","review_status":"pending"},{"class_id":"car-a","review_status":"rejected"},{"class_id":"car-b","review_status":"approved"}]
  self.assertEqual(2,candidate_count(assets,"car-a"))
 def test_category_names_keep_generation_specific_classes_narrow(self):
  self.assertEqual(["Porsche 992","Porsche 911 (992)"],category_names({"make":"Porsche","model":"911","generation_label":"992"}))
 def test_category_names_expand_slash_separated_model_aliases(self):
  self.assertEqual(["Honda Jazz / Fit","Honda Jazz","Honda Fit"],category_names({"make":"Honda","model":"Jazz / Fit"}))
 def test_discovered_category_requires_generation_for_versioned_class(self):
  klass={"make":"Porsche","model":"911","generation_label":"992"}
  self.assertTrue(relevant_category(klass,"Category:Porsche 911 (992) GT3"));self.assertFalse(relevant_category(klass,"Category:Porsche 911 (991)"))
 def test_discovered_category_accepts_accented_make_and_model(self):
  self.assertTrue(relevant_category({"make":"Skoda","model":"Kamiq"},"Category:Škoda Kamiq"))
if __name__=="__main__":unittest.main()
