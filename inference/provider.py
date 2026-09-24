import asyncio
import os
import math
from dataclasses import dataclass, field

import httpx

from inference.errors import ServiceError


ENDPOINT = "https://ai.open.hhodata.com/api/v2/dongniao"


@dataclass
class ProviderResult:
    status: str
    candidates: list[dict] = field(default_factory=list)
    task_id: str | None = None


class HhodataProvider:
    def __init__(self, api_key: str, animal_class: str, catalog: list[dict], transport=None):
        self.api_key = api_key
        self.animal_class = animal_class
        self.transport = transport
        self.names = {}
        for species in catalog:
            # 2026-09-24 修订（契约附则 B）：映射准入从「verificationStatus == verified」改为
            # 「在本地目录内」。旧闸门会让上游 97.6 分答对的短尾蝮（剧毒）、赤链蛇整个落成
            # uncertain——「联网也判不准」的直接根因。
            # 核验状态不再被丢弃，而是逐条带出（demoRelease / nameStatus），披露不降级。
            self._index(self.names, species)

    @staticmethod
    def _index(target: dict, species: dict):
        # 别名可为对象（带地域/来源）或旧式字符串；英文名仅用于匹配供应商返回，不进入展示层
        aliases = [a["alias"] if isinstance(a, dict) else a for a in species.get("aliases", [])]
        english = (species.get("englishCommonName") or "").split("（")[0]
        names = [species["commonName"], species["scientificName"], *aliases,
                 *[t.strip() for t in english.split("/") if t.strip()]]
        for name in names:
            target[name.strip().casefold()] = species

    async def upload(self, image: bytes) -> ProviderResult:
        return await self._request(
            files={"image": ("capture.jpg", image, "image/jpeg")},
            data={"upload": "1", "class": self.animal_class},
        )

    async def refresh(self, task_id: str) -> ProviderResult:
        return await self._request(files={"resultid": (None, task_id)}, expected_task=task_id)

    async def _request(self, *, files, data=None, expected_task=None):
        try:
            async with asyncio.timeout(20):
                async with httpx.AsyncClient(
                    transport=self.transport,
                    timeout=httpx.Timeout(20, connect=5),
                    follow_redirects=False,
                    trust_env=False,
                ) as client:
                    response = await client.post(
                        ENDPOINT, headers={"api_key": self.api_key}, files=files, data=data
                    )
        except (TimeoutError, httpx.TimeoutException):
            raise ServiceError("UPSTREAM_TIMEOUT", "模型请求超时；本次尝试仍计入本地预算", 504) from None
        except httpx.HTTPError:
            raise ServiceError("UPSTREAM_ERROR", "模型请求失败；请先排查，不自动重试") from None
        if response.status_code in {429, 503}:
            raise ServiceError("UPSTREAM_LIMITED", "上游配额不足或限流，请人工核实", 429)
        if response.status_code in {401, 403}:
            raise ServiceError("UPSTREAM_AUTH_ERROR", "模型鉴权失败，请检查代理侧配置")
        if response.status_code != 200:
            raise ServiceError("UPSTREAM_ERROR", "模型返回非预期 HTTP 状态")
        try:
            payload = response.json()
        except ValueError:
            raise ServiceError("INVALID_MODEL_OUTPUT", "模型响应不是有效 JSON") from None
        raw_log = os.environ.get("RAW_LOG_PATH")
        if raw_log:
            import json as _json
            from datetime import datetime as _dt
            with open(raw_log, "a", encoding="utf-8") as fh:
                fh.write(_json.dumps({
                    "at": _dt.now().isoformat(timespec="seconds"),
                    "kind": "result_query" if "resultid" in (files or {}) else "upload",
                    "payload": payload,
                }, ensure_ascii=False) + "\n")
        return self.parse(payload, expected_task)

    def parse(self, response, expected_task=None) -> ProviderResult:
        if not isinstance(response, list) or len(response) != 2 or type(response[0]) is not int:
            raise ServiceError("INVALID_MODEL_OUTPUT", "模型响应不符合二元组约定")
        code, payload = response
        if code == 1007:
            raise ServiceError("UPSTREAM_LIMITED", "上游配额不足或限流，请人工核实", 429)
        if code in {1008, 1010}:
            # 1008="No boxes"、1010="No animals"：上游明确表示「未检测到目标」，
            # 属于正常返回而非故障。契约规定空检测结果映射为 uncertain——
            # 未识别到爬行动物不能证明蛇不存在（contracts/recognition-contract.md 第 49 行）。
            # 实测：2026-09-24 02:45 对同一 taskId 连续两次查询均返回 [1008,"No boxes"]。
            return ProviderResult("uncertain")
        if code == 1001:
            if expected_task and payload == expected_task:
                return ProviderResult("pending", task_id=expected_task)
            raise ServiceError("UPSTREAM_ERROR", "结果查询失败；不会自动轮询")
        if code != 1000:
            raise ServiceError("UPSTREAM_ERROR", "模型返回业务错误")
        if isinstance(payload, str) and 0 < len(payload) <= 256:
            return ProviderResult("pending", task_id=payload)
        if not isinstance(payload, list):
            raise ServiceError("INVALID_MODEL_OUTPUT", "模型结果结构不符合约定")
        candidates = []
        seen = set()
        unknown = False
        for detection in payload:
            if not isinstance(detection, dict) or not isinstance(detection.get("list"), list):
                raise ServiceError("INVALID_MODEL_OUTPUT", "模型候选结构不符合约定")
            for row in detection["list"]:
                if (
                    not isinstance(row, list) or len(row) < 2
                    or type(row[0]) not in {int, float}
                    or not isinstance(row[1], str)
                ):
                    raise ServiceError("INVALID_MODEL_OUTPUT", "模型候选字段不符合约定")
                try:
                    if not math.isfinite(row[0]):
                        raise ValueError
                except (ValueError, OverflowError):
                    raise ServiceError("INVALID_MODEL_OUTPUT", "模型分值不是有限数值") from None
                keys = [name.strip().casefold() for name in row[1].split("|") if name.strip()]
                matched = {self.names[k]["speciesId"]: self.names[k] for k in keys if k in self.names}
                if len(matched) != 1:
                    # 目录外名称：这一行不产出候选，并把整条结果标记为 uncertain。
                    # 契约 L43：此时候选列表仍保留其余已匹配项，客户端不得仅因该状态而忽略它们。
                    unknown = True
                    continue
                species_id, species = next(iter(matched.items()))
                if species_id in seen:
                    continue
                seen.add(species_id)
                if len(candidates) < 3:
                    verified = species.get("verificationStatus") == "verified"
                    candidates.append({
                        "speciesId": species_id,
                        "commonName": species["commonName"],
                        "scientificName": species["scientificName"],
                        "score": None,
                        "providerScore": row[0],
                        # 逐条披露：名称映射未经人工核验的候选仍可展示，但必须自带标记。
                        "demoRelease": not verified,
                        "nameStatus": "verified" if verified else "pending_review",
                    })
        return ProviderResult("candidates" if candidates and not unknown else "uncertain", candidates)
