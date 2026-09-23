# 面向小白的展示文案必须纯中文：描述类字段不允许出现拉丁字母。
# 学名、英文名、作者用户名、许可证代码、链接属于内部/技术字段，不在本校验范围。
import json
import re
import unittest
from pathlib import Path

SPECIES = Path(__file__).resolve().parents[2] / "data" / "species.json"
ASCII = re.compile(r"[A-Za-z]")


def _display_texts(entry):
    yield "commonName", entry.get("commonName") or ""
    for a in entry.get("aliases") or []:
        yield "aliases.alias", a.get("alias") or ""
    cp = entry.get("comparisonProfile") or {}
    yield "comparisonProfile.hook", cp.get("hook") or ""
    for i, s in enumerate(cp.get("layChecklist") or []):
        yield f"comparisonProfile.layChecklist[{i}]", s
    for i, s in enumerate(cp.get("doNot") or []):
        yield f"comparisonProfile.doNot[{i}]", s
    for i, la in enumerate(cp.get("layLookAlikes") or []):
        yield f"comparisonProfile.layLookAlikes[{i}].layHowToTell", la.get("layHowToTell") or ""
    for i, r in enumerate(entry.get("referenceImages") or []):
        yield f"referenceImages[{i}].role", r.get("role") or ""


class DisplayChineseOnlyTests(unittest.TestCase):
    def test_display_fields_contain_no_latin_letters(self):
        entries = json.loads(SPECIES.read_text(encoding="utf-8"))
        bad = []
        for e in entries:
            for field, text in _display_texts(e):
                if ASCII.search(text):
                    bad.append(f"{e.get('speciesId')}.{field}: {text}")
        self.assertEqual([], bad, "展示文案出现拉丁字母：" + "；".join(bad))

    def test_english_image_role_detected_without_comparison_profile(self):
        for profile in ({}, {"comparisonProfile": None}, {"comparisonProfile": {}}):
            with self.subTest(profile=profile):
                entry = {**profile, "referenceImages": [{"role": "head detail"}]}
                bad = [(field, text) for field, text in _display_texts(entry) if ASCII.search(text)]
                self.assertEqual([("referenceImages[0].role", "head detail")], bad)

    def test_chinese_display_texts_allow_english_technical_fields(self):
        entry = {
            "commonName": "测试蛇",
            "aliases": [{"alias": "测试别名"}],
            "englishCommonName": "Test Snake",
            "scientificName": "Testus serpentis",
            "comparisonProfile": {
                "hook": "观察身体花纹",
                "layChecklist": ["背部有斑纹"],
                "doNot": ["不要靠近"],
                "layLookAlikes": [{"layHowToTell": "观察头部形状"}],
            },
            "referenceImages": [{"role": "头部特写", "rights": "CC BY", "author": "TestAuthor"}],
        }
        bad = [(field, text) for field, text in _display_texts(entry) if ASCII.search(text)]
        self.assertEqual([], bad)

    def test_latin_in_each_description_field_is_detected(self):
        text = "说明含 A"
        cases = (
            ("commonName", {"commonName": text}),
            ("aliases.alias", {"aliases": [{"alias": text}]}),
            ("comparisonProfile.hook", {"comparisonProfile": {"hook": text}}),
            ("comparisonProfile.layChecklist[0]", {"comparisonProfile": {"layChecklist": [text]}}),
            ("comparisonProfile.doNot[0]", {"comparisonProfile": {"doNot": [text]}}),
            ("comparisonProfile.layLookAlikes[0].layHowToTell",
             {"comparisonProfile": {"layLookAlikes": [{"layHowToTell": text}]}}),
        )
        for expected_field, entry in cases:
            with self.subTest(field=expected_field):
                bad = [(field, value) for field, value in _display_texts(entry) if ASCII.search(value)]
                self.assertEqual([(expected_field, text)], bad)


if __name__ == "__main__":
    unittest.main()
