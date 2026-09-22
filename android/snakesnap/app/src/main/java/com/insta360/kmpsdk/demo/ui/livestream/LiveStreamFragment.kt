package com.insta360.kmpsdk.demo.ui.livestream

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.arashivision.sdk.camera.api.param.listener.CameraPostureUpdate
import com.arashivision.sdk.camera.api.preview.CameraLiveListener
import com.arashivision.sdk.camera.api.preview.CameraLiveParams
import com.arashivision.sdk.camera.api.preview.CameraStreamListener
import com.arashivision.sdk.camera.api.preview.PreviewStreamFrame
import com.arashivision.sdk.camera.api.preview.PreviewStreamParamsUpdate
import com.arashivision.sdk.camera.core.model.FunctionMode
import com.arashivision.sdk.camera.core.model.notify.CameraPosture
import com.arashivision.sdk.common.exception.InstaException
import com.arashivision.sdk.media.api.common.RenderModel
import com.arashivision.sdk.media.api.listener.PlayerViewListener
import com.arashivision.sdk.media.api.params.PreviewParams
import com.arashivision.sdk.media.core.model.OffsetData
import com.arashivision.sdk.media.player.preview.InstaCapturePlayerView
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentLiveStreamBinding
import com.insta360.kmpsdk.demo.ui.common.observeCameraDisconnectedNavigateToConnection
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import com.arashivision.sdk.media.core.model.WindowCropInfo as MediaWindowCropInfo

/**
 * 直播推流页。预览/统计/姿态/退出逻辑与拍摄预览页一致，直播推流走 camera 侧的
 * [com.arashivision.sdk.camera.api.CameraPreview] 接口：
 *
 * 1. 发起推流：device.preview.startLive(CameraLiveParams)（挂起，返回 Result）
 * 2. 停止推流：device.preview.stopLive()
 * 3. 状态回调：device.preview.registerCameraLiveListener([CameraLiveListener])
 *
 * 注意：[CameraLiveParams.bitrate] 单位为 Mbps（底层内部转 bps）。
 */
class LiveStreamFragment : Fragment() {
    private companion object {
        private const val SAMPLE_WINDOW_MS = 2000L
    }

    private var _binding: FragmentLiveStreamBinding? = null
    private val binding get() = _binding!!

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    private val viewModel: LiveStreamViewModel by viewModels()

    private var previewView: InstaCapturePlayerView? = null
    private var latestPosture: CameraPosture = CameraPosture.CAMERA_POSTURE_ROTATE_0

    // 切换分辨率需先停后开，置位后在 onStopped 确认释放时再发起 startLive，避免在底层 live 释放前重入
    private var pendingRestartAfterStop = false

    // 预览实际分辨率，用于按比例计算 player 高度（避免 wrap_content 父容器内 MATCH_PARENT 子 view 塌缩为 0）
    private var previewWidth: Int = 1280
    private var previewHeight: Int = 720

    private val previewLayoutChangeListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPreviewAspect() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLiveStreamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        observeCameraDisconnectedNavigateToConnection(connectionViewModel)

        initPreviewPlayer()
        binding.previewPlaceholder.addOnLayoutChangeListener(previewLayoutChangeListener)

        binding.backLink.setOnClickListener { handleExit() }
        // 拦截系统返回键，推流中需先停流再退出
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleExit()
            },
        )
        binding.rtmpInput.setText(viewModel.uiState.value.rtmpUrl)
        binding.rtmpInput.doAfterTextChanged { editable ->
            viewModel.onRtmpUrlChange(editable?.toString().orEmpty())
        }
        binding.chip720.setOnClickListener { switchResolution(Resolution.HD720) }
        binding.chip1080.setOnClickListener { switchResolution(Resolution.HD1080) }
        binding.btnLive.setOnClickListener {
            when (viewModel.uiState.value.livePhase) {
                is LivePhase.Starting, is LivePhase.Pushing -> stopLive()
                is LivePhase.Idle, is LivePhase.Failed -> startLive()
                is LivePhase.Stopping -> Unit // 停止处理中，等 onStopped 确认释放，按钮此时已禁用
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is LiveStreamEvent.ShowToast ->
                            Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 进入页面自动开启预览（不再提供手动开启/停止），仅在尚未预览时触发
        val previewPhase = viewModel.uiState.value.previewPhase
        if (previewPhase is PreviewPhase.Idle || previewPhase is PreviewPhase.Failed) {
            startPreview()
        }
    }

    override fun onDestroyView() {
        Timber.d("onDestroyView")
        _binding?.previewPlaceholder?.removeOnLayoutChangeListener(previewLayoutChangeListener)
        // 销毁时绕过状态守卫直接释放推流资源，确保底层 live 不残留到下次进入。
        // 用 callback 版 stopLive：viewLifecycleScope 此刻已取消，挂起调用无法保证执行完。
        runCatching {
            connectionViewModel.getCameraDevice()?.preview?.stopLive(
                object : com.arashivision.sdk.common.callback.Callback<Unit> {
                    override fun onSuccess(result: Unit) {}
                    override fun onThrowable(throwable: Throwable) {
                        Timber.w(throwable, "stopLive on destroy")
                    }
                },
            )
        }
        runCatching { stopPreview() }
        releasePreviewPlayer()
        super.onDestroyView()
        _binding = null
    }

    private fun initPreviewPlayer() {
        if (previewView != null || _binding == null) return
        previewView = InstaCapturePlayerView(requireContext()).also { view ->
            view.setListener(playerViewListener)
            view.setLifecycle(lifecycle)
            binding.previewPlaceholder.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
        }
        applyPreviewAspect()
    }

    /**
     * 按预览分辨率给 player 显式设定高度。预览播放时占位文字被隐藏，若 player 仍是 MATCH_PARENT，
     * 在 wrap_content 的父容器里会被解析成 0 高度，导致整张卡片塌缩成一条线。
     */
    private fun applyPreviewAspect() {
        val playerView = previewView ?: return
        if (_binding == null) return
        val containerWidth = binding.previewPlaceholder.width
        if (containerWidth <= 0) return
        val videoWidth = previewWidth.coerceAtLeast(1)
        val videoHeight = previewHeight.coerceAtLeast(1)
        val targetHeight = (containerWidth / (videoWidth.toFloat() / videoHeight.toFloat()))
            .toInt()
            .coerceAtLeast(1)
        val lp = (playerView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(containerWidth, targetHeight, Gravity.CENTER)
        if (lp.width != containerWidth || lp.height != targetHeight || lp.gravity != Gravity.CENTER) {
            lp.width = containerWidth
            lp.height = targetHeight
            lp.gravity = Gravity.CENTER
            playerView.layoutParams = lp
        }
    }
    // region UI 渲染
    private fun render(state: LiveStreamUiState) {
        if (_binding == null) return

        binding.statusBadge.text = getString(
            when (state.previewPhase) {
                PreviewPhase.Idle -> R.string.live_status_idle
                PreviewPhase.Starting -> R.string.live_status_starting
                PreviewPhase.Playing -> R.string.live_status_playing
                is PreviewPhase.Failed -> R.string.live_status_failed
            },
        )
        binding.previewPlaceholderText.isVisible = state.previewPhase !is PreviewPhase.Playing
        // 推流状态徽章：开启中（主题色）/ LIVE（红）/ 停止中（灰），覆盖两个异步过渡态
        renderLiveBadge(state.livePhase)

        val stats = state.stats
        if (stats != null && state.previewPhase is PreviewPhase.Playing) {
            val preview = getString(R.string.live_stats_preview_format, stats.previewFps, stats.previewMbps)
            binding.statsOverlay.text = if (stats.liveFps != null) {
                preview + "\n" + getString(R.string.live_stats_push_format, stats.liveFps)
            } else {
                preview
            }
            binding.statsOverlay.isVisible = true
        } else {
            binding.statsOverlay.isVisible = false
        }

        // 推流相关进行中（启动/推流/停止）时锁定 rtmp 输入
        val livePhase = state.livePhase
        val active = livePhase is LivePhase.Starting || livePhase is LivePhase.Pushing
        val stopping = livePhase is LivePhase.Stopping
        val locked = active || stopping
        binding.rtmpInputLayout.isEnabled = !locked
        binding.rtmpInput.isEnabled = !locked
        binding.chip720.isChecked = state.resolution == Resolution.HD720
        binding.chip1080.isChecked = state.resolution == Resolution.HD1080

        // 按钮文案：停止中→「停止中…」（禁用）；活跃→「停止推流」；否则「开始推流」
        binding.btnLive.setText(
            when {
                stopping -> R.string.live_stopping
                active -> R.string.live_stop
                else -> R.string.live_start
            },
        )
        binding.btnLive.setBackgroundColor(
            requireContext().getColor(if (active || stopping) R.color.live_red else R.color.demo_accent),
        )
        binding.btnLive.isEnabled = when {
            active -> state.canStopLive
            stopping -> false
            else -> state.canStartLive
        }

        val hint = when {
            state.previewPhase !is PreviewPhase.Playing -> getString(R.string.live_hint_need_preview)
            state.rtmpUrl.isBlank() -> getString(R.string.live_hint_need_rtmp)
            state.livePhase is LivePhase.Failed ->
                getString(R.string.live_hint_failed, state.livePhase.message)
            else -> null
        }
        binding.liveHint.text = hint.orEmpty()
        binding.liveHint.isVisible = hint != null
    }

    private fun renderLiveBadge(livePhase: LivePhase) {
        val badge = binding.liveBadge
        val spec = when (livePhase) {
            is LivePhase.Starting -> R.string.live_badge_starting to R.color.demo_accent
            is LivePhase.Pushing -> R.string.live_badge to R.color.live_red
            is LivePhase.Stopping -> R.string.live_badge_stopping to R.color.demo_secondary_text
            else -> null
        }
        if (spec == null) {
            badge.isVisible = false
            return
        }
        badge.setText(spec.first)
        badge.setBackgroundColor(requireContext().getColor(spec.second))
        badge.isVisible = true
    }
    // endregion
    // region 预览生命周期
    private fun startPreview() {
        val device = connectionViewModel.getCameraDevice()
        if (device == null) {
            viewModel.emitToast(getString(R.string.live_toast_no_camera))
            return
        }
        Timber.d("startPreview")
        viewModel.onPreviewPhaseChange(PreviewPhase.Starting)
        viewLifecycleOwner.lifecycleScope.launch {
            device.capture.functionMode.setValue(FunctionMode.VIDEO_LIVE)
            Timber.d("startPreview: mode switched to VIDEO_LIVE, starting stream")
            viewModel.emitToast(getString(R.string.live_toast_mode_switched))
            runCatching {
                device.preview.init(requireActivity().application)
                device.preview.registerCameraStreamListener(streamListener)
                device.preview.registerCameraLiveListener(cameraLiveListener)
                device.preview.registerPostureListener(postureListener)
                device.preview.startStream()
            }.onFailure {
                Timber.e(it, "startPreview failed")
                viewModel.onPreviewPhaseChange(PreviewPhase.Failed(it.message ?: getString(R.string.live_unknown_error)))
                viewModel.emitToast(getString(R.string.live_toast_start_preview_failed, it.message.orEmpty()))
            }
        }
    }

    private fun stopPreview() {
        Timber.d("stopPreview")
        val device = connectionViewModel.getCameraDevice() ?: run {
            viewModel.onPreviewPhaseChange(PreviewPhase.Idle)
            return
        }
        // 释放顺序对齐 PreviewFragment：解注册 → 解绑 pipeline → 停流，避免 SDK 侧悬挂渲染管线引用
        runCatching {
            device.preview.unregisterCameraStreamListener(streamListener)
            device.preview.unregisterCameraLiveListener(cameraLiveListener)
            device.preview.unregisterPostureListener(postureListener)
            device.preview.setPipeline(null)
            device.preview.stopStream()
        }.onFailure { Timber.w(it, "stop preview stream") }
        viewModel.onPreviewPhaseChange(PreviewPhase.Idle)
    }

    private fun releasePreviewPlayer() {
        val view = previewView ?: return
        Timber.d("releasePreviewPlayer")
        view.setListener(null)
        view.destroy()
        _binding?.previewPlaceholder?.removeView(view)
        previewView = null
    }
    // endregion
    // region 推流控制（CameraPreview 新接口）
    private fun startLive() {
        val device = connectionViewModel.getCameraDevice()
        val state = viewModel.uiState.value
        if (device == null) {
            viewModel.emitToast(getString(R.string.live_toast_no_camera))
            return
        }
        if (state.previewPhase !is PreviewPhase.Playing) {
            viewModel.emitToast(getString(R.string.live_toast_start_preview_first))
            return
        }
        if (state.rtmpUrl.isBlank()) {
            viewModel.emitToast(getString(R.string.live_toast_fill_rtmp))
            return
        }
        // 串行化守卫：仅在上一次会话已确认释放（Idle/Failed）时发起，杜绝底层 live 未释放时重入
        if (state.livePhase !is LivePhase.Idle && state.livePhase !is LivePhase.Failed) {
            Timber.w("startLive ignored, livePhase=%s", state.livePhase)
            return
        }
        Timber.d("startLive url=%s resolution=%s", state.rtmpUrl.trim(), state.resolution)
        viewModel.onLivePhaseChange(LivePhase.Starting)
        viewLifecycleOwner.lifecycleScope.launch {
            // bitrate 单位为 Mbps（底层内部转 bps），与旧接口的 bps 不同，需换算
            device.preview.startLive(
                CameraLiveParams(
                    rtmpUrl = state.rtmpUrl.trim(),
                    width = state.resolution.width,
                    height = state.resolution.height,
                    fps = LiveStreamUiState.FIXED_FPS,
                    bitrate = LiveStreamUiState.FIXED_BITRATE_BPS / 1_000_000,
                ),
            ).onFailure {
                // 受理失败：底层 live 可能已部分创建，发起 stopLive 释放后再回到可重启状态
                Timber.e(it, "startLive failed")
                val msg = it.message ?: getString(R.string.live_unknown_error)
                viewModel.onLivePhaseChange(LivePhase.Stopping(failureMessage = msg))
                runCatching { device.preview.stopLive() }
            }
            // 成功仅表示请求受理，正式 Pushing 由 cameraLiveListener.onStarted 落地
        }
    }

    private fun stopLive() {
        val phase = viewModel.uiState.value.livePhase
        Timber.d("stopLive phase=%s", phase)
        // 仅活跃会话可停止；已在停止/已停止时直接返回，避免重复发停止请求
        if (phase !is LivePhase.Starting && phase !is LivePhase.Pushing) return
        val device = connectionViewModel.getCameraDevice() ?: return
        viewModel.onLivePhaseChange(LivePhase.Stopping())
        viewLifecycleOwner.lifecycleScope.launch {
            device.preview.stopLive().onFailure { Timber.e(it, "stopLive failed") }
        }
        val current = viewModel.uiState.value.stats
        viewModel.onStatsUpdate(current?.copy(liveFps = null))
    }

    private fun switchResolution(resolution: Resolution) {
        val state = viewModel.uiState.value
        if (state.resolution == resolution) return
        val wasActive = state.livePhase is LivePhase.Starting || state.livePhase is LivePhase.Pushing
        Timber.d("switchResolution %s -> %s wasActive=%s", state.resolution, resolution, wasActive)
        viewModel.onResolutionChange(resolution)
        if (wasActive) {
            // 停后开：在 onStopped 确认释放后再重启，避免连续 stop/start 撞底层 live 未释放
            pendingRestartAfterStop = true
            stopLive()
        }
    }

    /**
     * 退出页面。推流相关进行中时直接销毁视图，live 编码器仍占用 surface 会导致主线程死锁，
     * 因此要求先手动停止推流再返回，仅在 Idle/Failed 时放行。
     */
    private fun handleExit() {
        when (viewModel.uiState.value.livePhase) {
            is LivePhase.Idle, is LivePhase.Failed -> findNavController().popBackStack()
            else -> viewModel.emitToast(getString(R.string.live_toast_stop_before_exit))
        }
    }
    // endregion
    // region SDK 监听
    private val streamListener = object : CameraStreamListener {
        private var lastSampleAtMs = 0L
        private var sampledFrames = 0L
        private var sampledBytes = 0L

        override fun onOpening() {}

        override fun onOpened() {
            Timber.d("streamListener.onOpened")
            // 请求一次关键帧，加速首帧出图、减少进入预览的黑屏
            connectionViewModel.getCameraDevice()?.let { runCatching { it.preview.requestStreamIframe() } }
            val view = previewView ?: return
            view.post {
                // onOpened 为 SDK 异步回调，执行时 Fragment 可能已销毁，需二次校验避免操作已 destroy 的 player
                if (!isAdded || _binding == null) return@post
                val pv = previewView ?: return@post
                pv.destroyRender()
                pv.prepare(PreviewParams())
                pv.play()
                applyRotation(latestPosture)
                applyPreviewAspect()
            }
        }

        override fun onIdle() {
            Timber.d("streamListener.onIdle")
        }

        override fun onParamsChanged(paramsUpdate: PreviewStreamParamsUpdate) {
            val view = previewView ?: return
            paramsUpdate.offsetData?.let { offsetData ->
                view.setOffset(
                    OffsetData(
                        offsetV1 = offsetData.offsetV1,
                        offsetV2 = offsetData.offsetV2,
                        offsetV3 = offsetData.offsetV3,
                        offsetV6 = offsetData.offsetV6
                    ),
                    paramsUpdate.stabOffset.orEmpty(),
                )
            }
            if (paramsUpdate.previewWidth > 0 && paramsUpdate.previewHeight > 0 && paramsUpdate.previewFps > 0) {
                Timber.d("onParamsChanged wxh=%dx%d fps=%d", paramsUpdate.previewWidth, paramsUpdate.previewHeight, paramsUpdate.previewFps)
                view.setPreviewResolution(paramsUpdate.previewWidth, paramsUpdate.previewHeight)
                view.setFps(paramsUpdate.previewFps)
                previewWidth = paramsUpdate.previewWidth
                previewHeight = paramsUpdate.previewHeight
                view.post { applyPreviewAspect() }
            }
            paramsUpdate.windowCropInfo?.let { crop ->
                view.setWindowCropInfo(
                    MediaWindowCropInfo(
                        srcWidth = crop.src_width,
                        srcHeight = crop.src_height,
                        dstWidth = crop.dst_width,
                        dstHeight = crop.dst_height,
                        offsetX = crop.crop_offset_x,
                        offsetY = crop.crop_offset_y,
                    ),
                )
            }
        }

        override fun onStreamDataNotify(streamData: PreviewStreamFrame) {
            if (!streamData.type.isVideo) return
            val now = System.currentTimeMillis()
            if (lastSampleAtMs == 0L) {
                // 第一帧只用来标定时间窗起点，本身不计入样本，避免首窗高估。
                lastSampleAtMs = now
                return
            }
            sampledFrames += 1
            sampledBytes += streamData.data.size
            val elapsed = now - lastSampleAtMs
            if (elapsed >= SAMPLE_WINDOW_MS) {
                // 先乘后除，全 Float / Double 算，避免整数截断
                val seconds = elapsed / 1000.0
                val previewFps = (sampledFrames / seconds).toFloat()
                // Mbps 按行业惯用十进制（1 Mbps = 1,000,000 bit/s），不是二进制 Mibps
                val previewMbps = (sampledBytes * 8.0 / 1_000_000.0 / seconds).toFloat()
                val current = viewModel.uiState.value.stats
                viewModel.onStatsUpdate(
                    StreamStats(
                        previewFps = previewFps,
                        previewMbps = previewMbps,
                        liveFps = current?.liveFps,
                    ),
                )
                lastSampleAtMs = now
                sampledFrames = 0
                sampledBytes = 0
            }
        }
    }
    // CameraPreview 路径的直播状态回调。回调在协程 scope 内分发（非主线程），
    // 这里仅写 StateFlow（线程安全），UI 渲染交由 repeatOnLifecycle 收集，无需手动切主线程。
    private val cameraLiveListener = object : CameraLiveListener {
        override fun onStarted() {
            Timber.d("cameraLiveListener.onStarted")
            // 仅在等待启动确认时落入 Pushing，避免覆盖期间已切入的停止/失败流程
            if (viewModel.uiState.value.livePhase is LivePhase.Starting) {
                viewModel.onLivePhaseChange(LivePhase.Pushing)
                viewModel.emitToast(getString(R.string.live_toast_started))
            }
        }

        override fun onFps(fps: Int) {
            val current = viewModel.uiState.value.stats ?: StreamStats()
            viewModel.onStatsUpdate(current.copy(liveFps = fps))
        }

        override fun onStopped() {
            val phase = viewModel.uiState.value.livePhase
            Timber.d("cameraLiveListener.onStopped phase=%s", phase)
            // onStopped 标志底层 live 已释放。SDK 可能重复回调，按当前态幂等落地。
            when (phase) {
                is LivePhase.Stopping ->
                    viewModel.onLivePhaseChange(
                        phase.failureMessage?.let { LivePhase.Failed(it) } ?: LivePhase.Idle,
                    )
                is LivePhase.Starting, is LivePhase.Pushing ->
                    // SDK 侧主动停止（如对端断开），无本地停止流程
                    viewModel.onLivePhaseChange(LivePhase.Idle)
                else -> Unit // 重复 onStopped，已落地，忽略
            }
            val current = viewModel.uiState.value.stats
            viewModel.onStatsUpdate(current?.copy(liveFps = null))
            // 切分辨率触发的重启：仅在已确认释放并回到 Idle 后发起
            if (pendingRestartAfterStop && viewModel.uiState.value.livePhase is LivePhase.Idle) {
                pendingRestartAfterStop = false
                startLive()
            }
        }

        override fun onFailed(errorCode: Int, message: String?) {
            Timber.e("cameraLiveListener.onFailed errorCode=%d message=%s", errorCode, message)
            val msg = message ?: getString(R.string.live_push_failed_code, errorCode)
            viewModel.emitToast(msg)
            when (viewModel.uiState.value.livePhase) {
                is LivePhase.Stopping ->
                    // 已在停止流程，记录失败信息，待 onStopped 落入 Failed
                    viewModel.onLivePhaseChange(LivePhase.Stopping(failureMessage = msg))
                is LivePhase.Idle, is LivePhase.Failed -> Unit // 无活跃会话，忽略
                else -> {
                    // Starting/Pushing 活跃中失败：转入停止流程释放底层 live，由 onStopped 落入 Failed
                    pendingRestartAfterStop = false
                    viewModel.onLivePhaseChange(LivePhase.Stopping(failureMessage = msg))
                    connectionViewModel.getCameraDevice()?.let { device ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            runCatching { device.preview.stopLive() }
                        }
                    }
                }
            }
        }
    }

    private val playerViewListener = object : PlayerViewListener {
        override fun onLoadingStatusChanged(isLoading: Boolean) {}

        override fun onLoadingFinish() {
            val device = connectionViewModel.getCameraDevice()
            val pipeline = previewView?.getPipeline()
            if (device == null || pipeline == null) {
                Timber.w("onLoadingFinish but device or pipeline is null")
                viewModel.onPreviewPhaseChange(PreviewPhase.Failed(getString(R.string.live_camera_disconnected)))
                return
            }
            Timber.d("onLoadingFinish: bind pipeline")
            device.preview.setPipeline(pipeline)
            // pipeline 绑定后再请一次关键帧，确保绑定即有帧可渲染
            runCatching { device.preview.requestStreamIframe() }
            viewModel.onPreviewPhaseChange(PreviewPhase.Playing)
        }

        override fun onFail(exception: InstaException) {
            Timber.e(exception, "playerView onFail")
            viewModel.onPreviewPhaseChange(PreviewPhase.Failed(exception.message ?: getString(R.string.live_unknown_error)))
        }

        override fun onFirstFrameRendered() {
            Timber.d("playerView onFirstFrameRendered")
        }

        override fun onReleaseCameraPipeline() {
            Timber.d("playerView onReleaseCameraPipeline")
            connectionViewModel.getCameraDevice()?.preview?.setPipeline(null)
        }
    }

    private val postureListener = object : CameraPostureUpdate {
        override fun updatePosture(cameraPosture: CameraPosture) {
            Timber.d("postureListener.updatePosture posture=%s", cameraPosture)
            latestPosture = cameraPosture
            previewView?.post { applyRotation(cameraPosture) }
        }
    }
    // endregion

    private fun applyRotation(posture: CameraPosture) {
        val view = previewView ?: return
        val rotateDegreeContent = when (posture) {
            CameraPosture.CAMERA_POSTURE_ROTATE_90 -> 90
            CameraPosture.CAMERA_POSTURE_ROTATE_180 -> 180
            CameraPosture.CAMERA_POSTURE_ROTATE_270 -> 270
            else -> 0
        }
        view.updateRotate(
            rotateDegreeContent,
            0,
            posture.nativeValue,
            posture.nativeValue,
        )
        view.redetectCameraRotation()
    }
}
