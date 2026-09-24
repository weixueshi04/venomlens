package com.insta360.kmpsdk.demo.ui.emergency

import com.insta360.kmpsdk.demo.recognition.QualityIssue
import com.insta360.kmpsdk.demo.recognition.RecognitionErrorCode
import com.insta360.kmpsdk.demo.recognition.RecognitionResponse
import com.insta360.kmpsdk.demo.recognition.RecognitionStatus

/**
 * 紧急一键流程的纯逻辑：文件名匹配、求助出口可用性判定、合规文案渲染。
 *
 * 这里刻意不依赖任何 Android / SDK 类型，全部可在 JVM 单元测试中直接覆盖。
 */

/**
 * 紧急流程阶段。[FAILED] 只表示「自动链路」失败，不代表求助出口不可用。
 * [PENDING] 是真实识别的 202 中间态：先执行**有界自动查询**（见 [PendingRefreshPolicy]），
 * 超限后退回人工单次查询。任何情况下都不是无限轮询。
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
 * 结果来源标注：已按 2026-09-24 需求**整体移除**（不再在界面区分 MOCK / LIVE / CACHE）。
 * 保留的是产品安全口径本身——候选不代表已确认、无可靠置信度、不生成诊断结论。
 */

/** 有界自动查询的单步决策，见 [PendingRefreshPolicy.nextAutoStep]。 */
enum class AutoRefreshStep {
    /** 还可以再自动查询一次（次数未超上限、上一次仍是合法 pending）。 */
    CONTINUE,

    /** 5 次自动查询后仍 pending：停止自动查询，退回人工单次查询。 */
    STOP_TO_MANUAL,

    /** 出现失败（含 409/429/504）：立即停止，不自动重试。 */
    STOP_NO_RETRY,

    /** 拿到终态（candidates / uncertain / no_snake）：渲染结果并停止。 */
    STOP_DONE,

    /** 页面退出 / ViewModel 清空 / 新一轮拍摄：取消在途查询。 */
    STOP_CANCELLED,
}

/**
 * pending → 有界自动查询 + 人工兜底的状态机契约。
 *
 * 2026-09-24 修订（见 `contracts/recognition-contract.md` 第 3 节修订块）：真实供应商几乎总是
 * 先返回 pending，紧急场景下不能把流程卡在用户手上，故客户端在 pending 后**自动**发起有界查询。
 * 严格按契约 L126 的供应商建议设上限（间隔 1–3 秒、超过 5 次视为超时），**绝不允许无限轮询**：
 * 间隔 [PENDING_REFRESH_INTERVAL_MS]、最多 [PENDING_REFRESH_MAX_ATTEMPTS] 次，
 * 遇 409 / 429 / 504 或任何 [com.insta360.kmpsdk.demo.recognition.RecognitionResult.Failure] 立即停止，
 * 超限后退回 [canManuallyRefresh] 的人工单次查询。上传仍不自动重试（契约 L20）。
 */
object PendingRefreshPolicy {

    /** 自动查询间隔。供应商建议 1–3 秒（契约 L126），取中值 2 秒。 */
    const val PENDING_REFRESH_INTERVAL_MS = 2000L

    /** 自动查询次数上限。供应商建议超过 5 次视为超时（契约 L126）。 */
    const val PENDING_REFRESH_MAX_ATTEMPTS = 5

    /** recognitionId 格式，与 HttpRecognitionAdapter.refresh 的校验保持一致。 */
    private val recognitionIdPattern = Regex("[a-f0-9]{64}")

    /**
     * 出现即停止自动查询的错误码（契约 L80 / L83 / L85）：
     * 409 禁止重复发送、429 需人工确认（预算不能被轮询烧光）、504 不自动重试。
     * 实际上任何 Failure 都会停止（契约 L20：客户端不自动重试），此集合只为把这三条显式留痕。
     */
    val IMMEDIATE_STOP_ERROR_CODES: Set<RecognitionErrorCode> = setOf(
        RecognitionErrorCode.OPERATION_IN_PROGRESS,
        RecognitionErrorCode.UPSTREAM_LIMITED,
        RecognitionErrorCode.LOCAL_BUDGET_EXHAUSTED,
        RecognitionErrorCode.UPSTREAM_TIMEOUT,
    )

    /** 仅当状态确为 PENDING 且 recognitionId 合法时，才进入「等待查询结果」。 */
    fun pendingRecognitionId(
        status: RecognitionStatus,
        recognitionId: String?,
    ): String? = recognitionId?.takeIf { status == RecognitionStatus.PENDING && recognitionIdPattern.matches(it) }

    /**
     * 是否应发起下一次**自动**查询。纯函数，完全离线可测。
     *
     * @param attemptsSoFar 已完成的自动查询次数（0 表示上传刚返回 pending，还没查过）。
     * @param pendingRecognitionId 最新一次响应给出的 pending 任务 id；终态或非法 id 传 null。
     * @param lastErrorCode 最新一次响应的错误码；没有失败传 null。
     * @param cancelled 是否已被取消（页面退出 / ViewModel 清空 / 新一轮拍摄）。
     */
    fun nextAutoStep(
        attemptsSoFar: Int,
        pendingRecognitionId: String?,
        lastErrorCode: RecognitionErrorCode?,
        cancelled: Boolean,
    ): AutoRefreshStep = when {
        cancelled -> AutoRefreshStep.STOP_CANCELLED
        // 契约 L20：任何失败都不自动重试；409/429/504 更是明确禁止（见 IMMEDIATE_STOP_ERROR_CODES）。
        lastErrorCode != null -> AutoRefreshStep.STOP_NO_RETRY
        // 终态（candidates / uncertain / no_snake）：渲染结果，不再查询。
        pendingRecognitionId == null -> AutoRefreshStep.STOP_DONE
        attemptsSoFar >= PENDING_REFRESH_MAX_ATTEMPTS -> AutoRefreshStep.STOP_TO_MANUAL
        else -> AutoRefreshStep.CONTINUE
    }

    /**
     * 「查询一次结果」按钮是否可点。[manualClick] 必须由真实点击事件驱动。
     * 自动查询在途时 [requestInFlight] 为 true，人工点击被拒绝，避免与自动查询撞车（契约 L80）。
     */
    fun canManuallyRefresh(
        recognitionId: String?,
        requestInFlight: Boolean,
        manualClick: Boolean,
    ): Boolean = manualClick && !requestInFlight && recognitionId != null
}

/**
 * 上传授权闸门的纯逻辑：一次明示授权、后续零操作；撤销立即生效。
 *
 * 授权状态**只**认持久化存储（[com.insta360.kmpsdk.demo.util.DemoAppPreferences]），
 * 不走 View 的 saveInstanceState，避免进程重建后 UI 状态与实际授权不一致。
 */
object UploadConsentGatePolicy {

    /** MOCK 降级模式不发任何网络请求，因此不需要授权闸门。 */
    fun gateRequired(liveEnabled: Boolean): Boolean = liveEnabled

    /** 未授权时按快门：不发送任何网络请求，直接给未授权文案；求助出口仍常亮。 */
    fun mayUpload(consentGranted: Boolean): Boolean = consentGranted

    /** 撤销后回到闸门态：授权标记清除、指示与撤销入口隐藏。 */
    fun stateAfterRevoke(): Boolean = false
}

/**
 * 识别结果合规文案。只保留安全表述，**不再带结果来源标注**（2026-09-24 需求：
 * 界面不再区分 MOCK / LIVE / CACHE）。
 */
fun renderRecognitionSummary(response: RecognitionResponse): String = buildString {
    appendLine(when (response.status) {
        RecognitionStatus.CANDIDATES -> "候选蛇种（不代表已确认）"
        RecognitionStatus.NO_SNAKE -> "未检测到蛇，不代表现场安全。"
        RecognitionStatus.UNCERTAIN -> if (response.candidates.isEmpty()) {
            // 上游明确表示没检出目标（1008 / 1010）或候选全在本地目录之外：确实没有可用信息。
            "未获得可用候选，无法可靠判断。"
        } else {
            // 上游同时提到了本地未收录的物种，故整条降级 uncertain，但已匹配的候选照常保留
            // （契约 L43，客户端不得丢弃）。文案必须说明「为什么降级」，
            // 否则「无法可靠判断」会被读成识别失败，而实际上正确物种就在下面的候选里。
            "已给出候选；上游还提到本地未收录的物种，因此未作整体判定。"
        }
        RecognitionStatus.PENDING -> "识别处理中；正在自动查询结果（间隔 2 秒、最多 5 次），超限后可人工查询一次。"
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
    appendLine("识别结果不能用于排除危险或替代医疗判断。")
}
