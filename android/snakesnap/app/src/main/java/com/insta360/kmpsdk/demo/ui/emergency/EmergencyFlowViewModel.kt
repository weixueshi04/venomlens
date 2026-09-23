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
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.recognition.MockRecognitionAdapter
import com.insta360.kmpsdk.demo.recognition.MockScenario
import com.insta360.kmpsdk.demo.recognition.RecognitionCall
import com.insta360.kmpsdk.demo.recognition.RecognitionImage
import com.insta360.kmpsdk.demo.recognition.RecognitionResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

/** 主线页需要跳转的三个出口，交给 Fragment 执行（VM 不持有 Context/Activity）。 */
sealed interface EmergencyFlowEvent {
    data class OpenCare(val bitten: Boolean) : EmergencyFlowEvent
    data object OpenHospital : EmergencyFlowEvent
    data class OpenSpecies(val speciesId: String, val resultSourceName: String) : EmergencyFlowEvent
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
    /** 顶部固定 MOCK 标注是否显示。 */
    val mockBadgeVisible: Boolean = false,
    /** 识别合规文案（照抄 MockRecognitionActivity.renderSuccess 措辞）。 */
    val summaryText: String = "",
    val candidates: List<EmergencyCandidateUi> = emptyList(),
    val resultSourceName: String = "",
    /**
     * 求助出口（病例卡 / 医院）是否可点。
     * 按产品红线恒为 true —— 见 [EmergencyFlowPolicy.helpExitsEnabled]。
     */
    val helpExitsEnabled: Boolean = true,
    /** 图片降级提示，例如读图失败但仍可记录伤情。 */
    val imageWarningText: String = "",
)

/**
 * 紧急一键流程编排：拍照 → 取回到手机 → MOCK 识别 → 病例卡/医院求助。
 *
 * 拍摄控制完全复用 [com.insta360.kmpsdk.demo.ui.capture.CameraCaptureViewModel]，
 * 本 VM 只负责「拍完之后」的链路与 UI 状态，不重写任何 SDK 拍摄调用。
 */
class EmergencyFlowViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(EmergencyFlowUiState())
    val ui: StateFlow<EmergencyFlowUiState> = _ui.asStateFlow()

    private val _event = MutableSharedFlow<EmergencyFlowEvent>(extraBufferCapacity = 4)
    val event: SharedFlow<EmergencyFlowEvent> = _event.asSharedFlow()

    private var pipelineJob: Job? = null
    private var activeCall: RecognitionCall? = null

    /** 传给病例卡的本机图片 Uri（FileProvider content Uri），失败时为 null。 */
    private var preparedImageUri: Uri? = null
    private var preparedAt: String? = null

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

    fun onHelpClicked(bitten: Boolean) {
        _event.tryEmit(EmergencyFlowEvent.OpenCare(bitten))
    }

    fun onHospitalClicked() {
        _event.tryEmit(EmergencyFlowEvent.OpenHospital)
    }

    fun onCandidateClicked(candidate: EmergencyCandidateUi) {
        _event.tryEmit(EmergencyFlowEvent.OpenSpecies(candidate.speciesId, _ui.value.resultSourceName))
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
        preparedImageUri = null
        preparedAt = null
        activeCall = null

        if (!EmergencyFlowPolicy.shouldEnterPipeline(isPhoto)) {
            _ui.update {
                EmergencyFlowUiState(
                    connected = it.connected,
                    captureEnabled = it.captureEnabled,
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
        // 每次新链路开始，清掉上一轮的 MOCK 标注与候选，避免残留误导。
        _ui.update {
            it.copy(
                mockBadgeVisible = false,
                summaryText = "",
                candidates = emptyList(),
                resultSourceName = "",
                imageWarningText = "",
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

        // ── 阶段 3：生成候选分析（MOCK）─────────────────────────────────────
        setStage(
            EmergencyStage.RECOGNIZING,
            str(R.string.emergency_flow_stage_recognizing),
            null,
            -1,
        )
        val result = runMockRecognition(image?.jpeg)

        // ── 阶段 4：展示结果与出口 ────────────────────────────────────────────
        when (result) {
            is RecognitionResult.Success -> renderSuccess(result)
            is RecognitionResult.Failure -> renderFailure(result, image == null)
            null -> renderNoResult()
        }
    }

    /**
     * 轮询相机相册，按文件名把 `onCaptureFinish` 的路径对到 [WorkWrapper]。
     * 相机写卡有延迟，故按 [POLL_INTERVAL_MS] 轮询，上限约 [POLL_TIMEOUT_MS]。
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

    private suspend fun runMockRecognition(jpeg: ByteArray?): RecognitionResult? =
        suspendCancellableCoroutine { cont: CancellableContinuation<RecognitionResult?> ->
            val adapter = MockRecognitionAdapter(getApplication<Application>().assets, MockScenario.CANDIDATES)
            val requestId = UUID.randomUUID().toString()
            val call = adapter.recognize(requestId, jpeg ?: ByteArray(0), false) { result ->
                activeCall = null
                if (cont.isActive) cont.resume(result)
            }
            activeCall = call
            cont.invokeOnCancellation {
                activeCall = null
                call.cancel()
            }
        }

    private fun renderSuccess(result: RecognitionResult.Success) {
        val response = result.response
        val summary = renderRecognitionSummary(response)
        _ui.update {
            it.copy(
                stage = EmergencyStage.DONE,
                stageText = str(R.string.emergency_flow_stage_done),
                detailText = "",
                progressPercent = -1,
                mockBadgeVisible = true,
                summaryText = summary,
                candidates = response.candidates.map { c ->
                    EmergencyCandidateUi(c.speciesId, c.commonName, c.scientificName)
                },
                resultSourceName = response.resultSource.name,
                helpExitsEnabled = true,
            )
        }
    }

    private fun renderFailure(result: RecognitionResult.Failure, imageMissing: Boolean) {
        _ui.update {
            it.copy(
                stage = EmergencyStage.FAILED,
                stageText = str(R.string.emergency_flow_recognition_failed_title),
                detailText = result.error.message,
                progressPercent = -1,
                mockBadgeVisible = true,
                summaryText = "",
                candidates = emptyList(),
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
                detailText = str(R.string.emergency_flow_recognition_no_result),
                progressPercent = -1,
                mockBadgeVisible = true,
                helpExitsEnabled = true,
            )
        }
    }

    private fun fail(title: String, detail: String) {
        _ui.update {
            EmergencyFlowUiState(
                connected = it.connected,
                captureEnabled = it.captureEnabled,
                stage = EmergencyStage.FAILED,
                stageText = title,
                detailText = detail,
                mockBadgeVisible = it.mockBadgeVisible,
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
