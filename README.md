# VenomLens · 一拍知蛇

**简体中文** | [English](README.en.md)

影石 Bold Maker 2026 参赛原型：从 Insta360 相机获取照片，辅助查看蛇种候选，并计划提供伤情记录与就医信息。**当前是开发联调版本，不是可用于医疗判断的成品。**

> 模拟结果与照片无关；“未检测到蛇”不表示现场安全。候选与供应商原始分值不是物种确认或准确率，不能排除危险或替代医疗判断。

## 下载与安装

从 [GitHub Releases](https://github.com/weixueshi04/venomlens/releases) 下载 `v0.1-dev` 的 APK 和 SHA256 校验文件。仓库为私有，需要协作者权限。

- APK 源码固定为 **`2e51bca7e2ec92b9838fd7d4f7efc73d9705742a`**；后续 README 文档提交不包含在该 APK 中。
- 文件名：`venomlens-v0.1-dev-2e51bca-debug.apk`，Android 10 / API 29 及以上，**arm64-v8a**。
- 这是 debug 签名预览包，不是生产签名版本。应用 ID 为 `com.insta360.kmpsdk.demo`；可能与已安装的官方 Demo 冲突。签名不匹配时不要直接卸载丢失数据，请先备份。
- Release 标签为 `v0.1-dev`，应用内部仍沿用 Demo 的 `versionName=2.1.5`、`versionCode=3`，两者不是同一版本标识。
- 当前 APK 为 **278,256,962 字节（约 278.3 MB / 265.4 MiB）**，包含 SDK 原生库、算法与示例资源。本轮不裁剪 SDK；功能完成后再分析包体、精简界面并做回归验证。
- APK、SDK 压缩包和构建产物不进入 Git；安装包通过 Release 附件交付。

```bash
sha256sum -c SHA256SUMS.txt
adb install -r venomlens-v0.1-dev-2e51bca-debug.apk
```

## 为什么有两个桌面入口？

| 入口 | 用途 | 使用方式 |
| --- | --- | --- |
| **一拍知蛇**（英文系统为 **SnakeSnap**） | 原 Insta360 SDK Demo（`MainActivity`） | 真机侧连接相机，检查预览、拍摄、图库与下载；这些真实相机能力尚未完成本项目真机验收。 |
| **Mock识别(开发)** | 模拟识别联调页（`MockRecognitionActivity`） | 无需相机即可选本地图、运行 7 种固定响应、验证 pending 手动查询。页面内可跳转到 SDK 入口。 |

两个入口属于**同一个 APK**，不是两个独立应用，尚未合并为最终业务主流程。

### 模拟页怎么用

1. 打开 **Mock识别(开发)**，确认顶部的红色 MOCK 提示。
2. 可选“从本地选择照片”：本地校正方向、JPEG 重编码、去除 EXIF/GPS，并检查 2,000,000 字节限制。**选图不会上传，也不会影响模拟结果。**
3. 点击任一场景：单候选、多个候选、无法判断、未检测到蛇、处理中、上游超时、模型输出违规。
4. 选择“识别处理中（202）”，等待“识别任务”出现；只有点击 **手动查询一次** 才执行一次查询，不自动轮询。
5. 请求中可取消；旋转/重建页面不会让旧响应覆盖新页面。

## 已实现与尚未完成

| 模块 | 当前边界 |
| --- | --- |
| 本地图与 mock 页面 | 已实现；固定样例验证展示与交互，不代表实景识别准确率。 |
| Android HTTP Adapter | 已实现上传同意字段、Bearer token、200/202、手动 refresh、安全错误消息、超时和取消；**尚未接入页面，也未打通相机拍照后的识别流程**。 |
| FastAPI 识别代理 | HHodata adapter、图片清理、物种映射、SQLite 预算账本、去重与缓存；默认 mock，真实调用预算为 0。 |
| 病例卡 / 医院页 | **尚未实现**，不是现有 SDK 设置页或图库页。 |
| 真实相机与联网 | 手机/相机型号和固件待记录；连接、拍摄、下载、相机 Wi-Fi 下访问代理均待真机验证。 |

本轮交付仅为指定提交的安装包和项目说明，不修改 HTTP、病例卡或医院页代码。后续应先对齐文件责任与验收范围，再安排这些功能的交付时间。

## 验证不能混为一谈

- **构建/单元测试**：本次独立快照构建通过，Android JVM **28/28**、Python **48/48** 通过（未调用真实供应商）。它们证明源码可编译、隔离逻辑符合测试断言；不代表设备链路可用。
- **模拟器运行**：本次 `2e51bca` 独立快照重建后，在 `Medium_Phone_API_36.1`（Android 16 / API 36.1）重新完成仪器测试 **10/10**，包含 7 场景、pending 手动查询、取消、Activity 重建与图片处理。模拟器依靠 ABI 翻译运行，不代表真实相机兼容性。
- **选图系统交互**：图片处理测试不等于“点击系统选图器并选图”的完整验收；该流程的录屏和人工实测单独交付。
- **真机运行**：当前没有可报告的相机连接/拍摄/下载或真实识别通过记录。相机型号/固件、手机型号/系统与网络结果必须实测填写，不能从编译结果推断。
- 每个 APK 的重建、测试、大小与 SHA256 以对应 Release 说明为准；录屏未随本次安装包交付。

## 开发与构建

### Android

- AGP **8.7.3**，Kotlin **2.3.20**，compile/target SDK **35**，min SDK **29**，Insta360 Camera/Media SDK **2.1.5**。
- 本机验证工具链：Android Studio **JBR 21**（Java/Kotlin 编译目标 17），**Gradle 8.12**。
- 仓库 wrapper 仍为 **8.11.1**。由于本机下载 wrapper 超时，实际成功构建使用已缓存的 8.12；没有把版本替换隐瞒成 wrapper 成功。

将 `android/snakesnap/local.properties.example` 复制为同目录的 `local.properties`，填写本机 `sdk.dir` 及通过官方授权资料获得的 Maven 凭据。该文件被 Git 忽略。

也可在本机环境中设置 `INSTA360_MAVEN_USERNAME` / `INSTA360_MAVEN_PASSWORD`；它们优先于 `local.properties` 的 `insta360.maven.username` / `insta360.maven.password`。**不把真实值粘贴到 README、APK 配置、提交或日志中。**

以下为本机实际采用的 Git Bash 工具链命令；其他机器替换路径。从源码快照或仓库的 `android/snakesnap` 目录执行：

```bash
export JAVA_HOME="E:/Android Studio/jbr"
export ANDROID_HOME="C:/Users/WEIXUESHI/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE="C:/Users/WEIXUESHI/.gradle/wrapper/dists/gradle-8.12-all/ejduaidbjup3bmmkhw3rie4zb/gradle-8.12/bin/gradle.bat"
"$GRADLE" :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest \
  --no-build-cache --rerun-tasks --offline --console=plain --max-workers=2 --no-daemon
```

`--offline` 只适用于依赖已经缓存的机器；首次构建去掉该参数并确保 Maven 可访问。可下载仓库 wrapper 时可用 `bash ./gradlew`（Windows 原生终端用 `gradlew.bat`）代替 Gradle 可执行文件，但这会使用 **8.11.1**，不是上述已验证的 **8.12**。

输出：`android/snakesnap/app/build/outputs/apk/debug/app-debug.apk`。运行设备测试需先连接设备，再执行 `:app:connectedDebugAndroidTest`；Espresso 建议关闭测试设备动画。

### Python 代理（仅 mock）

使用 Python 3.11 环境，从仓库根目录执行；已有 Conda 环境也可直接使用：

```bash
python -m pip install -r inference/requirements.txt
export RECOGNITION_MODE=mock
export LIVE_CALL_LIMIT=0
export HHODATA_API_KEY=
export PROXY_TOKEN=
python -m unittest discover -s inference/tests -v
python -m uvicorn inference.app:app --host 127.0.0.1 --port 8765
```

`.env.example` 仅是模板，应用**不会自动加载 `.env`**。上述服务仅监听本机；无需真实 key，测试不消耗供应商预算。开启 live 前必须另行授权、确认图片上传同意、代理鉴权与持久预算账本；禁止通过换账本、改 requestId 等方式绕过预算或去重。

### 真机拓扑

```text
电脑 ── USB / ADB（安装、调试）── Android 手机 ── Wi-Fi / SDK ── Insta360 相机
```

相机 SDK 在手机上运行，Python 代理不控制相机。后续本地 HTTP 联调可测试 `adb reverse tcp:8765 tcp:8765`，但当前 mock 页面不会因此切到 HTTP。相机 Wi-Fi 可能无互联网，SDK 还可能绑定进程网络；回环转发或独立移动数据路由是否可用必须实测，公网 HTTPS 并不自动解决路由问题。非回环代理地址必须使用 HTTPS。

## 目录与契约

- [`android/snakesnap/`](android/snakesnap/)：Android SDK Demo、识别 adapter、mock 页面及测试。
- [`inference/`](inference/)：FastAPI 代理与离线回归测试。
- [`contracts/recognition-contract.md`](contracts/recognition-contract.md)：**当前接口依据**；[`contracts/fixtures/`](contracts/fixtures/) 提供 7 个样例。
- [`data/species.json`](data/species.json)：带审核状态的物种映射，不是医疗知识库。
- [`docs/handoff/`](docs/handoff/)：交接记录；[`PLAN.md`](PLAN.md)：开发计划。

`docs/archive/` 与 `开发文档/` 中可能保留旧契约，不应覆盖正式契约。188 张蛇图只计划用于验证，不训练模型，也不是开发前置条件。

## 协作与后续优化

优先完成可验证的业务闭环；未获授权不调用真实供应商。Android 业务、推理代理和契约变更应先约定负责人与接口边界，不把本说明当作限制队友修改权限的决定。

功能完成后再根据 APK Analyzer 的实际占比精简 SDK 示例资源、无用能力与界面入口，并验证连接、拍摄、下载和识别回归。本次不提前调整 ABI、混淆或裁剪规则。SDK 与第三方资源仍受各自许可约束，本仓库不额外授予其使用或再分发权。
