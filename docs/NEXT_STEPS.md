# VenomLens 接管开发计划（2026-09-23 起）

> 接管基线：本地工作区（34 项未提交变更），远端 origin/main = `87895f2`
> 唯一事实源：`E:\insta\venomlens_怕草绳`（根目录 `E:\insta\android`、`E:\insta\inference` 为过时旧副本，禁止在其上开发）

## 阶段 0：止损与基线固化（立即，半天内）

| # | 任务 | 说明 | 验收 |
|---|---|---|---|
| 0.1 | **提交并推送 34 项未提交变更** | 病例卡（care/）、医院（hospital/）、物种比对（species/）、HttpMock 测试、species.json 更新等全部只存在于本地 | GitHub main 包含全部 9/23 成果 |
| 0.2 | 跑通全量回归并记录 | Android JVM 55 项 + Python 离线 50 项（conda 环境 `insta360-reptile`）| 日志存 `logs/`，结果写入提交说明 |
| 0.3 | 清理根目录旧副本 | 归档或删除 `E:\insta\android`、`E:\insta\inference` 等旧文件，避免误改 | 工作区只保留仓库一份代码 |
| 0.4 | 修复交接文件非法 Git 路径 | `docs/handoff/` 7 份文件的路径问题（总结中遗留债务）| 文件可正常提交、跨平台检出 |

## 阶段 1：演示前必做（优先级 1）

| # | 任务 | 说明 | 验收 |
|---|---|---|---|
| 1.1 | **补充医院数据** | 当前 `assets/hospitals.json` 为空。选定演示城市，搜集有来源可追溯的蛇伤救治医院条目（舒负责搜集、韦校验），建立 `data/hospitals.json` → assets 的同步校验脚本 | 至少 1 个演示城市 ≥5 条带来源的条目，离线校验通过 |
| 1.2 | **物种比对资料复核** | 解决颈棱蛇 2 张图文件名与观察页记录冲突；全量核对 species.json 图片引用、署名、来源页 | 冲突清零，species.json 校验脚本通过 |
| 1.3 | **真机端到端验收** | Xiaomi 13 Pro + Insta360 X5：相机连接 → 拍照 → 下载 → mock 识别 → 病例卡保存 → 医院页拨号，全程录像 | T01 测试记录归档 `docs/`，APK + 录像入 `deliverables/` |

## 阶段 2：演示后工程化（优先级 2）

| # | 任务 | 说明 |
|---|---|---|
| 2.1 | 打通 HTTP Adapter 真实链路 | 相机拍照后走 `HttpRecognitionAdapter`（现仅 mock 入口验证过）；含相机 Wi-Fi 热点下访问代理的网络方案（USB reverse 或局域网）|
| 2.2 | 应用 ID 重命名 | `com.insta360.kmpsdk.demo` 与官方 Demo 冲突，改为独立包名（涉及 manifest、测试、签名配置）|
| 2.3 | APK 瘦身 | 278 MB → 分析 SDK 原生库/算法/示例资源占比，abi split + 资源裁剪，目标 <100 MB |
| 2.4 | Git 历史合并 | 队友 34 个文件已按 blob SHA 导入但分支历史未合并，评估是否补合并或记录为快照导入 |

## 阶段 3：长期（优先级 3，需外部授权）

| # | 任务 | 前置条件 |
|---|---|---|
| 3.1 | 接入 HHodata 真实识别 | API key、PROXY_TOKEN（≥16 字符）、LIVE_CALL_LIMIT 预算授权、合规审查；代理已具备 SQLite 账本防绕过 |
| 3.2 | 医疗文案审核 | 专业医疗人员复核所有页面文案（现均为"不诊断"免责标注）|
| 3.3 | 实景效果评估 | 188 张蛇图仅作验证集，评估供应商候选命中率 |

## 构建环境速查

```bash
# Android 构建（Gradle 8.12 缓存，wrapper 8.11.1 下载超时勿用）
cd android/snakesnap && JAVA_HOME="/e/Android Studio/jbr" \
  "/c/Users/WEIXUESHI/.gradle/wrapper/dists/gradle-8.12-all/ejduaidbjup3bmmkhw3rie4zb/gradle-8.12/bin/gradle.bat" \
  assembleDebug --console=plain

# Python 代理测试
conda activate insta360-reptile && cd inference && python -m pytest tests/

# 模拟器（本机 Vulkan 报错，需软渲染）
emulator -avd Medium_Phone_API_36.1 -gpu swiftshader_indirect -feature -Vulkan
```

## 安全红线（继承自原团队，不得突破）

- 不生成诊断结论；候选不显示为准确率
- 原图不上传、不自动定位、病例卡仅存 `noBackupFilesDir`
- 代理仅监听 127.0.0.1；真实调用预算默认 0，需人工单次触发
- 所有 mock 界面保留红色 MOCK 标注
