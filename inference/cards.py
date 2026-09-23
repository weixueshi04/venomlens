import argparse
import hashlib
import json
import shutil
from pathlib import Path
from urllib.parse import urlsplit

from PIL import Image

from inference.settings import ROOT


SAFETY = [
    "与蛇保持距离，不追赶、触碰或为辨认而靠近。",
    "候选和参考照片不能判断有毒无毒，也不能排除危险。",
    "如已被咬伤，尽快联系急救并就医，不等待识别或照片比对。",
]
SCENARIOS = ("candidates", "multiple", "uncertain", "no_snake", "pending", "timeout", "invalid_output")


def reviewed(value):
    return (
        value.get("reviewStatus") == "verified"
        and bool(value.get("reviewedBy"))
        and bool(value.get("reviewedAt"))
    )


def https_url(value):
    if not isinstance(value, str):
        return None
    parsed = urlsplit(value)
    return value if parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password else None


def build_card(entry, *, preview=False):
    name_verified = entry.get("verificationStatus") == "verified"
    profile = entry.get("comparisonProfile") or {}
    content_verified = reviewed(profile)
    show_profile = bool(profile) and (preview or (name_verified and content_verified))
    images = []
    for image in entry.get("referenceImages", []):
        source_matched = image.get("sourceStatus") in {"record_matched", "team_declared"}
        if not source_matched or not image.get("rights") or not image.get("source"):
            continue
        if not preview and not (name_verified and reviewed(image)):
            continue
        images.append({
            "file": image["file"],
            "role": image["role"],
            "source": image["source"],
            "rights": image["rights"],
            "sourcePage": https_url(image.get("sourcePage")),
            "reviewStatus": "verified" if reviewed(image) else "pending_review",
            "sourceStatus": image["sourceStatus"],
            "modification": "发布副本已缩小并去除元数据；未裁切内容",
        })
    return {
        "speciesId": entry["speciesId"],
        "commonName": entry["commonName"] if name_verified or preview else "物种名称待核验",
        "scientificName": entry["scientificName"] if name_verified or preview else None,
        "nameStatus": "verified" if name_verified else "pending_review",
        "contentStatus": "verified" if content_verified else "pending_review" if profile else "unavailable",
        # 候选已知信息：别名仅作地域参考展示（带来源），随名称可见性放行
        "aliases": [
            {"alias": a.get("alias"), "region": a.get("region"), "level": a.get("level")}
            for a in entry.get("aliases", [])
            if isinstance(a, dict) and a.get("alias")
        ] if (preview or name_verified) else [],
        "previewOnly": preview,
        "hook": profile.get("hook") if show_profile else None,
        "checklist": profile.get("layChecklist", []) if show_profile else [],
        "lookAlikes": profile.get("layLookAlikes", []) if show_profile else [],
        "images": images,
        "hiddenImageCount": len(entry.get("referenceImages", [])) - len(images),
        "externalRefs": [
            {"name": ref["name"], "url": ref["url"]}
            for ref in entry.get("externalRefs", [])
            if (preview or name_verified) and https_url(ref.get("url"))
        ],
        "riskStatus": "unknown",
        "safety": list(SAFETY),
        "notice": "演示草稿：名称、文案与图片分别标注状态，不能用于实际辨认或医疗判断。" if preview else
                  "仅展示已核验材料；没有图片或说明不代表安全。",
    }


def export_bundle(output, root=ROOT):
    output = Path(output).resolve()
    root = Path(root).resolve()
    protected = [root / name for name in ("inference", "data", "contracts", "api", ".runtime")]
    if output == root or any(output.is_relative_to(p) for p in protected):
        raise ValueError("请使用独立交付目录，不覆盖源码、数据或运行状态")
    entries = json.loads((root / "data/species.json").read_text(encoding="utf-8"))
    normal = {row["speciesId"]: build_card(row) for row in entries}
    preview = {row["speciesId"]: build_card(row, preview=True) for row in entries}
    files = sorted({image["file"] for card in preview.values() for image in card["images"]})
    for relative in files:
        image_path = (root / relative).resolve()
        if not image_path.is_relative_to(root / "data/card_images") or image_path.suffix.lower() != ".jpg":
            raise ValueError("仅打包 data/card_images 下的去元数据发布图")
        with Image.open(image_path) as image:
            if image.getexif() or image.info.get("comment") or max(image.size) > 1280:
                raise ValueError(f"图片未满足发布约束：{relative}")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("输出目录必须为空；请使用新目录，避免混入旧版或私人文件")
    bundle = {"cardSchemaVersion": "1", "mode": "mock", "liveCallsEnabled": False,
              "normal": normal, "preview": preview, "safety": SAFETY}
    (output / "species-cards.json").write_text(json.dumps(bundle, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for name in ("index.html", "cards.js", "cards.css"):
        shutil.copy2(root / "inference/card_preview" / name, output / name)
    (output / "fixtures").mkdir()
    for scenario in SCENARIOS:
        fixture = root / "contracts/fixtures" / f"{scenario}.json"
        shutil.copy2(fixture, output / "fixtures" / fixture.name)
    for relative in files:
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / relative, target)
    manifest = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(output).as_posix()}"
                for path in sorted(output.rglob("*")) if path.is_file()]
    (output / "SHA256SUMS.txt").write_text("\n".join(manifest) + "\n", encoding="utf-8")
    return {"species": len(entries), "images": len(files), "output": str(output)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="生成本机离线物种卡演示，不调用模型")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    print(json.dumps(export_bundle(args.output), ensure_ascii=False))
