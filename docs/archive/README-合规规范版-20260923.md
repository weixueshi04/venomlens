# VenomLens · 一拍知蛇

**简体中文（规范版）** | [English](README.en.md)（英文版更新滞后，以本文件为准）

影石 Bold Maker 2026 · 赛道三 AI+社会公益 参赛原型：从 Insta360 相机获取照片，辅助查看蛇种候选，并提供伤情记录与就医信息入口。**当前是开发联调版本，不是可用于医疗判断的成品。**

> **红线（阅读任何演示前先看）**
> - 模拟结果与照片无关；「未检测到蛇」**不表示现场安全**。
> - 候选与供应商原始分值**不是物种确认、不是准确率**；不能排除危险、不能替代医疗判断。
> - 本仓库任何内容不构成诊断、血清用法、处置建议或实时血清库存承诺。
> - 被咬伤：立即就医，把手机递给医生；不要等待识别或比对结果。

## 0. 合规口径总览

四类结论**互不替代**，引用时必须注明属于哪一类：

| 口径 | 含义 | 当前状态（2026-09-23） |
|---|---|---|
| 模拟通过 | mock fixtures / 离线测试通过 | ✅ Python 离线回归 71/71；Android JVM 55/55、模拟器仪器 35/35（韦 9/23 验证） |
| 编码符合本地规范 | 契约、审核门禁、发布管线约束被测试固定 | ✅ 契约 `contracts/recognition-contract.md`（含附则 A） |
| 供应商实测通过 | 真实 HHodata 调用取得响应 | ✅ 4 轮真实整图（9/22×1、9/23×3），raw 存档于 `docs/handoff/郭侧-20260923/` |
| 实景效果已验证 | 真实抓拍样本的小样本评测 | ⬜ **尚未验证**；受控场地照片只做流程测试 |

演示标注义务：
- 预览/演示模式的所有草稿内容必须带「目标态演示」呈现级披露（页面横幅或口播），见契约附则 A；
- `demoRelease=true` 的候选**禁止**进入病例卡身份或任何核验结论；
- 部署环境**禁止**开启 `DEMO_RELEASE_UNVERIFIED`。

## 1. 项目与团队

| 成员 | 职责 |
|---|---|
| 郭浩天 | AI 接入、识别代理、物种卡数据、测试与部署边界 |
| 韦仕学 | Android、Insta360 相机链路、病例卡/医院目录/物种比对模块 |
| 舒豪杰 | 用户调研、设计与交付 |

产品链路：影石影像 → Android → 识别代理（候选+不确定性）→ 物种卡比对 → 本地病例信息卡 → 有来源的医院/求助入口。**识别可以不确定，记录与求助不能被它卡住。**

## 2. 目录结构

```text
android/snakesnap/        Android 业务 App（Insta360 SDK Demo 改造）
  .../demo/care/          病例信息卡（本地存储，noBackupFilesDir）
  .../demo/hospital/      医院目录（离线校验、复制、系统拨号界面）
  .../demo/species/       物种比对页（消费 data/species-cards.json）
inference/                FastAPI 识别代理 + HHodata Adapter + 预算账本
  cards.py                物种卡审核投影（normal/preview）与发布包导出
  card_preview/           纯前端离线演示页（7 种 mock 场景、审核开关）
  tests/                  71 项离线测试（unittest，无 pytest 依赖）
contracts/                识别通信契约（唯一 Interface）+ 7 组 fixtures
data/species.json         物种卡数据源（v2 schema，独立审核状态）
data/species-cards.json   客户端投影产物（Android/演示页消费）
data/card_images/         发布图管线产物（去 GPS、≤1280px、q85）
data/reference_images/    策展参考图原档 + meta + 候选留档（不打包发布）
docs/handoff/             交接文档与实测记录（郭侧-20260922 / 20260923）
```

## 3. 快速开始

### 3.1 Python 代理与离线回归

```bash
python -m venv .venv && source .venv/Scripts/activate   # Python 3.12
python -m pip install -r inference/requirements.txt
RECOGNITION_MODE=mock LIVE_CALL_LIMIT=0 python -B -m unittest discover -s inference/tests
# 期望：Ran 71 tests … OK；不产生任何供应商请求
```

### 3.2 本机 mock 代理

```bash
RECOGNITION_MODE=mock LIVE_CALL_LIMIT=0 HHODATA_API_KEY= PROXY_TOKEN= \
  python -B -m uvicorn inference.app:app --host 127.0.0.1 --port 8765
curl --noproxy '*' http://127.0.0.1:8765/healthz
# 期望：mode=mock、localCallLimit=0、localCallsUsed=0、demoReleaseUnverified=false
```

### 3.3 离线演示页

`python -m inference.cards --output <新空目录>` 生成自包含演示包（species-cards.json + 页面 + fixtures + 发布图 + SHA256SUMS），用静态服务器打开 `index.html`（勿双击）。页面只演示固定响应，**不调用供应商**。

### 3.4 演示 lane（路演专用）

```bash
DEMO_RELEASE_UNVERIFIED=1   # 其余同真实模式；healthz 会显示 demoReleaseUnverified=true
```
- 未核验名称以 `demoRelease=true / nameStatus=pending_review` 进入候选；演示页顶部出现「目标态演示」横幅；
- 义务与边界见**契约附则 A**：呈现级披露必须存在；候选不得进入病例卡身份；部署禁开；
- 真实模式（开关关闭）只放行 `verificationStatus=verified` 的名称映射。

### 3.5 Android（韦侧验收命令）

```bash
export ANDROID_SERIAL="<adb devices 序列号>"      # 真机+模拟器同在时必须指定
adb -s "$ANDROID_SERIAL" reverse tcp:8765 tcp:8765
"$GRADLE" :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.mockProxyUrl=http://127.0.0.1:8765
```
不传 `mockProxyUrl` 时外部 HTTP 集成测试**跳过而非记为通过**。仅有文件传输连接不代表已开 USB 调试。

## 4. 识别链路与预算

- 供应商：HHodata `/api/v2/dongniao`，蛇类使用已确认类别 `class=R`（非供应商默认 B）；
- 流程：上传返回 HTTP 202 + recognitionId → **单次手动 refresh**；不自动轮询、不自动重试；
- 预算账本：`LIVE_CALL_LIMIT`（0–50，默认 0）累计上限，持久化于 `LIVE_LEDGER_PATH`（默认 `.runtime/hhodata.sqlite3`）；重启不清零；同图去重命中回放 `resultSource=cache`；失败/超时不退款；
- `RAW_LOG_PATH`（可选）留存上游 raw 响应 jsonl，用于区分「门禁丢弃」与「上游空」；
- 配额口径：`quota=1` 免费是单独事实，**不能推广**为结果查询/失败/超时免费；最近快照 remaining=47（9/23，非实时）；本地账本累计 8；
- 百科端点：9/23 实测 R/M 类返回 `id error`（含供应商文档 M 示例）、B 类可用且无图片字段、自述 AI 整理——**覆盖与计费待供应商确认，不作永久结论**。

## 5. 物种卡系统（data/species.json v2）

| 字段 | 说明 |
|---|---|
| `verificationStatus` | 名称映射核验；真实模式仅 `verified` 进入候选 |
| `verificationRecord` | 核验人/时间/依据/范围；猪鼻蛇仅命名对应，含个体归属 conflict 记录 |
| `comparisonProfile.reviewStatus` | 文案审核；三要素 `reviewStatus+reviewedBy+reviewedAt` 齐才放行正常投影 |
| `referenceImages[].reviewStatus / sourceStatus` | 图片审核与来源闭合；未闭合连草稿演示也不出包 |
| `demoDraft` | 演示草稿标记；仅 mock/预览驱动 |
| Candidate 附加字段 | `demoRelease`、`nameStatus`（演示 lane 标注，extra=forbid 下显式声明） |

投影规则：正常投影=名称∧文案（图片独立门禁）；预览投影=全显示+「目标态演示」披露；`hiddenImageCount` 明示被门禁拦截的图片数，**不伪装**。

发布图管线约束（`inference/cards.py` 强制）：去 GPS/EXIF、最大边 1280px、JPEG q85、仅 `data/card_images/` 下 `.jpg`、输出目录必须为空。

## 6. 数据归属与署名

| 图 | 权利 | 状态 |
|---|---|---|
| 玉米蛇 01、猪鼻蛇 02 | 团队自有（2026-09-19 自贡调研实拍） | ✅ 来源闭合，可发布（署名行：团队自有） |
| 猪鼻蛇 01（cc0, k-simpkins）等 7 张网络图 | iNaturalist CC | ⬜ `sourceStatus=pending_review`：文件-照片编号-作者绑定未闭合，**暂不发布** |
| 颈棱蛇 01/02 | 旧观察页已确认指向其他物种 | ⛔ `rejectedSourcePage` 记录在案，**禁止按旧链接署名发布** |

**有署名 ≠ 审核通过 ≠ 独立验证了授权或物种。** 团队自有声明是既有交接声明，非权利或物种审核。

## 7. 测试矩阵

- Python 71 项：代理行为、预算保护、同图去重、7 场景状态码、映射回归（含 9/22 录制响应重放）、中文展示校验（4 例）、审核投影、发布图约束、演示 lane（4 例）、healthz 标志；
- Android（韦 9/23 验证）：JVM 55/55；Android 16 模拟器仪器 35/35（无跳过），含 HTTP 7 场景、pending 手动查询、回环明文策略、病例部分提交恢复、医院安全拨号 Intent；
- 人工操作验证：系统选图器选合成图、保存并重开带原图信息卡、未咬分支、医院空目录、复制号码；**未实际拨打电话**；
- 以上均为模拟器/模拟数据验证，**不是真机相机或真实识别验收**。

## 8. 真机与部署边界

真机：Xiaomi 13 Pro（2210132C）/ Android 16 / API 36；Insta360 X5 固件 v1.13.21。
已实测：SDK 连接、后镜头拍照、下载、JPEG 导出、系统选图、USB reverse 的 mock/pending 查询、相机 Wi-Fi 与蜂窝同开（修复下载误走蜂窝超时）。
**未验证**：公网代理、真实识别、全景取图、物理按键、长期稳定性（曾现 `-2110103` 断连后重连成功）。

部署约束：HTTPS + 受控凭证 + **单一固定持久化账本**；不用重启即丢账本的临时磁盘；不部署多账本副本；代理不保存图片与伤情；供应商留存条款待核实并须如实告知用户。

## 9. 安全

- 密钥只进进程环境变量（`HHODATA_API_KEY`、`PROXY_TOKEN`≥16 字符且不复用供应商密钥）；源码与 APK 不读取 `api/*.txt`；仓库与交付包不含密钥、账本、测试原图；
- 凭据一旦在聊天/工单明文出现即视为泄露，立即轮换；
- 上传前必须取得图片外发同意（`uploadConsent=true` 显式字段）；页面与 Adapter 均阻止未同意上传。

## 10. 已知未验证清单（持续更新）

实景准确率｜Android 真机闭环与相机 Wi-Fi/公网共存｜医疗处置效果｜供应商计费细则与图片留存｜百科 R 类覆盖｜个体物种归属（测试图片/猪鼻蛇.jpg：供应商首候选玉米蛇，待饲养盒确认）｜7 张网络图来源闭合｜医院目录有来源核验资料（生产列表当前为空）。

## 11. 协作约定

- `contracts/recognition-contract.md` 是通信约定**唯一**维护位置；改动前通知韦仕学；
- 郭侧可改 `inference/`、`contracts/`、`data/species.json` 与交接文档；保留 SDK 原归档与 `分类整理/备份勿动/`；
- 网络受限时经 Git Data API 推送须做碰撞检测与 blob 级校验；中文路径一律 `core.quotePath=false` 处理（历史事故见 `docs/handoff/郭侧-20260923/实测记录_20260923.md` 与提交 `84a3b8cc`/`2071f733`）；
- 交接索引：`docs/handoff/郭侧-20260922/`（骨架与打包）、`郭侧-20260923/`（实测、演示 lane、示意图）。

## 12. 下载与安装（v0.1-dev，韦侧）

从 GitHub Releases 下载 APK 与 SHA256（私有仓库需协作者权限）：
- APK 源码固定 `2e51bca7e2ec92b9838fd7d4f7efc73d9705742a`；文件名 `venomlens-v0.1-dev-2e51bca-debug.apk`；Android 10/API 29+，arm64-v8a；
- debug 签名预览包；应用 ID `com.insta360.kmpsdk.demo`，可能与官方 Demo 冲突；签名不匹配先备份勿直接卸载；
- Release 标签 `v0.1-dev` 与应用内 `versionName=2.1.5/versionCode=3` 不是同一版本标识；
- APK 278,256,962 字节（约 278.3 MB）；含 SDK 原生库与示例资源，本轮不裁剪；
- APK/SDK/构建产物不进 Git，经 Release 附件交付。

```bash
sha256sum -c SHA256SUMS.txt
adb install -r venomlens-v0.1-dev-2e51bca-debug.apk
```

## 13. 桌面入口说明

| 入口 | 用途 | 边界 |
|---|---|---|
| 一拍知蛇 / SnakeSnap | 原 Insta360 SDK Demo（MainActivity） | 真机连相机、预览/拍摄/图库/下载；真实相机能力尚未完成本项目验收 |
| Mock识别(开发) | 模拟识别联调页（MockRecognitionActivity） | 无相机选本地图、7 种固定响应、pending 手动查询；可跳转 SDK 入口 |

## 14. 署名与许可

- 代码与文档：参赛作品 © 两爬青年（2026）；Insta360 SDK 版权归属其权利人；
- 图片：团队实拍为团队自有；网络图按各自 CC 许可（作者与观察页见 `data/species.json.rights/sourcePage`），未闭合者不发布；
- 物种命名参考：自然观察社区（iNaturalist）taxa 页、The Reptile Database；科普文案种子注明来源且**未经人工审核不进入正常投影**。

## 15. 变更与交接索引

| 提交 | 内容 |
|---|---|
| `2e51bca` | SnakeSnap 识别原型集成（APK 源码基线） |
| `87895f26` | 双语 setup/preview 文档 |
| `bb926840`/`4b929ea5`/`be4c3886` | 郭侧：识别实测、审核投影与发布管线、species-cards.json |
| `84a3b8cc`/`2071f733` | 非法文件名修复（郭 API 修复 / 韦 rename，17:22 合并汇合） |
| `2508050e` | 韦侧：care/hospital/species 三模块 + 仪器测试集成 |
| `e7d59006` | 郭侧：演示 lane + 呈现级披露 + raw 留存（71 测试） |

详细过程见 `docs/handoff/`；本 README 与事实冲突时，以契约、测试与实测记录为准。
