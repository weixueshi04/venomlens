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
    cp = entry.get("comparisonProfile")
    if not cp:
        return
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
    def setUp(self):
        self.entries = json.loads(SPECIES.read_text(encoding="utf-8"))

    def test_display_fields_contain_no_latin_letters(self):
        bad = []
        for e in self.entries:
            for field, text in _display_texts(e):
                if ASCII.search(text):
                    bad.append(f"{e.get('speciesId')}.{field}: {text}")
        self.assertEqual([], bad, "展示文案出现拉丁字母：" + "；".join(bad))

    def test_english_common_names_not_in_display_layer(self):
        for e in self.entries:
            en = e.get("englishCommonName") or ""
            for field, text in _display_texts(e):
                for token in re.findall(r"[A-Za-z]+", en):
                    if len(token) > 3 and token in text:
                        self.fail(f"{e.get('speciesId')}.{field} 泄漏英文名 {token}")


if __name__ == "__main__":
    unittest.main()
