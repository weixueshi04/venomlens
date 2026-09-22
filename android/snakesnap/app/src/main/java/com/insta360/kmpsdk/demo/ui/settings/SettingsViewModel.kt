package com.insta360.kmpsdk.demo.ui.settings

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.param.listener.AuthorizationListener
import com.arashivision.sdk.camera.api.param.listener.BleWakeUpListener
import com.arashivision.sdk.camera.api.param.listener.StorageStateListener
import com.arashivision.sdk.camera.core.model.CameraType
import com.arashivision.sdk.camera.core.model.ConnectType
import com.arashivision.sdk.camera.core.model.authorization.AuthorizationOperationType
import com.arashivision.sdk.camera.core.model.authorization.AuthorizationResult
import com.arashivision.sdk.camera.core.model.authorization.AuthorizationStatus
import com.arashivision.sdk.camera.core.model.capture.LockScreenState
import com.arashivision.sdk.camera.core.model.option.Sharpness
import com.arashivision.sdk.camera.core.model.option.StorageData
import com.arashivision.sdk.camera.core.model.option.VideoEncode
import com.arashivision.sdk.common.file.InstaFileManager
import com.arashivision.sdk.common.log.LogLevel
import com.arashivision.sdk.common.log.Logger
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.util.DemoAppPreferences
import com.insta360.kmpsdk.demo.util.DemoLogcatDumper
import com.insta360.kmpsdk.demo.util.NanProcessNetworkBinding
import com.insta360.kmpsdk.demo.util.formatLocalTime
import com.insta360.kmpsdk.demo.util.zipDirectoryContentsToFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

sealed interface CameraLogExportState {
    data object Idle : CameraLogExportState

    /** Percentage text when total size is known, e.g. `35%` */
    data class Exporting(
        val message: String,
    ) : CameraLogExportState
}

sealed interface DemoLogExportState {
    data object Idle : DemoLogExportState
    data class Exporting(val message: String) : DemoLogExportState
}

sealed interface FirmwareUpgradeUiState {
    data object Idle : FirmwareUpgradeUiState

    data class Progress(
        val message: String,
    ) : FirmwareUpgradeUiState
}

sealed interface SettingsUiEffect {
    data class ShowToast(
        val message: String,
    ) : SettingsUiEffect

    data class ShowDialog(
        val message: String,
    ) : SettingsUiEffect

    data class ShareDemoLogZip(
        val uri: Uri,
    ) : SettingsUiEffect

    data class ShareCameraLogFile(
        val uri: Uri,
    ) : SettingsUiEffect
}

data class SettingsUiState(
    val expandedModuleIds: Set<String> = setOf("log", "wifi", "activation"),
    val logCaptureEnabled: Boolean = false,
    /** dumper 读循环真实运行状态；与 [logCaptureEnabled]（用户开关）区分展示 */
    val dumperRunning: Boolean = false,
    val logLevel: LogLevel = LogLevel.INFO,
    val wifiName: String = "—",
    val wifiPassword: String = "—",
    val wifiMac: String = "",
    val countryCode: String = "—",
    val currentChannel: Int? = null,
    val channelOptions: List<Int> = emptyList(),
    val soundMuted: Boolean = false, // true 表示有声音
    val cameraLocked: Boolean = false,
    val encodingIndex: Int = 0,
    val sharpnessIndex: Int = Sharpness.MEDIUM.nativeValue,
    /** 蓝牙唤醒命令进行中；用于禁用入口按钮，避免重复触发 */
    val wakingUp: Boolean = false,
    /** 当前连接机型需要 BLE 授权（GO 3S / GO Ultra），隐藏蓝牙唤醒行，展示授权按钮 */
    val showBleAuth: Boolean = false,
    /** 相机上报的存储位置列表；能力驱动型渲染，条数由固件决定，不按机型判断 */
    val storageDataList: List<StorageData> = emptyList(),
)

class SettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    private val _cameraLogExport = MutableStateFlow<CameraLogExportState>(CameraLogExportState.Idle)
    val cameraLogExport: StateFlow<CameraLogExportState> = _cameraLogExport.asStateFlow()

    private val _demoLogExport = MutableStateFlow<DemoLogExportState>(DemoLogExportState.Idle)
    val demoLogExport: StateFlow<DemoLogExportState> = _demoLogExport.asStateFlow()

    private val _firmwareUpgrade = MutableStateFlow<FirmwareUpgradeUiState>(FirmwareUpgradeUiState.Idle)
    val firmwareUpgrade: StateFlow<FirmwareUpgradeUiState> = _firmwareUpgrade.asStateFlow()

    private val _effects =
        MutableSharedFlow<SettingsUiEffect>(
            replay = 0,
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val effects: SharedFlow<SettingsUiEffect> = _effects.asSharedFlow()

    init {
        val app = getApplication<Application>()
        val cap = DemoAppPreferences.readLogcatDumpEnabled(app)
        val level = DemoAppPreferences.readLogLevel(app)
        _ui.update { it.copy(logCaptureEnabled = cap, logLevel = level) }
        viewModelScope.launch {
            DemoLogcatDumper.runningState.collect { r ->
                _ui.update { it.copy(dumperRunning = r) }
            }
        }
    }

    fun toggleModule(id: String) {
        _ui.update { s ->
            val next = s.expandedModuleIds.toMutableSet()
            if (!next.add(id)) next.remove(id)
            s.copy(expandedModuleIds = next)
        }
    }

    private fun str(resId: Int) = getApplication<Application>().getString(resId)

    private fun str(
        resId: Int,
        vararg args: Any,
    ) = getApplication<Application>().getString(resId, *args)

    private fun toast(msg: String) {
        viewModelScope.launch { _effects.emit(SettingsUiEffect.ShowToast(msg)) }
    }

    private fun dialog(msg: String) {
        viewModelScope.launch { _effects.emit(SettingsUiEffect.ShowDialog(msg)) }
    }

    private fun requireDevice(device: CameraDevice?): Boolean {
        if (device == null) {
            dialog(str(R.string.camera_not_connected))
            return false
        }
        return true
    }

    /** Wi-Fi 配置变更后刷新显示。 */
    private fun refreshWifiDisplayFromCamera(device: CameraDevice?) {
        if (device == null) return
        viewModelScope.launch {
            device.system
                .fetchWifiData()
                .onSuccess { data ->
                    _ui.update {
                        it.copy(
                            wifiName = data.ssid,
                            wifiPassword = data.pwd,
                            wifiMac = data.mac,
                            currentChannel = data.channel,
                        )
                    }
                }.onFailure { e ->
                    Timber.w(e, "refresh WiFi info failed")
                }
            device.system
                .fetchWifiChannelList()
                .onSuccess { ch ->
                    _ui.update {
                        it.copy(
                            countryCode = ch.country,
                            channelOptions = ch.channelList,
                        )
                    }
                }.onFailure { e ->
                    Timber.w(e, "refresh WiFi channel list failed")
                }
        }
    }

    // region ---- Wi-Fi ----

    fun fetchWifiInfo(device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            Timber.d("fetchWifiInfo")
            device!!
                .system
                .fetchWifiData()
                .onSuccess { data ->
                    _ui.update {
                        it.copy(
                            wifiName = data.ssid,
                            wifiPassword = data.pwd,
                            wifiMac = data.mac,
                            currentChannel = data.channel,
                        )
                    }
                }.onFailure { e ->
                    Timber.w(e, "fetchWifiInfo failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun fetchWifiChannels(device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            Timber.d("fetchWifiChannels")
            device!!
                .system
                .fetchWifiChannelList()
                .onSuccess { ch ->
                    _ui.update {
                        it.copy(
                            countryCode = ch.country,
                            channelOptions = ch.channelList,
                        )
                    }
                }.onFailure { e ->
                    Timber.w(e, "fetchWifiChannels failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun fetchCurrentChannel(device: CameraDevice?) = fetchWifiInfo(device)

    fun setCountryCode(
        device: CameraDevice?,
        code: String,
    ) {
        if (!requireDevice(device)) return
        if (code.isBlank()) {
            dialog(str(R.string.input_cannot_be_empty))
            return
        }
        viewModelScope.launch {
            Timber.d("setCountryCode: %s", code)
            device!!
                .system
                .setWiFiCountry(code)
                .onSuccess {
                    toast(str(R.string.operation_success))
                    refreshWifiDisplayFromCamera(device)
                }.onFailure { e ->
                    Timber.w(e, "setCountryCode failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun setWifiChannel(
        device: CameraDevice?,
        channel: Int,
    ) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            Timber.d("setWifiChannel: %d", channel)
            device!!
                .system
                .resetCameraWiFi(channel)
                .onSuccess {
                    toast(str(R.string.operation_success))
                    refreshWifiDisplayFromCamera(device)
                }.onFailure { e ->
                    Timber.w(e, "setWifiChannel failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun openWifi(device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            device!!
                .system
                .openCameraWiFi()
                .onSuccess {
                    toast(str(R.string.operation_success))
                    refreshWifiDisplayFromCamera(device)
                }.onFailure { e ->
                    Timber.w(e, "openWifi failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun closeWifi(device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            device!!
                .system
                .closeCameraWiFi()
                .onSuccess {
                    toast(str(R.string.operation_success))
                    refreshWifiDisplayFromCamera(device)
                }.onFailure { e ->
                    Timber.w(e, "closeWifi failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun restartWifi(device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            device!!
                .system
                .resetCameraWiFi()
                .onSuccess {
                    toast(str(R.string.operation_success))
                    refreshWifiDisplayFromCamera(device)
                }.onFailure { e ->
                    Timber.w(e, "restartWifi failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    // endregion

    // region ---- Activation ----

    fun activateCamera(
        device: CameraDevice?,
        appId: String,
        secretKey: String,
    ) {
        if (!requireDevice(device)) return
        if (appId.isBlank() || secretKey.isBlank()) {
            dialog(str(R.string.input_cannot_be_empty))
            return
        }
        viewModelScope.launch {
            Timber.d("activateCamera appId=%s", appId)
            NanProcessNetworkBinding
                .runOnDefaultNetwork { device!!.system.activeCamera(appId, secretKey) }
                .onSuccess { toast(str(R.string.operation_success)) }
                .onFailure { e ->
                    Timber.w(e, "activateCamera failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    // endregion

    // region ---- log  ----

    fun setLogCaptureEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled) {
            // 开关只由用户决定：即便本次启动失败也保持 true，交由看门狗持续重试
            DemoAppPreferences.persistLogcatDumpEnabled(app, true)
            _ui.update { it.copy(logCaptureEnabled = true) }
            val err = DemoLogcatDumper.start(app)
            if (err != null) {
                Timber.w("setLogCaptureEnabled: start failed, watchdog will retry: %s", err)
                dialog(str(R.string.demo_logcat_dump_failed, err))
            }
        } else {
            DemoLogcatDumper.stop()
            DemoAppPreferences.persistLogcatDumpEnabled(app, false)
            _ui.update { it.copy(logCaptureEnabled = false) }
        }
    }

    fun setSdkLogLevel(level: LogLevel) {
        if (_ui.value.logLevel == level) return
        DemoAppPreferences.persistLogLevel(getApplication(), level)
        Logger.setLogLevel(level)
        _ui.update { it.copy(logLevel = level) }
    }

    fun exportAppLog() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            Timber.d("exportAppLog")
            _demoLogExport.value = DemoLogExportState.Exporting(str(R.string.exporting_demo_log))
            val logDirPath =
                withContext(Dispatchers.IO) {
                    runCatching { InstaFileManager.getLogDir() }.getOrNull()
                }
            if (logDirPath.isNullOrBlank()) {
                Timber.w("exportAppLog: log dir unavailable")
                _demoLogExport.value = DemoLogExportState.Idle
                dialog(str(R.string.demo_log_sdk_not_initialized))
                return@launch
            }
            val logDir = File(logDirPath)
            if (!logDir.isDirectory) {
                Timber.w("exportAppLog: log dir missing: %s", logDirPath)
                _demoLogExport.value = DemoLogExportState.Idle
                dialog(str(R.string.demo_log_dir_missing))
                return@launch
            }
            val hasFiles =
                withContext(Dispatchers.IO) {
                    logDir.walkTopDown().any { it.isFile }
                }
            if (!hasFiles) {
                Timber.w("exportAppLog: log dir empty")
                _demoLogExport.value = DemoLogExportState.Idle
                dialog(str(R.string.demo_log_dir_empty))
                return@launch
            }
            val fileName =
                System
                    .currentTimeMillis()
                    .formatLocalTime()
                    .replace(" ", "_")
                    .replace(":", "-")
                    .let { "demo_logs_$it" }
            val exportRoot = app.getExternalFilesDir(null) ?: app.cacheDir
            val exportDir = File(exportRoot, "export").apply { mkdirs() }
            val zipFile = File(exportDir, "$fileName.zip")
            val zipErr =
                withContext(Dispatchers.IO) {
                    DemoLogcatDumper.flush()
                    runCatching { zipDirectoryContentsToFile(logDir, zipFile) }
                        .exceptionOrNull()
                }
            if (zipErr != null) {
                Timber.w(zipErr, "exportAppLog: zip failed")
                _demoLogExport.value = DemoLogExportState.Idle
                dialog(str(R.string.demo_log_zip_failed, zipErr.message ?: ""))
                return@launch
            }
            val uri =
                FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.fileprovider",
                    zipFile,
                )
            Timber.d("exportAppLog done, sharing: %s", uri)
            _demoLogExport.value = DemoLogExportState.Idle
            _effects.emit(SettingsUiEffect.ShareDemoLogZip(uri))
        }
    }

    fun exportCameraLog(device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            Timber.d("exportCameraLog")
            _cameraLogExport.value =
                CameraLogExportState.Exporting(str(R.string.exporting_camera_log))
            var lastEmittedPercent = -1
            val result =
                device!!.file.downloadCameraLogFile { progress, total ->
                    if (total <= 0) return@downloadCameraLogFile
                    val pct = ((progress * 100L) / total).toInt().coerceIn(0, 100)
                    val done = progress >= total
                    if (!done && lastEmittedPercent >= 0 && pct <= lastEmittedPercent) {
                        return@downloadCameraLogFile
                    }
                    lastEmittedPercent = if (done) 100 else pct
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        val p = ((progress * 100L) / total).toInt().coerceIn(0, 100)
                        _cameraLogExport.value =
                            CameraLogExportState.Exporting(
                                str(
                                    R.string.camera_log_exported,
                                    "$p%",
                                ),
                            )
                    }
                }
            result.fold(
                onSuccess = { path ->
                    Timber.d("exportCameraLog done: %s", path)
                    _cameraLogExport.value = CameraLogExportState.Idle
                    val app = getApplication<Application>()
                    val uri = runCatching {
                        FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", File(path))
                    }.getOrNull()
                    if (uri != null) {
                        _effects.emit(SettingsUiEffect.ShareCameraLogFile(uri))
                    } else {
                        Timber.w("exportCameraLog: cannot convert path to URI: %s", path)
                        dialog(str(R.string.camera_log_exported, path))
                    }
                },
                onFailure = { e ->
                    Timber.w(e, "exportCameraLog failed")
                    _cameraLogExport.value = CameraLogExportState.Idle
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                },
            )
        }
    }

    // endregion

    // region ---- camera setting ----

    fun loadMuteState(device: CameraDevice?) {
        if (device == null) return
        viewModelScope.launch {
            device.system
                .fetchMute()
                .onSuccess { muted -> _ui.update { it.copy(soundMuted = muted) } }
                .onFailure { Timber.w(it, "fetchMute failed") }
        }
    }

    fun switchMute(mute: Boolean, device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            device!!
                .system
                .setMute(mute)
                .onSuccess { _ui.update { it.copy(soundMuted = mute) } }
                .onFailure { e ->
                    Timber.w(e, "setMute failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun toggleCameraLock(device: CameraDevice?) {
        if (!requireDevice(device)) return
        val newLocked = !_ui.value.cameraLocked
        val lockState = if (newLocked) LockScreenState.LOCK else LockScreenState.IDLE
        viewModelScope.launch {
            device!!
                .system
                .setLockScreenState(lockState)
                .onSuccess { _ui.update { it.copy(cameraLocked = newLocked) } }
                .onFailure { e ->
                    Timber.w(e, "setLockScreenState failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun shutdown(device: CameraDevice?) {
        if (!requireDevice(device)) return
        device!!.system.shutdown()
    }

    fun calibrateGyro(device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            device!!
                .system
                .calibrateGyro()
                .onSuccess { toast(str(R.string.operation_success)) }
                .onFailure { e ->
                    Timber.w(e, "calibrateGyro failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun formatSdCard(device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            device!!
                .system
                .formatSdCard()
                .onSuccess { toast(str(R.string.operation_success)) }
                .onFailure { e ->
                    Timber.w(e, "formatSdCard failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    private var storageListener: StorageStateListener? = null
    private var storageListenerDevice: CameraDevice? = null

    fun loadStorageDataList(device: CameraDevice?) {
        if (device == null) return
        registerStorageStateListener(device)
        viewModelScope.launch {
            device.system
                .fetchStorageDataList()
                .onSuccess { list -> _ui.update { it.copy(storageDataList = list) } }
                .onFailure { Timber.w(it, "fetchStorageDataList failed") }
        }
    }

    private fun registerStorageStateListener(device: CameraDevice) {
        if (storageListenerDevice === device) return
        releaseStorageListener()
        val listener = object : StorageStateListener {
            override fun onStorageStateListChanged(storageDataList: List<StorageData>) {
                _ui.update { it.copy(storageDataList = storageDataList) }
            }
        }
        device.system.registerStorageStatusListener(listener)
        storageListener = listener
        storageListenerDevice = device
    }

    private fun releaseStorageListener() {
        storageListenerDevice?.let { d ->
            storageListener?.let { d.system.unregisterStorageStatusListener(it) }
        }
        storageListener = null
        storageListenerDevice = null
    }

    fun formatStorage(location: StorageData.FileLocation, device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            device!!
                .system
                .formatStorage(location)
                .onSuccess {
                    toast(str(R.string.operation_success))
                    loadStorageDataList(device)
                }.onFailure { e ->
                    Timber.w(e, "format Storage failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun setMainStorage(location: StorageData.FileLocation, device: CameraDevice?) {
        if (!requireDevice(device)) return
        viewModelScope.launch {
            device!!
                .system
                .setMainStorage(location)
                .onSuccess {
                    toast(str(R.string.operation_success))
                    loadStorageDataList(device)
                }.onFailure { e ->
                    Timber.w(e, "set main storage failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }


    fun loadEncodingType(device: CameraDevice?) {
        if (device == null) return
        viewModelScope.launch {
            device.system
                .fetchVideoEncodeType()
                .onSuccess { encode -> _ui.update { it.copy(encodingIndex = encode.nativeValue) } }
                .onFailure { Timber.w(it, "fetchVideoEncodeType failed") }
        }
    }

    fun loadSharpness(device: CameraDevice?) {
        if (device == null) return
        viewModelScope.launch {
            device.system
                .fetchSharpness()
                .onSuccess { sharp ->
                    _ui.update { it.copy(sharpnessIndex = sharp.nativeValue) }
                }.onFailure { Timber.w(it, "fetchSharpness failed") }
        }
    }

    fun setSharpness(
        device: CameraDevice?,
        index: Int,
    ) {
        if (!requireDevice(device)) return
        val sharpness = Sharpness.entries.getOrElse(index) { Sharpness.MEDIUM }
        viewModelScope.launch {
            device!!
                .system
                .setSharpness(sharpness)
                .onSuccess {
                    _ui.update { it.copy(sharpnessIndex = sharpness.nativeValue) }
                    toast(str(R.string.operation_success))
                }.onFailure { e ->
                    Timber.w(e, "setSharpness failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun setEncoding(
        device: CameraDevice?,
        index: Int,
    ) {
        if (!requireDevice(device)) return
        val encode = VideoEncode.entries.getOrElse(index) { VideoEncode.ENCODE_H264 }
        viewModelScope.launch {
            device!!
                .system
                .setVideoEncodeType(encode)
                .onSuccess {
                    _ui.update { it.copy(encodingIndex = index) }
                    toast(str(R.string.operation_success))
                }.onFailure { e ->
                    Timber.w(e, "setVideoEncodeType failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    fun upgradeFirmware(
        device: CameraDevice?,
        firmwareLocalPath: String,
    ) {
        if (!requireDevice(device)) return
        val file = File(firmwareLocalPath)
        if (!file.isFile || !file.canRead()) {
            dialog(str(R.string.firmware_file_invalid))
            return
        }
        viewModelScope.launch {
            _firmwareUpgrade.value = FirmwareUpgradeUiState.Progress("0%")
            var lastEmittedPercent = -1
            val result =
                device!!.firmware.upgradeFirmware(firmwareLocalPath) { fraction ->
                    val pct = (fraction.coerceIn(0.0, 1.0) * 100).toInt()
                    if (pct < 100 && lastEmittedPercent >= 0 && pct <= lastEmittedPercent) {
                        return@upgradeFirmware
                    }
                    lastEmittedPercent = pct
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        _firmwareUpgrade.value = FirmwareUpgradeUiState.Progress("$pct%")
                    }
                }
            result.fold(
                onSuccess = {
                    _firmwareUpgrade.value = FirmwareUpgradeUiState.Idle
                    toast(str(R.string.firmware_upgrade_success))
                },
                onFailure = { e ->
                    Timber.w(e, "upgradeFirmware failed")
                    _firmwareUpgrade.value = FirmwareUpgradeUiState.Idle
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                },
            )
        }
    }

    // endregion

    // region ---- BLE 连接授权（GO 3S / GO Ultra）----

    private var authListener: AuthorizationListener? = null
    private var authListenerDevice: CameraDevice? = null

    /** 根据已连接机型决定是否显示 BLE 授权按钮。 */
    fun setConnectedCameraType(cameraTypeDisplayName: String?) {
        val type = CameraType.getForType(cameraTypeDisplayName ?: "")
        val needAuth = type == CameraType.GO_3S || type == CameraType.GO_ULTRA
        _ui.update { it.copy(showBleAuth = needAuth) }
    }

    private fun ensureAuthListenerRegistered(device: CameraDevice) {
        if (authListenerDevice === device) return
        releaseAuthListener()
        val listener = object : AuthorizationListener {
            override fun onAuthorizationResult(
                operationType: AuthorizationOperationType,
                result: AuthorizationResult,
            ) {
                Timber.d("onAuthorizationResult: op=%s, result=%s", operationType, result)
                val msgRes = when (result) {
                    AuthorizationResult.SUCCESS -> R.string.ble_auth_result_authorized
                    AuthorizationResult.REJECT -> R.string.ble_auth_result_rejected
                    AuthorizationResult.TIMEOUT -> R.string.ble_auth_result_timeout
                    AuthorizationResult.SYSTEM_BUSY -> R.string.ble_auth_result_busy
                }
                viewModelScope.launch { _effects.emit(SettingsUiEffect.ShowDialog(str(msgRes))) }
            }
        }
        device.registerAuthorizationListener(listener)
        authListener = listener
        authListenerDevice = device
    }

    private fun releaseAuthListener() {
        authListenerDevice?.let { d -> authListener?.let { d.unregisterAuthorizationListener(it) } }
        authListener = null
        authListenerDevice = null
    }

    fun requestBleAuthorization(device: CameraDevice?) {
        if (!requireDevice(device)) return
        ensureAuthListenerRegistered(device!!)
        viewModelScope.launch {
            Timber.d("requestBleAuthorization: checkAuthorization")
            device.checkAuthorization()
                .onSuccess { status ->
                    val msg = when (status) {
                        AuthorizationStatus.AUTHORIZED -> str(R.string.ble_auth_authorized)
                        AuthorizationStatus.UNAUTHORIZED -> str(R.string.ble_auth_pending)
                        AuthorizationStatus.SYSTEM_BUSY -> str(R.string.ble_auth_busy)
                    }
                    toast(msg)
                }
                .onFailure { e ->
                    Timber.w(e, "checkAuthorization failed")
                    dialog(e.message ?: str(R.string.operation_failed_generic))
                }
        }
    }

    // endregion

    // ---- Init after camera connected ----

    fun loadCameraState(device: CameraDevice?) {
        loadMuteState(device)
        loadEncodingType(device)
        loadSharpness(device)
        loadStorageDataList(device)
    }

    fun clearCameraStateOnDisconnected() {
        _cameraLogExport.value = CameraLogExportState.Idle
        _firmwareUpgrade.value = FirmwareUpgradeUiState.Idle
        releaseAuthListener()
        releaseStorageListener()
        _ui.update {
            it.copy(
                wifiName = "—",
                wifiPassword = "—",
                wifiMac = "",
                countryCode = "—",
                currentChannel = null,
                channelOptions = emptyList(),
                soundMuted = false,
                cameraLocked = false,
                encodingIndex = 0,
                sharpnessIndex = Sharpness.MEDIUM.nativeValue,
                showBleAuth = false,
                storageDataList = emptyList(),
            )
        }
    }

    /**
     * 通过 SN 在最近设备列表中匹配并发起蓝牙广播唤醒。
     * 无需相机当前已连接：`CameraDevice.get(ConnectType.BLE)` 直接拿到 BLE 单例发广播。
     */
    fun wakeBySn(sn: String) {
        if (_ui.value.wakingUp) return
        val app = getApplication<Application>()
        val record = DemoAppPreferences.readRecentBleDevices(app).firstOrNull { it.sn == sn }
        if (record == null) {
            dialog(str(R.string.wake_no_recent_device))
            return
        }
        val type = CameraType.getForType(record.cameraTypeKey)
        if (type == CameraType.UNKNOWN) {
            // 持久化的 type 串在当前 SDK 枚举中找不到（跨版本/降级），提示用户重新连接
            dialog(str(R.string.wake_camera_type_unrecognized, record.cameraTypeKey))
            return
        }
        _ui.update { it.copy(wakingUp = true) }
        Timber.d("ble wake up start: sn=%s, type=%s", record.sn, type.name)
        val ble = CameraDevice.get(ConnectType.BLE)
        val watchdog: Job = viewModelScope.launch {
            delay(WAKE_TIMEOUT_MS)
            if (_ui.value.wakingUp) {
                Timber.w("ble wake up timeout: sn=%s", record.sn)
                _ui.update { it.copy(wakingUp = false) }
                _effects.emit(SettingsUiEffect.ShowDialog(str(R.string.wake_err_timeout)))
                ble.release()
            }
        }
        ble.bleWakeUp(type, record.deviceName, object : BleWakeUpListener {
            override fun onWakeUpSuccess() {
                Timber.d("ble wake up success: sn=%s", record.sn)
                watchdog.cancel()
                viewModelScope.launch {
                    _ui.update { it.copy(wakingUp = false) }
                    _effects.emit(SettingsUiEffect.ShowToast(str(R.string.wake_success)))
                    ble.release()
                }
            }

            override fun onWakeUpError(errCode: Int) {
                Timber.w("ble wake up error: sn=%s, code=%d", record.sn, errCode)
                watchdog.cancel()
                viewModelScope.launch {
                    _ui.update { it.copy(wakingUp = false) }
                    val msg = if (errCode == ERR_NOT_SUPPORTED) {
                        str(R.string.wake_err_unsupported)
                    } else {
                        str(R.string.wake_err_generic, errCode)
                    }
                    _effects.emit(SettingsUiEffect.ShowDialog(msg))
                    ble.release()
                }
            }
        })
    }

    override fun onCleared() {
        releaseAuthListener()
        releaseStorageListener()
        super.onCleared()
    }

    private companion object {
        // SDK 回调可能不返回（相机断电/超出广播距离），watchdog 兜底解锁按钮
        const val WAKE_TIMEOUT_MS = 8_000L

        // DeviceCoreImpl 在型号不支持广播唤醒时回传的固定错误码
        const val ERR_NOT_SUPPORTED = -99
    }
}
