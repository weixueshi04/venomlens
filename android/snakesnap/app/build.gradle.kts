import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
}

/**
 * 真实识别代理配置（HHodata）注入点：
 * 只从 local.properties（已在 .gitignore 内）或环境变量读取，默认空串。
 * 密钥绝不进仓库、绝不硬编码进源码；空串 → App 运行时降级为本地 MOCK。
 */
val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(::load)
}

fun recognitionConfig(envName: String, propertyKey: String): String =
    providers.environmentVariable(envName).orNull
        ?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(propertyKey)?.trim().orEmpty()

val recognitionProxyBaseUrl = recognitionConfig("RECOGNITION_PROXY_BASE_URL", "recognition.proxy.baseUrl")
val recognitionProxyToken = recognitionConfig("RECOGNITION_PROXY_TOKEN", "recognition.proxy.token")

/** buildConfigField 的字符串值需要二次转义：反斜杠与引号。 */
fun asJavaStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val repositoryRoot = rootProject.file("../..")
val speciesCatalogFile = repositoryRoot.resolve("data/species.json")
val cardImageRoot = repositoryRoot.resolve("data/card_images")

/**
 * 物种图片两层结构（郭侧 2026-09-23 重构，见 inference/cards.py 的 export_bundle）：
 * - `data/reference_images/<speciesId>/…`：原始图，带 EXIF，权利与来源记录在同目录 meta.json，不随包发布；
 * - `data/card_images/<speciesId>/NN.jpg`：脱敏发布图（去 EXIF、最长边 ≤1280、仅 jpg），APK assets 只打包这一层。
 *
 * 因此新版 species.json 的 `referenceImages[].file` 一律形如 `data/card_images/<speciesId>/NN.jpg`，
 * 而 meta.json 的 `referenceImages[].file` 仍是原始图相对文件名（如 `ref_01_head_coiled.jpg`）。
 * 两侧映射由 meta.json 的 `publishedFile` 字段显式给出（其值 == species.json 的 `file`），
 * species.json 侧另有 `originalFile` 指回原始图，可双向印证，无需依赖数组顺序或图片内容猜测。
 * 已实测：当前 9 张发布图全部能唯一映射，且实际 sha256 与两处登记的 publishedSha256 完全一致。
 *
 * 校验策略决策（保留原逻辑的全部意图，不删校验，只换映射方式并加强）：
 * 1. 路径白名单：仅接受本物种 `data/card_images/<speciesId>/` 下的 `.jpg`；正则拒绝目录穿越、
 *    绝对路径、URL 与其他后缀，另用规范化路径二次确认没有逃出该目录，并确认文件真实存在。
 * 2. 署名齐备：`rights` 与 `source` 缺任一项直接让构建失败（数据契约违背，须修数据而非放行）。
 * 3. 署名一致性：仍与 meta.json 原始记录逐字段核对 `rights`/`source`/`sourcePage`/`photoId`；
 *    匹配键由旧的「同目录同名文件」改为「`publishedFile` == `file`」，并要求 `originalFile` 的基名
 *    等于 meta 记录的 `file`，两个字段互证以避免错配到别的照片。
 *    任一字段不一致即判为待复核：不打包，App 侧 fail-closed 只显示占位文案。
 *    注：pseudagkistrodon_rudis 两张图因 species.json 主动撤下 sourcePage/photoId 并记录
 *    sourceConflict（观察编号与 meta.json 不一致），在此规则下判为不一致，与重构前行为一致。
 * 4. 内容级校验（比原逻辑更强）：实际计算发布图字节的 sha256，必须同时等于 species.json 与
 *    meta.json 登记的 `publishedSha256`，确保打进 APK 的就是已登记审核的那一版，而不只是元数据碰巧相同。
 * 5. 脱敏校验：扫描 JPEG 标记，残留 APP1(EXIF/XMP) 段的图片不打包，防止拍摄者与定位信息随 APK 外泄。
 *    与 cards.py 的发布约束（仅 card_images、仅 .jpg）保持一致。
 */
// 不用 String.format("%02x") 以免受默认 locale 影响得到本地化数字，导致与登记值比对失败
val hexDigits = "0123456789abcdef".toCharArray()

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    val builder = StringBuilder(digest.size * 2)
    digest.forEach {
        builder.append(hexDigits[(it.toInt() shr 4) and 0x0F]).append(hexDigits[it.toInt() and 0x0F])
    }
    return builder.toString()
}

/** 规范化路径后确认 candidate 确实位于 parent 之内，防目录穿越与符号链接逃逸。 */
fun isInside(parent: File, candidate: File): Boolean =
    candidate.canonicalFile.toPath().startsWith(parent.canonicalFile.toPath())

/**
 * 判断 JPEG 是否残留 APP1(EXIF/XMP) 段。只扫描标记、不解码像素，因此不受色彩空间等影响。
 * 非 JPEG 或结构异常一律返回 true（视为不可信、不打包），保持 fail-closed。
 */
fun hasExifSegment(bytes: ByteArray): Boolean {
    val soi = 0xFF.toByte()
    if (bytes.size < 4 || bytes[0] != soi || bytes[1] != 0xD8.toByte()) return true
    var index = 2
    while (index + 4 <= bytes.size) {
        if (bytes[index] != soi) return true
        while (index + 1 < bytes.size && bytes[index + 1] == soi) index++
        if (index + 1 >= bytes.size) return true
        val marker = bytes[index + 1].toInt() and 0xFF
        // 独立标记（无长度字段）：RSTn、TEM；SOI 不应重复出现
        if (marker == 0x01 || marker in 0xD0..0xD7) { index += 2; continue }
        // SOS 之后是压缩数据，D9 是 EOI：EXIF 段只可能出现在这之前
        if (marker == 0xDA || marker == 0xD9) return false
        val length = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
        if (length < 2 || index + 2 + length > bytes.size) return true
        if (marker == 0xE1) return true
        index += 2 + length
    }
    return true
}

val speciesRows = JsonSlurper().parse(speciesCatalogFile.reader(Charsets.UTF_8)) as List<*>
val referenceMetadataFiles = mutableListOf<File>()
val bundledImageFiles = mutableListOf<File>()
val bundledImagePaths = mutableListOf<String>()
speciesRows.forEach { row ->
    val species = row as Map<*, *>
    val id = species["speciesId"] as String
    require(id.matches(Regex("[a-z0-9_]+")))
    val references = (species["referenceImages"] as? List<*>).orEmpty()
    val metadataFile = repositoryRoot.resolve("data/reference_images/$id/meta.json")
    val metadata = if (references.isNotEmpty()) {
        referenceMetadataFiles += metadataFile
        require(metadataFile.isFile) { "Missing reference metadata: data/reference_images/$id/meta.json" }
        (JsonSlurper().parse(metadataFile.reader(Charsets.UTF_8)) as Map<*, *>)["referenceImages"] as List<*>
    } else emptyList<Any>()
    val cardDirectory = cardImageRoot.resolve(id)
    references.forEach { value ->
        @Suppress("UNCHECKED_CAST")
        val image = value as MutableMap<String, Any?>
        val path = image["file"] as String
        // 校验 1：发布图路径白名单（脱敏层、本物种目录、仅 jpg）
        require(path.matches(Regex("""data/card_images/$id/[A-Za-z0-9_-]+\.jpg"""))) {
            "Unsafe published image path: $path"
        }
        // 校验 2：署名齐备，缺项即数据契约违背
        require(image["rights"] != null && !image["rights"].toString().isBlank()) { "Missing rights: $path" }
        require(image["source"] != null && !image["source"].toString().isBlank()) { "Missing source: $path" }
        // 校验 3 的准备：originalFile 指回原始图层，形状同样受白名单约束。
        // 与 file 的白名单不同，这里缺项/越界判为「待复核」（不打包、App 显示占位），
        // 而不是让构建失败：署名核对属于数据质量校验，原逻辑就是这样 fail-closed 而非中止构建。
        val original = image["originalFile"] as? String
        val originalWellFormed = original != null &&
            original.matches(Regex("""data/reference_images/$id/[A-Za-z0-9_.-]+\.jpg"""))
        val cardImage = repositoryRoot.resolve(path)
        require(isInside(cardDirectory, cardImage)) { "Published image escapes its species directory: $path" }
        require(cardImage.isFile) {
            "Missing published card image: $path —— species.json 登记的发布图不存在，" +
                "请先跑发布管线生成 data/card_images，或从 species.json 移除该条目"
        }
        val bytes = cardImage.readBytes()
        // 校验 3：publishedFile 唯一映射 + originalFile 基名反向印证，两个字段互证避免错配到别的照片
        val matching = metadata.filterIsInstance<Map<*, *>>().filter { it["publishedFile"] == path }
        require(matching.size <= 1) { "Ambiguous publishedFile mapping in $metadataFile: $path" }
        val record = matching.singleOrNull()
        // 校验 4：实际 sha256 必须与两处登记值一致，确保打包的就是已登记审核的那一版
        val digest = sha256Hex(cardImage)
        val digestVerified = image["publishedSha256"] == digest && record?.get("publishedSha256") == digest
        // 校验 5：不得残留 EXIF
        val deidentified = !hasExifSegment(bytes)
        val consistent = deidentified && digestVerified && originalWellFormed && record != null &&
            record["file"] == requireNotNull(original).substringAfterLast('/') &&
            listOf("rights", "source", "sourcePage", "photoId").all { image[it] == record[it] }
        image["attributionConsistent"] = consistent
        if (consistent) {
            // path 就是仓库相对路径，正好等于 Sync from(repositoryRoot) 的 include 模式
            bundledImageFiles += cardImage
            bundledImagePaths += path
        }
    }
}
val speciesAssetsDirectory = layout.buildDirectory.dir("generated/speciesAssets")
val prepareSpeciesAssets by tasks.registering(Sync::class) {
    from(repositoryRoot) {
        include("data/species.json")
        include(bundledImagePaths)
    }
    into(speciesAssetsDirectory)
    inputs.file(speciesCatalogFile)
    inputs.files(referenceMetadataFiles)
    // sha256 与 EXIF 校验依赖图片实际字节，必须把图片登记为输入，否则改图后任务会被误判为 UP-TO-DATE
    inputs.files(bundledImageFiles)
    doLast {
        speciesAssetsDirectory.get().file("data/species.json").asFile
            .writeText(JsonOutput.toJson(speciesRows), Charsets.UTF_8)
    }
}

android {
    namespace = "com.insta360.kmpsdk.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.insta360.kmpsdk.demo"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = libs.versions.inskmpVersion.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 真实识别代理配置：默认空串（→ 运行时降级 MOCK）。值来自 local.properties 或环境变量，
        // 二者都不进仓库。转义用 asJavaStringLiteral，防 baseUrl/token 里的引号或反斜杠破坏生成代码。
        buildConfigField("String", "RECOGNITION_PROXY_BASE_URL", asJavaStringLiteral(recognitionProxyBaseUrl))
        buildConfigField("String", "RECOGNITION_PROXY_TOKEN", asJavaStringLiteral(recognitionProxyToken))

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    sourceSets.getByName("main").assets.srcDir(speciesAssetsDirectory)
    testOptions {
        unitTests.all {
            it.systemProperty("recognitionFixtures", file("src/main/assets/mock").absolutePath)
            it.systemProperty("speciesCatalogFile", speciesCatalogFile.absolutePath)
        }
    }
    // lint 与当前 Kotlin UAST 工具链偶发不兼容导致 NonNullableMutableLiveDataDetector 崩溃，禁用该检测器以恢复 lintDebug
    lint {
        disable += "NullSafeMutableLiveData"
        // 仓库内尚有大量历史 lint error（如缺失翻译），本次功能不改变文案覆盖面；不因 lint error 中止以便产出报告
        abortOnError = false
    }
}

tasks.named("preBuild").configure { dependsOn(prepareSpeciesAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.immersionbar)
    implementation(libs.xx.permission)
    implementation(libs.timber)
    implementation(libs.glide)
    kapt(libs.glide.compiler)

    implementation(libs.inskmp.camera)
    implementation(libs.inskmp.media)
    implementation(libs.okhttp)
}