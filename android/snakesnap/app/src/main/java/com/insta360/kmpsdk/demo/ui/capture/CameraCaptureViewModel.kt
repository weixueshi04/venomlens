package com.insta360.kmpsdk.demo.ui.capture

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arashivision.sdk.camera.api.CameraCapture
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.param.CameraParam
import com.arashivision.sdk.camera.api.param.listener.CaptureStatusListener
import com.arashivision.sdk.camera.core.model.ControlMode
import com.arashivision.sdk.camera.core.model.FunctionMode
import com.arashivision.sdk.camera.core.model.capture.CameraCaptureStatus
import com.arashivision.sdk.camera.core.model.capture.LockScreenState
import com.arashivision.sdk.camera.core.model.option.SensorMode
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.ui.common.CameraDemoDisplayLabels
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** 拍摄命令在途方向：开始/停止命令已发送、相机尚未真正切换到对应状态的过渡期。 */
enum class CaptureCommandPhase { STARTING, STOPPING }

sealed interface CaptureUiEffect {
    data class CaptureFinished(val functionMode: FunctionMode) : CaptureUiEffect

    // TODO: 临时兜底，等相机固件修复后移除。
    //  部分机型改视频分辨率后相机侧会自行重启预览流，但不回调任何事件；预览侧需兜底重启预览流。
    data object RecordResolutionChanged : CaptureUiEffect
}

/**
 * 拍摄会话状态（带预览/不带预览共用）。
 *
 * - [busy]：全屏遮罩（切镜头/模式/加载配置）。
 * - [captureCommand]：开始/停止命令在途的过渡遮罩；非空即显示遮罩，文案复用 [loadingText]。
 * - [captureFlowActive]：App 侧拍摄流程进行中，禁止切镜头/模式。
 */
data class CameraCaptureUiState(
    val lensRowVisible: Boolean = false,
    val lensOptions: List<SensorMode> = emptyList(),
    val selectedLens: SensorMode? = null,
    val modeOptions: List<FunctionMode> = emptyList(),
    val selectedMode: FunctionMode? = null,
    val paramRows: List<CaptureParamRow> = emptyList(),
    val captureButtonLabel: String = "",
    val captureButtonEnabled: Boolean = false,
    val resultVisible: Boolean = false,
    val resultSummary: String = "",
    val busy: Boolean = false,
    val loadingText: String? = null,
    val captureCommand: CaptureCommandPhase? = null,
    val captureFlowActive: Boolean = false,
    val progressLabel: String? = null,
    val subStatusLabel: String? = null,
)

data class CaptureParamRow(
    val key: String,
    val displayLabel: String,
    val optionLabels: List<String>,
    val optionValues: List<Any>,
    val selectedIndex: Int,
)

/** 编排拍摄参数、镜头/模式切换与拍摄状态。 */
class CameraCaptureViewModel(
    application: Application,
    private val cameraDeviceProvider: () -> CameraDevice?,
) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(CameraCaptureUiState())
    val ui: StateFlow<CameraCaptureUiState> = _ui.asStateFlow()

    private val _effect = MutableSharedFlow<CaptureUiEffect>(extraBufferCapacity = 1)
    val effect: SharedFlow<CaptureUiEffect> = _effect.asSharedFlow()

    private var captureListener: CaptureStatusListener? = null
    private var attachedCapture: CameraCapture? = null

    private val excludedParamKeys =
        setOf(
            "capture_function_mode",
            "focus_sensor",
        )

    private val recordResolutionParamKey = "record_resolution"

    fun onAppear() {
        viewModelScope.launch { refreshAll() }
    }

    suspend fun lockCameraScreen() {
        val device = cameraDeviceProvider() ?: return
        device.system.setLockScreenState(LockScreenState.LOCK)
            .onFailure { Timber.w(it, "lockCameraScreen failed") }
    }

    /** 当前连接的机型是否为低功耗设备（如 X6）。 */
    fun isLowPowerDevice(): Boolean {
        val device = cameraDeviceProvider() ?: return false
        return device.system.getSupportConfig().getOrNull()?.supportLowPowerMode() == true
    }

    fun unlockCameraScreen() {
        viewModelScope.launch {
            val device = cameraDeviceProvider() ?: return@launch
            device.system.setLockScreenState(LockScreenState.IDLE)
                .onFailure { Timber.w(it, "unlockCameraScreen failed") }
        }
    }

    /**
     * 预览页专用：在 onAppear/refreshAll 前同步置 busy=true，
     * 防止预览流 onOpened 在 refreshAll 完成前误判就绪。
     */
    fun markCapturePreviewConfigLoadingEarly() {
        val device = cameraDeviceProvider()
        if (device == null || !device.isConnected()) return
        if (!_ui.value.busy) {
            _ui.update { it.copy(busy = true) }
        }
    }

    fun selectLens(mode: SensorMode) {
        viewModelScope.launch {
            val cap = cameraDeviceProvider()?.capture ?: return@launch
            performLensSwitchCore(cap, mode)
        }
    }

    /** 带预览页调用；由调用方在切换完成后自行 prepare 播放器。 */
    suspend fun switchLensFromPreview(mode: SensorMode): Boolean {
        val cap = cameraDeviceProvider()?.capture ?: return false
        return performLensSwitchCore(cap, mode)
    }

    fun selectMode(mode: FunctionMode) {
        viewModelScope.launch {
            val cap = cameraDeviceProvider()?.capture ?: return@launch
            performModeSwitchCore(cap, mode)
        }
    }

    /** 带预览页调用；由调用方在切换完成后自行 prepare 播放器。 */
    suspend fun switchModeFromPreview(mode: FunctionMode): Boolean {
        val cap = cameraDeviceProvider()?.capture ?: return false
        return performModeSwitchCore(cap, mode)
    }

    suspend fun pushLocalParam(cap: CameraCapture) {
        if (supportNewCaptureControlFlow) return
        val params = cap.getSupportParam().filter { it.getName() !in excludedParamKeys }
        params.forEach { param ->
            @Suppress("UNCHECKED_CAST")
            val anyParam = param as CameraParam<Any>
            val value =
                anyParam
                    .getValue()
                    .onFailure { Timber.w(it, "pushLocalParam getValue %s", anyParam.getName()) }
                    .getOrNull()
            value?.let {
                anyParam
                    .setValue(value)
                    .onFailure { Timber.w(it, "pushLocalParam setValue %s", value) }
            }
        }
    }

    private suspend fun resyncParamsAfterCapture(cap: CameraCapture) {
        if (supportNewCaptureControlFlow) return
        setBusy(true, getApplication<Application>().getString(R.string.camera_capture_fetching_config))
        cap.syncAllParams()
        setBusy(true, getApplication<Application>().getString(R.string.camera_capture_applying_config))
        pushLocalParam(cap)
        setBusy(false)
    }

    private suspend fun performLensSwitchCore(
        cap: CameraCapture,
        mode: SensorMode,
    ): Boolean {
        Timber.d("performLensSwitchCore lens=%s", mode)
        _ui.update {
            it.copy(
                captureFlowActive = false,
                progressLabel = null,
                subStatusLabel = null,
                captureCommand = null,
            )
        }
        setBusy(
            true,
            getApplication<Application>().getString(R.string.camera_capture_switching_lens)
        )
        val setOk =
            cap.lensType
                .setValue(mode)
                .onFailure { e ->
                    Timber.w(e, "set lensType")
                    toastFailure(e.message)
                }.isSuccess
        setBusy(
            true,
            getApplication<Application>().getString(R.string.camera_capture_fetching_config)
        )
        cap.syncAllParams()
        setBusy(
            true,
            getApplication<Application>().getString(R.string.camera_capture_applying_config)
        )
        cap.functionMode.setValue(FunctionMode.VIDEO_NORMAL).onFailure {
            Timber.w("set FunctionMode to default(VIDEO_NORMAL) fail when switch lensType.")
        }
        pushLensModesAndParams(cap)
        pushLocalParam(cap)
        setBusy(false)
        return setOk
    }

    private suspend fun performModeSwitchCore(
        cap: CameraCapture,
        mode: FunctionMode,
    ): Boolean {
        Timber.d("performModeSwitchCore mode=%s", mode)
        if (mode == FunctionMode.NONE) return false
        _ui.update { it.copy(captureCommand = null) }
        setBusy(
            true,
            getApplication<Application>().getString(R.string.camera_capture_switching_mode)
        )
        var setOk = false
        try {
            cap.functionMode
                .setValue(mode)
                .onSuccess { setOk = true }
                .onFailure { e ->
                    Timber.w(e, "set functionMode")
                    toastFailure(e.message)
                    return false
                }
            setBusy(
                true,
                getApplication<Application>().getString(R.string.camera_capture_fetching_config)
            )
            cap.syncAllParams()
            setBusy(
                true,
                getApplication<Application>().getString(R.string.camera_capture_applying_config)
            )
            pushLocalParam(cap)
            val working = runCatching { cap.isWorking() }.getOrDefault(false)
            _ui.update {
                val (label, enabled) =
                    primaryButtonLabelAndEnabled(
                        mode,
                        captureFlowActive = false,
                        sdkWorking = working,
                    )
                it.copy(
                    busy = false,
                    captureFlowActive = false,
                    selectedMode = mode,
                    paramRows = computeParamRows(cap, mode),
                    captureButtonLabel = label,
                    captureButtonEnabled = enabled,
                    progressLabel = null,
                    subStatusLabel = null,
                )
            }
        } finally {
            if (_ui.value.busy) {
                setBusy(false)
            }
        }
        return setOk
    }

    fun onParamSelectionChanged(
        rowKey: String,
        index: Int,
    ) {
        viewModelScope.launch {
            val cap = cameraDeviceProvider()?.capture ?: return@launch
            val row = _ui.value.paramRows.find { it.key == rowKey } ?: return@launch
            if (index < 0 || index >= row.optionValues.size) return@launch
            val param =
                cap.getSupportParam().find { it.getName() == rowKey } ?: return@launch
            val value = row.optionValues[index]
            if (index == row.selectedIndex) {
                return@launch
            }

            @Suppress("UNCHECKED_CAST")
            val anyParam = param as CameraParam<Any>
            val currentValue =
                anyParam
                    .getValue()
                    .onFailure { Timber.w(it, "getValue $rowKey before setValue") }
                    .getOrNull()
            if (currentValue == value) {
                return@launch
            }

            Timber.d("onParamSelectionChanged key=%s value=%s", rowKey, value)
            val setResult = anyParam.setValue(value)
            setResult.onFailure { Timber.w(it, "setValue %s", rowKey) }
            if (rowKey == recordResolutionParamKey && setResult.isSuccess) {
                _effect.tryEmit(CaptureUiEffect.RecordResolutionChanged)
            }
//            cap.syncAllParams()
            _ui.update { it.copy(paramRows = computeParamRows(cap, it.selectedMode)) }
        }
    }

    fun onPrimaryCaptureButtonClicked() {
        val pre = _ui.value
        val mode = pre.selectedMode ?: return
        if (mode == FunctionMode.NONE) return
        // 已上遮罩或命令在途：在点击的同一帧同步丢弃重复点击
        if (pre.busy || pre.captureCommand != null) return

        // 在首个挂起点之前同步上遮罩并锁定，杜绝 isWorking() 期间的重复点击；
        // 方向先按 App 侧状态预判，真实判定在挂起后完成
        val provisionalStop = pre.captureFlowActive && timedOrCount(mode)
        setCaptureCommand(provisionalStop)

        viewModelScope.launch {
            val cap = cameraDeviceProvider()?.capture
            if (cap == null) {
                clearCaptureCommand()
                return@launch
            }
            val sdkWorking = runCatching { cap.isWorking() }.getOrDefault(false)
            if (!canTapPrimary(mode, pre.captureFlowActive, sdkWorking)) {
                clearCaptureCommand() // 不可操作，撤销预上的遮罩
                return@launch
            }
            val stopNow =
                timedOrCount(mode) &&
                        showStopForTimedCount(mode, pre.captureFlowActive, sdkWorking)
            Timber.d(
                "onPrimaryCaptureButtonClicked mode=%s stopNow=%s sdkWorking=%s",
                mode,
                stopNow,
                sdkWorking,
            )
            if (!stopNow) {
                clearResult()
            }
            // 遮罩已在点击瞬间同步上好；此处不重置 captureCommand，
            // 避免在 isWorking() 挂起期间已被终态回调清除后又被复活导致卡死。
            // 能走到这里说明 canTapPrimary 通过，此时预判方向必等于 stopNow。
            _ui.update { cur ->
                cur.copy(
                    captureFlowActive = if (!stopNow) true else cur.captureFlowActive,
                )
            }
            val actionOk =
                runCatching {
                    if (stopNow) {
                        cap.stopCapture()
                    } else {
                        cap.startCapture()
                    }
                }.onFailure { Timber.w(it, "primary capture action") }
                    .isSuccess
            val w = runCatching { cap.isWorking() }.getOrDefault(false)
            Timber.d(
                "capture action done stopNow=%s actionOk=%s sdkWorking=%s",
                stopNow,
                actionOk,
                w,
            )
            _ui.update { cur ->
                val modeNow = cur.selectedMode ?: mode
                val newFlow =
                    if (!stopNow && !actionOk) false else cur.captureFlowActive
                val (label, enabled) =
                    primaryButtonLabelAndEnabled(
                        modeNow,
                        captureFlowActive = newFlow,
                        sdkWorking = w,
                    )
                cur.copy(
                    captureFlowActive = newFlow,
                    captureButtonLabel = label,
                    captureButtonEnabled = enabled,
                    // 命令发送失败：立即移除遮罩，避免卡死；成功则等回调清除
                    captureCommand = if (actionOk) cur.captureCommand else null,
                    loadingText = if (actionOk) cur.loadingText else null,
                )
            }
        }
    }

    val supportNewCaptureControlFlow: Boolean =
        cameraDeviceProvider()
            ?.system
            ?.getSupportConfig()
            ?.getOrNull()
            ?.supportNewCaptureControlFlow() == true

    fun clearResult() {
        _ui.update { it.copy(resultVisible = false, resultSummary = "") }
    }

    // captureTime 单位为秒（实测确认）
    private fun formatCaptureTime(captureTimeSec: Long): String {
        val totalSec = captureTimeSec.coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val app = getApplication<Application>()
        return if (totalSec >= 3600) {
            app.getString(R.string.camera_capture_progress_time_long, h, m, s)
        } else {
            app.getString(R.string.camera_capture_progress_time_short, m, s)
        }
    }

    private fun formatCaptureCount(count: Int): String =
        getApplication<Application>().getString(
            R.string.camera_capture_progress_count,
            count.coerceAtLeast(0),
        )

    override fun onCleared() {
        detachCaptureListener()
        super.onCleared()
    }

    private suspend fun refreshAll() {
        val device = cameraDeviceProvider()
        if (device == null || !device.isConnected()) {
            detachCaptureListener()
            val app = getApplication<Application>()
            _ui.value =
                CameraCaptureUiState(
                    captureButtonLabel = app.getString(R.string.camera_capture_btn_start),
                    captureButtonEnabled = false,
                )
            return
        }
        val cap = device.capture
        setBusy(
            true,
            getApplication<Application>().getString(R.string.camera_capture_fetching_config)
        )
        try {
            cap.syncAllParams()
            attachCaptureListener(cap)
            setBusy(
                true,
                getApplication<Application>().getString(R.string.camera_capture_applying_config)
            )
            pushLensModesAndParams(cap)
            pushLocalParam(cap)
        } catch (e: Exception) {
            Timber.e(e, "refreshAll failed")
        } finally {
            setBusy(false)
        }
        Timber.d("refreshAll done")
    }

    private suspend fun pushLensModesAndParams(cap: CameraCapture) {
        val supportedLenses =
            cap.lensType
                .getSupported()
                .getOrNull()
                .orEmpty()
                .filter { it != SensorMode.UNKNOWN }
        val currentLens = cap.lensType.getValue().getOrNull()
        val lensVisible = supportedLenses.size > 1
        val lensOpts = if (lensVisible) supportedLenses else emptyList()
        val selLens = currentLens?.takeIf { supportedLenses.contains(it) } ?: supportedLenses.firstOrNull()
        if(supportNewCaptureControlFlow) {
            if (selLens != null) {
                cap.lensType
                    .setValue(selLens)
                    .onFailure { Timber.w(it, "push local lensType %s to camera failed", selLens) }
            }
        }
        Timber.d("supportedLenses = $supportedLenses, currentLens = $currentLens, lensOpts = $lensOpts, selLens = $selLens")

        val supportedModes =
            cap.functionMode
                .getSupported()
                .getOrNull()
                .orEmpty()
                .filter { it != FunctionMode.NONE && it != FunctionMode.VIDEO_LIVE }
        val currentMode = cap.functionMode.getValue().getOrNull()
        val selMode =
            currentMode?.takeIf { supportedModes.contains(it) } ?: FunctionMode.VIDEO_NORMAL
        if(supportNewCaptureControlFlow) {
            cap.functionMode
                .setValue(selMode)
                .onFailure { Timber.w(it, "push local functionMode %s to camera failed", selMode) }
        }
        Timber.d("supportedModes = $supportedModes, currentMode = $currentMode, selMode = $selMode")
        if(supportNewCaptureControlFlow) {
            cap.syncAllParams() // 修改 lens 和 functionMode 后，重新同步参数列表
        }
        val rows = computeParamRows(cap, selMode)
        val working = runCatching { cap.isWorking() }.getOrDefault(false)

        _ui.update {
            // 相机已在拍摄时进入页面：SDK working=true 代表拍摄流程实际已激活
            val effectiveFlowActive = if (working) true else it.captureFlowActive
            val (label, enabled) =
                primaryButtonLabelAndEnabled(
                    selMode,
                    captureFlowActive = effectiveFlowActive,
                    sdkWorking = working,
                )
            it.copy(
                lensRowVisible = lensVisible,
                lensOptions = lensOpts,
                selectedLens = selLens,
                modeOptions = supportedModes,
                selectedMode = selMode,
                captureButtonLabel = label,
                captureButtonEnabled = enabled,
                paramRows = rows,
                captureFlowActive = effectiveFlowActive,
            )
        }
    }

    private fun scheduleRefreshCapturingState() {
        viewModelScope.launch {
            val cap = cameraDeviceProvider()?.capture
            if (cap == null) {
                _ui.update {
                    val mode = it.selectedMode
                    val (label, enabled) =
                        primaryButtonLabelAndEnabled(
                            mode,
                            captureFlowActive = false,
                            sdkWorking = false,
                        )
                    it.copy(
                        captureFlowActive = false,
                        captureButtonEnabled = enabled,
                        captureButtonLabel = label,
                        progressLabel = null,
                        subStatusLabel = null,
                    )
                }
                return@launch
            }
            refreshCapturingStateFromCapture(cap)
        }
    }

    private suspend fun refreshCapturingStateFromCapture(cap: CameraCapture) {
        val working = runCatching { cap.isWorking() }.getOrDefault(false)
        val mode = _ui.value.selectedMode
        _ui.update {
            val (label, enabled) =
                primaryButtonLabelAndEnabled(
                    mode,
                    captureFlowActive = it.captureFlowActive,
                    sdkWorking = working,
                )
            it.copy(
                captureButtonLabel = label,
                captureButtonEnabled = enabled,
            )
        }
    }

    private fun timedOrCount(mode: FunctionMode?): Boolean =
        mode != null &&
                (
                        mode.controlMode == ControlMode.MANUAL_START_STOP_TIMED ||
                                mode.controlMode == ControlMode.MANUAL_START_STOP_COUNT
                        )

    /** 间隔/星轨在两次快门间 isWorking 可能为 false，仍应显示「停止拍摄」。 */
    private fun showStopForTimedCount(
        mode: FunctionMode,
        captureFlowActive: Boolean,
        sdkWorking: Boolean,
    ): Boolean =
        captureFlowActive &&
                (
                        sdkWorking ||
                                mode == FunctionMode.PHOTO_INTERVAL ||
                                mode == FunctionMode.PHOTO_STARLAPSE ||
                                mode == FunctionMode.VIDEO_TIMELAPSE ||
                                mode == FunctionMode.PHOTO_BURST
                        )

    private fun primaryButtonLabelAndEnabled(
        mode: FunctionMode?,
        captureFlowActive: Boolean,
        sdkWorking: Boolean,
    ): Pair<String, Boolean> {
        val app = getApplication<Application>()
        if (mode == null || mode == FunctionMode.NONE) {
            return app.getString(R.string.camera_capture_btn_start) to false
        }
        if (!captureFlowActive) {
            return app.getString(R.string.camera_capture_btn_start) to true
        }
        if (timedOrCount(mode)) {
            return if (showStopForTimedCount(mode, captureFlowActive, sdkWorking)) {
                app.getString(R.string.camera_capture_btn_stop) to true
            } else {
                middleCaptureProgressLabel(mode) to false
            }
        }
        return middleCaptureProgressLabel(mode) to false
    }

    private fun middleCaptureProgressLabel(mode: FunctionMode): String {
        val app = getApplication<Application>()
        return when (mode.controlMode) {
            ControlMode.MANUAL_START_AUTO_STOP -> app.getString(R.string.camera_capture_in_progress)
            else -> app.getString(R.string.camera_capture_video_command_in_progress)
        }
    }

    private fun canTapPrimary(
        mode: FunctionMode,
        captureFlowActive: Boolean,
        sdkWorking: Boolean,
    ): Boolean {
        if (!captureFlowActive) return true
        if (timedOrCount(mode) &&
            showStopForTimedCount(
                mode,
                captureFlowActive,
                sdkWorking,
            )
        ) {
            return true
        }
        if (timedOrCount(mode)) return false
        if (mode.controlMode == ControlMode.MANUAL_START_AUTO_STOP && sdkWorking) return false
        if (mode.controlMode == ControlMode.MANUAL_NONE) return false
        return true
    }

    private suspend fun computeParamRows(
        cap: CameraCapture,
        mode: FunctionMode?,
    ): List<CaptureParamRow> {
        val all = cap.getSupportParam()
        val filtered =
            all.filter { p ->
                val n = p.getName()
                n !in excludedParamKeys
            }
        return filtered.mapNotNull { buildRow(it, mode) }
    }

    private suspend fun buildRow(
        param: CameraParam<*>,
        mode: FunctionMode?,
    ): CaptureParamRow? {
        val key = param.getName()

        @Suppress("UNCHECKED_CAST")
        val anyParam = param as CameraParam<Any>
        val supported = anyParam.getSupported().getOrElse { return null }
        if (supported.isEmpty()) return null
        val current = anyParam.getValue().getOrElse { return null }
        val labels = supported.map { valueLabel(it) }
        val selectedIndex =
            supported.indexOf(current).takeIf { it >= 0 }
                ?: supported
                    .indexOfFirst { valueLabel(it) == valueLabel(current) }
                    .takeIf { it >= 0 }
                ?: 0
        return CaptureParamRow(
            key = key,
            displayLabel = CameraDemoDisplayLabels.captureParamLabel(getApplication(), key, mode),
            optionLabels = labels,
            optionValues = supported,
            selectedIndex = selectedIndex,
        )
    }

    private fun valueLabel(v: Any): String = v.toString()

    private fun setBusy(busy: Boolean, loadingText: String? = null) {
        _ui.update { it.copy(busy = busy, loadingText = loadingText) }
    }

    /** 上「命令在途」遮罩并设置对应文案（开始/停止）。 */
    private fun setCaptureCommand(stop: Boolean) {
        val phase = if (stop) CaptureCommandPhase.STOPPING else CaptureCommandPhase.STARTING
        val msg =
            getApplication<Application>().getString(
                if (stop) R.string.camera_capture_stopping else R.string.camera_capture_starting,
            )
        _ui.update { it.copy(captureCommand = phase, loadingText = msg) }
    }

    /** 无条件移除「命令在途」遮罩。 */
    private fun clearCaptureCommand() {
        _ui.update {
            if (it.captureCommand != null) it.copy(captureCommand = null, loadingText = null) else it
        }
    }

    /** 仅清除「开始」在途遮罩：进度/工作态回调到达即代表拍摄已真正开始。 */
    private fun clearCaptureCommandIfStarting() {
        _ui.update {
            if (it.captureCommand == CaptureCommandPhase.STARTING) {
                it.copy(captureCommand = null, loadingText = null)
            } else {
                it
            }
        }
    }

    private fun toastFailure(msg: String?) {
        val app = getApplication<Application>()
        _ui.update {
            val mode = it.selectedMode
            val (label, enabled) =
                primaryButtonLabelAndEnabled(
                    mode,
                    captureFlowActive = false,
                    sdkWorking = false,
                )
            it.copy(
                captureFlowActive = false,
                captureButtonLabel = label,
                captureButtonEnabled = enabled,
                resultVisible = true,
                resultSummary =
                    msg
                        ?: app.getString(R.string.operation_failed_generic),
                progressLabel = null,
                subStatusLabel = null,
                captureCommand = null,
                loadingText = null,
            )
        }
    }

    private fun attachCaptureListener(cap: CameraCapture) {
        if (attachedCapture === cap && captureListener != null) return
        detachCaptureListener()
        val listener =
            object : CaptureStatusListener {
                override fun onCaptureStarting(functionMode: FunctionMode) {
                    scheduleRefreshCapturingState()
                }

                override fun onCaptureWorking(functionMode: FunctionMode) {
                    clearCaptureCommandIfStarting()
                    scheduleRefreshCapturingState()
                }

                override fun onCaptureStopping(functionMode: FunctionMode) {
                    scheduleRefreshCapturingState()
                }

                override fun onCaptureFinish(
                    functionMode: FunctionMode,
                    filePaths: List<String>,
                ) {
                    Timber.d("onCaptureFinish mode=%s files=%d", functionMode, filePaths.size)
                    val paths = filePaths.joinToString("\n")
                    _ui.update {
                        val mode = it.selectedMode
                        val (label, enabled) =
                            primaryButtonLabelAndEnabled(
                                mode,
                                captureFlowActive = false,
                                sdkWorking = false,
                            )
                        it.copy(
                            resultVisible = true,
                            resultSummary =
                                getApplication<Application>().getString(
                                    R.string.camera_capture_finish,
                                    CameraDemoDisplayLabels.functionMode(
                                        getApplication(),
                                        functionMode,
                                    ),
                                    paths,
                                ),
                            captureFlowActive = false,
                            captureButtonLabel = label,
                            captureButtonEnabled = enabled,
                            progressLabel = null,
                            subStatusLabel = null,
                            captureCommand = null,
                            loadingText = null,
                        )
                    }
                    _effect.tryEmit(CaptureUiEffect.CaptureFinished(functionMode))
                    viewModelScope.launch {
                        val cap = cameraDeviceProvider()?.capture ?: return@launch
                        resyncParamsAfterCapture(cap)
                    }
                    scheduleRefreshCapturingState()
                }

                override fun onCaptureError(
                    functionMode: FunctionMode,
                    throwable: Throwable,
                ) {
                    Timber.w(throwable, "onCaptureError mode=%s", functionMode)
                    _ui.update {
                        val mode = it.selectedMode
                        val (label, enabled) =
                            primaryButtonLabelAndEnabled(
                                mode,
                                captureFlowActive = false,
                                sdkWorking = false,
                            )
                        it.copy(
                            captureFlowActive = false,
                            captureButtonLabel = label,
                            captureButtonEnabled = enabled,
                            resultVisible = true,
                            resultSummary =
                                getApplication<Application>().getString(
                                    R.string.camera_capture_error,
                                    CameraDemoDisplayLabels.functionMode(
                                        getApplication(),
                                        functionMode,
                                    ),
                                    throwable.message ?: "",
                                ),
                            progressLabel = null,
                            captureCommand = null,
                            loadingText = null,
                        )
                    }
                    scheduleRefreshCapturingState()
                }

                override fun onCaptureTimeChanged(
                    functionMode: FunctionMode,
                    captureTime: Long,
                ) {
                    val label = formatCaptureTime(captureTime)
                    Timber.d(
                        "onCaptureTimeChanged mode=%s time=%d label=%s",
                        functionMode,
                        captureTime,
                        label,
                    )
                    clearCaptureCommandIfStarting()
                    _ui.update { s ->
                        // 仅刷新进度文案，不改写 captureFlowActive/按钮态：那些由开始/结束的
                        // 权威入口维护。本回调与 onCaptureFinish 到达顺序不保证，若 finish 已
                        // 先到（captureFlowActive=false），迟到的通知不应再刷新已清空的文案。
                        if (!s.captureFlowActive || s.progressLabel == label) return@update s
                        s.copy(progressLabel = label)
                    }
                }

                override fun onCaptureCountChanged(
                    functionMode: FunctionMode,
                    captureCount: Int,
                ) {
                    val label = formatCaptureCount(captureCount)
                    Timber.d(
                        "onCaptureCountChanged mode=%s count=%d label=%s",
                        functionMode,
                        captureCount,
                        label,
                    )
                    clearCaptureCommandIfStarting()
                    _ui.update { s ->
                        // 仅刷新进度文案，不改写 captureFlowActive/按钮态：那些由开始/结束的
                        // 权威入口维护。本回调与 onCaptureFinish 到达顺序不保证，若 finish 已
                        // 先到（captureFlowActive=false），迟到的通知不应再刷新已清空的文案。
                        if (!s.captureFlowActive || s.progressLabel == label) return@update s
                        s.copy(progressLabel = label)
                    }
                }

                override fun onCaptureSubStatusChanged(
                    functionMode: FunctionMode,
                    subStatus: CameraCaptureStatus.SubStatus,
                ) {
                    Timber.d("onCaptureSubStatusChanged mode=%s subStatus=%s", functionMode, subStatus)
                    when (subStatus) {
                        CameraCaptureStatus.SubStatus.RECORD_CANCEL,
                        CameraCaptureStatus.SubStatus.PHOTO_CANCEL,
                            -> {
                            _ui.update {
                                val mode = it.selectedMode
                                val (label, enabled) =
                                    primaryButtonLabelAndEnabled(
                                        mode,
                                        captureFlowActive = false,
                                        sdkWorking = false,
                                    )
                                it.copy(
                                    captureFlowActive = false,
                                    captureButtonLabel = label,
                                    captureButtonEnabled = enabled,
                                    progressLabel = null,
                                    subStatusLabel = null,
                                    captureCommand = null,
                                    loadingText = null,
                                )
                            }
                            scheduleRefreshCapturingState()
                        }

                        CameraCaptureStatus.SubStatus.EXPOSURE,
                        CameraCaptureStatus.SubStatus.PHOTO_SAVE,
                        CameraCaptureStatus.SubStatus.RECORD_SAVE,
                            -> {
                            // 部分机型 onCaptureFinish 会先于本回调到达，此时拍摄流程已结束，
                            // 不应再点亮子状态文案，否则会在结果展示之后残留一条过期状态。
                            _ui.update {
                                if (!it.captureFlowActive) return@update it
                                val label =
                                    CameraDemoDisplayLabels.captureSubStatus(getApplication(), subStatus)
                                it.copy(subStatusLabel = label)
                            }
                        }

                        else -> {}
                    }
                }
            }
        captureListener = listener
        attachedCapture = cap
        cap.registerCaptureStatusListener(listener)
    }

    private fun detachCaptureListener() {
        val cap = attachedCapture
        val listener = captureListener
        if (cap != null && listener != null) {
            cap.unregisterCaptureStatusListener(listener)
        }
        attachedCapture = null
        captureListener = null
    }

    class Factory(
        private val application: Application,
        private val cameraDeviceProvider: () -> CameraDevice?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CameraCaptureViewModel(application, cameraDeviceProvider) as T
    }
}
