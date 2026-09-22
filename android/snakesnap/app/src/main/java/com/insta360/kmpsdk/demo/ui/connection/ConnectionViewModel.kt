package com.insta360.kmpsdk.demo.ui.connection

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.param.listener.BatteryListener
import com.arashivision.sdk.camera.api.param.listener.DisconnectListener
import com.arashivision.sdk.camera.api.param.listener.StorageStateListener
import com.arashivision.sdk.camera.core.callback.BleScanCallback
import com.arashivision.sdk.camera.core.model.CameraType
import com.arashivision.sdk.camera.core.model.ConnectType
import com.arashivision.sdk.camera.core.model.option.BatteryData
import com.arashivision.sdk.camera.core.model.option.StorageData
import com.arashivision.sdk.camera.core.model.option.WiFiData
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.data.DeviceInfo
import com.insta360.kmpsdk.demo.data.RecentBleDevice
import com.insta360.kmpsdk.demo.data.toDeviceInfo
import com.insta360.kmpsdk.demo.data.withBattery
import com.insta360.kmpsdk.demo.data.withStorageList
import com.insta360.kmpsdk.demo.service.CameraSessionForegroundService
import com.insta360.kmpsdk.demo.util.CameraWifiProcessNetworkBinder
import com.insta360.kmpsdk.demo.util.DemoAppPreferences
import com.insta360.kmpsdk.demo.util.NanConnectHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import timber.log.Timber.Forest.w
import kotlin.coroutines.resume

sealed class ConnectState {
    object Idle : ConnectState()

    object Connecting : ConnectState()

    object Connected : ConnectState()
}

data class ConnectionPageUiState(
    val statusMessage: String = "",
    val scanListVisible: Boolean = false,
    val scannedDevices: List<BleDeviceCore> = emptyList(),
    val connectionButtonActiveIndex: Int? = null,
    val connectState: ConnectState = ConnectState.Idle,
    val connectionMethodLabel: String = "",
    val deviceInfoVisible: Boolean = false,
    /** 纯蓝牙连接不支持预览流，不展示带预览拍摄入口。 */
    val previewCaptureEntryVisible: Boolean = false,
    /** X6 暂不支持直播模式，连接 X6 时隐藏直播推流入口。 */
    val liveStreamEntryVisible: Boolean = false,
    val device: DeviceInfo? = null,
    val statusText: String = "",
    val dynamicInfoRefreshing: Boolean = false,
)

data class CameraDisconnectedEvent(
    val id: Long,
)

class ConnectionViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val wifiManager = application.getSystemService(Application.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val usbManager = application.getSystemService(Application.USB_SERVICE) as UsbManager

    var netId = -1L
    private val _ui =
        MutableStateFlow(
            ConnectionPageUiState(
                statusMessage = application.getString(R.string.please_select_connection_method),
                statusText = application.getString(R.string.not_connected),
            ),
        )
    val connectionUi: StateFlow<ConnectionPageUiState> = _ui.asStateFlow()

    private var cameraDisconnectedEventId = 0L
    private val _cameraDisconnectedSignal = MutableStateFlow<CameraDisconnectedEvent?>(null)
    val cameraDisconnectedSignal: StateFlow<CameraDisconnectedEvent?> = _cameraDisconnectedSignal.asStateFlow()

    private var currentCameraDevice: CameraDevice? = null
    private var connectionHealthJob: Job? = null

    /**
     * 当前连接尝试（BLE/AP/Aware 模式切换、NAN 握手等一整条链路）的根 Job。
     * 断连事件发生时需要 cancel 掉整条链路，否则中途的模式切换轮询会在断连处理完之后才超时，
     * 用"切模式失败/超时"覆盖掉已经写入的"已断开"状态。
     */
    private var connectionAttemptJob: Job? = null

    /**
     * [connectSystemWifi] 通过 WifiNetworkSpecifier 发起的进程专属网络请求回调。
     * 该请求必须持续注册才能保持对应 Network 存活，直到断连/清理时才 unregisterNetworkCallback；
     * 此前未反注册会导致请求泄漏，断连重连同一 SSID 时可能拿到陈旧/失效的 Network。
     */
    private var systemWifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val nanHelper: NanConnectHelper by lazy { NanConnectHelper(getApplication()) }

    private var systemListenersDevice: CameraDevice? = null

    private val systemBatteryListener =
        object : BatteryListener {
            override fun onBatteryLevelChange(batteryData: BatteryData) {
                applyBatteryToUi(batteryData)
            }

            override fun onLowBatteryWarning() {
                val dev = currentCameraDevice ?: return
                dev.system.getBatteryData().onSuccess { applyBatteryToUi(it) }
            }
        }

    private val systemStorageListener =
        object : StorageStateListener {
            override fun onStorageStateListChanged(storageDataList: List<StorageData>) {
                applyStorageListToUi(storageDataList)
            }
        }

    fun getCameraDevice(): CameraDevice? = currentCameraDevice

    fun isSdReady(): Boolean = _ui.value.device?.sdAvailable == true

    private var bleScanStopped = true
    private var suppressDisconnectCallback = false

    private val disconnectListener =
        object : DisconnectListener {
            override fun onDisconnect(throwable: Throwable?) {
                if (suppressDisconnectCallback) {
                    suppressDisconnectCallback = false
                    return
                }
                Timber.w(throwable, "camera disconnected (listener)")
                handleDisconnected(throwable?.message)
            }
        }

    private fun setCurrentCameraDevice(device: CameraDevice?) {
        if (currentCameraDevice === device) return
        unregisterSystemDynamicListeners()
        currentCameraDevice?.unregisterDisconnectListener(disconnectListener)
        currentCameraDevice = device
        // 连接期间（包括 SDK 内部同步数据阶段）就要能感知心跳超时等断连事件，
        // 不能等最终 applyConnected() 才注册，否则该窗口期断连会被无声丢弃，UI 卡在"连接中"
        device?.registerDisconnectListener(disconnectListener)
    }

    private fun registerSystemDynamicListeners(device: CameraDevice) {
        unregisterSystemDynamicListeners()
        device.system.registerBatteryListener(systemBatteryListener)
        device.system.registerStorageStatusListener(systemStorageListener)
        systemListenersDevice = device
    }

    private fun unregisterSystemDynamicListeners() {
        val d = systemListenersDevice ?: return
        d.system.unregisterBatteryListener(systemBatteryListener)
        d.system.unregisterStorageStatusListener(systemStorageListener)
        systemListenersDevice = null
    }

    private fun applyBatteryToUi(batteryData: BatteryData) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (_ui.value.connectState != ConnectState.Connected) return@launch
            val ctx = getApplication<Application>()
            val base = _ui.value.device ?: return@launch
            _ui.update { s -> s.copy(device = base.withBattery(ctx, batteryData)) }
        }
    }

    private fun applyStorageListToUi(storageDataList: List<StorageData>) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (_ui.value.connectState != ConnectState.Connected) return@launch
            val ctx = getApplication<Application>()
            val base = _ui.value.device ?: return@launch
            _ui.update { s -> s.copy(device = base.withStorageList(ctx, storageDataList)) }
        }
    }

    private fun startConnectionHealthWatch(device: CameraDevice) {
        connectionHealthJob?.cancel()
        connectionHealthJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(CONNECTION_HEALTH_CHECK_INTERVAL_MS)
                    if (currentCameraDevice !== device || _ui.value.connectState != ConnectState.Connected) {
                        return@launch
                    }
                    val connected =
                        runCatching { device.isConnected() }
                            .onFailure { Timber.w(it, "health check failed, treating as disconnected") }
                            .getOrDefault(false)
                    if (!connected) {
                        Timber.w("camera disconnected (health watch)")
                        handleDisconnected(null)
                        return@launch
                    }
                }
            }
    }

    private fun stopConnectionHealthWatch() {
        connectionHealthJob?.cancel()
        connectionHealthJob = null
    }

    private fun handleDisconnected(reason: String?) {
        stopConnectionHealthWatch()
        // 取消整条连接尝试链路（模式切换轮询、NAN 握手后续等），否则它们会在断连处理完之后
        // 才自然超时，用"切模式失败/超时"覆盖掉这里刚写入的"已断开"状态
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        val device = currentCameraDevice
        setCurrentCameraDevice(null)
        // 健康监测/连接失败等自动断开路径此前从未通知相机侧断连，相机会一直占着会话直至固件自身超时，
        // 期间立即重连会返回冲突错误码；断连指令须在解绑进程网络前送达，故先 release 再 unbind。
        // release() 内部已包含断连语义，不再额外调用 disconnect()，避免协议层重复发送断连帧
        viewModelScope.launch {
            device?.let {
                runCatching { it.release() }
                    .onFailure { e -> Timber.w(e, "release camera device failed") }
            }
            CameraWifiProcessNetworkBinder.unbindProcess(connectivityManager)
            unregisterSystemWifiNetworkCallback()
            nanHelper.destroy()
        }
        _ui.update {
            it.copy(
                connectState = ConnectState.Idle,
                connectionButtonActiveIndex = null,
                scanListVisible = false,
                deviceInfoVisible = false,
                previewCaptureEntryVisible = false,
                device = null,
                dynamicInfoRefreshing = false,
                statusMessage =
                    if (reason.isNullOrBlank()) {
                        getApplication<Application>().getString(R.string.camera_disconnected)
                    } else {
                        getApplication<Application>().getString(R.string.connection_failed, reason)
                    },
                statusText = getApplication<Application>().getString(R.string.not_connected),
            )
        }
        _cameraDisconnectedSignal.value = CameraDisconnectedEvent(++cameraDisconnectedEventId)
        CameraSessionForegroundService.stop(getApplication())
    }

    fun consumeCameraDisconnectedSignal(eventId: Long) {
        if (_cameraDisconnectedSignal.value?.id == eventId) {
            _cameraDisconnectedSignal.value = null
        }
    }

    /**
     * wifi 连接
     */
    fun onConnectWifiClicked() {
        enterConnectingState(buttonIndex = 0, statusResId = R.string.connecting_via_wifi)
        launchConnectionAttempt {
            Timber.d("connecting via WiFi")
            val camera = CameraDevice.get(ConnectType.WIFI)
            setCurrentCameraDevice(camera)
            camera
                .connect(getWlan0NetworkId())
                .onSuccess {
                    Timber.d("WiFi connected")
                    applyConnected(
                        device = camera.toDeviceInfo(getApplication()),
                        connectType = ConnectType.WIFI,
                        cameraDevice = camera,
                    )
                }.onFailure {
                    Timber.d("WiFi connect failed")
                    connectFailed(it.message ?: "")
                }
        }
    }


    @SuppressLint("ThrowableNotAtBeginning")
    fun getWlan0NetworkId(): Long {
        return try {
            connectivityManager.allNetworks.firstOrNull { network ->
                // 1. 仅普通STA WiFi，过滤P2P相机直连
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
                // 2. 匹配网卡名wlan0
                val linkProp: LinkProperties = connectivityManager.getLinkProperties(network) ?: return@firstOrNull false
                linkProp.interfaceName == "wlan0"
            }?.networkHandle ?: -1L
        } catch (t: Throwable) {
            w("get wlan0 networkId failed:${t.message}", t)
            -1L
        }
    }

    /**
     * 蓝牙扫描
     */
    fun onScanClicked() {
        _ui.update {
            it.copy(
                connectionButtonActiveIndex = 1,
                scanListVisible = true,
                scannedDevices = emptyList(),
                statusMessage = getApplication<Application>().getString(R.string.ble_scan_started),
            )
        }
        viewModelScope.launch {
            Timber.d("starting BLE scan")
            currentCameraDevice?.stopScan()
            bleScanStopped = false
            val camera = CameraDevice.get(ConnectType.BLE)
            setCurrentCameraDevice(camera)
            camera.scan(
                10_000L,
                object : BleScanCallback {
                    override fun onStarted() {
                        _ui.update {
                            it.copy(
                                connectionButtonActiveIndex = 1,
                                scanListVisible = true,
                                scannedDevices = emptyList(),
                                statusMessage =
                                    getApplication<Application>().getString(R.string.ble_scanning),
                            )
                        }
                    }

                    override fun onScanning(bleDevice: BleDeviceCore) {
                        Timber.d("BLE device found: %s", bleDevice.name)
                        if (bleScanStopped) return
                        addDevice(bleDevice)
                    }

                    override fun onFinished(bleDeviceList: List<BleDeviceCore>) {
                        Timber.d("BLE scan finished: count=%d", bleDeviceList.size)
                        if (bleScanStopped) return
                        _ui.update {
                            it.copy(
                                connectionButtonActiveIndex = 1,
                                scanListVisible = it.scanListVisible,
                                scannedDevices = bleDeviceList,
                                statusMessage = getApplication<Application>().getString(R.string.devices_found_please_select),
                            )
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        Timber.d("BLE scan failed: %s", throwable.message)
                        _ui.update {
                            it.copy(
                                scanListVisible = false,
                                scannedDevices = emptyList(),
                                statusMessage =
                                    getApplication<Application>().getString(R.string.ble_scan_failed),
                            )
                        }
                    }
                },
            )
        }
    }

    private fun addDevice(bleDevice: BleDeviceCore) {
        val list = _ui.value.scannedDevices.toMutableList()
        if (list.none { it.address == bleDevice.address }) {
            list.add(bleDevice)
            _ui.update {
                it.copy(
                    scannedDevices = list,
                )
            }
        }
    }

    /**
     * usb 连接
     */
    fun onUsbClicked() {
        enterConnectingState(buttonIndex = 2, statusResId = R.string.connecting_via_usb)
        launchConnectionAttempt {
            Timber.d("connecting via USB")
            val usbDevices =
                usbManager.deviceList.filter { entry ->
                    val device = entry.value
                    device.vendorId == 0x4255 || device.vendorId == 0x2e1a
                }
            if (usbDevices.isEmpty()) {
                connectFailed(getApplication<Application>().getString(R.string.usb_no_device))
                return@launchConnectionAttempt
            }

            val camera = CameraDevice.get(ConnectType.USB)
            setCurrentCameraDevice(camera)
            camera
                .connect()
                .onSuccess {
                    Timber.d("USB connected")
                    applyConnected(
                        device = camera.toDeviceInfo(getApplication()),
                        connectType = ConnectType.USB,
                        cameraDevice = camera,
                    )
                }.onFailure {
                    Timber.d("USB connect failed")
                    connectFailed(it.message ?: "")
                }
        }
    }

    /**
     * 蓝牙连接
     */
    fun onConnectBluetooth(index: Int) {
        Timber.d("connecting via BLE")
        bleScanStopped = true
        currentCameraDevice?.stopScan()
        onConnectBluetooth(index, false)
    }

    /**
     * 通过蓝牙连接 Wi‑Fi
     */
    fun onConnectWifiByBluetooth(index: Int) {
        Timber.d("connecting WiFi via BLE")
        bleScanStopped = true
        currentCameraDevice?.stopScan()
        onConnectBluetooth(index, true)
    }

    /** 手机端是否支持 WiFi Aware（供扫描列表按钮显隐判断）。 */
    fun isWifiAwareSupported(): Boolean = nanHelper.isSupported()

    /**
     * 通过蓝牙连接 WiFi Aware（NAN）。
     *
     * 流程：BLE 连接 → 切相机到 Aware 模式 → 轮询确认并取凭据 → NAN 握手 → connectWiFiAware。
     * 与 WiFi via BLE 路径的差异：握手期间保持 BLE 连接（NAN 走独立通道，BLE 作为并存的控制通道）；
     * 中段任一步失败统一走 connectFailed → 整体 teardown（含释放 BLE），回到干净的未连接态。
     */
    fun onConnectWiFiAwareByBluetooth(index: Int) {
        Timber.d("connecting WiFi Aware via BLE")
        bleScanStopped = true
        currentCameraDevice?.stopScan()
        val list = _ui.value.scannedDevices
        if (index !in list.indices) {
            connectFailed(
                getApplication<Application>().getString(R.string.invalid_bluetooth_device_selection),
            )
            return
        }
        val bleDevice: BleDeviceCore = list[index]
        enterConnectingState(buttonIndex = 1, statusResId = R.string.connecting_via_bluetooth)
        Timber.d("connecting to BLE device for WiFi Aware: %s", bleDevice.name)
        launchConnectionAttempt {
            val bleCamera = CameraDevice.get(ConnectType.BLE)
            setCurrentCameraDevice(bleCamera)
            bleCamera
                // Aware 是独立于 BLE 的网络通道，BLE 作为前置控制通道需保持连接，isBleOnly 传 false
                .connect(bleDevice, false)
                .onSuccess {
                    Timber.d("BLE connected, switching camera to Aware mode")
                    _ui.update {
                        it.copy(
                            statusMessage = getApplication<Application>().getString(R.string.connecting_via_wifi_aware),
                        )
                    }
                    launch {
                        bleCamera.system
                            .setWifiMode(WiFiData.Mode.Android_Aware, "")
                            .onSuccess {
                                launch {
                                    val wifiData = awaitAwareWifiData(bleCamera)
                                    if (wifiData == null) {
                                        Timber.d("camera did not enter Aware mode in time")
                                        connectFailed(
                                            getApplication<Application>().getString(
                                                R.string.wifi_aware_enter_mode_timeout,
                                            ),
                                        )
                                        return@launch
                                    }
                                    Timber.d("camera entered Aware mode, starting NAN handshake: ssid=%s", wifiData.ssid)
                                    // NAN 握手只需 ssid/pwd，不再依赖 BLE，此处即可释放前置 BLE 控制通道；
                                    // release() 内部已包含断连语义，不再额外调用 disconnect()
                                    runCatching { bleCamera.release() }
                                        .onFailure { e -> Timber.w(e, "release BLE control device failed") }
                                    startNanHandshake(wifiData.ssid, wifiData.pwd)
                                }
                            }.onFailure {
                                Timber.d("switch to Aware mode failed")
                                connectFailed(
                                    getApplication<Application>().getString(R.string.wifi_aware_switch_mode_failed),
                                )
                            }
                    }
                }.onFailure {
                    Timber.d("BLE connect failed")
                    connectFailed(it.message ?: "")
                }
        }
    }

    /** 发起 NAN 握手；回调在主线程触发，无需额外切线程即可更新 UI 状态。 */
    private fun startNanHandshake(ssid: String, pwd: String) {
        val serviceName = ssid.replace(" ", "_")
        nanHelper.attach(
            serviceName,
            pwd.toByteArray(),
            object : NanConnectHelper.Callback {
                override fun onSuccess(networkHandle: Long, peerIpv6: String) {
                    Timber.d("NAN handshake succeeded: networkHandle=%d", networkHandle)
                    netId = networkHandle
                    launchOnCurrentConnectionAttempt {
                        val awareCamera = CameraDevice.get(ConnectType.WIFI_AWARE)
                        setCurrentCameraDevice(awareCamera)
                        awareCamera
                            .connectWiFiAware(networkHandle, peerIpv6)
                            .onSuccess {
                                Timber.d("WiFi Aware connected")
                                applyConnected(
                                    device = awareCamera.toDeviceInfo(getApplication()),
                                    connectType = ConnectType.WIFI_AWARE,
                                    cameraDevice = awareCamera,
                                )
                            }.onFailure {
                                Timber.d("WiFi Aware connect failed")
                                nanHelper.destroy()
                                connectFailed(
                                    getApplication<Application>().getString(
                                        R.string.wifi_aware_connect_failed,
                                        it.message ?: "",
                                    ),
                                )
                            }
                    }
                }

                override fun onFailure(reason: String) {
                    Timber.d("NAN handshake failed: %s", reason)
                    connectFailed(
                        getApplication<Application>().getString(R.string.wifi_aware_handshake_failed, reason),
                    )
                }
            },
        )
    }

    /**
     * 切模式后相机会重启 WiFi，轮询 fetchWifiData 直到 mode 变为 Android_Aware，最多约 5s。
     * 复用 WiFiData.Mode 的发现判断能力确认相机确实进入 Aware；超时返回 null。
     */
    private suspend fun awaitAwareWifiData(bleCamera: CameraDevice): WiFiData? {
        repeat(AWARE_MODE_POLL_TIMES) {
            val data = bleCamera.system.fetchWifiData().getOrNull()
            if (data?.mode == WiFiData.Mode.Android_Aware) return data
            delay(AWARE_MODE_POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * 确保相机 WiFi 处于 AP 模式再走系统 Wi‑Fi 连接。
     * 相机曾切换到 Aware 等其他模式后，若不切回 AP，STA 侧无法扫到目标热点导致连接失败。
     * 已是 AP 则直接返回 null（无错误）；否则调用 setWifiMode(AP) 并轮询确认切换完成，
     * 失败时返回对应的错误提示字符串资源 id，供调用方展示。
     */
    private suspend fun ensureApMode(bleCamera: CameraDevice): Int? {
        val currentMode = bleCamera.system.fetchWifiData().getOrNull()?.mode
        if (currentMode == WiFiData.Mode.AP) return null
        Timber.d("camera WiFi mode is %s, switching to AP", currentMode)
        val switched = bleCamera.system.setWifiMode(WiFiData.Mode.AP, "").isSuccess
        if (!switched) return R.string.wifi_ap_switch_mode_failed
        return if (awaitApWifiData(bleCamera) != null) null else R.string.wifi_ap_enter_mode_timeout
    }

    /**
     * 切模式后相机会重启 WiFi，轮询 fetchWifiData 直到 mode 变为 AP，最多约 5s。
     * 与 [awaitAwareWifiData] 对称，超时返回 null。
     */
    private suspend fun awaitApWifiData(bleCamera: CameraDevice): WiFiData? {
        repeat(AWARE_MODE_POLL_TIMES) {
            val data = bleCamera.system.fetchWifiData().getOrNull()
            if (data?.mode == WiFiData.Mode.AP) return data
            delay(AWARE_MODE_POLL_INTERVAL_MS)
        }
        return null
    }

    private fun onConnectBluetooth(
        index: Int,
        isWifiConnect: Boolean,
    ) {
        val list = _ui.value.scannedDevices
        if (index !in list.indices) {
            connectFailed(
                getApplication<Application>().getString(R.string.invalid_bluetooth_device_selection),
            )
            return
        }
        val bleDevice: BleDeviceCore = list[index]
        enterConnectingState(buttonIndex = 1, statusResId = R.string.connecting_via_bluetooth)
        Timber.d("connecting to BLE device: %s", bleDevice.name)
        launchConnectionAttempt {
            val bleCamera = CameraDevice.get(ConnectType.BLE)
            setCurrentCameraDevice(bleCamera)
            bleCamera
                // 蓝牙连接不是最终目标，isBleOnly传false
                .connect(bleDevice,!isWifiConnect)
                .onSuccess {
                    Timber.d("BLE connected")
                    if (isWifiConnect) {
                        _ui.update {
                            it.copy(
                                statusMessage = getApplication<Application>().getString(R.string.connecting_via_wifi),
                            )
                        }

                        Timber.d("ensuring camera WiFi mode is AP")
                        val apModeErrorResId = ensureApMode(bleCamera)
                        if (apModeErrorResId != null) {
                            Timber.d("failed to ensure camera AP mode")
                            connectFailed(getApplication<Application>().getString(apModeErrorResId))
                            return@launchConnectionAttempt
                        }

                        Timber.d("fetching WiFi credentials from camera")
                        bleCamera.system
                            .getWifiData()
                            .onSuccess {
                                Timber.d("WiFi credentials received: ssid=%s", it.ssid)
                                launch {
                                    val cameraWifiNetwork = connectSystemWifi(it.ssid, it.pwd)
                                    if (cameraWifiNetwork == null) {
                                        Timber.d("system WiFi connect failed")
                                        connectFailed(
                                            getApplication<Application>().getString(
                                                R.string.system_wifi_connect_failed,
                                            ),
                                        )
                                        return@launch
                                    }

                                    // 先注释按 IP 重新查找替换 Network 的逻辑，验证是否为断连重连异常的根因
//                                    val wifiNetworkForBind =
//                                        CameraWifiProcessNetworkBinder.findWifiNetworkMatchingConnectionInfo(
//                                            connectivityManager,
//                                            wifiManager,
//                                        ) ?: cameraWifiNetwork
                                    val wifiNetworkForBind = cameraWifiNetwork
                                    CameraWifiProcessNetworkBinder.bindProcessToNetwork(
                                        connectivityManager,
                                        wifiNetworkForBind,
                                    )
                                    netId = wifiNetworkForBind.networkHandle
                                    val wifiCameraDevice = CameraDevice.get(ConnectType.WIFI)
                                    Timber.d("disconnecting BLE, switching to WiFi")
                                    // BLE 与 WiFi 是独立通道，已拿到 Network 即可释放前置 BLE 控制通道，不必等 WiFi 连接成功；
                                    // release() 内部已包含断连语义，不再额外调用 disconnect()
                                    runCatching { bleCamera.release() }
                                        .onFailure { e -> Timber.w(e, "release BLE control device failed") }
                                    setCurrentCameraDevice(wifiCameraDevice)
                                    wifiCameraDevice
                                        .connect(wifiNetworkForBind.networkHandle)
                                        .onSuccess {
                                            Timber.d("WiFi via BLE connected")
                                            applyConnected(
                                                device =
                                                    wifiCameraDevice.toDeviceInfo(
                                                        getApplication(),
                                                    ),
                                                connectType = ConnectType.WIFI,
                                                cameraDevice = wifiCameraDevice,
                                            )
                                        }.onFailure {
                                            Timber.d("WiFi via BLE connect failed")
                                            connectFailed(it.message ?: "")
                                        }
                                }
                            }.onFailure {
                                Timber.d("fetch WiFi credentials failed")
                                connectFailed(it.message ?: "")
                            }
                    } else {
                        applyConnected(
                            device = bleCamera.toDeviceInfo(getApplication()),
                            connectType = ConnectType.BLE,
                            cameraDevice = bleCamera,
                        )
                    }
                }.onFailure {
                    Timber.d("BLE connect failed")
                    connectFailed(it.message ?: "")
                }
        }
    }

    /**
     * 尝试连接系统 Wi‑Fi
     */
    private suspend fun connectSystemWifi(
        ssid: String,
        password: String,
    ): Network? {
        if (!wifiManager.isWifiEnabled) {
            Timber.d("system WiFi disabled")
            return null
        }
        unregisterSystemWifiNetworkCallback()
        return suspendCancellableCoroutine { cont ->
            val specifier =
                WifiNetworkSpecifier
                    .Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build()

            val request =
                NetworkRequest
                    .Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier)
                    .build()

            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    private var finished = false

                    private fun finishOnce(network: Network?) {
                        if (finished) return
                        finished = true
                        cont.resume(network)
                    }

                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Timber.d("system WiFi connected, handle=%d", network.networkHandle)
                        finishOnce(network)
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        Timber.d("system WiFi unavailable")
                        finishOnce(null)
                    }
                }
            connectivityManager.requestNetwork(request, callback)
            systemWifiNetworkCallback = callback
            Timber.d("requesting system WiFi: ssid=%s", ssid)
        }
    }

    private fun unregisterSystemWifiNetworkCallback() {
        val callback = systemWifiNetworkCallback ?: return
        systemWifiNetworkCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            .onFailure { Timber.w(it, "unregister system WiFi network callback failed") }
    }

    fun disconnect() {
        Timber.d("disconnect")
        stopConnectionHealthWatch()
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        val device = currentCameraDevice
        suppressDisconnectCallback = true
        setCurrentCameraDevice(null)
        _ui.value =
            ConnectionPageUiState(
                statusMessage = getApplication<Application>().getString(R.string.please_select_connection_method),
                statusText = getApplication<Application>().getString(R.string.not_connected),
            )
        CameraSessionForegroundService.stop(getApplication())
        // 断连指令必须在解绑进程网络前送达相机，否则相机侧会话残留直至固件自身超时才释放，
        // 复现为 UI 已断开、相机仍显示连接，此间重连会返回冲突错误码。
        // release() 内部已包含断连语义，不再额外调用 disconnect()，避免协议层重复发送断连帧
        viewModelScope.launch {
            device?.let {
                runCatching { it.release() }
                    .onFailure { e -> Timber.w(e, "release camera device failed") }
            }
            CameraWifiProcessNetworkBinder.unbindProcess(connectivityManager)
            unregisterSystemWifiNetworkCallback()
            nanHelper.destroy()
        }
    }

    fun refreshDynamicInfo() {
        val camera = currentCameraDevice
        val app = getApplication<Application>()
        if (camera == null || _ui.value.connectState != ConnectState.Connected) {
            Toast
                .makeText(
                    app,
                    app.getString(R.string.refresh_dynamic_need_connection),
                    Toast.LENGTH_SHORT,
                ).show()
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(dynamicInfoRefreshing = true) }
            val result =
                withContext(Dispatchers.Default) {
                    runCatching {
                        camera.system.fetchBatteryData()
                        camera.system.fetchStorageDataList()
                        camera.system.fetchMediaTime()
                        camera.toDeviceInfo(getApplication())
                    }
                }
            result.fold(
                onSuccess = { info ->
                    _ui.update { s ->
                        s.copy(
                            dynamicInfoRefreshing = false,
                            device = info,
                        )
                    }
                },
                onFailure = { e ->
                    Timber.w(e, "refreshDynamicInfo failed")
                    _ui.update { it.copy(dynamicInfoRefreshing = false) }
                    Toast
                        .makeText(
                            app,
                            app.getString(R.string.refresh_dynamic_failed, e.message ?: ""),
                            Toast.LENGTH_SHORT,
                        ).show()
                },
            )
        }
    }

    private fun applyConnected(
        device: DeviceInfo,
        connectType: ConnectType,
        cameraDevice: CameraDevice,
    ) {
        _cameraDisconnectedSignal.value = null
        setCurrentCameraDevice(cameraDevice)
        val methodLabel = connectType.name
        _ui.update {
            ConnectionPageUiState(
                connectState = ConnectState.Connected,
                statusMessage =
                    getApplication<Application>().getString(
                        R.string.connected_with_method_and_name,
                        methodLabel,
                        device.cameraType,
                    ),
                scanListVisible = it.scanListVisible,
                scannedDevices = it.scannedDevices,
                connectionButtonActiveIndex = it.connectionButtonActiveIndex,
                connectionMethodLabel = methodLabel,
                deviceInfoVisible = true,
                previewCaptureEntryVisible = connectType != ConnectType.BLE,
                liveStreamEntryVisible = connectType != ConnectType.BLE &&
                        CameraType.getForType(device.cameraType) != CameraType.X6,
                device = device,
                statusText =
                    getApplication<Application>().getString(
                        R.string.connected_with_method,
                        methodLabel,
                    ),
                dynamicInfoRefreshing = false,
            )
        }
        registerSystemDynamicListeners(cameraDevice)
        startConnectionHealthWatch(cameraDevice)
        CameraSessionForegroundService.start(getApplication())
        persistRecentDeviceIfPossible(device)
    }

    // SN 与型号齐全才落库；deviceName = SN 后 6 位（与 SDK bleWakeUp 入参约定一致）
    private fun persistRecentDeviceIfPossible(device: DeviceInfo) {
        val sn = device.sn
        val typeKey = device.cameraType
        if (sn.isBlank() || sn == "-" || typeKey.isBlank() || typeKey == "-") return
        DemoAppPreferences.prependRecentBleDevice(
            getApplication(),
            RecentBleDevice(
                deviceName = sn.takeLast(6),
                cameraTypeKey = typeKey,
                sn = sn,
                lastConnectedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun enterConnectingState(buttonIndex: Int, statusResId: Int) {
        _ui.update {
            it.copy(
                connectState = ConnectState.Connecting,
                connectionButtonActiveIndex = buttonIndex,
                scanListVisible = false,
                statusMessage = getApplication<Application>().getString(statusResId),
            )
        }
    }

    /**
     * 发起一次新连接尝试的根 Job；后续所有相关 launch（最外层入口、模式切换轮询、
     * NAN 握手异步回调等）都必须以它为 parent context，这样断连事件到达时 cancel 一次
     * 即可让整条链路（哪怕分散在不同时间点触发的多个 launch）一起停下，不会等轮询
     * 自然超时后再用"切模式失败"覆盖掉已经写入的"已断开"状态。
     */
    private fun launchConnectionAttempt(block: suspend CoroutineScope.() -> Unit) {
        connectionAttemptJob?.cancel()
        val job = Job()
        connectionAttemptJob = job
        viewModelScope.launch(job, block = block)
    }

    /**
     * 在系统/SDK 异步回调（如 NAN 握手结果）中，把后续逻辑挂到当前连接尝试的 Job 下，
     * 而不是新开一条独立链路——这样断连仍可通过 [connectionAttemptJob] 统一取消到它。
     */
    private fun launchOnCurrentConnectionAttempt(block: suspend CoroutineScope.() -> Unit) {
        val job = connectionAttemptJob ?: Job().also { connectionAttemptJob = it }
        viewModelScope.launch(job, block = block)
    }

    override fun onCleared() {
        stopConnectionHealthWatch()
        CameraWifiProcessNetworkBinder.unbindProcess(connectivityManager)
        unregisterSystemWifiNetworkCallback()
        nanHelper.destroy()
        // viewModelScope 在 onCleared() 被调用前已取消，无法用协程走 suspend disconnect()，
        // 这里只能同步调用 release()（内部走同步 camera.disconnect() + destroy()）做最后的收尾
        runCatching { currentCameraDevice?.release() }
            .onFailure { Timber.w(it, "release camera device on clear failed") }
        setCurrentCameraDevice(null)
        super.onCleared()
    }

    private fun connectFailed(message: String) {
        handleDisconnected(message)
    }

    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ConnectionViewModel::class.java)) {
                return ConnectionViewModel(application) as T
            }
            error("Unknown ViewModel class")
        }
    }

    private companion object {
        const val CONNECTION_HEALTH_CHECK_INTERVAL_MS = 1_000L
        const val AWARE_MODE_POLL_TIMES = 10
        const val AWARE_MODE_POLL_INTERVAL_MS = 500L
    }
}
