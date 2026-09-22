package com.insta360.kmpsdk.demo.ui.capture

import com.arashivision.sdk.camera.core.model.FunctionMode
import com.arashivision.sdk.camera.core.model.option.SensorMode

/** 切镜头/切模式后预览侧采取的恢复策略。 */
internal enum class PreviewRefreshStrategy {
    /** 完整重启 SDK 预览流，prepare 由 streamListener.onOpened 自动触发。 */
    RESTART_STREAM,

    /** 不停 stream，仅 setPipeline(null) + 重新 prepare。 */
    REBUILD_PLAYER,

    /** SDK 切换后预览自然延续，不做任何 prepare/stream 动作。 */
    NO_OP,
}

/** 触发预览刷新的动作类型，供 Decider 判定策略。 */
internal sealed interface PreviewSwitchAction {
    val sdkSwitch: suspend () -> Boolean

    data class Lens(
        val target: SensorMode,
        override val sdkSwitch: suspend () -> Boolean,
    ) : PreviewSwitchAction

    data class Mode(
        val target: FunctionMode,
        override val sdkSwitch: suspend () -> Boolean,
    ) : PreviewSwitchAction
}

internal object PreviewCapability {
    // 拍摄过程中不支持实时预览的模式：连拍/间隔/星空延时/移动延时/延时录像。
    // 拓展点：发现新的同类模式时，仅需在此集合追加枚举值。
    private val MODES_WITHOUT_PREVIEW_WHILE_CAPTURING =
        setOf(
            FunctionMode.PHOTO_BURST,
            FunctionMode.PHOTO_INTERVAL,
            FunctionMode.PHOTO_STARLAPSE,
            FunctionMode.VIDEO_TIMESHIFT,
            FunctionMode.VIDEO_TIMELAPSE,
        )

    fun supportsPreviewWhileCapturing(mode: FunctionMode?): Boolean = mode !in MODES_WITHOUT_PREVIEW_WHILE_CAPTURING
}

internal object PreviewRefreshStrategyDecider {
    // 切模式时需要重建播放器的目标模式：底层管线参数与默认录像/拍照差异较大，必须重新 prepare
    private val MODES_REQUIRING_REBUILD =
        setOf(FunctionMode.PHOTO_STARLAPSE, FunctionMode.PHOTO_INTERVAL,
            // x4, x4 air, x5 的单镜头切换这两个模式需要重启播放器
            FunctionMode.PHOTO_NORMAL, FunctionMode.VIDEO_SUPER
        )

    fun decide(
        action: PreviewSwitchAction,
        supportNewCaptureControlFlow: Boolean = false,
    ): PreviewRefreshStrategy =
        when (action) {
            is PreviewSwitchAction.Lens -> {
                if (!supportNewCaptureControlFlow) {
                    PreviewRefreshStrategy.RESTART_STREAM
                } else {
                    PreviewRefreshStrategy.REBUILD_PLAYER
                }
            }

            is PreviewSwitchAction.Mode -> {
                if (action.target in MODES_REQUIRING_REBUILD) {
                    PreviewRefreshStrategy.REBUILD_PLAYER
                } else {
                    // FIXME: 非"必须重建"模式一律走 NO_OP，是基于"SDK 内部会自然延续预览"的乐观假设。
                    //  旧实现（runPreviewSwitchRebuild）对所有模式切换都会重新 prepare，本次改动是一处行为回归：
                    //  若 SDK 在切换后会调整预览分辨率/fps/管线参数，没有 prepare 的话画面会停留在旧参数下，
                    //  直到下一次无关事件触发重 prepare。需要按设备型号验证 SDK 是否真的能无缝切换；
                    //  若不能，考虑改回 REBUILD_PLAYER 或通过 onParamsChanged 显式驱动一次 prepare。
                    PreviewRefreshStrategy.NO_OP
                }
            }
        }
}
