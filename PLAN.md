# BoldMaker2026「一拍知蛇」— 本地开发规划（E:\insta）

> 基准：2026-09-22 ~17:00｜硬截止：9/23 24:00（赛道三）
> 总规划见微信归档 `BoldMaker2026_模块化并行开发规划.md`，本文档只管本机落地。

## 1. 环境核查结论（2026-09-22 实测）

| 项 | 状态 | 位置/版本 |
|---|---|---|
| Android SDK | ✅ | `C:\Users\WEIXUESHI\AppData\Local\Android\Sdk`，platforms 33–36，build-tools 35.0.0/36.1.0 |
| Android Studio | ✅ | `E:\Android Studio`，自带 JBR 21.0.8（无独立 JDK 17；JBR 21 可编译 jvmTarget 17 工程） |
| adb | ✅ | `C:\platform-tools\adb` v36.0.0（当前无设备连接） |
| Gradle | 已验证 | Wrapper 8.11.1 首次下载超时；用本机缓存 Gradle 8.12 实际构建原始 Demo 与业务 App 成功 |
| conda 影石环境 | ✅ | `E:\conda-envs\insta360-reptile`（Python 3.11.16）：torch 2.6.0+cu126、ultralytics 8.4.157、openai 3.16.2、fastapi/uvicorn、opencv、onnxruntime、gradio |
| GPU | ✅ | RTX 4060 Laptop 8GB，驱动 595.79，CUDA 可用 |
| 磁盘 | ✅ | C: 97G / D: 149G / E: 76G 可用 |
| SDK 压缩包 | ✅ | `D:\Edgedownload\Android-SDK-2.1.5.zip`（已解压到 `E:\insta\SDK\`）|
| 影石 Maven 仓库 | ✅ | 凭据从已忽略的 local.properties 或环境变量注入；阿里云镜像与公网直连可用 |
| CameraSDK_MediaSDK.zip | ⏭️ 跳过 | 2.7GB 桌面（Win/Linux）包，Android 用不到，不解压 |
| 待补 | 未联调 | 真机（Android 10+ arm64）与相机实体；HHodata 代理已交接但默认 mock／真实预算 0；188 张蛇图仅作验证，不训练，当前视为不可用，不阻塞开发 |

## 2. 项目结构

```text
E:\insta\
  PLAN.md                 # 本文档
  android/                # 业务 App（从 SDK Demo 改造，M1/M4/M5）
  inference/              # 郭：FastAPI + HHodata 代理，默认 mock／真实预算 0
  contracts/              # 正式识别契约 + 7 份模拟响应（郭维护，韦确认）
    recognition-contract.md
    fixtures/             # 7 份固定响应，供代理与 Android 联调
  data/
    species.json          # 郭：物种知识库
    hospitals.json        # 舒搜集、韦校验接入（演示城市少量可追溯条目）
  deliverables/           # APK、视频、PPT、BOM、测试记录
  docs/                   # 过程文档、测试记录 T01–T12
  SDK/                    # 影石原始 SDK（Android-SDK-2.1.5 已解压；zip 原件在 D:\Edgedownload）
```

## 3. 我的任务（韦：Android 关键路径）

- M1 影像采集：复用 Demo 连接/拍摄/下载（`CameraCaptureViewModel.kt` 拍摄回调、`GalleryViewModel.kt` 下载流程），不重写。
- 采集 Seam：相机采集 Adapter ↔ 本地导入 Adapter（导入用于开发和标注过的降级演示）。
- M4 交互与病例卡：结果页、咬伤/未咬伤分支、本地病例卡。
- M5 医院查询：`HospitalDirectory.list(location?)`，复制/拨号，离线可读。
- 识别 Seam：HTTP 真实 Adapter ↔ mock Adapter（显著标注模拟模式）。

## 4. 识别策略结论（demo 优先）

**采用 HHodata 爬行类识别代理；不上 VLA、不自训练小模型、不配置自动模型兜底。**

- FastAPI 代理按正式契约接收 JPEG，调用 HHodata `class=R`，输出最多 3 个本地已核验物种候选。
- 默认 `mock`、真实调用预算 0；真实模式要求上传同意、代理令牌、持久预算账本与人工单次查询。
- 供应商原始分值保留为 `providerScore`，含义未经确认，不显示成准确率或百分比。
- `pending` 只允许人工 refresh，不自动轮询、重试或切换其他模型。
- 188 张蛇图只用于后续验证，不训练；受控样本不能替代实景效果评估。

## 5. 今晚检查点

| 时间 | 事项 |
|---|---|
| 18:30 | Demo 构建成功 + 装真机；相机按键链路/机型支持决策（不行就 App 按钮拍摄，如实标注） |
| 21:00–22:00 | 首次端到端联调（真实图片→真实推理→病例卡） |
| 22:00 前 | 保存可运行 APK + 联调录像 |

---

## 6. 开发环境关键坑（9/22 记录）

- **Gradle wrapper 首次下载超时**：gradle-8.11.1-bin.zip 首次下载触发 10s 读取超时，并非证明每次必失败。现用完整缓存的 gradle-8.12-all（`C:\Users\WEIXUESHI\.gradle\wrapper\dists\gradle-8.12-all\ejduaidbjup3bmmkhw3rie4zb\gradle-8.12`）的 bin/gradle.bat；原始 Demo 已实际 BUILD SUCCESSFUL（logs/demo-build.log），保持 wrapper 配置不变。
- 构建命令模板：`cd <工程> && JAVA_HOME="/e/Android Studio/jbr" <gradle-8.12 路径>/gradle.bat assembleDebug --console=plain`
- 构建日志统一写 `/e/insta/logs/*.log`，勿用 tail 管道（缓冲导致看不到输出）。

## 7. 当前进度（9/22）

- `android/snakesnap/`：Demo 清洁副本，rootProject.name=snakesnap，包名暂保持 com.insta360.kmpsdk.demo（为速度不改包）。
- 郭侧 FastAPI + HHodata 代理已合入 `inference/`：默认 mock／预算 0，具备上传同意、代理鉴权、2 MB 图片校验、EXIF 清理、SQLite 预算与去重、pending 手动查询；48 项离线测试通过且未访问供应商。
- Android `recognition/` 已同步正式契约：`resultSource`、`recognitionId`、`providerScore`、HTTP 202/pending、手动 refresh、扩展错误码和 2,000,000 字节限制。
- okhttp 4.12.0 已加入依赖；7 个正式 fixture 已同步到 `contracts/fixtures/` 与 `app/src/main/assets/mock/`。
- MockRecognitionActivity 为第二启动入口，红色 MOCK 标注；支持系统选图、方向校正、JPEG 重编码、7 场景展示及 pending → 人工查询演示。
- 验证：原始 SDK Demo 与业务 APK 均构建成功；最新 Android JVM 测试与仪器测试编译通过，Python 代理 48 项离线测试通过；仍需重新执行模拟器 UI 回归。
- 现有 AVD 为 Medium_Phone_API_36.1（x86_64，实测报告 arm64-v8a 翻译支持），已安装启动业务 App。Windows x86 宿主不能原生运行 ARM AVD；此前“只是慢”的说法不准确。
- 模拟器启动需本进程 ANDROID_HOME/ANDROID_SDK_ROOT 指向实际 SDK；本机 Vulkan 启动报错，实测使用 -gpu swiftshader_indirect -feature -Vulkan 成功，不改全局环境变量和 AVD 配置。
- 交付 APK：deliverables/snakesnap-dev-20260922.apk；人工选图截图：logs/snakesnap-import.png。
- 待办：病例卡／医院业务页、真机相机 T01、相机热点下访问受控代理、真实调用授权与实景效果验证。Android 只连接团队代理，不在 APK 中保存供应商密钥。
