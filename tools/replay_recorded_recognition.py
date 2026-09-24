# -*- coding: utf-8 -*-
"""用**已记录的上游原始响应**离线重放，验证名称映射改动。

为什么不在线重跑：契约第 64 行明确规定「更新物种表不会重新解析已缓存的业务响应；
新映射通过已记录供应商响应的离线重放验证，不清空账本、不重新上传旧图」。
同图 sha256 去重会让在线重跑直接回放旧投影，既看不出改动、也白扣预算。

输入：venomlens_怕草绳/logs/raw_provider.log（每行一条 {"at","kind","payload"}）
输出：映射后的 status / candidates，以及每条候选的核验状态。

用法：
    python replay_recorded_recognition.py
    python replay_recorded_recognition.py --raw <path>
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT
sys.path.insert(0, str(REPO))

from inference.provider import HhodataProvider  # noqa: E402


def load_catalog():
    return json.loads((REPO / "data" / "species.json").read_text(encoding="utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default=str(REPO / "logs" / "raw_provider.log"))
    args = ap.parse_args()

    raw = Path(args.raw)
    if not raw.exists():
        sys.exit("缺少原始响应存档：%s" % raw)

    provider = HhodataProvider("offline-replay-key", "R", load_catalog())

    rows = []
    for line in raw.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        record = json.loads(line)
        if record.get("kind") != "result_query":
            continue
        payload = record["payload"]
        code = payload[0] if isinstance(payload, list) and payload else None

        # 上游给的是「候选列表」，逐行打印它提到过什么，再看本地能映射到哪几个。
        upstream_names = []
        if code == 1000 and isinstance(payload[1], list):
            for detection in payload[1]:
                for row in detection.get("list", []):
                    upstream_names.append((row[0], row[1]))

        result = provider.parse(payload)
        marked = []
        for cand in result.candidates:
            flag = cand["nameStatus"]
            marked.append("%s(%s, %.2f)" % (cand["commonName"], flag, cand["providerScore"]))

        print("─" * 78)
        print("at=%s  上游 code=%s" % (record["at"], code))
        for score, name in upstream_names:
            print("   上游候选 %8.2f  %s" % (score, name))
        print("   本地映射 → status=%-11s candidates=[%s]" % (result.status, "、".join(marked) or "—"))

        rows.append({
            "at": record["at"],
            "upstreamCode": code,
            "upstream": [{"score": s, "name": n} for s, n in upstream_names],
            "status": result.status,
            "mapped": [
                {"speciesId": c["speciesId"], "commonName": c["commonName"],
                 "providerScore": c["providerScore"], "nameStatus": c["nameStatus"],
                 "demoRelease": c["demoRelease"]}
                for c in result.candidates
            ],
        })

    hit = sum(1 for r in rows if r["mapped"])
    print("─" * 78)
    print("共重放 %d 条结果查询，其中 %d 条本地映射出至少 1 个候选。" % (len(rows), hit))
    out = ROOT / "logs" / "replay-recorded-recognition.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print("明细 ->", out)


if __name__ == "__main__":
    main()
