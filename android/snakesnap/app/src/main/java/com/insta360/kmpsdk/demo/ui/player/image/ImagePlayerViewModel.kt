package com.insta360.kmpsdk.demo.ui.player.image

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
import com.arashivision.sdk.media.api.params.ImageExportParams
import com.arashivision.sdk.media.api.params.ImagePlayerParams
import com.arashivision.sdk.media.api.stitch.StitchManager
import com.arashivision.sdk.media.api.work.WorkWrapper
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.ext.copyFile
import com.insta360.kmpsdk.demo.ext.emit
import com.insta360.kmpsdk.demo.ext.getContext
import com.insta360.kmpsdk.demo.ext.getString
import com.insta360.kmpsdk.demo.ui.player.adapter.BOOLEAN_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.COLOR_PLUS_INTENSITY_OPTIONS
import com.insta360.kmpsdk.demo.ui.player.adapter.EXPORT_MODE_OPTIONS
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import timber.log.Timber.Forest.d
import java.io.File
import kotlin.math.max
import kotlin.math.min


data class ImagePlayerUiState(
    val isLoadedExtraData: Boolean = false,
    val settings: List<Triple<SettingType, List<SettingOption>, Int>> = emptyList(),
    val index: Int = 0,
    val count: Int = 0,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val supportHdrGenerate: Boolean = false,
    val hdrGeneratePath: String = "",
    val isShowHdrGenerate: Boolean = false,
    val supportPureShotGenerate: Boolean = false,
    val pureShotGeneratePath: String = "",
    val isShowPureShotGenerate: Boolean = false,
)

sealed class ImageUiEvent {
    data class Toast(val message: String) : ImageUiEvent()
    object Pop : ImageUiEvent()

    data class Play(val params: ImagePlayerParams, val playerViewListener: PlayerViewListener) : ImageUiEvent()
    object Stop : ImageUiEvent()
    data class SetParams(val type: SettingType, val option: SettingOption) : ImageUiEvent()

    object ShowExportDialog : ImageUiEvent()
    object HideExportDialog : ImageUiEvent()
    data class ShowLoading(val message: String) : ImageUiEvent()
    object HideLoading : ImageUiEvent()
}

class ImagePlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(ImagePlayerUiState())
    val ui: StateFlow<ImagePlayerUiState> = _ui.asStateFlow()

    private val _uiEvent = MutableSharedFlow<ImageUiEvent>()
    val uiEvent = _uiEvent.asSharedFlow() // 只读

    private var isPrepared = false

    private var exportId = -1

    companion object {
        var workWrapper: WorkWrapper? = null
    }

    init {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    isLoadedExtraData = workWrapper?.isExtraDataLoaded() == true,
                    count = workWrapper?.getCount() ?: 0,
                    supportHdrGenerate = workWrapper?.supportHdrGenerate() == true,
                    supportPureShotGenerate = workWrapper?.supportPureShotGenerate() == true,
                )
            }
            updateSettings()
        }
    }

    fun loadExtraData() {
        workWrapper?.let { work ->
            // loadExtraData需要在IO线程；连拍/星空延时等场景文件数较多、逐张解析耗时较长，用遮罩展示加载进度
            viewModelScope.launch(Dispatchers.IO) {
                val count = work.getCount()
                // 同一协程内顺序 emit，避免多个 emit() 扩展函数各自开协程导致的乱序
                _uiEvent.emit(ImageUiEvent.ShowLoading(getString(R.string.player_loading_extra_data, "0", count.toString())))
                for (i in 0 until count) {
                    if (!work.isExtraDataLoaded(i)) {
                        work.loadExtraData(i)
                    }
                    _uiEvent.emit(ImageUiEvent.ShowLoading(getString(R.string.player_loading_extra_data, (i + 1).toString(), count.toString())))
                }
                _uiEvent.emit(ImageUiEvent.HideLoading)
                if (!work.isExtraDataLoaded()) {
                    _uiEvent.emit(ImageUiEvent.Toast(getString(R.string.player_toast_load_extra_data_failed)))
                } else {
                    _uiEvent.emit(ImageUiEvent.Toast(getString(R.string.player_toast_load_extra_data_success)))
                    _ui.update { it.copy(isLoadedExtraData = true) }
                }
            }
        } ?: run {
            emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
        }
    }

    fun play() {
        viewModelScope.launch {
            if (!_ui.value.isLoadedExtraData) {
                emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_extra_data_not_loaded)))
                return@launch
            }
            workWrapper?.let {
                emit(_uiEvent, ImageUiEvent.Play(createImagePlayerParams(it), object : PlayerViewListener {

                    override fun onLoadingStatusChanged(isLoading: Boolean) {
                        d("onLoadingStatusChanged  isLoading=>$isLoading")
                        if (isLoading) {
                            emit(_uiEvent, ImageUiEvent.ShowLoading(""))
                        } else {
                            emit(_uiEvent, ImageUiEvent.HideLoading)
                        }
                    }

                    override fun onLoadingFinish() {
                        d("onLoadingFinish")
                        isPrepared = true
                    }

                    override fun onFail(exception: InstaException) {
                        d("onFail  ==> $exception")
                        emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_player_failed, exception.toString())))

                    }

                    override fun onFirstFrameRendered() {
                        d("onFirstFrameRendered")
                    }

                    override fun onReleaseCameraPipeline() {
                        d("onReleaseCameraPipeline")
                    }
                }))
            } ?: run {
                emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
            }
        }
    }

    private fun createImagePlayerParams(workWrapper: WorkWrapper): ImagePlayerParams {
        val params = ImagePlayerParams(workWrapper)
        _ui.value.settings.forEach { settingType ->
            when (settingType.first) {
                SettingType.DYNAMIC_STITCH -> {
                    params.isDynamicStitch = (settingType.second[settingType.third].value as? Boolean) ?: false
                }

                SettingType.DE_PURPLE_FILTER -> {
                    params.isDePurpleFilterOn = (settingType.second[settingType.third].value as? Boolean) ?: false
                }

                SettingType.COLOR_PLUS -> {
                    params.colorPlusEnable = (settingType.second[settingType.third].value as? Boolean) ?: false
                }

                SettingType.COLOR_PLUS_INTENSITY -> {
                    params.colorPlusFilterIntensity = (settingType.second[settingType.third].value as? Float) ?: 0f
                }

                SettingType.IMAGE_FUSION -> {
                    params.isColorFusion = (settingType.second[settingType.third].value as? Boolean) ?: false
                }

                SettingType.SCREEN_RATE -> {
                    params.screenRatio = (settingType.second[settingType.third].value as? ScreenRate)?.toIntArray() ?: intArrayOf(9, 16)
                }

                SettingType.RENDER_MODE -> {
                    params.renderModel = (settingType.second[settingType.third].value as? RenderModel) ?: RenderModel.AUTO
                }

                SettingType.OFFSET_TYPE -> {
                    params.offsetType = (settingType.second[settingType.third].value as? OffsetType) ?: OffsetType.ORIGINAL
                }

                SettingType.STAB_TYPE -> {
                    params.stabType = (settingType.second[settingType.third].value as? StabType) ?: StabType.AUTO
                }

                else -> {}
            }
        }
        params.index = _ui.value.index
        params.urlForAction = if (_ui.value.isShowHdrGenerate) _ui.value.hdrGeneratePath else if (_ui.value.isShowPureShotGenerate) _ui.value.pureShotGeneratePath else ""
        // 全景素材可以启用手势查看四周
        params.isGestureEnabled = workWrapper.isPanoramaFile()
        params.isGestureVerticalEnabled = workWrapper.isPanoramaFile()
        params.isGestureHorizontalEnabled = workWrapper.isPanoramaFile()
        params.isGestureZoomEnabled = workWrapper.isPanoramaFile()
        return params
    }

    private fun createImageExportParams(workWrapper: WorkWrapper, yaw: Float, distance: Float, fov: Float, pitch: Float): ImageExportParams {
        val params = ImageExportParams(workWrapper)
        _ui.value.settings.forEach { settingType ->
            when (settingType.first) {
                SettingType.DYNAMIC_STITCH -> {
                    params.isDynamicStitch = settingType.second[settingType.third].value as Boolean
                }

                SettingType.DE_PURPLE_FILTER -> {
                    params.isDePurpleFilterOn = settingType.second[settingType.third].value as Boolean
                }

                SettingType.COLOR_PLUS -> {
                    params.colorPlusEnable = settingType.second[settingType.third].value as Boolean
                }

                SettingType.COLOR_PLUS_INTENSITY -> {
                    params.colorPlusFilterIntensity = settingType.second[settingType.third].value as Float
                }

                SettingType.IMAGE_FUSION -> {
                    params.isColorFusion = settingType.second[settingType.third].value as Boolean
                }

                SettingType.SCREEN_RATE -> {
                    params.screenRatio = (settingType.second[settingType.third].value as ScreenRate).toIntArray()
                }

                SettingType.OFFSET_TYPE -> {
                    params.offsetType = settingType.second[settingType.third].value as OffsetType
                }

                SettingType.STAB_TYPE -> {
                    params.stabType = settingType.second[settingType.third].value as StabType
                }

                SettingType.EXPORT_MODE -> {
                    params.exportMode = settingType.second[settingType.third].value as ExportMode
                }

                SettingType.DENOISE -> {
                    params.isDenoise = settingType.second[settingType.third].value as Boolean
                }

                SettingType.RESOLUTION -> {
                    val size = (settingType.second[settingType.third].value as Pair<*, *>).first as Size
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
        params.index = _ui.value.index
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
                SettingType.EXPORT_MODE -> {
                    val exportModeOptions = EXPORT_MODE_OPTIONS.filter {
                        // 非全景素材不支持ExportMode.PANORAMA
                        if (workWrapper!!.isPanoramaFile()) true else it.value as ExportMode == ExportMode.SPHERE
                    }
                    settings.add(Triple(SettingType.EXPORT_MODE, exportModeOptions, selectionIndex(exportModeOptions)))
                }
                // 预览模式
                SettingType.PREVIEW_MODE -> {
                    val previewModeOptions = if (workWrapper!!.isPanoramaFile()) PREVIEW_MODE_OPTIONS else emptyList()
                    if (previewModeOptions.isNotEmpty()) {
                        settings.add(Triple(SettingType.PREVIEW_MODE, previewModeOptions, selectionIndex(previewModeOptions)))
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

                SettingType.RESOLUTION -> {
                    // 分辨率
                    val resolutionOptions = RESOLUTION_OPTIONS.filter { opt ->
                        val size = (opt.value as Pair<*, *>).first as Size
                        val screenRate = opt.value.second as ScreenRate
                        val currentScreenRate = previousList.find { it.first == SettingType.SCREEN_RATE }?.let { setting ->
                            resolveSelectedOption(setting.second, setting.third)?.value as? ScreenRate
                        } ?: SCREEN_RATE_OPTIONS[0].value as ScreenRate
                        currentScreenRate.x == screenRate.x && currentScreenRate.y == screenRate.y && size.width <= workWrapper!!.getWidth() && size.height <= workWrapper!!.getHeight()
                    }
                    if (resolutionOptions.isNotEmpty()) {
                        settings.add(Triple(SettingType.RESOLUTION, resolutionOptions, selectionIndex(resolutionOptions)))
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
                SettingType.COLOR_PLUS_INTENSITY -> settings.add(Triple(SettingType.COLOR_PLUS_INTENSITY, COLOR_PLUS_INTENSITY_OPTIONS, selectionIndex(COLOR_PLUS_INTENSITY_OPTIONS)))
                // 消色差
                SettingType.IMAGE_FUSION -> settings.add(Triple(SettingType.IMAGE_FUSION, BOOLEAN_OPTIONS, selectionIndex(BOOLEAN_OPTIONS)))
                // 保护镜类型
                SettingType.OFFSET_TYPE -> settings.add(Triple(SettingType.OFFSET_TYPE, OFFSET_TYPE_OPTIONS, selectionIndex(OFFSET_TYPE_OPTIONS)))

                else -> {}
            }
        }

        _ui.update { it.copy(settings = settings) }
    }

    fun startExportImage(yaw: Float, distance: Float, fov: Float, pitch: Float) {
        if (!_ui.value.isLoadedExtraData) {
            emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_please_load_extra_data_first_and_then_export)))
            return
        }
        workWrapper?.let { work ->
            val params = createImageExportParams(work, yaw, distance, fov, pitch)
            ExporterManager.exportImage(params, object : IExportCallback {
                override fun onStart(id: Int) {
                    d("开始导出  id=$id")
                    exportId = id
                    emit(_uiEvent, ImageUiEvent.ShowExportDialog)
                }

                override fun onSuccess() {
                    d("导出成功")
                    viewModelScope.launch {
                        val target =
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/INSTA_EXPORT/IMAGE/" + System.currentTimeMillis() + ".jpg"
                        copyFile(getContext(), File(params.targetPath ?: ""), target)
                        emit(_uiEvent, ImageUiEvent.HideExportDialog)
                        emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_export_success)))
                    }
                }

                override fun onFail(throwable: Throwable) {
                    d("导出失败：${throwable.message}")
                    emit(_uiEvent, ImageUiEvent.HideExportDialog)
                    emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_export_failed, throwable.message ?: "")))
                }

                override fun onCancel() {
                    d("取消导出")
                    emit(_uiEvent, ImageUiEvent.HideExportDialog)
                }

                override fun onProgress(progress: Float) {
                    d("导出中：$progress")
                    _ui.update { it.copy(exportProgress = progress) }
                }

            })
        } ?: run {
            emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
        }
    }

    fun stopExportImage() {
        d("用户取消导出  exportId=$exportId")
        ExporterManager.stopExport(exportId)
    }

    fun setParams(type: SettingType, option: SettingOption) {
        val settings = _ui.value.settings.toMutableList()
        settings.find { it.first == type }?.let { find ->
            val selectedIndex = findOptionIndex(find.second, option)
            settings.replaceIf(Triple(type, find.second, selectedIndex)) { it.first == type && it.third != selectedIndex }
        }
        updateSettings(settings)
        emit(_uiEvent, ImageUiEvent.SetParams(type, option))

        if (type == SettingType.RENDER_MODE && (option.value as RenderModel) == RenderModel.PLANE_STITCH) {
            emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_render_plan_stitch_tips)))
        }
    }

    fun previous() {
        workWrapper?.let {
            if (this._ui.value.index == 0) {
                emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_already_first_picture)))
            } else {
                val index = max(0, this._ui.value.index - 1)
                _ui.update { it.copy(index = index) }
            }

        } ?: run {
            emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
        }
    }

    fun next() {
        workWrapper?.let { work ->
            if (this._ui.value.index == work.getCount() - 1) {
                emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_already_last_picture)))
            } else {
                val index = min(work.getCount() - 1, this._ui.value.index + 1)
                _ui.update { it.copy(index = index) }
            }
        } ?: run {
            emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
        }
    }

    fun stop() {
        viewModelScope.launch {
            if (!_ui.value.isLoadedExtraData) {
                emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_extra_data_not_loaded)))
                return@launch
            }
            workWrapper?.let {
                emit(_uiEvent, ImageUiEvent.Stop)
                isPrepared = false
            } ?: run {
                emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_error_work_is_null)))
            }
        }
    }

    fun hdrGenerate() {
        workWrapper?.let {
            viewModelScope.launch {
                emit(_uiEvent, ImageUiEvent.ShowLoading(getString(R.string.player_toast_hdr_generate_in_progress)))
                val path = getContext().externalCacheDir?.absolutePath + "/demo/statch/hdr/" + System.currentTimeMillis() + ".jpg"
                d("path = ${path.toPath().parent?.toString()}")
                withContext(Dispatchers.IO) {
                    StitchManager.generateHDR(it, path)
                }.onSuccess {
                    val target = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/INSTA_STITCH/" + System.currentTimeMillis() + ".jpg"
                    copyFile(getContext(), File(path), target)
                    emit(_uiEvent, ImageUiEvent.HideLoading)
                    emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_hdr_generate_success)))
                    _ui.update { it.copy(hdrGeneratePath = path) }
                }.onFailure {
                    emit(_uiEvent, ImageUiEvent.HideLoading)
                    emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_hdr_generate_failed, it.message ?: "")))
                }
            }
        }
    }

    fun pureShotGenerate() {
        workWrapper?.let {
            viewModelScope.launch {
                emit(_uiEvent, ImageUiEvent.ShowLoading(getString(R.string.player_toast_pure_shot_generate_in_progress)))
                val path = getContext().externalCacheDir?.absolutePath + "/demo/statch/pureshot/" + System.currentTimeMillis() + ".jpg"
                withContext(Dispatchers.IO) {
                    StitchManager.generatePureShot(it, path, "${getContext().getExternalFilesDir(null)}/pure_shot")
                }.onSuccess {
                    val target = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/INSTA_STITCH/" + System.currentTimeMillis() + ".jpg"
                    copyFile(getContext(), File(path), target)
                    emit(_uiEvent, ImageUiEvent.HideLoading)
                    emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_pure_shot_generate_success)))
                    _ui.update { it.copy(pureShotGeneratePath = path) }
                }.onFailure {
                    emit(_uiEvent, ImageUiEvent.HideLoading)
                    emit(_uiEvent, ImageUiEvent.Toast(getString(R.string.player_toast_pure_shot_generate_failed, it.message ?: "")))
                }
            }
        }
    }

    fun onHdrClick() {
        if (_ui.value.hdrGeneratePath.isEmpty()) {
            hdrGenerate()
        } else if (!_ui.value.isShowHdrGenerate) {
            showHdrGenerate()
        } else {
            showOriginal()
        }
    }

    fun onPureShotClick() {
        if (_ui.value.pureShotGeneratePath.isEmpty()) {
            pureShotGenerate()
        } else if (!_ui.value.isShowPureShotGenerate) {
            showPureShotGenerate()
        } else {
            showOriginal()
        }
    }

    fun showHdrGenerate() {
        _ui.update { it.copy(isShowHdrGenerate = true) }
        play()
    }

    fun showPureShotGenerate() {
        _ui.update { it.copy(isShowPureShotGenerate = true) }
        play()
    }

    fun showOriginal() {
        _ui.update { it.copy(isShowPureShotGenerate = false, isShowHdrGenerate = false) }
        play()
    }

    override fun onCleared() {
        super.onCleared()
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ImagePlayerViewModel(application) as T
        }
    }
}