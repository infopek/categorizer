import unittest
from ml.dataset.collect_wikimedia import https_url,license_info,plain,safe_id
class WikimediaCollectionTests(unittest.TestCase):
 def test_only_accepted_license_ids_map(self):
  self.assertEqual("CC-BY-4.0",license_info({"License":{"value":"cc-by-4.0"}})[0]);self.assertIsNone(license_info({"License":{"value":"cc-by-sa-4.0"}}));self.assertIsNone(license_info({"License":{"value":"unknown"}}))
 def test_html_is_removed_from_author(self):self.assertEqual("Jane Doe",plain('<a href="x">Jane Doe</a>'))
 def test_asset_ids_are_stable_and_safe(self):self.assertEqual("commons-42-file-toyota-corolla-jpg",safe_id(42,"File:Toyota Corolla.jpg"))
 def test_protocol_relative_license_url_becomes_https(self):self.assertEqual("https://creativecommons.org/x",https_url("//creativecommons.org/x","fallback"))
if __name__=="__main__":unittest.main()
