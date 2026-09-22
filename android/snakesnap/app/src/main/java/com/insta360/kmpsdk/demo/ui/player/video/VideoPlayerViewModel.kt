package com.insta360.kmpsdk.demo.ui.player.video

import android.app.Application
import android.os.Environment
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arashivision.inskmp.editsdk.utils.replaceIf
import com.arashivision.sdk.common.exception.InstaException
import com.arashivision.sdk.media.api.common.ExportMode
import com.arashivision.sdk.media.api.common.OffsetType
import com.arashivision.sdk.media.api.common.RenderModel
import com.arashivision.sdk.media.api.common.StabType
import com.arashivision.sdk.media.api.export.ExporterManager
import com.arashivision.sdk.media.api.export.IExportCallback
import com.arashivision.sdk.media.api.listener.PlayerViewListener
import com.arashivision.sdk.media.api.listener.VideoStatusListener
import com.arashivision.sdk.media.api.params.ImageExportParams
import com.arashivision.sdk.media.api.params.VideoExportParams
import com.arashivision.sdk.media.api.params.VideoPlayerParams
import com.arashivision.sdk.media.api.work.WorkWrapper
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.ext.copyFile
import com.insta360.kmpsdk.demo.ext.emit
import com.insta360.kmpsdk.demo.ext.getContext
import com.insta360.kmpsdk.demo.ext.getString
import com.insta360.kmpsdk.demo.ui.player.adapter.BITRATE_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.BOOLEAN_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.COLOR_PLUS_INTENSITY_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.EXPORT_MODE_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.FPS_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.OFFSET_TYPE_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.PREVIEW_MODE_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.RENDER_MODE_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.RESOLUTION_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.SCREEN_RATE_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.STAB_TYPE_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.ScreenRate
import com.insta360.kmpsdk.demo.ui.player.adapter.SettingOption
import com.insta360.kmpsdk.demo.ui.player.adapter.SettingType
import com.insta360.kmpsdk.demo.ui.player.adapter.findOptionIndex
import com.insta360.kmpsdk.demo.ui.player.adapter.resolveSelectedOption
import com.insta360.kmpsdk.demo.ui.player.adapter.resolveSettingSelectionIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber.Forest.d
import java.io.File


data class VideoPlayerUiState(
    val isLoadedExtraData: Boolean = false,
    val settings: List<Triple<SettingType, List<SettingOption>, Int>> = emptyList(),
    val index: Int = 0,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val videoPlayProgress: Pair<Long, Long> = Pair(0, 0),
    val playingFluencyStatus: PlayingFluencyStatus = PlayingFluencyStatus.UNKNOWN,
)

// 流畅度检测结果的展示态分类，依据 VideoStatusListener#onPlayingFluencyResult 的 fluentFactor 区间换算而来
enum class PlayingFluencyStatus {
    UNKNOWN, SMOOTH, STUTTER, DETECT_FAILED
}

sealed class VideoUiEvent {
    data class Toast(val message: String) : VideoUiEvent()
    object Pop : VideoUiEvent()

    data class Play(val params: VideoPlayerParams, val playerViewListener: PlayerViewListener, val videoStatusListener: VideoStatusListener) : VideoUiEvent()
    object Pause : VideoUiEvent()
    object Resume : VideoUiEvent()
    object Stop : VideoUiEvent()
    object SeekComplete : VideoUiEvent()
    data class SetParams(val type: SettingType, val option: SettingOption) : VideoUiEvent()

    object ShowExportDialog : VideoUiEvent()
    object HideExportDialog : VideoUiEvent()
    object ShowLoading : VideoUiEvent()
    object HideLoading : VideoUiEvent()
}

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(VideoPlayerUiState())
    val ui: StateFlow<VideoPlayerUiState> = _ui.asStateFlow()

    private val _uiEvent = MutableSharedFlow<VideoUiEvent>()
    val uiEvent: SharedFlow<VideoUiEvent> = _uiEvent.asSharedFlow() // 只读

    private var exportId = -1

    private var isPrepared = false
    private var isPlaying = false
    private var isPaused: Boolean = false

    companion object {
        var workWrapper: WorkWrapper? = null
    }

    init {
        viewModelScope.launch {
            workWrapper?.let { work ->
                _ui.update { it.copy(isLoadedExtraData = work.isExtraDataLoaded()) }
                updateSettings()
            } ?: emit(_uiEvent, VideoUiEvent.Pop)
        }
    }

    fun loadExtraData() {
        workWrapper?.let { work ->
            // loadExtraData需要在IO线程
            viewModelScope.launch(Dispatchers.IO) {
                _uiEvent.emit(VideoUiEvent.ShowLoading)
                if (!work.isExtraDataLoaded()) {
                    work.loadExtraData()
                }
                _uiEvent.emit(VideoUiEvent.HideLoading)
                if (!work.isExtraDataLoaded()) {
                    emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_load_extra_data_failed)))
                } else {
                    emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_load_extra_data_success)))
                    _ui.update { it.copy(isLoadedExtraData = true) }
                }
            }
        } ?: run {
            emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
        }
    }

    val videoStatusListener = object : VideoStatusListener {
        override fun onProgressChanged(position: Long, length: Long) {
            d("Video onProgressChanged   position=$position   length=$length")
            _ui.update { it.copy(videoPlayProgress = position to length) }
        }

        override fun onPlayStateChanged(isPlaying: Boolean) {
            d("Video onPlayStateChanged   isPlaying=$isPlaying")
            this@VideoPlayerViewModel.isPlaying = isPlaying
            this@VideoPlayerViewModel.isPaused = !isPlaying
        }

        override fun onSeekComplete() {
            d("Video onSeekComplete")
            emit(_uiEvent, VideoUiEvent.SeekComplete)
        }

        override fun onComplete() {
            d("Video onCompletion")
        }

        override fun onPlayingFluencyResult(fluentFactor: Double, srcTime: Double, detectTime: Double) {
            d("Video onPlayingFluencyResult   fluentFactor=$fluentFactor  srcTime=$srcTime  detectTime=$detectTime")
            val status = when {
                fluentFactor < 0 -> PlayingFluencyStatus.DETECT_FAILED
                fluentFactor >= 0.65 -> PlayingFluencyStatus.SMOOTH
                else -> PlayingFluencyStatus.STUTTER
            }
            _ui.update { it.copy(playingFluencyStatus = status) }
        }
    }

    val playerViewListener = object : PlayerViewListener {

        override fun onLoadingStatusChanged(isLoading: Boolean) {
            d("onLoadingStatusChanged  isLoading=>$isLoading")
            if (isLoading) {
                emit(_uiEvent, VideoUiEvent.ShowLoading)
            } else {
                emit(_uiEvent, VideoUiEvent.HideLoading)
            }
        }

        override fun onLoadingFinish() {
            d("onLoadingFinish")
            isPrepared = true
        }

        override fun onFail(exception: InstaException) {
            d("onFail  ==> $exception")
            emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_player_failed, exception.toString())))

        }

        override fun onFirstFrameRendered() {
            d("onFirstFrameRendered")
        }

        override fun onReleaseCameraPipeline() {
            d("onReleaseCameraPipeline")
        }
    }

    fun play() {
        viewModelScope.launch {
            if (!_ui.value.isLoadedExtraData) {
                emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_extra_data_not_loaded)))
                return@launch
            }
            workWrapper?.let {
                it.allUrls.forEach { url ->
                    d("url=$url")
                }
                emit(_uiEvent, VideoUiEvent.Play(createVideoPlayerParams(it), playerViewListener, videoStatusListener))
            } ?: run {
                emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
            }
        }
    }

    private fun createVideoPlayerParams(workWrapper: WorkWrapper): VideoPlayerParams {
        val params = VideoPlayerParams(workWrapper)
        params.isAutoPlayAfterPrepared = true
        _ui.value.settings.forEach {
            when (it.first) {
                SettingType.DYNAMIC_STITCH -> {
                    params.isDynamicStitch = (it.second[it.third].value as? Boolean) ?: false
                }

                SettingType.DE_PURPLE_FILTER -> {
                    params.isDePurpleFilterOn = (it.second[it.third].value as? Boolean) ?: false
                }

                SettingType.COLOR_PLUS -> {
                    params.colorPlusEnable = (it.second[it.third].value as? Boolean) ?: false
                }

                SettingType.COLOR_PLUS_INTENSITY -> {
                    params.colorPlusFilterIntensity = (it.second[it.third].value as? Float) ?: 0f
                }

                SettingType.IMAGE_FUSION -> {
                    params.isColorFusion = (it.second[it.third].value as? Boolean) ?: false
                }


                SettingType.SCREEN_RATE -> {
                    params.screenRatio = (it.second[it.third].value as? ScreenRate)?.toIntArray() ?: intArrayOf(9, 16)
                }

                SettingType.RENDER_MODE -> {
                    params.renderModel = (it.second[it.third].value as? RenderModel) ?: RenderModel.AUTO
                }

                SettingType.OFFSET_TYPE -> {
                    params.offsetType = (it.second[it.third].value as? OffsetType) ?: OffsetType.ORIGINAL
                }

                SettingType.STAB_TYPE -> {
                    params.stabType = (it.second[it.third].value as? StabType) ?: StabType.AUTO
                }

                SettingType.SUB_STREAM -> {
                    params.isLrvEnable = (it.second[it.third].value as? Boolean) ?: false
                }

                else -> {}
            }
        }
        params.isLooping = true
        // 全景素材可以启用手势查看四周
        params.isGestureEnabled = workWrapper.isPanoramaFile()
        params.isAutoPlayAfterPrepared = true
        return params
    }

    private fun createVideoExportParams(workWrapper: WorkWrapper, yaw: Float, distance: Float, fov: Float, pitch: Float, roll: Float): VideoExportParams {
        val params = VideoExportParams(workWrapper)
        _ui.value.settings.forEach {
            when (it.first) {
                SettingType.DYNAMIC_STITCH -> {
                    params.isDynamicStitch = it.second[it.third].value as Boolean
                }

                SettingType.DE_PURPLE_FILTER -> {
                    params.isDePurpleFilterOn = it.second[it.third].value as Boolean
                }

                SettingType.COLOR_PLUS -> {
                    params.colorPlusEnable = it.second[it.third].value as Boolean
                }

                SettingType.COLOR_PLUS_INTENSITY -> {
                    params.colorPlusFilterIntensity = it.second[it.third].value as Float
                }

                SettingType.IMAGE_FUSION -> {
                    params.isColorFusion = it.second[it.third].value as Boolean
                }

                SettingType.SCREEN_RATE -> {
                    params.screenRatio = (it.second[it.third].value as ScreenRate).toIntArray()
                }

                SettingType.OFFSET_TYPE -> {
                    params.offsetType = it.second[it.third].value as OffsetType
                }

                SettingType.STAB_TYPE -> {
                    params.stabType = it.second[it.third].value as StabType
                }

                SettingType.EXPORT_MODE -> {
                    params.exportMode = it.second[it.third].value as ExportMode
                }

                SettingType.DENOISE -> {
                    params.isDenoise = it.second[it.third].value as Boolean
                }

                SettingType.RESOLUTION -> {
                    val size = (it.second[it.third].value as Pair<*, *>).first as Size
                    params.width = size.width
                    params.height = size.height
                }

                SettingType.FPS -> {
                    val fps = it.second[it.third].value as Int
                    params.fps = fps
                }

                SettingType.BITRATE -> {
                    val bitrate = it.second[it.third].value as Int
                    params.bitrate = bitrate
                }

                else -> {}
            }
        }
        params.yaw = yaw
        params.distance = distance
        params.pitch = pitch
        params.fov = fov
        params.roll = roll
        params.targetPath = getContext().externalCacheDir?.absolutePath + "/demo/export/video/" + System.currentTimeMillis() + ".jpg"
        d("targetPath=${params.targetPath}")
        return params
    }

    private fun createImageExportParams(workWrapper: WorkWrapper, yaw: Float, distance: Float, fov: Float, pitch: Float, time: Double): ImageExportParams {
        val params = ImageExportParams(workWrapper)
        _ui.value.settings.forEach {
            when (it.first) {
                SettingType.DYNAMIC_STITCH -> {
                    params.isDynamicStitch = it.second[it.third].value as Boolean
                }

                SettingType.DE_PURPLE_FILTER -> {
                    params.isDePurpleFilterOn = it.second[it.third].value as Boolean
                }

                SettingType.COLOR_PLUS -> {
                    params.colorPlusEnable = it.second[it.third].value as Boolean
                }

                SettingType.COLOR_PLUS_INTENSITY -> {
                    params.colorPlusFilterIntensity = it.second[it.third].value as Float
                }

                SettingType.IMAGE_FUSION -> {
                    params.isColorFusion = it.second[it.third].value as Boolean
                }

                SettingType.SCREEN_RATE -> {
                    params.screenRatio = (it.second[it.third].value as ScreenRate).toIntArray()
                }

                SettingType.OFFSET_TYPE -> {
                    params.offsetType = it.second[it.third].value as OffsetType
                }

                SettingType.STAB_TYPE -> {
                    params.stabType = it.second[it.third].value as StabType
                }

                SettingType.EXPORT_MODE -> {
                    params.exportMode = it.second[it.third].value as ExportMode
                }

                SettingType.DENOISE -> {
                    params.isDenoise = it.second[it.third].value as Boolean
                }

                SettingType.RESOLUTION -> {
                    val size = (it.second[it.third].value as Pair<*, *>).first as Size
                    params.width = size.width
                    params.height = size.height
                }

                else -> {}
            }
        }
        params.yaw = yaw
        params.distance = distance
        params.pitch = pitch
        params.fov = fov
        params.targetPath = getContext().externalCacheDir?.absolutePath + "/demo/export/image/" + System.currentTimeMillis() + ".jpg"
        d("targetPath=${params.targetPath}")
        params.timestampList = listOf(0.0, time)
        return params
    }

    private fun updateSettings(previousList: List<Triple<SettingType, List<SettingOption>, Int>> = emptyList()) {
        val settings: MutableList<Triple<SettingType, List<SettingOption>, Int>> = mutableListOf()

        SettingType.entries.forEach { settingType ->
            val previous = previousList.find { it.first == settingType }
            val previousSelected = previous?.third ?: 0
            val previousOptions = previous?.second ?: emptyList()
            fun selectionIndex(options: List<SettingOption>) =
                resolveSettingSelectionIndex(options, previousOptions, previousSelected)
            when (settingType) {
                // 导出模式
                SettingType.EXPORT_MODE -> settings.add(Triple(SettingType.EXPORT_MODE, EXPORT_MODE_OPTIONS, selectionIndex(EXPORT_MODE_OPTIONS)))
                // 预览模式
                SettingType.PREVIEW_MODE -> {
                    val previewModeOptions = if (workWrapper!!.isPanoramaFile()) PREVIEW_MODE_OPTIONS else emptyList()
                    if (previewModeOptions.isNotEmpty()) {
                        settings.add(Triple(SettingType.PREVIEW_MODE, previewModeOptions, selectionIndex(previewModeOptions)))
                    }
                }
                // 副码流
                SettingType.SUB_STREAM -> {
                    if (workWrapper?.lrvUrls?.isNotEmpty() == true) {
                        settings.add(Triple(SettingType.SUB_STREAM, BOOLEAN_OPTIONS, selectionIndex(BOOLEAN_OPTIONS)))
                    }
                }
                // 渲染模式
                SettingType.RENDER_MODE -> {
                    val renderModeOptions = if (workWrapper!!.isPanoramaFile()) RENDER_MODE_OPTIONS else emptyList()
                    if (renderModeOptions.isNotEmpty()) {
                        settings.add(Triple(SettingType.RENDER_MODE, renderModeOptions, selectionIndex(renderModeOptions)))
                    }
                }
                // 屏幕比例
                SettingType.SCREEN_RATE -> settings.add(Triple(SettingType.SCREEN_RATE, SCREEN_RATE_OPTIONS, selectionIndex(SCREEN_RATE_OPTIONS)))
                // 分辨率
                SettingType.RESOLUTION -> {
                    val resolutionOptions = RESOLUTION_OPTIONS.filter { opt ->
                        val size = (opt.value as Pair<*, *>).first as Size
                        val screenRate = opt.value.second as ScreenRate

                        val currentScreenRate = previousList.find { it.first == SettingType.SCREEN_RATE }?.let { setting ->
                            resolveSelectedOption(setting.second, setting.third)?.value as? ScreenRate
                        } ?: (SCREEN_RATE_OPTIONS[0].value as ScreenRate)
                        currentScreenRate.x == screenRate.x && currentScreenRate.y == screenRate.y && size.width <= workWrapper!!.getWidth() && size.height <= workWrapper!!.getHeight()
                    }
                    if (resolutionOptions.isNotEmpty()) {
                        settings.add(
                            Triple(
                                SettingType.RESOLUTION,
                                resolutionOptions,
                                selectionIndex(resolutionOptions)
                            )
                        )
                    }
                }

                // 码率
                SettingType.BITRATE -> settings.add(Triple(SettingType.BITRATE, BITRATE_OPTIONS, selectionIndex(BITRATE_OPTIONS)))
                // 帧率
                SettingType.FPS -> {
                    val fpsOptions = FPS_OPTIONS.filter {
                        it.value as Int <= workWrapper!!.getFps()
                    }
                    if (fpsOptions.isNotEmpty()) {
                        settings.add(Triple(SettingType.FPS, fpsOptions, selectionIndex(fpsOptions)))
                    }
                }
                // 防抖类型
                SettingType.STAB_TYPE -> settings.add(Triple(SettingType.STAB_TYPE, STAB_TYPE_OPTIONS, selectionIndex(STAB_TYPE_OPTIONS)))
                // 降噪
                SettingType.DENOISE -> settings.add(Triple(SettingType.DENOISE, BOOLEAN_OPTIONS, selectionIndex(BOOLEAN_OPTIONS)))
                // 动态拼接
                SettingType.DYNAMIC_STITCH -> settings.add(Triple(SettingType.DYNAMIC_STITCH, BOOLEAN_OPTIONS, selectionIndex(BOOLEAN_OPTIONS)))
                // 去紫边
                SettingType.DE_PURPLE_FILTER -> settings.add(Triple(SettingType.DE_PURPLE_FILTER, BOOLEAN_OPTIONS, selectionIndex(BOOLEAN_OPTIONS)))
                // 色彩增强
                SettingType.COLOR_PLUS -> settings.add(Triple(SettingType.COLOR_PLUS, BOOLEAN_OPTIONS, selectionIndex(BOOLEAN_OPTIONS)))
                // 色彩增强强度
                SettingType.COLOR_PLUS_INTENSITY -> settings.add(
                    Triple(
                        SettingType.COLOR_PLUS_INTENSITY,
                        COLOR_PLUS_INTENSITY_OPTIONS,
                        selectionIndex(COLOR_PLUS_INTENSITY_OPTIONS)
                    )
                )
                // 消色差
                SettingType.IMAGE_FUSION -> settings.add(Triple(SettingType.IMAGE_FUSION, BOOLEAN_OPTIONS, selectionIndex(BOOLEAN_OPTIONS)))
                // 保护镜类型
                SettingType.OFFSET_TYPE -> settings.add(Triple(SettingType.OFFSET_TYPE, OFFSET_TYPE_OPTIONS, selectionIndex(OFFSET_TYPE_OPTIONS)))
            }
        }

        _ui.update { it.copy(settings = settings) }
    }

    fun startExportVideo(yaw: Float, distance: Float, fov: Float, pitch: Float, roll: Float) {
        if (!_ui.value.isLoadedExtraData) {
            emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_please_load_extra_data_first_and_then_export)))
            return
        }
        workWrapper?.let { work ->
            val params = createVideoExportParams(work, yaw, distance, fov, pitch, roll)
            ExporterManager.exportVideo(params, object : IExportCallback {
                override fun onStart(id: Int) {
                    d("开始导出  id=$id")
                    exportId = id
                    emit(_uiEvent, VideoUiEvent.ShowExportDialog)
                }

                override fun onSuccess() {
                    d("导出成功")
                    val target =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/INSTA_EXPORT/VIDEO/" + System.currentTimeMillis() + ".mp4"
                    viewModelScope.launch {
                        copyFile(getContext(), File(params.targetPath ?: ""), target)
                        emit(_uiEvent, VideoUiEvent.HideExportDialog)
                        emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_export_success)))
                    }
                }

                override fun onFail(throwable: Throwable) {
                    d("导出失败：${throwable.message}")
                    emit(_uiEvent, VideoUiEvent.HideExportDialog)
                    emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_export_failed, throwable.message ?: "")))
                }

                override fun onCancel() {
                    d("取消导出")
                    emit(_uiEvent, VideoUiEvent.HideExportDialog)
                }

                override fun onProgress(progress: Float) {
                    d("导出中：$progress")
                    _ui.update { it.copy(exportProgress = progress) }
                }

            })
        } ?: run {
            emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
        }
    }

    fun startExportImages(yaw: Float, distance: Float, fov: Float, pitch: Float, time: Double) {
        if (!_ui.value.isLoadedExtraData) {
            emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_please_load_extra_data_first_and_then_export)))
            return
        }
        workWrapper?.let { work ->
            val params = createImageExportParams(work, yaw, distance, fov, pitch, time)
            ExporterManager.exportVideoToImage(params, object : IExportCallback {
                override fun onStart(id: Int) {
                    d("开始导出  id=$id")
                    exportId = id
                    emit(_uiEvent, VideoUiEvent.ShowExportDialog)
                }

                override fun onSuccess() {
                    d("导出成功")
                    viewModelScope.launch {
                        val target =
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/INSTA_EXPORT/VIDEO_TO_IMAGE/" + System.currentTimeMillis() + ".jpg"
                        copyFile(getContext(), File(params.targetPath ?: ""), target)
                        emit(_uiEvent, VideoUiEvent.HideExportDialog)
                        emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_export_success)))
                    }
                }

                override fun onFail(throwable: Throwable) {
                    d("导出失败：${throwable.message}")
                    emit(_uiEvent, VideoUiEvent.HideExportDialog)
                    emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_export_failed, throwable.message ?: "")))
                }

                override fun onCancel() {
                    d("取消导出")
                    emit(_uiEvent, VideoUiEvent.HideExportDialog)
                }

                override fun onProgress(progress: Float) {
                    d("导出中：$progress")
                    _ui.update { it.copy(exportProgress = progress) }
                }

            })
        } ?: run {
            emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
        }
    }

    fun stopExportVideo() {
        d("用户取消导出  exportId=$exportId")
        ExporterManager.stopExport(exportId)
    }

    fun setParams(type: SettingType, option: SettingOption) {
        val settings = _ui.value.settings.toMutableList()
        settings.find { it.first == type }?.let { find ->
            val selectedIndex = findOptionIndex(find.second, option)
            settings.replaceIf(Triple(type, find.second, selectedIndex)) { it.first == type }
        }
        updateSettings(settings)

        emit(_uiEvent, VideoUiEvent.SetParams(type, option))

        if (type == SettingType.RENDER_MODE && (option.value as RenderModel) == RenderModel.PLANE_STITCH) {
            emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_render_plan_stitch_tips)))
        }
    }

    fun pause() {
        if (!isPlaying) {
            return
        }
        viewModelScope.launch {
            if (!_ui.value.isLoadedExtraData) {
                emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_extra_data_not_loaded)))
                return@launch
            }
            workWrapper?.let {
                emit(_uiEvent, VideoUiEvent.Pause)
            } ?: run {
                emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
            }
        }
    }

    fun resume() {
        if (!isPaused) {
            return
        }
        viewModelScope.launch {
            if (!_ui.value.isLoadedExtraData) {
                emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_extra_data_not_loaded)))
                return@launch
            }
            workWrapper?.let {
                emit(_uiEvent, VideoUiEvent.Resume)
            } ?: run {
                emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            if (!_ui.value.isLoadedExtraData) {
                emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_extra_data_not_loaded)))
                return@launch
            }
            workWrapper?.let {
                isPrepared = false
                isPlaying = false
                isPaused = false
                emit(_uiEvent, VideoUiEvent.Stop)
            } ?: run {
                emit(_uiEvent, VideoUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        workWrapper = null
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VideoPlayerViewModel(application) as T
        }
    }
}