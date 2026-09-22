package com.insta360.kmpsdk.demo.util

import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全局记录"进程是否已被 bindProcessToNetwork 绑到 Wi-Fi Aware 网络"，
 * 供任意组件在发起必须走公网的请求（如相机激活 openapi.insta360.com）前
 * 临时解绑、请求结束后恢复，避免因进程绑死在 Aware 网络上导致 DNS 解析失败。
 */
object NanProcessNetworkBinding {
    private val mutex = Mutex()

    @Volatile
    private var connectivityManager: ConnectivityManager? = null

    @Volatile
    private var boundNetwork: Network? = null

    /** NanConnectHelper 绑定成功后调用，登记当前绑定的网络。 */
    fun markBound(cm: ConnectivityManager, network: Network) {
        connectivityManager = cm
        boundNetwork = network
    }

    /** NanConnectHelper destroy/解绑时调用，清除登记。 */
    fun markUnbound() {
        boundNetwork = null
    }

    /**
     * 若当前进程绑定在 Aware 网络上，临时解绑后执行 [block]，结束后恢复绑定；
     * 若未绑定（未走 Aware，或已断开），直接执行 [block]，不做任何网络切换。
     * 用 Mutex 避免并发的公网请求互相打断彼此的解绑/恢复窗口。
     */
    suspend fun <T> runOnDefaultNetwork(block: suspend () -> T): T = mutex.withLock {
        val cm = connectivityManager
        val network = boundNetwork
        if (cm == null || network == null) return@withLock block()
        runCatching { cm.bindProcessToNetwork(null) }
        try {
            block()
        } finally {
            runCatching { cm.bindProcessToNetwork(network) }
        }
    }
}
