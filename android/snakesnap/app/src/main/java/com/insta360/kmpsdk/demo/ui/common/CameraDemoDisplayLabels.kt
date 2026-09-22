package com.insta360.kmpsdk.demo.ui.common

import android.content.Context
import com.arashivision.sdk.camera.core.model.FunctionMode
import com.arashivision.sdk.camera.core.model.capture.CameraCaptureStatus
import com.arashivision.sdk.camera.core.model.option.SensorMode
import com.arashivision.sdk.camera.core.model.option.StorageData
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.ui.player.video.PlayingFluencyStatus

/**
 * Demo 内与相机 SDK、配置文件、协议键等相关的**用户可见文案**统一入口（多语言走 `strings.xml`）。
 *
 * 可放入内容不限于枚举：拍摄参数键、能力枚举、后续连接状态码/错误码等，凡需把「机器名」转成可读文案的映射，
 * 优先集中在此维护；未配置映射时回退为原始 key 或 SDK 提供的字面量，避免界面空白。
 */
object CameraDemoDisplayLabels {

    fun functionMode(
        context: Context,
        mode: FunctionMode,
    ): String {
        val resId =
            when (mode) {
                FunctionMode.NONE -> R.string.function_mode_none
                FunctionMode.PHOTO_NORMAL -> R.string.function_mode_photo_normal
                FunctionMode.PHOTO_HDR -> R.string.function_mode_photo_hdr
                FunctionMode.PHOTO_BURST -> R.string.function_mode_photo_burst
                FunctionMode.PHOTO_INTERVAL -> R.string.function_mode_photo_interval
                FunctionMode.PHOTO_NIGHT -> R.string.function_mode_photo_night
                FunctionMode.PHOTO_PANO -> R.string.function_mode_photo_pano
                FunctionMode.PHOTO_PANO_HDR -> R.string.function_mode_photo_pano_hdr
                FunctionMode.PHOTO_STARLAPSE -> R.string.function_mode_photo_starlapse
                FunctionMode.VIDEO_NORMAL -> R.string.function_mode_video_normal
                FunctionMode.VIDEO_BULLET_TIME -> R.string.function_mode_video_bullet_time
                FunctionMode.VIDEO_TIMELAPSE -> R.string.function_mode_video_timelapse
                FunctionMode.VIDEO_HDR -> R.string.function_mode_video_hdr
                FunctionMode.VIDEO_TIMESHIFT -> R.string.function_mode_video_timeshift
                FunctionMode.VIDEO_SUPER -> R.string.function_mode_video_super
                FunctionMode.VIDEO_LOOP_RECORDING -> R.string.function_mode_video_loop_recording
                FunctionMode.VIDEO_FPV -> R.string.function_mode_video_fpv
                FunctionMode.VIDEO_MOVIE -> R.string.function_mode_video_movie
                FunctionMode.VIDEO_SLOW_MOTION -> R.string.function_mode_video_slow_motion
                FunctionMode.VIDEO_SELFIE -> R.string.function_mode_video_selfie
                FunctionMode.VIDEO_PURE -> R.string.function_mode_video_pure
                FunctionMode.VIDEO_LIVE -> R.string.function_mode_video_live
                FunctionMode.VIDEO_CAMERA_LIVE -> R.string.function_mode_video_camera_live
                FunctionMode.VIDEO_DASHCAM -> R.string.function_mode_video_dashcam
                FunctionMode.VIDEO_MOSQUITO -> R.string.function_mode_video_mosquito
            }
        return context.getString(resId)
    }

    fun sensorMode(
        context: Context,
        mode: SensorMode,
    ): String =
        when (mode) {
            SensorMode.FRONT -> context.getString(R.string.camera_capture_sensor_front)
            SensorMode.REAR -> context.getString(R.string.camera_capture_sensor_rear)
            SensorMode.ALL -> context.getString(R.string.camera_capture_sensor_all)
            SensorMode.UNKNOWN -> context.getString(R.string.camera_capture_sensor_unknown)
        }

    /**
     * [com.arashivision.sdk.camera.api.CameraCapture] / [com.arashivision.sdk.camera.api.param.CameraParam] 的属性键
     *（JSON、proto 等中的字段名）→ 列表行标题；未列出的键返回 [rawKey]。
     */
    fun captureParamLabel(
        context: Context,
        rawKey: String,
        mode: FunctionMode? = null,
    ): String {
        val resId =
            when (rawKey) {
                "aeb_capture_num" -> R.string.capture_param_aeb_capture_num
                "exposure_bias" -> R.string.capture_param_exposure_bias
                "white_balance" -> R.string.capture_param_white_balance
                "photography_self_timer" -> R.string.capture_param_photography_self_timer
                "splicing_base_enable" -> R.string.capture_param_splicing_base_enable
                "video_iso_top_limit" -> R.string.capture_param_video_iso_top_limit
                "accelerate_frequency" -> R.string.capture_param_accelerate_frequency
                "lapse_time" -> R.string.capture_param_lapse_time
                "record_duration" ->
                    if (mode == FunctionMode.VIDEO_LOOP_RECORDING) {
                        R.string.capture_param_loop_duration
                    } else {
                        R.string.capture_param_record_duration
                    }
                "living_bitrate" -> R.string.capture_param_living_bitrate
                "burst_capture_params" -> R.string.capture_param_burst_params
                "hdr_switch" -> R.string.capture_param_hdr_switch
                "p3_switch" -> R.string.capture_param_p3_switch
                "i_log_switch" -> R.string.capture_param_i_log_switch
                "pure_video_enhance_switch" -> R.string.capture_param_pure_video_enhance_switch
                "undamage_zoom" -> R.string.capture_param_undamage_zoom
                "live_photo_switch" -> R.string.capture_param_live_photo_switch
                "filter_mode" -> R.string.capture_param_filter_mode
                "color_mode" -> R.string.capture_param_color_mode
                "video_selfie_type" -> R.string.capture_param_video_selfie_type
                "record_resolution" -> R.string.capture_param_record_resolution
                "raw_capture_type" -> R.string.capture_param_raw_type
                "photo_size_id" -> R.string.capture_param_photo_size_id
                "photo_resolution" -> R.string.capture_param_photo_resolution
                "hdr_photo_mode" -> R.string.capture_param_hdr_photo_mode
                "exposure_individual" -> R.string.capture_param_exposure_individual
                "fov_type" -> R.string.capture_param_fov_type
                "flowstate_level" -> R.string.capture_param_flowstate_level
                "export_type" -> R.string.capture_param_export_type
                "exposure_program" -> R.string.capture_param_exposure_program
                "exposure_iso" -> R.string.capture_param_exposure_iso
                "exposure_shutter_speed" -> R.string.capture_param_exposure_shutter_speed
                "lens_accessory_type" -> R.string.capture_param_lens_accessory_type
                "iq_3a_mode" -> R.string.capture_param_iq_3a_mode
                else -> null
            }
        return resId?.let { context.getString(it) } ?: rawKey
    }

    fun storageFileLocation(
        context: Context,
        location: StorageData.FileLocation,
    ): String =
        when (location) {
            StorageData.FileLocation.CAMERA -> context.getString(R.string.storage_location_camera)
            StorageData.FileLocation.READER -> context.getString(R.string.storage_location_reader)
            StorageData.FileLocation.INNER -> context.getString(R.string.storage_location_inner)
            StorageData.FileLocation.SD -> context.getString(R.string.storage_location_sd)
        }

    fun storageState(
        context: Context,
        state: StorageData.State,
    ): String =
        when (state) {
            StorageData.State.PASS -> context.getString(R.string.storage_state_pass)
            StorageData.State.NO_CARD -> context.getString(R.string.storage_state_no_card)
            StorageData.State.NO_SPACE -> context.getString(R.string.storage_state_no_space)
            StorageData.State.INVALID_FORMAT -> context.getString(R.string.storage_state_invalid_format)
            StorageData.State.WP_CARD -> context.getString(R.string.storage_state_wp_card)
            StorageData.State.OTHER_ERROR -> context.getString(R.string.storage_state_other_error)
        }

    /** 拍摄子状态展示文案；仅覆盖需要向用户展示的子状态，其余返回空串。 */
    fun captureSubStatus(
        context: Context,
        subStatus: CameraCaptureStatus.SubStatus,
    ): String =
        when (subStatus) {
            CameraCaptureStatus.SubStatus.EXPOSURE -> context.getString(R.string.camera_capture_sub_status_exposure)
            CameraCaptureStatus.SubStatus.PHOTO_SAVE -> context.getString(R.string.camera_capture_sub_status_photo_save)
            CameraCaptureStatus.SubStatus.RECORD_SAVE -> context.getString(R.string.camera_capture_sub_status_record_save)
            else -> ""
        }

    fun playingFluencyStatus(
        context: Context,
        status: PlayingFluencyStatus,
    ): String =
        when (status) {
            PlayingFluencyStatus.SMOOTH -> context.getString(R.string.player_playing_fluency_smooth)
            PlayingFluencyStatus.STUTTER -> context.getString(R.string.player_playing_fluency_stutter)
            PlayingFluencyStatus.DETECT_FAILED -> context.getString(R.string.player_playing_fluency_detect_failed)
            PlayingFluencyStatus.UNKNOWN -> ""
        }
}
