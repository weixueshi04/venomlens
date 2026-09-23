package com.insta360.kmpsdk.demo.ui.emergency

import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.recognition.QualityIssue
import com.insta360.kmpsdk.demo.recognition.RecognitionResponse
import com.insta360.kmpsdk.demo.recognition.RecognitionSource
import com.insta360.kmpsdk.demo.recognition.RecognitionStatus

/**
 * 紧急一键流程的纯逻辑：文件名匹配、求助出口可用性判定、合规文案渲染。
 *
 * 这里刻意不依赖任何 Android / SDK 类型，全部可在 JVM 单元测试中直接覆盖。
 */

/**
 * 紧急流程阶段。[FAILED] 只表示「自动链路」失败，不代表求助出口不可用。
 * [PENDING] 是真实识别的 202 中间态：只能等人工点击「查询一次结果」，不自动轮询。
 */
enum class EmergencyStage { IDLE, FETCHING, PREPARING, RECOGNIZING, PENDING, DONE, FAILED }

object EmergencyFlowFiles {

    /**
     * 取路径的文件名部分，同时兼容 '/' 与 '\' 分隔符。
     * 相机侧回调路径与 SDK 本地路径的分隔符风格不保证一致，故两个都切。
     */
    fun basename(path: String): String {
        val trimmed = path.trim().trimEnd('/', '\\')
        val cut = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        return if (cut >= 0) trimmed.substring(cut + 1) else trimmed
    }

    /**
     * 在 [candidates] 中找出第一个与 [wanted] 里任一路径同名（忽略大小写）的候选，返回候选原值。
     *
     * 用于把 `onCaptureFinish(filePaths)` 给出的相机侧路径，对到 `WorkWrapper.mainUrls` 上。
     * 只比文件名不比全路径：相机侧是 `http://…` 或 `/mnt/…`，本地侧是 `/storage/…`，前缀必然不同。
     */
    fun firstMatchByBasename(wanted: List<String>, candidates: List<String>): String? {
        val index = indexOfFirstMatch(wanted, candidates)
        return if (index >= 0) candidates[index] else null
    }

    /** [firstMatchByBasename] 的下标版本；调用方需要按下标取回对应的 WorkWrapper。无匹配返回 -1。 */
    fun indexOfFirstMatch(wanted: List<String>, candidates: List<String>): Int {
        val wantedNames = wanted.map { basename(it) }.filter { it.isNotEmpty() }
        if (wantedNames.isEmpty()) return -1
        return candidates.indexOfFirst { candidate ->
            val name = basename(candidate)
            name.isNotEmpty() && wantedNames.any { it.equals(name, ignoreCase = true) }
        }
    }
}

object EmergencyFlowPolicy {

    /** 传给病例卡的候选上限，与 [com.insta360.kmpsdk.demo.care.CaseRecordActivity] 的 take(3) 对齐。 */
    private const val MAX_CANDIDATE_LABELS = 3

    /**
     * 只有照片才进入「取回 → 准备 → 识别」链路。
     * 视频（含延时/慢动作等）在紧急场景无意义，直接跳过，避免误下载大文件。
     */
    fun shouldEnterPipeline(isPhoto: Boolean): Boolean = isPhoto

    /**
     * 求助出口（病例卡 / 医院）是否可用。
     *
     * 产品红线：**恒为 true**。识别失败、图片读不出、照片取不回、甚至完全没拍照，
     * 都不能挡住用户记录伤情和找医院。本函数存在的意义是把这条红线固化成可测契约，
     * 防止后续改动误把出口跟链路状态绑在一起。
     */
    fun helpExitsEnabled(
        pipelineFailed: Boolean,
        imageUnavailable: Boolean,
        recognitionFailed: Boolean,
        hasPhotoAtAll: Boolean,
    ): Boolean = true

    /**
     * 病例卡用的候选标签：`"commonName / scientificName"`，最多 3 条。
     * 只展示名称，不带任何分值——分值不作为准确率呈现。
     */
    fun candidateLabels(candidates: List<Pair<String, String>>): List<String> =
        candidates.take(MAX_CANDIDATE_LABELS).map { (common, scientific) -> "$common / $scientific" }

    /** MOCK 识别在图片准备失败时是否仍然执行：是。MOCK 结果与照片字节无关，保留候选展示更有用。 */
    fun runMockWithoutImage(): Boolean = true
}

/**
 * 阶段3 走真实识别还是本地 MOCK，由构建期注入的代理配置决定。
 * 密钥只在 local.properties / 环境变量，默认空串 → MOCK。
 */
enum class RecognitionMode { LIVE, MOCK }

/**
 * [RecognitionMode.LIVE] 需要 baseUrl 与 token 同时非空。
 * 只给 baseUrl 不给 token（或反之）一律降级 MOCK：
 * 半配置状态下走真实链路只会得到 401/403，不如明确降级。
 */
fun recognitionModeOf(proxyBaseUrl: String?, proxyToken: String?): RecognitionMode =
    if (!proxyBaseUrl.isNullOrBlank() && !proxyToken.isNullOrBlank()) RecognitionMode.LIVE
    else RecognitionMode.MOCK

/**
 * 结果来源标注：MOCK 必须显著（红色）标注为模拟；LIVE/CACHE 也必须标注「候选、非诊断」。
 * 返回 (是否高危红色标注, 文案 string 资源 id)。
 */
fun resultBadgeOf(source: RecognitionSource): Pair<Boolean, Int> = when (source) {
    RecognitionSource.MOCK -> true to R.string.emergency_flow_badge_mock
    RecognitionSource.LIVE -> false to R.string.emergency_flow_badge_live
    RecognitionSource.CACHE -> false to R.string.emergency_flow_badge_cache
}

/**
 * pending → 人工查询的状态机契约。
 *
 * 契约规定：202=pending，**没有自动轮询、没有自动重试**，pending 只能由人工触发
 * 一次 `POST /v1/recognitions/{id}/refresh`。本对象只提供「人工点击」这一个入口，
 * 结构上就不存在自动触发的路径；查询后若仍 pending，可再次由人工点击。
 */
object PendingRefreshPolicy {

    /** recognitionId 格式，与 HttpRecognitionAdapter.refresh 的校验保持一致。 */
    private val recognitionIdPattern = Regex("[a-f0-9]{64}")

    /** 仅当状态确为 PENDING 且 recognitionId 合法时，才进入「等待人工查询」。 */
    fun pendingRecognitionId(
        status: RecognitionStatus,
        recognitionId: String?,
    ): String? = recognitionId?.takeIf { status == RecognitionStatus.PENDING && recognitionIdPattern.matches(it) }

    /**
     * 「查询一次结果」按钮是否可点。[manualClick] 必须由真实点击事件驱动；
     * 本函数没有任何自动调用路径，因此不存在自动轮询。
     */
    fun canManuallyRefresh(
        recognitionId: String?,
        requestInFlight: Boolean,
        manualClick: Boolean,
    ): Boolean = manualClick && !requestInFlight && recognitionId != null
}

/**
 * 识别结果合规文案，措辞与 `MockRecognitionActivity.renderSuccess()` 完全一致。
 * 主线页照抄同一套安全表述，避免两处口径漂移。
 */
fun renderRecognitionSummary(response: RecognitionResponse): String = buildString {
    val source = when (response.resultSource) {
        RecognitionSource.MOCK -> "MOCK · 模拟结果"
        RecognitionSource.LIVE -> "LIVE · 本次供应商响应"
        RecognitionSource.CACHE -> "CACHE · 历史结果回放"
    }
    appendLine("【$source】")
    appendLine(when (response.status) {
        RecognitionStatus.CANDIDATES -> "候选蛇种（不代表已确认）"
        RecognitionStatus.NO_SNAKE -> "未检测到蛇，不代表现场安全。"
        RecognitionStatus.UNCERTAIN -> "无法可靠判断；仍保留可用候选。"
        RecognitionStatus.PENDING -> "识别处理中；仅在人工确认后查询一次。"
    })
    response.candidates.forEachIndexed { index, candidate ->
        appendLine("\n${index + 1}. ${candidate.commonName}\n${candidate.scientificName}")
        candidate.providerScore?.let { appendLine("供应商原始分值：$it（含义未经校准）") }
    }
    appendLine("\n无可靠置信度，仅展示候选排序。")
    if (response.qualityIssues.isNotEmpty()) {
        appendLine("\n画质提醒：" + response.qualityIssues.joinToString("、") {
            when (it) {
                QualityIssue.BLURRED -> "画面模糊"
                QualityIssue.TOO_SMALL -> "目标过小"
                QualityIssue.LOW_LIGHT -> "光线不足"
            }
        })
    }
    response.recognitionId?.let { appendLine("\n识别任务：$it") }
    appendLine("\n响应耗时字段：${response.latencyMs} ms")
    if (response.resultSource == RecognitionSource.CACHE) appendLine("缓存耗时不是本次请求耗时。")
    appendLine("识别结果不能用于排除危险或替代医疗判断。")
}
