package com.insta360.kmpsdk.demo.ui.emergency

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arashivision.sdk.media.api.work.WorkManager
import com.arashivision.sdk.media.api.work.WorkWrapper
import com.insta360.kmpsdk.demo.BuildConfig
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.care.BiteStatus
import com.insta360.kmpsdk.demo.recognition.HttpRecognitionAdapter
import com.insta360.kmpsdk.demo.recognition.MockRecognitionAdapter
import com.insta360.kmpsdk.demo.recognition.MockScenario
import com.insta360.kmpsdk.demo.recognition.RecognitionAdapter
import com.insta360.kmpsdk.demo.recognition.RecognitionCall
import com.insta360.kmpsdk.demo.recognition.RecognitionErrorCode
import com.insta360.kmpsdk.demo.recognition.RecognitionImage
import com.insta360.kmpsdk.demo.recognition.RecognitionProxyHealth
import com.insta360.kmpsdk.demo.recognition.RecognitionResult
import com.insta360.kmpsdk.demo.recognition.RecognitionResponse
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

/** 主线页需要跳转的三个出口，交给 Fragment 执行（VM 不持有 Context/Activity）。 */
sealed interface EmergencyFlowEvent {
    /**
     * 打开本地伤情信息卡。
     *
     * [biteStatus] 恒为 [BiteStatus.UNKNOWN]（自动打开时）或用户显式点选的值——
     * 照片与识别链路都推不出「有没有被咬」，所以绝不给它兜底成 NOT_BITTEN。
     *
     * [auto] 为 true 表示这是「拍完即出病例」的自动打开：卡片上要有一句说明，
     * 免得用户以为咬伤情况已经被系统判定过了。
     */
    data class OpenCare(val biteStatus: BiteStatus, val auto: Boolean = false) : EmergencyFlowEvent

    data object OpenHospital : EmergencyFlowEvent
    data class OpenSpecies(val speciesId: String) : EmergencyFlowEvent
}

data class EmergencyCandidateUi(
    val speciesId: String,
    val commonName: String,
    val scientificName: String,
)

data class EmergencyFlowUiState(
    /** 相机是否已连接：未连接时主按钮禁用。 */
    val connected: Boolean = false,
    /** 拍摄按钮是否可点（连接 + 相机配置就绪 + 不在遮罩期）。 */
    val captureEnabled: Boolean = false,
    /** 当前阶段。 */
    val stage: EmergencyStage = EmergencyStage.IDLE,
    /** 阶段标题文案，始终可见，不静默。 */
    val stageText: String = "",
    /** 阶段细节/进度文案，可为空。 */
    val detailText: String = "",
    /** 下载进度 0..100，<0 表示不显示进度条。 */
    val progressPercent: Int = -1,
    /** 是否配置了真实识别代理（baseUrl 与 token 都非空）。false 即 MOCK 降级模式。 */
    val liveEnabled: Boolean = false,
    /**
     * 一次性上传授权是否已授予。默认 false：原图不发送（安全红线）。
     * 状态只认 [com.insta360.kmpsdk.demo.util.DemoAppPreferences] 持久化，
     * 不走 View 的 saveInstanceState，避免进程重建后与实际授权不一致。
     */
    val consentGranted: Boolean = false,
    /** 真实识别请求是否在途（upload 或 refresh），用于禁用按钮并显示等待文案。 */
    val requestInFlight: Boolean = false,
    /** 识别合规文案（候选不代表已确认、无可靠置信度、不生成诊断结论）。 */
    val summaryText: String = "",
    val candidates: List<EmergencyCandidateUi> = emptyList(),
    /** 非空表示结果处于 pending，正在有界自动查询或已退回人工查询。 */
    val pendingRecognitionId: String? = null,
    /** 已完成的**自动**查询次数，用于「第 N/5 次」文案。 */
    val pendingAutoAttempts: Int = 0,
    /**
     * 自动查询是否已停止并退回人工单次查询（5 次用尽）。
     * false 时人工按钮可见但禁用（自动查询在途，避免与自动查询撞车 → 契约 L80 禁止重复发送）。
     */
    val pendingManualAvailable: Boolean = false,
    /**
     * 求助出口（病例卡 / 医院）是否可点。
     * 按产品红线恒为 true —— 见 [EmergencyFlowPolicy.helpExitsEnabled]。
     */
    val helpExitsEnabled: Boolean = true,
    /** 图片降级提示，例如读图失败但仍可记录伤情。 */
    val imageWarningText: String = "",
)

/**
 * 紧急一键流程编排：拍照 → 取回到手机 → 识别（真实 / MOCK 降级）→ 病例卡/医院求助。
 *
 * 拍摄控制完全复用 [com.insta360.kmpsdk.demo.ui.capture.CameraCaptureViewModel]，
 * 本 VM 只负责「拍完之后」的链路与 UI 状态，不重写任何 SDK 拍摄调用。
 *
 * 识别双路径：构建期注入的 baseUrl 与 token **都非空**时走 [HttpRecognitionAdapter] 真实识别，
 * 否则降级 [MockRecognitionAdapter]（本地 fixture，不发网络请求）。见 [recognitionModeOf]。
 *
 * pending 处理（2026-09-24 契约修订，见 contracts/recognition-contract.md 第 3 节修订块）：
 * 真实供应商几乎总是先返回 pending，故上传后执行**有界自动查询**——间隔
 * [PendingRefreshPolicy.PENDING_REFRESH_INTERVAL_MS]、最多 [PendingRefreshPolicy.PENDING_REFRESH_MAX_ATTEMPTS] 次，
 * 遇 409/429/504 或任何失败立即停止（不自动重试），超限后退回人工单次查询。见 [PendingRefreshPolicy.nextAutoStep]。
 * 上传本身仍不自动重试。
 */
class EmergencyFlowViewModel(
    application: Application,
) : AndroidViewModel(application) {

    /**
     * 识别模式由构建期注入的配置决定；密钥只来自 local.properties / 环境变量，默认空串 → MOCK。
     * 必须先于 [_ui] 声明：Kotlin 属性按声明顺序初始化，_ui 的初值要用到它。
     */
    private val recognitionMode: RecognitionMode = recognitionModeOf(
        BuildConfig.RECOGNITION_PROXY_BASE_URL,
        BuildConfig.RECOGNITION_PROXY_TOKEN,
    )

    private val _ui = MutableStateFlow(
        EmergencyFlowUiState(
            liveEnabled = recognitionMode == RecognitionMode.LIVE,
            // 授权状态只认持久化存储：一次明示授权、后续零操作（进程重建不丢、不走 saveInstanceState）。
            consentGranted = com.insta360.kmpsdk.demo.util.DemoAppPreferences
                .readEmergencyUploadConsentGranted(application),
        )
    )
    val ui: StateFlow<EmergencyFlowUiState> = _ui.asStateFlow()

    private val _event = MutableSharedFlow<EmergencyFlowEvent>(extraBufferCapacity = 4)
    val event: SharedFlow<EmergencyFlowEvent> = _event.asSharedFlow()

    private var pipelineJob: Job? = null
    private var refreshJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var activeCall: RecognitionCall? = null

    /** 传给病例卡的本机图片 Uri（FileProvider content Uri），失败时为 null。 */
    private var preparedImageUri: Uri? = null
    private var preparedAt: String? = null

    /** pending 后人工 refresh 要复用同一个 adapter（含同一份代理配置），故保留引用。 */
    private var pendingAdapter: RecognitionAdapter? = null

    /** 最近一次自动查询的错误码；非 null 时 nextAutoStep 判 STOP_NO_RETRY。新一轮链路重置为 null。 */
    private var lastAutoRefreshErrorCode: RecognitionErrorCode? = null

    /** 本轮链路是否已经自动打开过信息卡，见 [maybeOpenCaseAutomatically]。 */
    private var autoCaseOpenedForRound = false

    /** 本轮链路上「改用本地数据集」的具体原因；为空表示走的是真实识别。 */
    private var offlineFallbackReason: String? = null

    /**
     * 构造本轮要用的识别适配器。
     *
     * 产品口径（用户明确要求）：**有网就必须走真实识别，禁止用本地数据集顶替**。
     * 所以这里没有「构造失败就悄悄换成 fixture」这种分支了，判定顺序是：
     * 1. 构建期配置成 LIVE → 先短超时探测代理 `/healthz`（[RecognitionProxyHealth]）；
     * 2. 探测通过 → 走真实识别；
     * 3. 探测不通过 / 未配置 / 配置不合法 → 才落到本地 fixture，**并且把原因写进界面**
     *    （[R.string.emergency_flow_stage_recognizing_local]），不会伪装成真实结果。
     *
     * 探测失败选择「降级」而不是「直接失败」，是因为产品红线要求任何一步失败都不能阻断求助路径；
     * 但降级必须是**看得见**的，否则就退化成最初那个「用本地数据集冒充 AI 识图」的问题了。
     * 注意：2026-09-24 起界面不再区分结果来源（MOCK / LIVE / CACHE），降级的可见性由这条
     * 「本地数据集」阶段文案承担，而不是由结果区徽标承担。
     */
    private suspend fun resolveAdapter(): RecognitionAdapter {
        val app = getApplication<Application>()
        if (recognitionMode != RecognitionMode.LIVE) {
            applyLocalDatasetFallback(str(R.string.emergency_flow_fallback_not_configured))
            return MockRecognitionAdapter(app.assets, MockScenario.CANDIDATES)
        }
        val reachable =
            withContext(Dispatchers.IO) {
                RecognitionProxyHealth.reachable(
                    BuildConfig.RECOGNITION_PROXY_BASE_URL,
                    BuildConfig.RECOGNITION_PROXY_TOKEN,
                )
            }
        val liveAdapter =
            runCatching {
                HttpRecognitionAdapter(
                    baseUrl = BuildConfig.RECOGNITION_PROXY_BASE_URL,
                    authToken = BuildConfig.RECOGNITION_PROXY_TOKEN,
                    // adb reverse 会留下失效的空闲代理连接；用零空闲连接池，每次新建连接。
                    // 与 MockRecognitionActivity 的既有做法一致。
                    client = OkHttpClient.Builder()
                        .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
                        .build(),
                    // 真实模式必须 mockScenario=null，否则 init 校验抛异常（真实模式不接受 X-Mock-Scenario 头）。
                    mockScenario = null,
                )
            }.getOrElse { e ->
                // 配置本身不合法（baseUrl 非 http/https、带 query 等）。这是构建配置错误，
                // 不能拿 fixture 把错误盖掉——照实报出来，并标明本次是本地数据集。
                Timber.e(e, "recognition proxy config invalid")
                applyLocalDatasetFallback(str(R.string.emergency_flow_fallback_config_invalid))
                return MockRecognitionAdapter(app.assets, MockScenario.CANDIDATES)
            }
        if (reachable) {
            offlineFallbackReason = null
            _ui.update { it.copy(liveEnabled = true) }
            return liveAdapter
        }
        Timber.w(
            "recognition proxy unreachable at %s; this round falls back to the local dataset",
            BuildConfig.RECOGNITION_PROXY_BASE_URL,
        )
        applyLocalDatasetFallback(
            str(
                R.string.emergency_flow_fallback_proxy_unreachable,
                BuildConfig.RECOGNITION_PROXY_BASE_URL,
            ),
        )
        return MockRecognitionAdapter(app.assets, MockScenario.CANDIDATES)
    }

    /** 降级到本地数据集时把「这是本地数据集、不是真实识别」摆到界面上，不让它静默发生。 */
    private fun applyLocalDatasetFallback(reason: String) {
        offlineFallbackReason = reason
        Timber.w("emergency recognition degraded to local dataset: %s", reason)
        _ui.update {
            it.copy(
                liveEnabled = false,
                stageText = str(R.string.emergency_flow_stage_recognizing_local),
                detailText = reason,
            )
        }
    }

    private fun str(resId: Int): String = getApplication<Application>().getString(resId)

    private fun str(resId: Int, vararg args: Any?): String =
        getApplication<Application>().getString(resId, *args)

    fun onConnected(connected: Boolean) {
        _ui.update { if (it.connected == connected) it else it.copy(connected = connected) }
    }

    /** 拍摄按钮就绪态由 CameraCaptureViewModel 的 ui 驱动，这里只搬运结论。 */
    fun onCaptureAvailability(enabled: Boolean) {
        _ui.update { if (it.captureEnabled == enabled) it else it.copy(captureEnabled = enabled) }
    }

    /**
     * 上传授权（一次明示、后续零操作）。默认未授权：原图不发送（安全红线）。
     * 授权/撤销立即持久化，进程重建后仍以存储为准（见 [com.insta360.kmpsdk.demo.util.DemoAppPreferences]）。
     * 真实模式下未授权即点识别，[HttpRecognitionAdapter] 会在本地直接失败
     * UPLOAD_CONSENT_REQUIRED，不发出任何网络请求。
     */
    fun onUploadConsentChanged(consent: Boolean) {
        if (!UploadConsentGatePolicy.gateRequired(_ui.value.liveEnabled)) return
        com.insta360.kmpsdk.demo.util.DemoAppPreferences
            .persistEmergencyUploadConsent(getApplication(), consent)
        _ui.update { if (it.consentGranted == consent) it else it.copy(consentGranted = consent) }
    }

    fun onHelpClicked(biteStatus: BiteStatus) {
        _event.tryEmit(EmergencyFlowEvent.OpenCare(biteStatus))
    }

    fun onHospitalClicked() {
        _event.tryEmit(EmergencyFlowEvent.OpenHospital)
    }

    fun onCandidateClicked(candidate: EmergencyCandidateUi) {
        _event.tryEmit(EmergencyFlowEvent.OpenSpecies(candidate.speciesId))
    }

    /**
     * 「查询一次结果」：**只能由用户点击触发**（manualClick 恒为 true 的唯一调用点）。
     * 没有任何定时器、轮询或自动重试路径调用本函数 —— 契约要求 pending 只人工查询。
     * 查询后若仍是 pending，按钮仍可再次点击（仍属人工触发）。
     */
    fun onRefreshPendingClicked() {
        val state = _ui.value
        val recognitionId = state.pendingRecognitionId
        if (!PendingRefreshPolicy.canManuallyRefresh(recognitionId, state.requestInFlight, manualClick = true)) {
            return
        }
        val adapter = pendingAdapter ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    requestInFlight = true,
                    stageText = str(R.string.emergency_flow_stage_pending_refreshing),
                    detailText = str(R.string.emergency_flow_pending_refreshing_detail),
                )
            }
            val result = runAdapterRefresh(adapter, recognitionId!!)
            deliverRefreshResult(result)
        }
    }

    fun currentImageUri(): Uri? = preparedImageUri
    fun currentImportedAt(): String? = preparedAt
    fun currentSummary(): String = _ui.value.summaryText
    fun currentCandidateLabels(): List<String> =
        EmergencyFlowPolicy.candidateLabels(
            _ui.value.candidates.map { it.commonName to it.scientificName }
        )

    /**
     * 收到拍摄完成回调后启动链路。App 按钮触发与相机机身按键触发走的是同一入口，
     * 因此两者一视同仁。
     */
    fun onCaptureFinished(isPhoto: Boolean, filePaths: List<String>) {
        pipelineJob?.cancel()
        refreshJob?.cancel()
        autoRefreshJob?.cancel()
        preparedImageUri = null
        preparedAt = null
        activeCall = null
        pendingAdapter = null
        lastAutoRefreshErrorCode = null
        // 新一轮拍摄 = 允许再自动出一次病例卡
        autoCaseOpenedForRound = false

        if (!EmergencyFlowPolicy.shouldEnterPipeline(isPhoto)) {
            _ui.update {
                EmergencyFlowUiState(
                    connected = it.connected,
                    captureEnabled = it.captureEnabled,
                    liveEnabled = recognitionMode == RecognitionMode.LIVE,
                    consentGranted = it.consentGranted,
                    stage = EmergencyStage.FAILED,
                    stageText = str(R.string.emergency_flow_video_skipped_title),
                    detailText = str(R.string.emergency_flow_video_skipped_detail),
                    helpExitsEnabled = true,
                )
            }
            return
        }

        pipelineJob = viewModelScope.launch { runPipeline(filePaths) }
    }

    private suspend fun runPipeline(filePaths: List<String>) {
        // 每次新链路开始，清掉上一轮的结果与候选，避免残留误导。
        _ui.update {
            it.copy(
                summaryText = "",
                candidates = emptyList(),
                pendingRecognitionId = null,
                pendingAutoAttempts = 0,
                pendingManualAvailable = false,
                imageWarningText = "",
                requestInFlight = false,
            )
        }
        // ── 阶段 1：从相机取回照片 ────────────────────────────────────────────
        setStage(EmergencyStage.FETCHING, str(R.string.emergency_flow_stage_fetching), null, -1)

        val cameraWork = findCameraWork(filePaths)
        if (cameraWork == null) {
            fail(
                str(R.string.emergency_flow_fetch_failed_title),
                str(R.string.emergency_flow_fetch_failed_detail),
            )
            return
        }

        val downloaded = downloadWithProgress(cameraWork)
        val downloadedPaths = downloaded.getOrNull()
        if (downloaded.isFailure || downloadedPaths.isNullOrEmpty()) {
            val err = downloaded.exceptionOrNull()
            Timber.w(err, "emergency download failed")
            fail(
                str(R.string.emergency_flow_download_failed_title),
                str(R.string.emergency_flow_download_failed_detail),
            )
            return
        }

        // ── 阶段 2：准备图片 ──────────────────────────────────────────────────
        setStage(EmergencyStage.PREPARING, str(R.string.emergency_flow_stage_preparing), null, -1)

        val localPath = resolveLocalPath(downloadedPaths, filePaths)
        if (localPath == null) {
            // 下载报成功但找不到本地文件：不伪装成功，明确报错，但保留求助出口。
            fail(
                str(R.string.emergency_flow_local_missing_title),
                str(R.string.emergency_flow_local_missing_detail),
            )
            return
        }

        val image = prepareImage(localPath)
        // 读图失败不 return：MOCK 识别与照片字节无关，且求助出口必须一直可用。

        // ── 阶段 3：生成候选分析（网络优先，见 resolveAdapter 的口径说明）──────────
        setStage(
            EmergencyStage.RECOGNIZING,
            str(
                if (recognitionMode == RecognitionMode.LIVE) R.string.emergency_flow_stage_recognizing_live
                else R.string.emergency_flow_stage_recognizing
            ),
            null,
            -1,
        )
        val adapter = resolveAdapter()
        pendingAdapter = adapter
        val result = runRecognition(adapter, image?.jpeg, uploadConsent = _ui.value.consentGranted)
        renderResult(result, imageMissing = image == null)
        // 上传返回 pending 时，立即启动有界自动查询（间隔 2 秒、最多 5 次）；
        // 绝不无限轮询，任何失败/超限即停并退回人工单次查询。见 startAutoRefreshIfPending。
        startAutoRefreshIfPending()
    }

    /**
     * 轮询相机相册，按文件名把 `onCaptureFinish` 的路径对到 [WorkWrapper]。
     * 相机写卡有延迟，故按 [POLL_INTERVAL_MS] 轮询，上限约 [POLL_TIMEOUT_MS]。
     * 注意：这里的轮询是「等相机把文件写进相册列表」，与识别的 pending 无关；
     * 识别契约禁止的自动轮询只针对 refresh 接口。
     */
    private suspend fun findCameraWork(filePaths: List<String>): WorkWrapper? {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var attempt = 0
        while (true) {
            attempt++
            val works = runCatching { WorkManager.getAllCameraWorks() }.getOrNull()?.getOrNull()
            if (!works.isNullOrEmpty()) {
                val urls = works.map { it.mainUrls.firstOrNull().orEmpty() }
                val hitIndex = EmergencyFlowFiles.indexOfFirstMatch(filePaths, urls)
                if (hitIndex >= 0) {
                    Timber.d("emergency matched camera work at attempt=%d url=%s", attempt, urls[hitIndex])
                    return works[hitIndex]
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                Timber.w("emergency findCameraWork gave up after %d attempts, filePaths=%s", attempt, filePaths)
                return null
            }
            _ui.update {
                it.copy(detailText = str(R.string.emergency_flow_fetch_retry_detail, attempt))
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun downloadWithProgress(work: WorkWrapper): Result<List<String>> =
        runCatching {
            // download 本身返回 Result<List<String>>（onSuccess 即本地路径）；
            // getOrThrow 把内层失败抬成异常，交给外层 runCatching 统一成一层 Result。
            work.download { total, progress ->
                val percent = if (total > 0) ((progress * 100) / total).toInt().coerceIn(0, 100) else -1
                _ui.update {
                    it.copy(
                        progressPercent = percent,
                        detailText = str(R.string.emergency_flow_download_progress, percent),
                    )
                }
            }.getOrThrow()
        }

    /**
     * 确定下载后的本地绝对路径。
     *
     * 主路径：`download` 的 `Result<List<String>>` onSuccess 直接给本地路径
     * （已反编译确认 `WorkWrapperImpl.download` → `CameraModuleProvider.downloadCameraFiles`
     * 逐文件下载到本地并回传路径列表）。
     * 兜底：再查一次 `getAllLocalWorks()` 按同名匹配，防止个别机型回传的不是可用路径。
     */
    private suspend fun resolveLocalPath(
        downloadedPaths: List<String>,
        originalFilePaths: List<String>,
    ): String? = withContext(Dispatchers.IO) {
        val existing = downloadedPaths
            .map { it.substringAfter("file://", it) }
            .firstOrNull { it.isNotBlank() && File(it).let { f -> f.exists() && f.length() > 0 } }
        if (existing != null) return@withContext existing

        Timber.w("emergency downloaded paths not usable on disk: %s, fallback to getAllLocalWorks", downloadedPaths)
        val wanted = originalFilePaths + downloadedPaths
        val localWorks = runCatching { WorkManager.getAllLocalWorks() }.getOrNull().orEmpty()
        val urls = localWorks.map { it.mainUrls.firstOrNull().orEmpty() }
        val hit = EmergencyFlowFiles.firstMatchByBasename(wanted, urls)
            ?.substringAfter("file://")
            ?.takeIf { File(it).let { f -> f.exists() && f.length() > 0 } }
        hit
    }

    /**
     * 把本机文件复制进 `cacheDir` 并用 FileProvider 取 content Uri。
     *
     * 不能直接 `Uri.fromFile`：targetSdk 35 下跨 Activity 传 file:// 会抛
     * `FileUriExposedException`；也不能直接对下载目录取 FileProvider Uri，因为
     * `res/xml/file_paths.xml` 只授权了 `cache-path`(cacheDir) 与 `external-files-path`，
     * 而 SDK 的 `cacheDir` 配在 `externalCacheDir`（见 MainActivity.initSDK），不在授权范围内。
     * 复制进 cacheDir 是同时满足两者的最小改动。
     */
    private suspend fun prepareImage(localPath: String): RecognitionImage? = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val uri = try {
            val src = File(localPath)
            val dir = File(app.cacheDir, "emergency").apply { mkdirs() }
            val dest = File(dir, "capture_" + EmergencyFlowFiles.basename(localPath))
            src.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            preparedImageUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", dest)
            preparedAt = java.time.OffsetDateTime.now().toString()
            preparedImageUri
        } catch (e: Exception) {
            Timber.w(e, "emergency copy to cacheDir failed")
            null
        }
        if (uri == null) {
            _ui.update { it.copy(imageWarningText = str(R.string.emergency_flow_image_unavailable)) }
            return@withContext null
        }
        try {
            RecognitionImage.read(app.contentResolver, uri).also {
                preparedImageUri = uri
                _ui.update { s ->
                    s.copy(
                        detailText = str(R.string.emergency_flow_image_ready, it.width, it.height, it.jpeg.size / 1024),
                    )
                }
            }
        } catch (_: IOException) {
            warnImage(str(R.string.emergency_flow_image_io_failed))
        } catch (_: SecurityException) {
            warnImage(str(R.string.emergency_flow_image_security_failed))
        } catch (_: IllegalArgumentException) {
            warnImage(str(R.string.emergency_flow_image_invalid_failed))
        } catch (e: Exception) {
            Timber.w(e, "emergency RecognitionImage.read")
            warnImage(str(R.string.emergency_flow_image_io_failed))
        }
    }

    private fun warnImage(message: String): RecognitionImage? {
        // 读图失败：原图 Uri 不再传给病例卡，避免下游反复读失败；文字记录照常可用。
        preparedImageUri = null
        _ui.update { it.copy(imageWarningText = message) }
        return null
    }

    private suspend fun runRecognition(
        adapter: RecognitionAdapter,
        jpeg: ByteArray?,
        uploadConsent: Boolean,
    ): RecognitionResult? = suspendCancellableCoroutine { cont: CancellableContinuation<RecognitionResult?> ->
        _ui.update { it.copy(requestInFlight = true) }
        val requestId = UUID.randomUUID().toString()
        val call = adapter.recognize(requestId, jpeg ?: ByteArray(0), uploadConsent) { result ->
            activeCall = null
            if (cont.isActive) cont.resume(result)
        }
        activeCall = call
        cont.invokeOnCancellation {
            activeCall = null
            call.cancel()
        }
    }

    private suspend fun runAdapterRefresh(
        adapter: RecognitionAdapter,
        recognitionId: String,
    ): RecognitionResult? = suspendCancellableCoroutine { cont: CancellableContinuation<RecognitionResult?> ->
        val requestId = UUID.randomUUID().toString()
        val call = adapter.refresh(requestId, recognitionId) { result ->
            activeCall = null
            if (cont.isActive) cont.resume(result)
        }
        activeCall = call
        cont.invokeOnCancellation {
            activeCall = null
            call.cancel()
        }
    }

    /** 人工 refresh 的结果：仍 pending 就继续等下一次人工点击；拿到候选则正常渲染。 */
    private fun deliverRefreshResult(result: RecognitionResult?) {
        _ui.update { it.copy(requestInFlight = false) }
        when (result) {
            is RecognitionResult.Success -> renderSuccess(result.response)
            is RecognitionResult.Failure -> renderFailure(result, imageMissing = false)
            null -> renderNoResult()
        }
    }

    /**
     * 有界自动查询执行层（2026-09-24 契约修订，见 contracts/recognition-contract.md 第 3 节修订块）。
     *
     * 每一步都由纯函数 [PendingRefreshPolicy.nextAutoStep] 决策：
     * - CONTINUE：等 [PendingRefreshPolicy.PENDING_REFRESH_INTERVAL_MS]（2 秒）后自动查询一次；
     * - STOP_TO_MANUAL：5 次用尽仍 pending → 置 pendingManualAvailable=true，退回人工单次查询；
     * - STOP_NO_RETRY：任何失败（含 409/429/504）→ 立即停，渲染失败，不自动重试；
     * - STOP_DONE / STOP_CANCELLED：渲染结果 / 静默退出。
     *
     * 结构上不存在无限轮询：循环次数由 nextAutoStep 的上限分支保证。
     */
    private fun startAutoRefreshIfPending() {
        val state = _ui.value
        if (state.pendingRecognitionId == null || state.stage != EmergencyStage.PENDING) return
        val adapter = pendingAdapter ?: return
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            var attempts = state.pendingAutoAttempts
            while (true) {
                val step = PendingRefreshPolicy.nextAutoStep(
                    attemptsSoFar = attempts,
                    pendingRecognitionId = _ui.value.pendingRecognitionId,
                    lastErrorCode = lastAutoRefreshErrorCode,
                    cancelled = !isActive,
                )
                when (step) {
                    AutoRefreshStep.CONTINUE -> {
                        _ui.update {
                            it.copy(
                                requestInFlight = true,
                                pendingAutoAttempts = attempts,
                                pendingManualAvailable = false,
                                stageText = str(R.string.emergency_flow_stage_pending),
                                detailText = str(
                                    R.string.emergency_flow_pending_auto_detail,
                                    attempts + 1,
                                    PendingRefreshPolicy.PENDING_REFRESH_MAX_ATTEMPTS,
                                ),
                            )
                        }
                        delay(PendingRefreshPolicy.PENDING_REFRESH_INTERVAL_MS)
                        val id = _ui.value.pendingRecognitionId
                        if (id == null || !isActive) break
                        val result = runAdapterRefresh(adapter, id)
                        attempts++
                        lastAutoRefreshErrorCode = (result as? RecognitionResult.Failure)?.error?.code
                        _ui.update { it.copy(requestInFlight = false, pendingAutoAttempts = attempts) }
                        when (result) {
                            is RecognitionResult.Success -> {
                                renderSuccess(result.response)
                                // 终态渲染后循环会由下一轮 nextAutoStep 判 STOP_DONE / STOP_TO_MANUAL。
                                if (PendingRefreshPolicy.pendingRecognitionId(
                                        result.response.status, result.response.recognitionId,
                                    ) == null
                                ) break
                            }
                            is RecognitionResult.Failure -> {
                                renderFailure(result, imageMissing = false)
                                break // STOP_NO_RETRY 语义：失败立即停，绝不自动重试。
                            }
                            null -> {
                                renderNoResult()
                                break
                            }
                        }
                    }
                    AutoRefreshStep.STOP_TO_MANUAL -> {
                        _ui.update {
                            it.copy(
                                requestInFlight = false,
                                pendingManualAvailable = true,
                                stageText = str(R.string.emergency_flow_stage_pending_manual),
                                detailText = str(R.string.emergency_flow_pending_manual_detail),
                            )
                        }
                        break
                    }
                    AutoRefreshStep.STOP_NO_RETRY,
                    AutoRefreshStep.STOP_DONE,
                    AutoRefreshStep.STOP_CANCELLED,
                    -> {
                        _ui.update { it.copy(requestInFlight = false) }
                        break
                    }
                }
            }
        }
    }

    private fun renderResult(result: RecognitionResult?, imageMissing: Boolean) {
        _ui.update { it.copy(requestInFlight = false) }
        when (result) {
            is RecognitionResult.Success -> renderSuccess(result.response)
            is RecognitionResult.Failure -> renderFailure(result, imageMissing)
            null -> renderNoResult()
        }
    }

    private fun renderSuccess(response: RecognitionResponse) {
        val summary = renderRecognitionSummary(response)
        // 202=pending：不是「已完成」。只显示阶段文案 + 人工查询按钮，不自动轮询。
        val pendingId = PendingRefreshPolicy.pendingRecognitionId(response.status, response.recognitionId)
        if (pendingId != null) {
            _ui.update {
                it.copy(
                    stage = EmergencyStage.PENDING,
                    stageText = str(R.string.emergency_flow_stage_pending),
                    detailText = str(R.string.emergency_flow_pending_detail),
                    progressPercent = -1,
                    requestInFlight = false,
                    summaryText = summary,
                    candidates = emptyList(),
                    pendingRecognitionId = pendingId,
                    helpExitsEnabled = true,
                )
            }
            return
        }
        _ui.update {
            it.copy(
                stage = EmergencyStage.DONE,
                stageText = str(R.string.emergency_flow_stage_done),
                detailText = "",
                progressPercent = -1,
                requestInFlight = false,
                summaryText = summary,
                // 契约 L43：uncertain 也可能带着已匹配候选（存在目录外名称时），
                // 候选照常渲染，客户端不得仅因状态是 uncertain 就把它们丢掉。
                candidates = response.candidates.map { c ->
                    EmergencyCandidateUi(c.speciesId, c.commonName, c.scientificName)
                },
                pendingRecognitionId = null,
                helpExitsEnabled = true,
            )
        }
        // 「拍完即出病例」：拿到终态结果（含 uncertain / 一个候选都没检出）就地自动打开信息卡。
        // 放在这里而不是链路开头，是为了让卡上的识别快照就是上面这份刚渲染的结论，不另算一份。
        maybeOpenCaseAutomatically()
    }

    /**
     * 自动打开本地伤情信息卡（拍完即出病例）。
     *
     * 两个刻意的约束：
     * 1. 一轮链路只自动打开一次（[autoCaseOpenedForRound]）——自动查询拿到终态后还会再走一次
     *    [renderSuccess]，不能因此把用户已经翻过的页面再顶一次。
     * 2. 咬伤情况恒传 [BiteStatus.UNKNOWN]：照片与识别链路都推不出「有没有被咬」，
     *    自动打开更不能替用户认领一个安全结论。用户可以在卡上改。
     */
    private fun maybeOpenCaseAutomatically() {
        if (autoCaseOpenedForRound) return
        autoCaseOpenedForRound = true
        _event.tryEmit(EmergencyFlowEvent.OpenCare(BiteStatus.UNKNOWN, auto = true))
    }

    private fun renderFailure(result: RecognitionResult.Failure, imageMissing: Boolean) {
        // 未勾选同意：adapter 在本地就失败，照片根本没发出去，文案要如实说明。
        val consentBlocked = result.error.code == RecognitionErrorCode.UPLOAD_CONSENT_REQUIRED
        val detail = when {
            consentBlocked -> str(R.string.emergency_flow_consent_required_detail)
            else -> result.error.message + "\n" + str(R.string.emergency_flow_recognition_failed_suffix)
        }
        _ui.update {
            it.copy(
                stage = EmergencyStage.FAILED,
                stageText = if (consentBlocked) {
                    str(R.string.emergency_flow_consent_required_title)
                } else {
                    str(R.string.emergency_flow_recognition_failed_title)
                },
                detailText = detail,
                progressPercent = -1,
                requestInFlight = false,
                // 失败时不挂任何结果：没有结果就不该有任何可被误读成结论的东西。
                summaryText = "",
                candidates = emptyList(),
                pendingRecognitionId = null,
                helpExitsEnabled = true,
                imageWarningText = if (imageMissing) it.imageWarningText.ifBlank {
                    str(R.string.emergency_flow_image_unavailable)
                } else it.imageWarningText,
            )
        }
    }

    private fun renderNoResult() {
        _ui.update {
            it.copy(
                stage = EmergencyStage.FAILED,
                stageText = str(R.string.emergency_flow_recognition_failed_title),
                detailText = str(R.string.emergency_flow_recognition_no_result) + "\n" +
                    str(R.string.emergency_flow_recognition_failed_suffix),
                progressPercent = -1,
                requestInFlight = false,
                pendingRecognitionId = null,
                helpExitsEnabled = true,
            )
        }
    }

    private fun fail(title: String, detail: String) {
        _ui.update {
            EmergencyFlowUiState(
                connected = it.connected,
                captureEnabled = it.captureEnabled,
                liveEnabled = recognitionMode == RecognitionMode.LIVE,
                consentGranted = it.consentGranted,
                stage = EmergencyStage.FAILED,
                stageText = title,
                detailText = detail,
                imageWarningText = it.imageWarningText,
                // 红线：链路失败不阻断求助。
                helpExitsEnabled = EmergencyFlowPolicy.helpExitsEnabled(
                    pipelineFailed = true,
                    imageUnavailable = preparedImageUri == null,
                    recognitionFailed = true,
                    hasPhotoAtAll = false,
                ),
            )
        }
    }

    private fun setStage(
        stage: EmergencyStage,
        stageText: String,
        detailText: String?,
        progressPercent: Int,
    ) {
        _ui.update {
            it.copy(
                stage = stage,
                stageText = stageText,
                detailText = detailText ?: it.detailText,
                progressPercent = progressPercent,
            )
        }
    }

    override fun onCleared() {
        pipelineJob?.cancel()
        refreshJob?.cancel()
        autoRefreshJob?.cancel()
        activeCall?.cancel()
        activeCall = null
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EmergencyFlowViewModel(application) as T
    }

    private companion object {
        const val POLL_INTERVAL_MS = 800L
        const val POLL_TIMEOUT_MS = 10_000L
    }
}
