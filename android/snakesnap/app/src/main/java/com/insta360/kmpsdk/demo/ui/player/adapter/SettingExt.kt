package com.insta360.kmpsdk.demo.ui.player.adapter

import android.util.Size
import com.arashivision.sdk.media.api.common.ExportMode
import com.arashivision.sdk.media.api.common.OffsetType
import com.arashivision.sdk.media.api.common.RenderModel
import com.arashivision.sdk.media.api.common.StabType
import com.insta360.kmpsdk.demo.R

enum class SettingType(val textId: Int, val isPlay: Boolean, val isImageExport: Boolean, val isVideoExport: Boolean) {
    EXPORT_MODE(R.string.player_setting_export_mode, false, true, true),
    PREVIEW_MODE(R.string.player_setting_preview_mode, true, false, false),
    SUB_STREAM(R.string.player_setting_sub_stream, true, false, false),
    RENDER_MODE(R.string.player_setting_render_mode, true, true, true),
    SCREEN_RATE(R.string.player_setting_screen_rate, true, true, true),
    RESOLUTION(R.string.player_setting_resolution, false, true, true),
    BITRATE(R.string.player_setting_bitrate, false, false, true),
    FPS(R.string.player_setting_fps, false, false, true),
    STAB_TYPE(R.string.player_setting_stab_type, true, true, true),
    DENOISE(R.string.player_setting_denoise, false, true, true),
    DYNAMIC_STITCH(R.string.player_setting_dynamic_stitch, true, true, true),
    DE_PURPLE_FILTER(R.string.player_setting_de_purple_filter, true, true, true),
    COLOR_PLUS(R.string.player_setting_color_plus, true, true, true),
    COLOR_PLUS_INTENSITY(R.string.player_setting_color_plus_intensity, true, true, true),
    IMAGE_FUSION(R.string.player_setting_color_fusion, true, true, true),
    OFFSET_TYPE(R.string.player_setting_offset_type, true, true, true),
}

class ScreenRate {
    var x: Int = 0
    var y: Int = 0

    companion object {
        fun create(x: Int, y: Int): ScreenRate {
            return ScreenRate().apply {
                this.x = x
                this.y = y
            }
        }
    }

    fun toIntArray(): IntArray {
        return intArrayOf(x, y)
    }
}

data class SettingOption(val id: Int, val textId: Int = 0, val text: String = "", val value: Any)

/** third 可能存列表下标或历史遗留的 option.id */
internal fun resolveSelectedOption(options: List<SettingOption>, selected: Int): SettingOption? {
    if (options.isEmpty()) return null
    return options.getOrNull(selected)
        ?: options.firstOrNull { it.id == selected }
}

/** 将历史选中项映射到新选项列表中的下标 */
internal fun resolveSettingSelectionIndex(
    options: List<SettingOption>,
    previousOptions: List<SettingOption>,
    previousSelected: Int,
): Int {
    if (options.isEmpty()) return 0
    val previousOption = resolveSelectedOption(previousOptions, previousSelected) ?: return 0
    return options.indexOfFirst { it.id == previousOption.id && it.value == previousOption.value }
        .takeIf { it >= 0 }
        ?: options.indexOfFirst { it.id == previousOption.id }
            .takeIf { it >= 0 }
        ?: 0
}

internal fun findOptionIndex(options: List<SettingOption>, option: SettingOption): Int {
    return options.indexOfFirst { it.id == option.id && it.value == option.value }
        .takeIf { it >= 0 }
        ?: options.indexOfFirst { it.id == option.id }
            .takeIf { it >= 0 }
        ?: 0
}

internal fun Int.coerceSettingIndex(options: List<SettingOption>): Int {
    if (options.isEmpty()) return 0
    return coerceIn(0, options.lastIndex)
}

val BOOLEAN_OPTIONS = listOf(
    SettingOption(0, R.string.player_setting_boolean_option_close, value = false),
    SettingOption(1, R.string.player_setting_boolean_option_open, value = true),
)

val PREVIEW_MODE_OPTIONS = listOf(
    SettingOption(0, R.string.player_setting_preview_mode_normal, value = 0),
    SettingOption(1, R.string.player_setting_preview_mode_fisheye, value = 1),
    SettingOption(2, R.string.player_setting_preview_mode_perspective, value = 2)
)

val COLOR_PLUS_INTENSITY_OPTIONS = listOf(
    SettingOption(0, text = "0", value = 0f),
    SettingOption(1, text = "0.1", value = 0.1f),
    SettingOption(2, text = "0.3", value = 0.2f),
    SettingOption(3, text = "0.5", value = 0.5f),
    SettingOption(4, text = "0.8", value = 0.8f),
    SettingOption(5, text = "1.0", value = 1.0f),
)

val RENDER_MODE_OPTIONS = listOf(
    SettingOption(0, R.string.player_setting_render_normal, value = RenderModel.AUTO),
    SettingOption(1, R.string.player_setting_render_plane_stitch, value = RenderModel.PLANE_STITCH),
    SettingOption(2, R.string.player_setting_render_plane, value = RenderModel.PLANE),
)

val STAB_TYPE_OPTIONS = listOf(
    SettingOption(0, R.string.player_setting_stab_type_auto, value = StabType.AUTO),
    SettingOption(1, R.string.player_setting_stab_type_panorama, value = StabType.PANORAMA),
    SettingOption(2, R.string.player_setting_stab_type_calibrate_horizon, value = StabType.CALIBRATE_HORIZON),
    SettingOption(3, R.string.player_setting_stab_type_footage_motion_smooth, value = StabType.FOOTAGE_MOTION_SMOOTH),
)

val EXPORT_MODE_OPTIONS = listOf(
    SettingOption(0, R.string.player_setting_export_mode_panorama, value = ExportMode.PANORAMA),
    SettingOption(1, R.string.player_setting_export_mode_sphere, value = ExportMode.SPHERE),
)

val OFFSET_TYPE_OPTIONS = listOf(
    SettingOption(0, R.string.player_setting_offset_type_original, value = OffsetType.ORIGINAL),
    SettingOption(1, R.string.player_setting_offset_type_protector_fasten, value = OffsetType.PROTECTOR_FASTEN),
    SettingOption(2, R.string.player_setting_offset_type_diving_water, value = OffsetType.DIVING_WATER),
    SettingOption(3, R.string.player_setting_offset_type_diving_air, value = OffsetType.DIVING_AIR),
    SettingOption(4, R.string.player_setting_offset_type_waterproof, value = OffsetType.WATERPROOF),
    SettingOption(5, R.string.player_setting_offset_type_protector_adhere, value = OffsetType.PROTECTOR_ADHERE),
    SettingOption(6, R.string.player_setting_offset_type_diving_invisible_water, value = OffsetType.DIVING_INVISIBLE_WATER),
    SettingOption(7, R.string.player_setting_offset_type_diving_invisible_air, value = OffsetType.DIVING_INVISIBLE_AIR),
    SettingOption(8, R.string.player_setting_offset_type_protector_a, value = OffsetType.PROTECTOR_A),
    SettingOption(9, R.string.player_setting_offset_type_protector_s, value = OffsetType.PROTECTOR_S),
    SettingOption(10, R.string.player_setting_offset_type_protector_as_average, value = OffsetType.PROTECTOR_AS_AVERAGE),
)

val PROJECTION_OPTIONS = listOf(
    SettingOption(0, text = "AUTO", value = RenderModel.AUTO),
    SettingOption(1, text = "PLANE", value = RenderModel.PLANE),
    SettingOption(2, text = "PLANE_STITCH", value = RenderModel.PLANE_STITCH),
)

val SCREEN_RATE_OPTIONS = listOf(
    SettingOption(0, text = "9:16", value = ScreenRate.create(9, 16)),
    SettingOption(1, text = "16:9", value = ScreenRate.create(16, 9)),
    SettingOption(2, text = "3:4", value = ScreenRate.create(3, 4)),
    SettingOption(3, text = "4:3", value = ScreenRate.create(4, 3)),
    SettingOption(4, text = "1:1", value = ScreenRate.create(1, 1)),
    SettingOption(5, text = "2:1", value = ScreenRate.create(2, 1)),
    SettingOption(6, text = "2.35:1", value = ScreenRate.create(47, 20)),
)

val RESOLUTION_OPTIONS = listOf(
    SettingOption(0, text = "720×1280", value = Pair(Size(720, 1280), ScreenRate.create(9, 16))),
    SettingOption(1, text = "1080×1920", value = Pair(Size(1080, 1920), ScreenRate.create(9, 16))),
    SettingOption(2, text = "1440×2560", value = Pair(Size(1440, 2560), ScreenRate.create(9, 16))),
    SettingOption(3, text = "2160×3840", value = Pair(Size(2160, 3840), ScreenRate.create(9, 16))),
    SettingOption(4, text = "1280×720", value = Pair(Size(1280, 720), ScreenRate.create(16, 9))),
    SettingOption(5, text = "1920×1080", value = Pair(Size(1920, 1080), ScreenRate.create(16, 9))),
    SettingOption(6, text = "2560×1440", value = Pair(Size(2560, 1440), ScreenRate.create(16, 9))),
    SettingOption(7, text = "3840×2160", value = Pair(Size(3840, 2160), ScreenRate.create(16, 9))),
    SettingOption(8, text = "1920×1920", value = Pair(Size(1920, 1920), ScreenRate.create(1, 1))),
    SettingOption(9, text = "2880×2880", value = Pair(Size(2880, 2880), ScreenRate.create(1, 1))),
    SettingOption(10, text = "3840×3840", value = Pair(Size(3840, 3840), ScreenRate.create(1, 1))),
    SettingOption(11, text = "1440×720", value = Pair(Size(1440, 720), ScreenRate.create(2, 1))),
    SettingOption(12, text = "1920×960", value = Pair(Size(1920, 960), ScreenRate.create(2, 1))),
    SettingOption(13, text = "2560×1280", value = Pair(Size(2560, 1280), ScreenRate.create(2, 1))),
    SettingOption(14, text = "2880×1440", value = Pair(Size(2880, 1440), ScreenRate.create(2, 1))),
    SettingOption(15, text = "3840×1920", value = Pair(Size(3840, 1920), ScreenRate.create(2, 1))),
    SettingOption(16, text = "5760×2880", value = Pair(Size(5760, 2880), ScreenRate.create(2, 1))),
    SettingOption(17, text = "6400×3200", value = Pair(Size(6400, 3200), ScreenRate.create(2, 1))),
    SettingOption(18, text = "7680×3840", value = Pair(Size(7680, 3840), ScreenRate.create(2, 1))),
    SettingOption(19, text = "4096×1744", value = Pair(Size(4096, 1744), ScreenRate.create(47, 20))),
    SettingOption(20, text = "5472×2328", value = Pair(Size(5472, 2328), ScreenRate.create(47, 20))),
    SettingOption(21, text = "6144×2614", value = Pair(Size(6144, 2614), ScreenRate.create(47, 20))),
    SettingOption(22, text = "6720×2856", value = Pair(Size(6720, 2856), ScreenRate.create(47, 20))),
    SettingOption(23, text = "7680×3268", value = Pair(Size(7680, 3268), ScreenRate.create(47, 20))),
    SettingOption(24, text = "1440×1080", value = Pair(Size(1440, 1080), ScreenRate.create(4, 3))),
    SettingOption(25, text = "1920×1440", value = Pair(Size(1920, 1440), ScreenRate.create(4, 3))),
    SettingOption(26, text = "2720×2040", value = Pair(Size(2720, 2040), ScreenRate.create(4, 3))),
    SettingOption(27, text = "4000×3000", value = Pair(Size(4000, 3000), ScreenRate.create(4, 3))),
    SettingOption(28, text = "8000×6000", value = Pair(Size(8000, 6000), ScreenRate.create(4, 3))),
    SettingOption(29, text = "1080×1440", value = Pair(Size(1080, 1440), ScreenRate.create(3, 4))),
    SettingOption(30, text = "1440×1920", value = Pair(Size(1440, 1920), ScreenRate.create(3, 4))),
    SettingOption(31, text = "2040×2720", value = Pair(Size(2040, 2720), ScreenRate.create(3, 4))),
    SettingOption(32, text = "3000×4000", value = Pair(Size(3000, 4000), ScreenRate.create(3, 4))),
    SettingOption(33, text = "6000×8000", value = Pair(Size(6000, 8000), ScreenRate.create(3, 4))),
)

val FPS_OPTIONS = listOf(
    SettingOption(0, text = "24", value = 24),
    SettingOption(1, text = "25", value = 25),
    SettingOption(2, text = "30", value = 30),
    SettingOption(3, text = "60", value = 60),
    SettingOption(4, text = "120", value = 120),
)


val BITRATE_OPTIONS = listOf(
    SettingOption(0, text = "2Mbps", value = 2 * 1024 * 1024),
    SettingOption(1, text = "4Mbps", value = 4 * 1024 * 1024),
    SettingOption(2, text = "8Mbps", value = 8 * 1024 * 1024),
    SettingOption(3, text = "16Mbps", value = 16 * 1024 * 1024),
    SettingOption(4, text = "32Mbps", value = 32 * 1024 * 1024),
)