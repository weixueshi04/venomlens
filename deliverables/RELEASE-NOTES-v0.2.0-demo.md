# 一拍知蛇 · v0.2.0-demo（预发行）

「影石 BoldMaker 2026 智能影像挑战赛」赛道三（AI + 社会公益 · 公共健康）· 队伍「两爬青年」

这是**演示包**，不是可独立分发的成品：真实识别需要在本机跑推理代理（见下）。
相机侧依赖 Insta360 X5 与官方 SDK，没有相机时链路会停在连接页。

---

## 构建来源（重要）

本 release 的 tag 打在 **`release/v0.2.0-demo`** 分支上，**不是当前 `main`**。

原因：`main` 在 `b20c561` 之后由队友合入了两个提交（「移动端 H5 演示应用（同源挂载 `/m/`）」与
「H5 自动连拍模式 + 现场验收单」），其中**删除了整个 Android 紧急一键流程**
（`EmergencyFlowFragment/Logic/ViewModel`、`fragment_emergency_flow.xml`、相关 drawable/colors 与单测），
并把演示重心转向 H5（`inference/mobile_demo/`）。而本版 Android 包恰恰是在这条被删除的链路上开发的
（名称映射准入修复、来源标注移除、图标、真机链路）。两条线有 16 个文件重叠且方向相反。

因此本版**从独立分支发布**，两侧工作都未被覆盖。要收拢成一条线，需要先决定
「Android 原生紧急流程」与「H5 演示」谁保留、谁退场——这属于产品决策，不在本版 release 范围内。

## 附件

| 文件 | 大小 | MD5 | SHA-256 |
|---|---|---|---|
| `snakesnap-v0.2.0-demo.apk` | 279,337,968 B | `b57d4ee652979b415ef0ba09c03e8330` | `0935c9472801dbd56175167273192b0140b3a876eb8408bd4c70eb3505fb3e85` |

- 包名 `com.insta360.kmpsdk.demo`，应用名「一拍知蛇」
- `versionCode 4` / `versionName 0.2.0-demo`，`minSdk 29` / `targetSdk 35`，`arm64-v8a`
- 未签名（debug 构建），仅供演示与评测安装

## 运行前置

1. 本机跑推理代理：`python start_proxy.py 8200`（需 `venomlens_怕草绳/.env` 提供 `HHODATA_API_KEY` 与 `PROXY_TOKEN`）
2. 手机通过 USB 连本机并做端口转发：`adb reverse tcp:8200 tcp:8200`
3. 装上本 APK，进「一拍知蛇」页，勾选一次上传同意即可

> 本构建把代理地址 `http://127.0.0.1:8200` 与访问凭证编译进了 `BuildConfig`
> （值来自 `local.properties`，不入库）。所以它只在做了 `adb reverse` 的手机上有真实识别能力；
> 代理不可达时会退到本地数据集回放，并在界面上如实标注「改用本地数据集（非真实识别）」。

---

## 本版改动

### 1. 打通「联网能判准」——名称映射准入放开（根因修复）

改前：`HhodataProvider` 只把 `verificationStatus == "verified"` 的物种收进名称映射表。
而 `data/species.json` 里只有颈棱蛇一条是 `verified`，于是**上游 97.6 分答对的短尾蝮（剧毒）与赤链蛇被整个丢掉**，
App 返回「无法可靠判断」——这就是「联网也判不准」的直接根因。

改后：准入门槛改为「**在本地物种目录内**」。核验状态不再被丢弃，而是逐条披露
（未核验名称带 `demoRelease=true` / `nameStatus="pending_review"`）。
`data/species.json` 的 `verificationStatus` **一字未改**，没有任何条目被自动升级为已核验。

契约修订见 `contracts/recognition-contract.md` 附则 B；随之作废 `DEMO_RELEASE_UNVERIFIED`
开关与 `/healthz` 的 `demoReleaseUnverified` 字段（放开后该开关已成静默 no-op，留着比删掉更危险）。

### 2. `1008 "No boxes"` 不再被当成故障

上游明确表示「画面里没有检测框」属正常返回，与 `1010 "No animals"`、空结果同口径映射为 `uncertain`，
而不是 `UPSTREAM_ERROR`。

### 3. 修复分片落盘（隐私）

`MultiPartParser` 的分片上限属性名随 starlette 版本变更：≤0.37 读 `spool_max_size`，
**0.38+ 读 `max_file_size`（类默认 1 MB）**。此前只设了旧名，导致**大于 1 MB 的图片会静默落到磁盘临时文件**，
违反本项目「图片不得落盘」的隐私口径。现已同时设置两个属性名，并由
`test_valid_upload_stays_in_memory` 守住。

### 4. 移除结果来源标注

按需求，界面不再区分 MOCK / LIVE / CACHE：删掉结果区徽标、结果文案里的来源行、
对比页的来源字段，及其资源与测试。
**保留**「候选不代表已确认」「无可靠置信度」「不生成诊断结论」「被咬伤立即就医」等安全表述。

`uncertain` 文案同时改为分叉，避免「有候选却读起来像失败」：

- 候选为空 → 「未获得可用候选，无法可靠判断。」
- 有候选但整条降级 → 「已给出候选；上游还提到本地未收录的物种，因此未作整体判定。」

### 5. 品牌图标

由即梦原图（深墨绿 `#0C3B1E` 底 + 米白极细单线的取景框与探头蛇）提取线稿，
重新合成 5 档 density 自适应图标前景 PNG 与 10 个传统 webp；
自适应图标前景按 AOSP 规范映射到正中 72dp 可见区。可复现脚本：`android/snakesnap/tools/build_icon.py`。

### 6. 新增验证工具

- `tools/replay_recorded_recognition.py`：用**已记录的上游原始响应**离线重放，验证映射改动（不扣预算）
- `tools/verify_live_recognition.py`：分「本轮新图」与「上一轮旧图」两组打真实代理，并打印 `resultSource`

---

## 验证状态

| 项 | 结果 |
|---|---|
| Python 离线测试（unittest） | **73 项全通过** |
| Android JVM 单测 | **103 项全通过** |
| 离线重放已记录的上游响应 | 短尾蝮、赤链蛇 由「候选为空」变为**能映射出候选** |
| 在线真实识别（4 张全新图片，真实外发） | **4/4 返回了正确物种候选** |
| 真机安装 + 目视确认 | **本轮未做**（adb 未检测到设备），待补 |

## 已知限制

- **物种目录只有 6 条**（玉米蛇、加州王蛇、颈棱蛇、猪鼻蛇、赤链蛇、短尾蝮）。
  上游答对但不在目录内的物种仍落 `uncertain`。扩大覆盖需要逐条核对中文名 ↔ 学名后录入。
- **上游同时列举目录外物种时整条状态为 `uncertain`**（契约 L43），但已匹配的候选会照常保留并显示。
- **同一张图不重算**：`sha256` 命中预算账本即回放旧结果，因此**本版之前上传过的图片仍是旧投影**。
  要看到新映射的效果，需用新图，或看 `tools/replay_recorded_recognition.py` 的离线重放。
- 识别结果始终是**候选**，不是诊断，也不能用于排除危险。
