# 识别 Interface v1｜郭浩天 → 韦仕学

> 2026-09-22 实现版。取代早期规划中的 8MiB、同步返回和大模型自评分假设。字段约束见 `inference/models.py`，可交互结构见启动后的 `/docs`。
> 已采用爬行类 `class=R`，并完成一张整图的真实上传与结果查询；当时因映射缺失返回空候选。现已按郭的核验结果补齐颈棱蛇映射，离线重放能输出该候选，混合未知候选时仍为 `uncertain`。记录见郭侧 AI 指南第 7 节；实景效果与识别结果查询计费仍待确认。

## 1. 提交图片

`POST /v1/recognitions`，`multipart/form-data`：

| 字段／请求头 | 约定 |
|---|---|
| `requestId` | 必填，1–64 位字母、数字、点、下划线或连字符；响应原样带回 |
| `image` | 必填，单张实际 JPEG；最多 2,000,000 字节，每边 11–8192 像素，总像素不超过 1600 万 |
| `uploadConsent` | 字符串 `true` / `false`，默认 `false`；真实模式必须 `true`，表示已取得图片上传同意 |
| `Authorization` | `Bearer <代理访问凭证>`；真实模式必需，与供应商的 `api_key` 是不同凭据 |
| `X-Mock-Scenario` | 仅 mock 模式可用；见下方场景表。真实模式出现此头直接拒绝 |

代理检查实际图像、矫正 EXIF 方向、去除元数据后重新编码 JPEG；处理后仍超过大小上限则拒绝。首版不自动降分辨率，客户端选取能辨认斑纹且符合大小限制的图片。不接收 GPS、伤情、姓名、任意图片 URL 或 Base64。

总请求体上限为图片上限加 64KiB；解析和处理在内存中完成。额外字段或重复字段被拒绝。客户端不自动重试，收到 `requestId` 与当前采集不一致的响应时丢弃旧结果。

## 2. 识别结果

HTTP 200：已获得本次结果，但不代表识别正确。

```json
{
  "schemaVersion": "1",
  "requestId": "capture-001",
  "status": "uncertain",
  "candidates": [],
  "scoreType": "unavailable",
  "qualityIssues": [],
  "latencyMs": 420,
  "resultSource": "live",
  "recognitionId": "64位小写十六进制标识"
}
```

- `status`：`candidates` / `uncertain` / `no_snake` / `pending`。
- `candidates`：最多 3 条，字段为 `speciesId`、`commonName`、`scientificName`、`score`、`providerScore`。`candidates` 状态至少 1 条；`no_snake` 和 `pending` 必须为空。
- `score` 首版始终为 `null`，`scoreType` 始终为 `unavailable`。供应商原始数字放 `providerScore`；单位、范围和校准情况未确认，**界面不将其格式化成百分比，也不把 97.6 自动除以 100**。
- 名称仅从本地已核验映射表产生；存在未匹配候选时为 `uncertain`，但保留其余已匹配候选，客户端不能仅因该状态而忽略候选列表。`data/species.json` 已按郭的核验结果加入颈棱蛇；玉米蛇、加州王蛇仍待核对。名称核验不等于毒性或医疗资料核验。
- `qualityIssues` 可包含 `blurred` / `too_small` / `low_light`。真实 Adapter 当前没有质量判断证据，返回空数组；模拟场景可以演示这些提示。
- `resultSource` 必须可见：`mock` 是模拟，`live` 是本次供应商响应，`cache` 是账本回放。缓存的 `latencyMs` 是原尝试耗时，不是本次响应耗时。
- `recognitionId` 是代理侧标识，真实响应提供；不暴露供应商任务 ID。模拟的非 pending 响应可以省略此字段。
- 原始候选顺序保留，不把不同检测区域的分值解释为可比较的概率。

供应商 `[1010,"No animals"]` 以及空检测结果目前映射为 `uncertain`；即使使用 `class=R`，未识别到爬行动物也不能证明蛇不存在。`no_snake` 暂仅用于模拟客户端状态，任何状态都不能隐藏伤情记录和求助入口。

## 3. 异步任务：先返回 pending，再人工查询

供应商受理任务后，代理返回 **HTTP 202**：`status="pending"`、空候选和 `recognitionId`。这不是超时或识别失败。

客户端显示“识别处理中”，由操作人决定是否查询。**首版没有自动轮询、固定间隔计时器、退避重试或服务器后台查询。**

`POST /v1/recognitions/{recognitionId}/refresh`

- 鉴权方式相同。
- JSON 请求体：`{"requestId":"query-001"}`。
- pending 任务每次刷新最多产生 1 次供应商请求，先占用本地预算。
- 仍在处理时继续返回 202；完成返回 200；已完成／失败的记录只回放缓存，不再次联网。
- 重复提交同类别、相同规范化图片也只回放现有状态；改 `requestId` 不会绕过去重。
- 更新物种表不会重新解析已缓存的业务响应。首次实测的旧缓存仍为空候选；新映射通过已记录供应商响应的离线重放验证，不清空账本或重新上传旧图。

真实上传或单次查询的上游等待上限为 20 秒；客户端每个请求的总等待上限建议 25 秒。异步完成时间是另一回事，不能承诺 25 秒内一定得到物种结果。

## 4. 错误约定

```json
{"requestId":"capture-001","resultSource":"live","error":{"code":"LOCAL_BUDGET_EXHAUSTED","message":"本地调用额度已用尽"}}
```

| HTTP | 错误码 | 客户端处理 |
|---|---|---|
| 400 | `INVALID_REQUEST` / `INVALID_IMAGE` | 提示修正输入，不原样自动重试 |
| 401 | `UNAUTHORIZED` | 检查代理凭证 |
| 403 | `UPLOAD_CONSENT_REQUIRED` | 取得上传同意或走本地伤情流程 |
| 404 | `UNKNOWN_RECOGNITION` | 任务不存在，不自动重新上传 |
| 409 | `OPERATION_IN_PROGRESS` | 正在处理或上次进程中断待人工核查，禁止重复发送 |
| 413 | `IMAGE_TOO_LARGE` | 客户端重新选择／处理图片 |
| 429 | `LOCAL_BUDGET_EXHAUSTED` | 本地保护上限到达，由郭核实预算 |
| 429 | `UPSTREAM_LIMITED` | 上游配额不足或限流，文档未区分；人工确认 |
| 502 | `UPSTREAM_ERROR` / `UPSTREAM_AUTH_ERROR` / `INVALID_MODEL_OUTPUT` | 技术失败，不解释为“没有蛇” |
| 504 | `UPSTREAM_TIMEOUT` | 超时，不退还本地已占预算，不自动重试 |

输入解析前无法取得合法 `requestId` 时返回 `"unknown"`。上游错误文字、原始响应及密钥不回传客户端。

## 5. 零额度联调

在项目根目录启动：

```bash
RECOGNITION_MODE=mock LIVE_CALL_LIMIT=0 PROXY_TOKEN= python -m uvicorn inference.app:app --host 127.0.0.1 --port 8765
```

本机 `/healthz` 只读本地状态，不查询供应商配额。若手机需要访问，另行确认受控局域网地址、访问凭证与防火墙后再开放监听；本轮没有公网部署。

| `X-Mock-Scenario` | HTTP | 固定响应文件 |
|---|---|---|
| `candidates`（默认） | 200 | `fixtures/candidates.json` |
| `multiple` | 200 | `fixtures/multiple.json` |
| `uncertain` | 200 | `fixtures/uncertain.json` |
| `no_snake` | 200 | `fixtures/no_snake.json` |
| `pending` | 202 | `fixtures/pending.json` |
| `timeout` | 504 | `fixtures/timeout.json` |
| `invalid_output` | 502 | `fixtures/invalid_output.json` |

每个场景仍须上传合法 JPEG，响应中的 requestId 替换为本次值。模拟 pending 后可用 refresh 配合 `candidates` 场景演示完成；它不是实际供应商任务。

## 6. 供应商对接依据与尚未证实事项

本地依据：`api/识别模型api以及接口规范/doc.json`。

- 第 10–26 行：`POST https://ai.open.hhodata.com/api/v2/dongniao`，`api_key` 请求头鉴权。
- 第 39–45 行：multipart `image` + 非空 `upload`，2MB；供应商默认 `class=B`，允许 B/M/A/R/F 组合。
- 第 58–64 行：查询为 multipart `resultid`，不重新上传图片。
- 第 127–140 行：`[1000,任务ID]`、`[1000,检测数组]`、处理中和未识别到动物。
- 第 147 行：503／1007 表示限流或配额耗尽。

郭于 2026-09-22 补充的供应商文档与类别表：

- `B` 鸟、`M` 兽、`A` 两栖、`R` 爬行；本项目蛇类识别配置为 `R`。`F`／`S` 的识别类别数量仍为“敬请期待”，不据此新增识别支持。
- 爬行类识别支持版本 2.0、类别数 8592；百科支持版本 2.2、类别数 12019。两者口径不同，均不是蛇类数量，也不能当作准确率证据。
- `quota=1` 查询配额不消耗配额；不能据此推断识别结果查询也免费。当前代理没有配额查询入口，健康检查仍只读本地账本。
- 文档建议结果查询间隔 1–3 秒并限制次数，超过 5 次仍未完成可视为超时；这是供应商建议，不是自动轮询授权，首版仍只在人工操作与预算允许时单次查询。
- 郭最终确认当时剩余 50 次；本地累计上限仍允许 0–50、默认 0，不把账号余额视为本轮获批预算。

**待供应商／郭确认：**目标蛇种覆盖、图像分值范围与校准含义、识别结果查询及上传失败／超时是否扣次数、图像保留政策和可商用／参赛使用条件。类别已确认不等于真实效果已验证，首版保留模拟默认值和小额授权机制。

## 附则 A（2026-09-23 夜）：演示 lane `DEMO_RELEASE_UNVERIFIED`

- 环境变量 `DEMO_RELEASE_UNVERIFIED=1` 时，代理将**未核验名称**以 `demoRelease=true`、`nameStatus="pending_review"` 附加字段放入候选；默认关闭，部署禁止开启。
- Candidate 新增字段（ additive ）：`demoRelease: bool`、`nameStatus: "verified"|"pending_review"`。展示 `demoRelease=true` 候选的客户端**必须以呈现级披露（页面横幅或口播说明「目标态演示：候选与文案未经人工核验」）框定整段演示**；按郭 2026-09-23 夜指示，卡片本体不再加逐条标注以展示目标态效果；候选仍不得进入病例卡身份或任何核验结论；正常投影与图片 sourceStatus 门禁不受本开关影响。
- `/healthz` 增加 `demoReleaseUnverified` 字段，开关永不静默。
- 通知韦仕学：本附则为公共 Interface 变更，Android 渲染需同步加标注；未加标注前不得开启该环境变量。
