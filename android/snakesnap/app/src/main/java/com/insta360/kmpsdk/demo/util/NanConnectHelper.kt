package com.insta360.kmpsdk.demo.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.net.Inet6Address

/**
 * WiFi Aware（NAN）握手 helper。
 *
 * 职责边界：本类承担"App 外部完成 NAN 握手"这一步（attach → subscribe → requestNetwork →
 * 取 peer IPv6 → bindProcessToNetwork），握手成功后把 networkHandle + peerIpv6 回调给上层，
 * 由上层调用 [com.arashivision.sdk.camera.api.CameraDevice.connectWiFiAware] 连接相机。
 */
@SuppressLint("MissingPermission")
class NanConnectHelper(context: Context) {

    companion object {
        /** 整体握手超时（attach + 发现） */
        private const val DISCOVER_TIMEOUT_MS = 20_000L

        /** 发现服务后等待 peer IPv6（DHCP）超时 */
        private const val DHCP_TIMEOUT_MS = 5_000L
    }

    /** 握手结果回调，全部在主线程触发。 */
    interface Callback {
        fun onSuccess(networkHandle: Long, peerIpv6: String)
        fun onFailure(reason: String)
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiAwareManager =
        appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var session: WifiAwareSession? = null
    private var discoverySession: SubscribeDiscoverySession? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var timeoutRunnable: Runnable? = null
    private var dhcpTimeoutRunnable: Runnable? = null

    // 保证 success/failure 只回调一次，避免与超时、回调交错时重复触发。
    // attach/subscribe/网络回调与超时 runnable 均已投递到 mainHandler（主线程），
    // 此处 volatile 为防御性冗余，确保跨线程读写可见性即使未来回调线程调整仍安全
    @Volatile
    private var finished = false

    // destroy() 后置位，短路尚未到达的框架回调（attach/discover），避免在已释放会话上继续握手
    @Volatile
    private var released = false

    // 是否已 bindProcessToNetwork，destroy 时仅在绑定过时才解绑，避免误解绑他处网络
    private var processBound = false

    /** 当前手机是否支持 WiFi Aware（NDP 数据通道需 API 29+）。 */
    @RequiresPermission(Manifest.permission.ACCESS_WIFI_STATE)
    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && wifiAwareManager?.isAvailable == true

    /**
     * 发起 NAN 握手。
     *
     * @param serviceName BLE 广播中的 SSID 变体（空格替换为下划线）
     * @param serviceInfo WiFi 密码字节
     */
    @RequiresPermission(
        allOf = [
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ],
    )
    fun attach(serviceName: String, serviceInfo: ByteArray, callback: Callback) {
        finished = false
        // 复位 released：本 helper 为 ViewModel 单例，上一次 destroy() 会把 released 置 true，
        // 若不复位，后续 attach 的框架回调会被短路，导致仅首次可用
        released = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finishFailure(callback, "current OS does not support WiFi Aware data path (requires Android 10+)")
            return
        }
        val manager = wifiAwareManager
        if (manager == null || !manager.isAvailable) {
            finishFailure(callback, "WiFi Aware unavailable, check WiFi switch and airplane mode")
            return
        }
        if (connectivityManager == null) {
            finishFailure(callback, "ConnectivityManager unavailable")
            return
        }

        timeoutRunnable = Runnable { finishFailure(callback, "NAN handshake timeout (discovery phase)") }
        mainHandler.postDelayed(timeoutRunnable!!, DISCOVER_TIMEOUT_MS)

        manager.attach(object : AttachCallback() {
            override fun onAttached(awareSession: WifiAwareSession) {
                Timber.i("NAN onAttached")
                if (released) {
                    runCatching { awareSession.close() }
                    return
                }
                session = awareSession
                subscribe(awareSession, serviceName, serviceInfo, callback)
            }

            override fun onAttachFailed() {
                Timber.e("NAN onAttachFailed")
                finishFailure(callback, "NAN attach failed (missing permission or unsupported hardware)")
            }
        }, mainHandler)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun subscribe(
        awareSession: WifiAwareSession,
        serviceName: String,
        serviceInfo: ByteArray,
        callback: Callback,
    ) {
        val config = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            .setServiceSpecificInfo(serviceInfo)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
            .setTtlSec(20)
            .build()
        awareSession.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(subSession: SubscribeDiscoverySession) {
                Timber.i("NAN onSubscribeStarted")
                if (released) {
                    runCatching { subSession.close() }
                    return
                }
                discoverySession = subSession
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?,
            ) {
                Timber.i("NAN onServiceDiscovered")
                if (released) return
                cancelDiscoverTimeout()
                requestNetwork(peerHandle, callback)
            }
        }, mainHandler)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestNetwork(peerHandle: PeerHandle, callback: Callback) {
        val subSession = discoverySession
        if (subSession == null) {
            finishFailure(callback, "DiscoverySession no longer valid")
            return
        }
        val specifier = WifiAwareNetworkSpecifier.Builder(subSession, peerHandle).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            // 标记是否已拿到 peer IPv6 并完成绑定，避免 onCapabilitiesChanged 多次回调重复处理
            private var bound = false

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (bound || released) return
                // transportInfo 在 NDP 刚建立时可能尚未填充为 WifiAwareNetworkInfo，
                // peerIpv6 也可能尚未分配（等 DHCP）；两者皆视为「未就绪」，等待后续回调，由超时兜底，
                // 不可在首个回调就判失败。
                val peerIpv6: Inet6Address? = (caps.transportInfo as? WifiAwareNetworkInfo)?.peerIpv6Addr
                if (peerIpv6 != null) {
                    bound = true
                    cancelDhcpTimeout()
                    // App 侧负责进程网络绑定：使 SDK 的 HTTP（文件/固件）走 Aware 网络
                    val bindOk = connectivityManager?.bindProcessToNetwork(network) ?: false
                    if (bindOk) {
                        connectivityManager?.let { NanProcessNetworkBinding.markBound(it, network) }
                    }
                    processBound = processBound || bindOk
                    Timber.i("bindProcessToNetwork result=%b", bindOk)
                    finishSuccess(callback, network.networkHandle, peerIpv6.hostAddress ?: "")
                } else if (dhcpTimeoutRunnable == null) {
                    val rt = Runnable { finishFailure(callback, "timeout waiting for peer IPv6 (DHCP)") }
                    dhcpTimeoutRunnable = rt
                    mainHandler.postDelayed(rt, DHCP_TIMEOUT_MS)
                }
            }

            override fun onUnavailable() {
                if (released) return
                finishFailure(callback, "NAN network request unavailable (onUnavailable)")
            }

            override fun onLost(network: Network) {
                Timber.e("aware network onLost")
                if (released) return
                // 尚未拿到 peer IPv6 即丢失网络，直接判失败，避免空等 DHCP 超时
                if (!bound) finishFailure(callback, "Aware network lost before established (onLost)")
            }
        }
        networkCallback = cb
        // 传 mainHandler，使 NetworkCallback 回调在主线程触发（默认无 Handler 重载走 ConnectivityThread），
        // 与 attach/subscribe 回调线程一致，避免失败路径在后台线程注销 SDK 监听/停服务
        connectivityManager?.requestNetwork(request, cb, mainHandler)
    }

    /** 释放 NAN 会话与网络回调，并解除进程网络绑定。断连或退出时调用。 */
    fun destroy() {
        released = true
        cancelDiscoverTimeout()
        cancelDhcpTimeout()
        networkCallback?.let {
            runCatching { connectivityManager?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        runCatching { discoverySession?.close() }
        discoverySession = null
        runCatching { session?.close() }
        session = null
        // 仅在本类确实绑定过进程网络时才解绑，避免误解绑他处网络
        if (processBound) {
            runCatching { connectivityManager?.bindProcessToNetwork(null) }
            NanProcessNetworkBinding.markUnbound()
            processBound = false
        }
    }

    private fun finishSuccess(callback: Callback, networkHandle: Long, peerIpv6: String) {
        if (finished) return
        finished = true
        cancelDiscoverTimeout()
        cancelDhcpTimeout()
        callback.onSuccess(networkHandle, peerIpv6)
    }

    private fun finishFailure(callback: Callback, reason: String) {
        if (finished) return
        finished = true
        Timber.e("NAN handshake failed: %s", reason)
        destroy()
        callback.onFailure(reason)
    }

    private fun cancelDiscoverTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun cancelDhcpTimeout() {
        dhcpTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        dhcpTimeoutRunnable = null
    }
}
