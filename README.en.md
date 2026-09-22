# VenomLens · 一拍知蛇

[简体中文](README.md) | **English**

A prototype for Insta360 Bold Maker 2026: obtain camera photos, present snake-species candidates, and eventually provide injury records and care information. **This is a development build, not a finished medical product.**

> Mock results are unrelated to the selected photo. “No snake detected” does not establish safety. Candidates and raw provider scores are neither confirmed identification nor accuracy estimates; they cannot rule out danger or replace medical judgment.

## Download and install

Download the `v0.1-dev` APK and SHA256 file from [GitHub Releases](https://github.com/weixueshi04/venomlens/releases). This repository is private; collaborator access is required.

- APK source is pinned to **`2e51bca7e2ec92b9838fd7d4f7efc73d9705742a`**. Later README-only commits are not part of this APK.
- Asset: `venomlens-v0.1-dev-2e51bca-debug.apk`. Requires Android 10 / API 29 or newer and **arm64-v8a**.
- This is a debug-signed preview, not a production-signed build. Application ID: `com.insta360.kmpsdk.demo`. It may conflict with an installed official SDK demo. Back up data before resolving a signing conflict; do not blindly uninstall.
- The Release tag is `v0.1-dev`, while the app retains the demo's `versionName=2.1.5` and `versionCode=3`. These are different version identifiers.
- The APK is **278,256,962 bytes (about 278.3 MB / 265.4 MiB)**, including SDK native libraries, algorithm assets, and sample resources. No SDK trimming is included in this release; size and UI cleanup follow functional completion and regression testing.
- APKs, SDK archives, and build outputs are excluded from Git. Installable builds are delivered as Release assets.

```bash
sha256sum -c SHA256SUMS.txt
adb install -r venomlens-v0.1-dev-2e51bca-debug.apk
```

## Why are there two launcher icons?

| Launcher | Purpose | How to use it |
| --- | --- | --- |
| **一拍知蛇** (**SnakeSnap** on English-language devices) | Original Insta360 SDK demo (`MainActivity`) | Connect a camera on a physical phone and inspect preview, capture, gallery, and downloads. The project has not completed physical-camera acceptance testing. |
| **Mock识别(开发)** | Mock recognition page (`MockRecognitionActivity`) | Select a local photo, run seven fixed responses, and manually refresh pending results without a camera. A button opens the SDK entry point. |

Both launchers belong to **one APK**, not two separate apps. They have not yet been merged into a single product workflow.

### Try the mock page

1. Open **Mock识别(开发)** and check the red MOCK warning.
2. Optionally choose **从本地选择照片**: the app corrects orientation, re-encodes JPEG, removes EXIF/GPS, and checks the 2,000,000-byte limit locally. **Selecting a photo does not upload it or change the mock result.**
3. Choose a scenario: one candidate, multiple candidates, uncertain, no snake, pending, upstream timeout, or invalid model output.
4. Select **识别处理中（202）** and wait for **识别任务** (recognition ID). Only pressing **手动查询一次** triggers one refresh; there is no automatic polling.
5. Requests can be cancelled. Activity recreation does not let an old response overwrite the new page.

## Implemented versus unfinished

| Module | Current boundary |
| --- | --- |
| Local images and mock UI | Implemented; fixed fixtures validate presentation and interaction, not real-world identification accuracy. |
| Android HTTP adapter | Implements consent, Bearer token, 200/202 responses, manual refresh, safe errors, timeout, and cancellation. **Not connected to the UI or a camera-to-recognition workflow yet.** |
| FastAPI proxy | HHodata adapter, image sanitization, species mapping, SQLite budget ledger, deduplication, and caching. Defaults to mock with a live-call budget of zero. |
| Injury card / hospital page | **Not implemented**; SDK settings or gallery screens are not substitutes. |
| Physical camera and networking | Phone/camera models and firmware remain unrecorded. Connection, capture, download, and proxy access over camera Wi-Fi await physical-device testing. |

This delivery contains the pinned build and documentation only; it does not modify HTTP, injury-card, or hospital-page code. Agree on ownership and acceptance criteria before scheduling that work.

## Distinguish verification levels

- **Build/unit tests:** this isolated snapshot built successfully; Android JVM **28/28** and Python **48/48** passed without live-provider calls. These verify compilation and isolated assertions, not an operational device pipeline.
- **Emulator execution:** after this isolated rebuild of `2e51bca`, instrumentation passed **10/10** again on `Medium_Phone_API_36.1` (Android 16 / API 36.1), covering seven scenarios, manual pending refresh, cancellation, Activity recreation, and image processing. ABI translation is used; this is not proof of physical-camera compatibility.
- **System image picker:** image-processing tests do not verify the complete system-picker interaction. A recording and manual check of selecting a photo are separate deliverables.
- **Physical devices:** there is no verified camera connection/capture/download or live-recognition result to report. Record actual phone/OS, camera/firmware, and networking results rather than inferring success from compilation.
- Each Release lists the rebuild, tests, size, and SHA256 for that APK. No screen recording is included with this delivery.

## Development and build

### Android

- AGP **8.7.3**, Kotlin **2.3.20**, compile/target SDK **35**, min SDK **29**, Insta360 Camera/Media SDK **2.1.5**.
- Locally verified toolchain: Android Studio **JBR 21** (Java/Kotlin target 17), **Gradle 8.12**.
- The repository wrapper remains **8.11.1**. Its download timed out locally, so successful builds used cached 8.12 directly; wrapper success is not being claimed.

Copy `android/snakesnap/local.properties.example` to `local.properties` in the same directory. Fill in your `sdk.dir` and Maven credentials obtained through authorized Insta360 documentation. Git ignores this file.

Alternatively set `INSTA360_MAVEN_USERNAME` / `INSTA360_MAVEN_PASSWORD` locally. They override `insta360.maven.username` / `insta360.maven.password` in `local.properties`. **Never paste actual values into documentation, APK configuration, commits, or logs.**

The following Git Bash command uses the actual local toolchain. Replace paths on other machines and run from `android/snakesnap` in the source snapshot or repository:

```bash
export JAVA_HOME="E:/Android Studio/jbr"
export ANDROID_HOME="C:/Users/WEIXUESHI/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE="C:/Users/WEIXUESHI/.gradle/wrapper/dists/gradle-8.12-all/ejduaidbjup3bmmkhw3rie4zb/gradle-8.12/bin/gradle.bat"
"$GRADLE" :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest \
  --no-build-cache --rerun-tasks --offline --console=plain --max-workers=2 --no-daemon
```

`--offline` requires already-cached dependencies. Omit it for a first build and ensure Maven is reachable. If the repository wrapper can be downloaded, `bash ./gradlew` (`gradlew.bat` in a native Windows terminal) can replace the Gradle executable, but uses **8.11.1**, not the verified **8.12** above.

Output: `android/snakesnap/app/build/outputs/apk/debug/app-debug.apk`. Device tests require a connected device and `:app:connectedDebugAndroidTest`; Espresso recommends disabling animations on the test device.

### Python proxy (mock only)

Use a Python 3.11 environment and run from the repository root. An existing Conda environment is also suitable:

```bash
python -m pip install -r inference/requirements.txt
export RECOGNITION_MODE=mock
export LIVE_CALL_LIMIT=0
export HHODATA_API_KEY=
export PROXY_TOKEN=
python -m unittest discover -s inference/tests -v
python -m uvicorn inference.app:app --host 127.0.0.1 --port 8765
```

`.env.example` is a template; the app **does not automatically load `.env`**. This command binds only to loopback, needs no live key, and tests spend no provider budget. Live use requires separate authorization, upload consent, proxy authentication, and a persistent budget ledger. Never bypass budget or deduplication by replacing the ledger or changing request IDs.

### Physical-device topology

```text
Computer ── USB / ADB (install/debug) ── Android phone ── Wi-Fi / SDK ── Insta360 camera
```

The camera SDK runs on the phone; Python does not control the camera. Future local HTTP testing can try `adb reverse tcp:8765 tcp:8765`, but this does not switch the current mock UI to HTTP. Camera Wi-Fi may lack internet access and the SDK may bind the process network. Loopback forwarding or separate cellular routing must be tested; public HTTPS does not fix routing by itself. Non-loopback proxy addresses require HTTPS.

## Layout and contract

- [`android/snakesnap/`](android/snakesnap/): Android SDK demo, recognition adapters, mock UI, and tests.
- [`inference/`](inference/): FastAPI proxy and offline regression tests.
- [`contracts/recognition-contract.md`](contracts/recognition-contract.md): **current interface authority**; [`contracts/fixtures/`](contracts/fixtures/) contains seven fixtures.
- [`data/species.json`](data/species.json): species mappings with review status, not a medical knowledge base.
- [`docs/handoff/`](docs/handoff/): handoff records; [`PLAN.md`](PLAN.md): development plan.

`docs/archive/` and `开发文档/` may contain older contracts; they must not override the current contract. The 188 snake images are for validation only, not training or a prerequisite for development.

## Collaboration and later optimization

Prioritize a verifiable end-to-end workflow; do not call the live provider without authorization. Agree on Android, proxy, and contract ownership before overlapping edits; this README does not assign or restrict teammate permissions.

After functional completion, use APK Analyzer to measure actual contributions before removing unused demo resources, capabilities, and launcher clutter. Recheck connection, capture, download, and recognition afterward. This delivery does not change ABI, obfuscation, or trimming rules. SDK and third-party resources remain subject to their respective licenses; this repository grants no additional rights to use or redistribute them.
