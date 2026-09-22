# 识别契约（草稿 v1，2026-09-22）

> 郭浩天维护，韦仕学确认。字段/枚举变更需双方同意。冻结目标：9/22 17:00。

## 请求

`POST /v1/recognitions`，`multipart/form-data`。

| 字段 | 说明 |
|---|---|
| `requestId` | 客户端生成；新采集覆盖旧结果时核对 |
| `image` | JPEG，最大 8 MiB；客户端保证可解码、方向正确；压缩副本移除 EXIF 定位 |

鉴权值放请求头，不放 URL。首版只收 JPEG。同一页面最多一个活跃请求；客户端总等待上限 25s。

## 成功响应

```json
{
  "schemaVersion": "1",
  "requestId": "demo-001",
  "status": "uncertain",
  "candidates": [
    {
      "speciesId": "pantherophis_guttatus",
      "commonName": "玉米蛇",
      "scientificName": "Pantherophis guttatus",
      "score": null
    }
  ],
  "scoreType": "unavailable",
  "qualityIssues": ["blurred"],
  "latencyMs": 4200
}
```

不变量：

- `status` ∈ `candidates` / `no_snake` / `uncertain`，只表达识别状态，不表达伤者是否安全。
- `candidates` 状态须有 1–3 个候选；`no_snake` 必须为空数组；`uncertain` 可为空或最多 3 个。
- `speciesId` 必须来自 `species.json`；无法映射不编造 ID，返回 `uncertain`。
- 名称从知识数据映射，不信任模型自由生成的名称。
- `scoreType` 首版仅 `unavailable`（所有 score 为 null）或 `model_self_reported`（0–1，界面须写"AI 自评，未经校准"）。优先用候选排序而非百分比。
- `qualityIssues` ∈ `blurred` / `too_small` / `low_light`，可为空。
- 不接收模型输出的处置方法、血清用法或医疗建议自由文本。

## 失败（失败 ≠ 没检测到蛇）

| 情况 | HTTP / error.code |
|---|---|
| 无效图片/过大 | 400 / 413，`INVALID_IMAGE` / `IMAGE_TOO_LARGE` |
| 未授权/限流 | 401 / 429，`UNAUTHORIZED` / `RATE_LIMITED` |
| 上游不可用/超时 | 502 / 504，`UPSTREAM_ERROR` / `UPSTREAM_TIMEOUT` |
| 模型输出违反契约 | 502，`INVALID_MODEL_OUTPUT` |

错误体统一：`{"requestId":"demo-001","error":{"code":"UPSTREAM_TIMEOUT","message":"识别暂不可用"}}`。
不回传密钥、上游完整响应或内部堆栈。HTTP 200 的 `uncertain` 是有效不确定识别，与网络失败分开呈现。

## 模拟响应索引（contracts/mock/）

Android 模拟 Adapter 直接读取以下文件，正式演示不得把模拟当真实识别：

| 文件 | 场景 |
|---|---|
| `candidates_1.json` | 有候选（1 个） |
| `candidates_3.json` | 多个候选（3 个） |
| `uncertain.json` | 无法判断（空候选） |
| `no_snake.json` | 无蛇 |
| `error_timeout.json` | 上游超时（504 体） |
| `error_invalid_output.json` | 模型输出违规（502 体） |
