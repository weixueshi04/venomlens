# -*- coding: utf-8 -*-
"""联网状态下真实识别 API 的准确率验证。

用带物种标注的参考图打真实代理（LIVE / HHODATA），比对「期望物种」与「实际返回」。
每张图计入本地预算闸门；同图 sha256 去重，重跑不额外扣额度。

**为什么分两组**：
- `本轮新图`：从未上传过 → 真正走上游、真正扣预算，反映**当前映射逻辑**。
- `上一轮旧图`：sha256 已在账本里 → 只会回放**旧的投影结果**（契约 L64：更新物种表
  不会重新解析已缓存的业务响应）。它们用来对照，不代表当前逻辑的效果。
  旧图的当前逻辑效果看 `tools/replay_recorded_recognition.py`（离线重放）。

用法：
    python verify_live_recognition.py             # 新图 + 旧图对照
    python verify_live_recognition.py --fresh     # 只跑从未上传过的新图
    python verify_live_recognition.py --old       # 只跑上一轮的旧图（缓存回放）
"""
import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT
BASE = os.getenv("PROXY_BASE", "http://127.0.0.1:8200")

# 从未上传过 → 真实外发。目录名 = 期望物种（参考图是人工按物种归档的）。
FRESH_CASES = [
    ("gloydius_brevicaudus", "ref_02.jpg", "短尾蝮", "剧毒"),
    ("lycodon_rufozonatus", "ref_02.jpg", "赤链蛇", "微毒/无毒争议"),
    ("pantherophis_guttatus", "inat_730698881_cc-by.jpg", "玉米蛇", "无毒"),
    ("pseudagkistrodon_rudis", "ref_02_dorsal.jpg", "颈棱蛇", "无毒（但拟态毒蛇）"),
]

# 2026-09-24 10:20 已上传过 → 只会回放旧投影，仅作对照。
OLD_CASES = [
    ("gloydius_brevicaudus", "ref_01.jpg", "短尾蝮", "剧毒"),
    ("lycodon_rufozonatus", "ref_01.jpg", "赤链蛇", "微毒/无毒争议"),
    ("pantherophis_guttatus", "inat_727198541_cc-by.jpg", "玉米蛇", "无毒"),
    ("pseudagkistrodon_rudis", "ref_01_head_coiled.jpg", "颈棱蛇", "无毒（但拟态毒蛇）"),
]


def load_token() -> str:
    env = REPO / ".env"
    if not env.exists():
        sys.exit("缺少 %s" % env)
    for raw in env.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("PROXY_TOKEN="):
            return line.split("=", 1)[1].strip()
    sys.exit(" .env 里没有 PROXY_TOKEN")


def call(client, token, path: Path):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    headers = {"Authorization": f"Bearer {token}"}
    with path.open("rb") as fh:
        r = client.post(
            f"{BASE}/v1/recognitions",
            headers=headers,
            files={"image": (path.name, fh, "image/jpeg")},
            data={"requestId": digest[:16], "uploadConsent": "true"},
        )
    body = r.json()
    rounds = 1
    # 202 = 上游排队，按契约只查一次，不轮询
    while r.status_code == 202 and body.get("recognitionId") and rounds < 4:
        time.sleep(2.0)
        r = client.post(
            f"{BASE}/v1/recognitions/{body['recognitionId']}/refresh",
            headers={**headers, "Content-Type": "application/json"},
            json={"requestId": digest[:16]},
        )
        body = r.json()
        rounds += 1
    return r.status_code, body, rounds


def run_group(client, token, title, cases, rows, counters):
    print("\n──────── %s ────────" % title)
    for species_id, fname, expect, risk in cases:
        path = REPO / "data" / "reference_images" / species_id / fname
        if not path.exists():
            print("跳过（缺文件）:", path)
            continue
        try:
            code, body, rounds = call(client, token, path)
        except Exception as exc:                       # noqa: BLE001
            print("请求异常:", path.name, exc)
            counters["fail"] += 1
            continue
        status = body.get("status")
        source = body.get("resultSource")
        cands = body.get("candidates") or []
        names = "、".join(c.get("commonName") or "?" for c in cands) or "—"
        hit = species_id in [c.get("speciesId") for c in cands]
        if status == "candidates" and hit:
            counters["ok"] += 1
            verdict = "✓ 命中（status=candidates）"
        elif hit:
            counters["partial"] += 1
            verdict = "△ 候选里有它，但整条落 uncertain（上游还给了目录外名称）"
        elif status == "candidates":
            counters["wrong"] += 1
            verdict = "✗ 答错（给了别的物种）"
        else:
            counters["empty"] += 1
            verdict = "— 未给出候选（status=%s）" % status
        label = "LIVE" if source == "live" else ("CACHE 回放" if source == "cache" else str(source))
        print("%-30s 期望 %-6s → [%s] status=%-11s 候选=[%s]  %s"
              % (fname, expect, label, status, names, verdict))
        rows.append((title, fname, expect, risk, source, status, names, verdict, rounds, code))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fresh", action="store_true", help="只跑从未上传过的新图")
    ap.add_argument("--old", action="store_true", help="只跑上一轮的旧图（缓存回放）")
    args = ap.parse_args()

    token = load_token()
    rows = []
    counters = dict(ok=0, partial=0, wrong=0, empty=0, fail=0)

    with httpx.Client(timeout=60) as client:
        if not args.old:
            run_group(client, token, "本轮新图（真实外发，反映当前映射逻辑）", FRESH_CASES, rows, counters)
        if not args.fresh:
            run_group(client, token, "上一轮旧图（sha256 命中账本 → 回放旧投影，仅作对照）", OLD_CASES, rows, counters)

    print("\n================ 汇总 ================")
    print("命中 candidates %d ／ 候选里有它但整条 uncertain %d ／ 答错 %d ／ 空 %d ／ 请求失败 %d   （共 %d）"
          % (counters["ok"], counters["partial"], counters["wrong"], counters["empty"], counters["fail"], len(rows)))
    out = ROOT / "logs" / ("live-recognition-%s.json" % time.strftime("%H%M%S"))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(
        [dict(zip(["group", "file", "expect", "risk", "source", "status", "candidates",
                   "verdict", "rounds", "http"], r)) for r in rows], ensure_ascii=False, indent=2),
        encoding="utf-8")
    print("明细 ->", out)
    print("预算 ->", httpx.get(f"{BASE}/healthz", timeout=10).json())


if __name__ == "__main__":
    main()
