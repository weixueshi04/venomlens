from copy import deepcopy
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from PIL import Image

from inference.cards import SAFETY, build_card, export_bundle
from inference.settings import ROOT


ENTRY = {
    "speciesId": "test_snake", "commonName": "测试蛇", "scientificName": "Testus serpentis",
    "verificationStatus": "verified", "riskStatus": "unknown",
    "comparisonProfile": {"hook": "观察已有照片", "layChecklist": ["比对斑纹"],
                          "layLookAlikes": [], "reviewStatus": "pending_review", "doNot": ["草稿内容"]},
    "referenceImages": [{"file": "data/card_images/test_snake/01.jpg", "role": "参考图",
                         "rights": "团队自有", "source": "团队声明", "sourceStatus": "team_declared",
                         "reviewStatus": "pending_review"}],
    "externalRefs": [],
}
APPROVAL = {"reviewStatus": "verified", "reviewedBy": "测试审核人", "reviewedAt": "2026-09-23"}


class SpeciesCardTests(unittest.TestCase):
    def test_verified_name_does_not_release_unreviewed_materials(self):
        result = build_card(ENTRY)
        self.assertEqual(result["commonName"], "测试蛇")
        self.assertIsNone(result["hook"])
        self.assertEqual(result["images"], [])
        self.assertEqual(result["hiddenImageCount"], 1)
        self.assertEqual(result["contentStatus"], "pending_review")

    def test_preview_marks_drafts_but_does_not_upgrade_status(self):
        result = build_card(ENTRY, preview=True)
        self.assertTrue(result["previewOnly"])
        self.assertIn("演示草稿", result["notice"])
        self.assertEqual(result["hook"], "观察已有照片")
        self.assertEqual(result["images"][0]["reviewStatus"], "pending_review")
        self.assertEqual(result["riskStatus"], "unknown")
        self.assertNotIn("草稿内容", json.dumps(result, ensure_ascii=False))

    def test_independent_approvals_control_profile_and_images(self):
        entry = deepcopy(ENTRY)
        entry["comparisonProfile"].update(APPROVAL)
        result = build_card(entry)
        self.assertEqual(result["hook"], "观察已有照片")
        self.assertEqual(result["images"], [])
        entry["referenceImages"][0].update(APPROVAL)
        self.assertEqual(len(build_card(entry)["images"]), 1)

    def test_missing_reviewer_or_date_does_not_approve(self):
        for missing in ("reviewedBy", "reviewedAt"):
            entry = deepcopy(ENTRY)
            approval = {k: v for k, v in APPROVAL.items() if k != missing}
            entry["comparisonProfile"].update(approval)
            entry["referenceImages"][0].update(approval)
            with self.subTest(missing=missing):
                self.assertIsNone(build_card(entry)["hook"])
                self.assertEqual(build_card(entry)["images"], [])

    def test_unverified_name_remains_hidden_even_with_approved_materials(self):
        entry = deepcopy(ENTRY)
        entry["verificationStatus"] = "pending_review"
        entry["comparisonProfile"].update(APPROVAL)
        entry["referenceImages"][0].update(APPROVAL)
        result = build_card(entry)
        self.assertEqual(result["commonName"], "物种名称待核验")
        self.assertEqual(result["contentStatus"], "verified")
        self.assertIsNone(result["hook"])
        self.assertEqual(result["images"], [])

    def test_unresolved_provenance_is_hidden_even_in_preview(self):
        entry = deepcopy(ENTRY)
        entry["referenceImages"][0]["sourceStatus"] = "pending_review"
        for preview in (False, True):
            self.assertEqual(build_card(entry, preview=preview)["images"], [])

    def test_missing_rights_or_source_is_hidden(self):
        for field in ("rights", "source"):
            entry = deepcopy(ENTRY)
            entry["referenceImages"][0][field] = ""
            self.assertEqual(build_card(entry, preview=True)["images"], [])

    def test_unsafe_external_links_are_not_exported(self):
        entry = deepcopy(ENTRY)
        entry["externalRefs"] = [{"name": "错误链接", "url": url} for url in (
            "javascript:alert(1)", "file:///private", "http://example.invalid", "https://user:password@example.invalid"
        )]
        self.assertEqual(build_card(entry, preview=True)["externalRefs"], [])

    def test_empty_card_keeps_safety(self):
        entry = deepcopy(ENTRY)
        entry["comparisonProfile"] = None
        entry["referenceImages"] = []
        result = build_card(entry, preview=True)
        self.assertEqual(result["contentStatus"], "unavailable")
        self.assertEqual(result["safety"], SAFETY)
        self.assertEqual(result["riskStatus"], "unknown")

    def test_current_catalog_has_independent_review_states(self):
        entries = json.loads((ROOT / "data/species.json").read_text(encoding="utf-8"))
        for entry in entries:
            if entry.get("comparisonProfile"):
                self.assertEqual(entry["comparisonProfile"]["reviewStatus"], "pending_review")
            for image in entry.get("referenceImages", []):
                self.assertEqual(image["reviewStatus"], "pending_review")
                path = ROOT / image["file"]
                self.assertTrue(path.is_relative_to(ROOT / "data/card_images"))
                if not path.exists():
                    self.assertEqual(image["sourceStatus"], "pending_review")
                    continue
                with Image.open(path) as cleaned:
                    self.assertFalse(cleaned.getexif())
                    self.assertNotIn("comment", cleaned.info)
                    self.assertLessEqual(max(cleaned.size), 1280)
            self.assertEqual(build_card(entry)["images"], [])
            self.assertEqual(build_card(entry)["safety"], SAFETY)

    def test_export_has_only_sanitized_allowlisted_images(self):
        with TemporaryDirectory() as directory, patch("socket.create_connection", side_effect=AssertionError("禁止网络")):
            output = Path(directory) / "demo"
            report = export_bundle(output)
            self.assertEqual(report["species"], 6)
            self.assertEqual(report["images"], 2)
            self.assertEqual(len(list(output.rglob("*.jpg"))), 2)
            self.assertFalse((output / "data/reference_images").exists())
            self.assertFalse((output / ".runtime").exists())
            bundle = json.loads((output / "species-cards.json").read_text(encoding="utf-8"))
            self.assertFalse(bundle["liveCallsEnabled"])
            self.assertEqual(bundle["mode"], "mock")
            self.assertEqual(len(list((output / "fixtures").glob("*.json"))), 7)
            self.assertTrue((output / "mobile" / "index.html").is_file())
            self.assertEqual(len(list((output / "mobile" / "fixtures").glob("*.json"))), 7)
            self.assertTrue((output / "mobile" / "species-cards.json").is_file())
            self.assertTrue((output / "SHA256SUMS.txt").is_file())

    def test_export_rejects_nonempty_output_and_source_directories(self):
        with TemporaryDirectory() as directory:
            (Path(directory) / "private.txt").write_text("keep", encoding="utf-8")
            with self.assertRaises(ValueError):
                export_bundle(directory)
            self.assertEqual((Path(directory) / "private.txt").read_text(encoding="utf-8"), "keep")
        with self.assertRaises(ValueError):
            export_bundle(ROOT / "data/invalid-demo")



    def test_aliases_propagate_with_name_visibility(self):
        catalog = json.loads((ROOT / "data" / "species.json").read_text(encoding="utf-8"))
        keelback = next(row for row in catalog if row["speciesId"] == "pseudagkistrodon_rudis")
        corn = next(row for row in catalog if row["speciesId"] == "pantherophis_guttatus")
        cali = next(row for row in catalog if row["speciesId"] == "lampropeltis_californiae")
        self.assertEqual(build_card(keelback)["aliases"],
                         [{"alias": "伪腹蛇", "region": None, "level": "species"}])
        self.assertEqual(build_card(keelback, preview=True)["aliases"][0]["alias"], "伪腹蛇")
        self.assertEqual(build_card(corn)["aliases"], [])
        # 未核验名称：正常投影不放行名称，别名随名称隐藏；预览投影带标注展示
        self.assertEqual(build_card(cali)["aliases"], [])
        self.assertEqual(build_card(cali, preview=True)["aliases"], [])

if __name__ == "__main__":
    unittest.main()
