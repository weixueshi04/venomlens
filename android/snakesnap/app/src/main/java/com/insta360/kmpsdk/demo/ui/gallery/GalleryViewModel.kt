package com.insta360.kmpsdk.demo.ui.gallery

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arashivision.sdk.camera.core.model.CameraType
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.arashivision.sdk.camera.core.model.ConnectType
import com.arashivision.sdk.common.kotlin.roundTo
import com.arashivision.sdk.media.api.work.WorkManager
import com.arashivision.sdk.media.api.work.WorkWrapper
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.data.GalleryLocationFilter
import com.insta360.kmpsdk.demo.data.GalleryTypeFilter
import com.insta360.kmpsdk.demo.ext.durationFormat
import com.insta360.kmpsdk.demo.ext.emit
import com.insta360.kmpsdk.demo.ext.gb
import com.insta360.kmpsdk.demo.ext.getString
import com.insta360.kmpsdk.demo.ext.mb
import com.insta360.kmpsdk.demo.ext.speedFormat
import com.insta360.kmpsdk.demo.ext.timeFormat
import com.insta360.kmpsdk.demo.glide.GlideApp
import com.insta360.kmpsdk.demo.ui.connection.ConnectState
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.function.Consumer

data class GalleryUiItemState(
    val key: String,
    val size: String,
    val resolution: String,
    val date: String,
    val duration: String,
    val cameraType: String,
    val isLocal: Boolean,
    val isVideo: Boolean,
    val functionModeIconResId: Int?,
    val isDownloading: Boolean,
    val downloadFileProgress: Long,
    val downloadFileTotal: Long,
    val downloadSpeedBps: Long,
    val isDeleting: Boolean,
    val thumbnail: Bitmap?,
)

data class GalleryUiState(
    val typeFilter: GalleryTypeFilter = GalleryTypeFilter.All,
    val locationFilter: GalleryLocationFilter = GalleryLocationFilter.Local,
    val items: List<GalleryUiItemState> = emptyList(),
    /** 每次 [applyFilters] 递增，用于在原地改 item 场景区分前后两次 [GalleryUiState]（[MutableStateFlow] 按 equals 去重） */
    val contentVersion: Int = 0
)

sealed class GalleryUiEvent {
    data class Toast(val message: String) : GalleryUiEvent()
    data class PlayImage(val workWrapper: WorkWrapper) : GalleryUiEvent()
    data class PlayVideo(val workWrapper: WorkWrapper) : GalleryUiEvent()
}

class GalleryViewModel(val connectionViewModel: ConnectionViewModel, application: Application) : AndroidViewModel(application) {

    private var allCameraItems: MutableList<Pair<WorkWrapper, GalleryUiItemState>> = mutableListOf()
    private var allLocalItems: MutableList<Pair<WorkWrapper, GalleryUiItemState>> = mutableListOf()

    private val _ui = MutableStateFlow(applyFilters(GalleryUiState()))
    val ui: StateFlow<GalleryUiState> = _ui.asStateFlow()

    private val _uiEvent = MutableSharedFlow<GalleryUiEvent>()
    val uiEvent = _uiEvent.asSharedFlow() // 只读

    fun reload() {
        viewModelScope.launch {
            try {
                if (connectionViewModel.connectionUi.value.connectionMethodLabel != ConnectType.BLE.name) {
                    WorkManager.getAllCameraWorks().onSuccess { items ->
                        allCameraItems = mergeItems(allCameraItems, items)
                    }.onFailure {
                        Timber.d("it --->$it")
                    }
                }
            } catch (e: Exception) {
                Timber.d("Exception --->$e")
            }

            WorkManager.getAllLocalWorks().let { items ->
                allLocalItems = mergeItems(allLocalItems, items)
            }
            _ui.update { applyFilters(it) }
        }
    }

    /**
     * 用 [WorkWrapper.getIdenticalKey] 作为去重依据做增量合并。
     * WorkWrapperImpl.equals() 每次比较都会重新拼接 identicalKey 字符串，直接用 List.contains/removeIf
     * 做嵌套比较是 O(n²) 且单次比较开销很大，相册文件较多时会在主线程卡秒级导致 ANR，故在 IO 线程用 Set<String> 做 diff。
     */
    private suspend fun mergeItems(
        current: MutableList<Pair<WorkWrapper, GalleryUiItemState>>,
        items: List<WorkWrapper>
    ): MutableList<Pair<WorkWrapper, GalleryUiItemState>> = withContext(Dispatchers.IO) {
        val newKeys = items.mapTo(mutableSetOf()) { it.getIdenticalKey() }
        val kept = current.filter { it.first.getIdenticalKey() in newKeys }
        val keptKeys = kept.mapTo(mutableSetOf()) { it.first.getIdenticalKey() }
        val added = items.filter { it.getIdenticalKey() !in keptKeys }.map { it to createGalleryUiItemState(it) }
        (kept + added).toMutableList()
    }

    private fun createGalleryUiItemState(workWrapper: WorkWrapper): GalleryUiItemState {
        val resolution = "${workWrapper.getWidth()}×${workWrapper.getHeight()}"
        val fps = if (workWrapper.isVideo()) " · ${workWrapper.getFps().roundTo(2)}fps" else ""
        return GalleryUiItemState(
            key = workWrapper.getIdenticalKey(),
            size = workWrapper.getFileSize().let { if (it < 100 * 1024 * 1024) it.mb() else it.gb() },
            resolution = "$resolution$fps",
            date = workWrapper.getCreationTime().timeFormat(),
            duration = workWrapper.getDurationInMs().durationFormat(),
            // 显示相机名称，需要用CameraType的displayName
            cameraType = CameraType.getForType(workWrapper.getCameraType()).displayName,
            isLocal = workWrapper.isLocalFile(),
            isVideo = workWrapper.isVideo(),
            functionModeIconResId = getFunctionModeIconResId(workWrapper),
            isDownloading = false,
            isDeleting = false,
            downloadFileProgress = 0L,
            downloadFileTotal = 0L,
            downloadSpeedBps = 0L,
            thumbnail = null,
        )
    }

    private fun getFunctionModeIconResId(workWrapper: WorkWrapper): Int? {
        if (workWrapper.isBulletTime()) return R.drawable.ic_capture_mode_bullettime
        if (workWrapper.isBurst()) return R.drawable.ic_capture_mode_burst
        if (workWrapper.isHDRPhoto()) return R.drawable.ic_capture_mode_hdr_capture
        if (workWrapper.isHDRVideo()) return R.drawable.ic_capture_mode_hdr_record
        if (workWrapper.isIntervalShooting()) return R.drawable.ic_capture_mode_interval
        if (workWrapper.isLooperVideo()) return R.drawable.ic_capture_mode_loop_video
        if (workWrapper.isNormalPhoto()) return R.drawable.ic_capture_mode_capture
        if (workWrapper.isNormalVideo()) return R.drawable.ic_capture_mode_record
        if (workWrapper.isPureVideo()) return R.drawable.ic_capture_mode_pure_video
        if (workWrapper.isSelfieVideo()) return R.drawable.ic_capture_mode_selfie
        if (workWrapper.isSlowMotion()) return R.drawable.ic_capture_mode_slowmo
        if (workWrapper.isStarLapse()) return R.drawable.ic_capture_mode_starlapse
        if (workWrapper.isSuperNight()) return R.drawable.ic_capture_mode_super_night
        if (workWrapper.isSuperVideo()) return R.drawable.ic_capture_mode_super_record
        if (workWrapper.isTimeLapse()) return R.drawable.ic_capture_mode_timelapse
        if (workWrapper.isTimeShift()) return R.drawable.ic_capture_mode_timeshift
        return null
    }

    fun setLocationFilter(locationFilter: GalleryLocationFilter) {
        if (locationFilter == GalleryLocationFilter.Camera
            && connectionViewModel.connectionUi.value.connectState == ConnectState.Idle
        ) {
            // 相机未连接
            emit(_uiEvent, GalleryUiEvent.Toast(getString(R.string.camera_not_connected)))
            return
        }
        if (locationFilter == GalleryLocationFilter.Camera
            && connectionViewModel.connectionUi.value.connectionMethodLabel == ConnectType.BLE.name
        ) {
            // 蓝牙连接不支持查看相机相册
            emit(_uiEvent, GalleryUiEvent.Toast(getString(R.string.gallery_not_support_ble)))
            return
        }
        _ui.update { applyFilters(it.copy(locationFilter = locationFilter)) }
    }

    fun setTypeFilter(typeFilter: GalleryTypeFilter) {
        _ui.update { applyFilters(it.copy(typeFilter = typeFilter)) }
    }

    private fun applyFilters(state: GalleryUiState): GalleryUiState {
        val items = filter(state.locationFilter, state.typeFilter)
        return state.copy(
            items = items,
            contentVersion = state.contentVersion + 1
        )
    }

    fun onCameraDisconnected() {
        allCameraItems.clear()
        _ui.update { applyFilters(it) }
    }

    private fun replaceItemState(
        key: String,
        transform: (GalleryUiItemState) -> GalleryUiItemState
    ) {
        val inCamera = allCameraItems.indexOfFirst { it.second.key == key }
        if (inCamera >= 0) {
            val pair = allCameraItems[inCamera]
            allCameraItems[inCamera] = pair.first to transform(pair.second)
        } else {
            val inLocal = allLocalItems.indexOfFirst { it.second.key == key }
            if (inLocal >= 0) {
                val pair = allLocalItems[inLocal]
                allLocalItems[inLocal] = pair.first to transform(pair.second)
            } else {
                return
            }
        }
        _ui.update { applyFilters(it) }
    }

    private fun findItemByIndex(index: Int): Pair<WorkWrapper, GalleryUiItemState>? {
        val item = _ui.value.items[index]
        val allItems = if (item.isLocal) allLocalItems else allCameraItems
        return allItems.find { it.second.key == item.key }
    }

    fun play(index: Int) {
        findItemByIndex(index)?.let {
            if (it.first.isVideo()) {
                emit(_uiEvent, GalleryUiEvent.PlayVideo(it.first))
            } else {
                emit(_uiEvent, GalleryUiEvent.PlayImage(it.first))
            }
        }
    }


    /** 正在加载缩略图的 key 集合，避免同一 item 在 thumbnail 加载完成前被重复触发加载 */
    private val loadingThumbnailKeys = mutableSetOf<String>()

    fun loadThumbnail(index: Int) {
        findItemByIndex(index)?.let { item ->
            val k = item.second.key
            if (!loadingThumbnailKeys.add(k)) return
            loadBitmap(getApplication(), item.first) { bmp ->
                loadingThumbnailKeys.remove(k)
                replaceItemState(k) { it.copy(thumbnail = bmp) }
            }
        }
    }

    private fun loadBitmap(context: Context, data: WorkWrapper, consumer: Consumer<Bitmap>) {
        GlideApp.with(context).asBitmap().load(data).placeholder(R.drawable.ic_image_default).error(R.drawable.ic_image_default).priority(Priority.HIGH)
            .format(DecodeFormat.PREFER_RGB_565)
            .override(THUMBNAIL_WIDTH_PX, THUMBNAIL_HEIGHT_PX)
            .into(object : SimpleTarget<Bitmap>(THUMBNAIL_WIDTH_PX, THUMBNAIL_HEIGHT_PX) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    consumer.accept(resource)
                }
            })
    }

    private fun canDownload(index: Int): Boolean {
        return findItemByIndex(index)?.let { item ->
            // 通过对比第一个文件的文件名去判断是否已经下载
            val firstFileName = item.first.mainUrls[0].substringAfterLast('/', "")
            allLocalItems.find { it.first.mainUrls[0].contains(firstFileName) } == null
        } ?: false
    }

    fun downloadFiles(index: Int) {
        if (!canDownload(index)) {
            emit(_uiEvent, GalleryUiEvent.Toast(getString(R.string.gallery_download_already_exists)))
            return
        }
        findItemByIndex(index)?.let { item ->
            val key = item.second.key
            replaceItemState(key) { it.copy(isDownloading = true) }
            viewModelScope.launch {
                val startTime = System.currentTimeMillis()
                var lastSampleTime = startTime
                var lastSampleProgress = 0L
                item.first.download { total, progress ->
                    val now = System.currentTimeMillis()
                    val elapsedSinceSample = now - lastSampleTime
                    // 节流：至少间隔 DOWNLOAD_SAMPLE_INTERVAL_MS 才采样一次，避免 UI 刷新过于频繁
                    if (elapsedSinceSample < DOWNLOAD_SAMPLE_INTERVAL_MS && progress < total) return@download
                    val speedBps = if (elapsedSinceSample > 0) {
                        (progress - lastSampleProgress) * 1000 / elapsedSinceSample
                    } else 0L
                    lastSampleTime = now
                    lastSampleProgress = progress
                    Timber.d("Downloading key=$key $progress/$total speed=${speedBps.speedFormat()}")
                    replaceItemState(key) { s ->
                        s.copy(
                            isDownloading = true,
                            downloadFileProgress = progress,
                            downloadFileTotal = total,
                            downloadSpeedBps = speedBps
                        )
                    }
                }.onSuccess {
                    val elapsedTotal = System.currentTimeMillis() - startTime
                    val avgSpeedBps = if (elapsedTotal > 0) lastSampleProgress * 1000 / elapsedTotal else 0L
                    Timber.d("Download finished key=$key totalBytes=$lastSampleProgress elapsedMs=$elapsedTotal avgSpeed=${avgSpeedBps.speedFormat()}")
                    viewModelScope.launch(Dispatchers.Main) {
                        replaceItemState(key) { it.copy(isDownloading = false, downloadSpeedBps = 0L) }
                        emit(_uiEvent, GalleryUiEvent.Toast(getString(R.string.gallery_download_success, avgSpeedBps.speedFormat())))
                        reload()
                    }
                }.onFailure { err ->
                    Timber.w(err, "Download failed for key=$key")
                    viewModelScope.launch(Dispatchers.Main) {
                        replaceItemState(key) { it.copy(isDownloading = false, downloadSpeedBps = 0L) }
                        emit(_uiEvent, GalleryUiEvent.Toast(getString(R.string.gallery_download_failed, err.toString())))
                    }
                }
            }
        }
    }

    fun deleteFiles(index: Int) {
        viewModelScope.launch {
            findItemByIndex(index)?.let { item ->
                val key = item.second.key
                replaceItemState(key) { it.copy(isDeleting = true) }
                item.first.delete().onSuccess {
                    reload()
                }.onFailure { err ->
                    replaceItemState(key) { it.copy(isDeleting = false) }
                    emit(_uiEvent, GalleryUiEvent.Toast(getString(R.string.gallery_delete_failed, err.toString())))
                }
            }
        }
    }

    private fun filter(
        locationFilter: GalleryLocationFilter,
        typeFilter: GalleryTypeFilter
    ): List<GalleryUiItemState> = when (locationFilter) {
        GalleryLocationFilter.Camera -> allCameraItems
        GalleryLocationFilter.Local -> allLocalItems
    }.let { items ->
        when (typeFilter) {
            GalleryTypeFilter.All -> items
            GalleryTypeFilter.Photo -> items.filter { it.first.isPhoto() }
            GalleryTypeFilter.Video -> items.filter { it.first.isVideo() }
        }.sortedByDescending { it.first.getCreationTime() }
            .map { it.second }
    }

    class Factory(private val connectionViewModel: ConnectionViewModel, private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(connectionViewModel, application) as T
        }
    }

    private companion object {
        /** 下载进度采样节流间隔，避免下载回调过于频繁导致 UI 抖动 */
        const val DOWNLOAD_SAMPLE_INTERVAL_MS = 500L

        /** 相册网格缩略图目标解码尺寸（px），避免 Glide 按原图分辨率解码导致内存暴涨与 GC 卡顿 */
        const val THUMBNAIL_WIDTH_PX = 400
        const val THUMBNAIL_HEIGHT_PX = 200
    }
}
