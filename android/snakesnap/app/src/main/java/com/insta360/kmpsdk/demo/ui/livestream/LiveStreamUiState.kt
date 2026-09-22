package com.insta360.kmpsdk.demo.ui.livestream

data class LiveStreamUiState(
    val rtmpUrl: String = YOUTUBE_RTMP_URL,
    val resolution: Resolution = Resolution.HD720,
    val previewPhase: PreviewPhase = PreviewPhase.Idle,
    val livePhase: LivePhase = LivePhase.Idle,
    val stats: StreamStats? = null,
) {
    // 仅当上一次推流会话已确认释放（Idle/Failed）时才允许再次发起，避免在 mCameraLive 尚未释放时重入 onStartLive
    val canStartLive: Boolean
        get() = previewPhase is PreviewPhase.Playing &&
            (livePhase is LivePhase.Idle || livePhase is LivePhase.Failed) &&
            rtmpUrl.isNotBlank()

    // Starting 期间 mCameraLive 可能已创建，必须允许停止；Stopping 期间已在停止流程，不再重复发起
    val canStopLive: Boolean
        get() = livePhase is LivePhase.Starting || livePhase is LivePhase.Pushing

    companion object {
        const val DEFAULT_RTMP_URL = "rtmp://10.0.106.42:1935/live/test"
        const val YOUTUBE_RTMP_URL = "rtmp://a.rtmp.youtube.com/live2/4tut-vbfx-hkrf-qb8d-9uy2"
        const val FIXED_FPS = 30
        const val FIXED_BITRATE_BPS = 8_000_000
    }
}

enum class Resolution(
    val width: Int,
    val height: Int,
    val label: String,
) {
    HD720(1440, 720, "720P"),
    HD1080(1920, 1080, "1080P"),
}

sealed interface PreviewPhase {
    data object Idle : PreviewPhase

    data object Starting : PreviewPhase

    data object Playing : PreviewPhase

    data class Failed(val message: String) : PreviewPhase
}

sealed interface LivePhase {
    data object Idle : LivePhase

    // 已发起 onStartLive、等待 onStarted 期间。SDK 异步处理较慢，此态下禁止再次发起，但允许停止。
    data object Starting : LivePhase

    data object Pushing : LivePhase

    // 已发起 onStopLive、等待 onStopped 确认 mCameraLive 释放期间。failureMessage 非空表示停止源于失败，停止完成后落入 Failed。
    data class Stopping(val failureMessage: String? = null) : LivePhase

    data class Failed(val message: String) : LivePhase
}

/**
 * 直播页统计指标。
 *
 * preview 部分来自 [com.arashivision.sdk.camera.api.preview.CameraStreamListener.onStreamDataNotify]
 * 累加的相机预览码流字节/帧数（即从相机送给播放器的原始 H.26x 帧），不是真正推到 RTMP 服务器的字节。
 *
 * live 部分来自 onestream 的 [com.arashivision.onestream.pipeline.ICameraLivePipline.onCameraLiveNotify]
 * 推流管线回调，SDK 仅暴露 FPS（NotifyType=100），没有字节数 → liveMbps 不可得，仅 [liveFps] 可用。
 */
data class StreamStats(
    val previewFps: Float = 0f,
    val previewMbps: Float = 0f,
    val liveFps: Int? = null,
)
