package com.insta360.kmpsdk.demo.util

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import timber.log.Timber

/**
 * 蓝牙引导 [requestNetwork] 连上相机热点后绑定进程网络。
 */
object CameraWifiProcessNetworkBinder {
    /** 与系统 [android.net.wifi.WifiInfo.getIpAddress] 相同的 host 序 IPv4 字符串，便于对比。 */
    private fun ipv4IntToHostString(ip: Int): String =
        (ip and 0xFF).toString() + "." +
            ((ip ushr 8) and 0xFF) + "." +
            ((ip ushr 16) and 0xFF) + "." +
            ((ip ushr 24) and 0xFF)

    fun findWifiNetworkMatchingConnectionInfo(
        connectivityManager: ConnectivityManager,
        wifiManager: WifiManager,
    ): Network? {
        val refIp = ipv4IntToHostString(wifiManager.connectionInfo.ipAddress)
        if (refIp == "0.0.0.0") {
            Timber.w("connectionInfo IP is 0, cannot match Network")
            return null
        }
        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val wifiInfo = caps.transportInfo as? WifiInfo ?: continue
            val netIp = ipv4IntToHostString(wifiInfo.ipAddress)
            if (netIp == refIp) {
                Timber.d(
                    "Matched Wi-Fi Network with the same IP as connectionInfo: handle=%d ip=%s",
                    network.networkHandle,
                    refIp,
                )
                return network
            }
        }
        Timber.w("No Wi-Fi Network found matching connectionInfo IP=%s", refIp)
        return null
    }

    fun unbindProcess(connectivityManager: ConnectivityManager) {
        runCatching {
            connectivityManager.bindProcessToNetwork(null)
        }.onFailure { Timber.w(it, "Failed to unbind process network") }
    }

    fun bindProcessToNetwork(
        connectivityManager: ConnectivityManager,
        network: Network,
    ): Boolean =
        runCatching {
            val ok = connectivityManager.bindProcessToNetwork(network)
            Timber.d("bindProcessToNetwork handle=${network.networkHandle} result=$ok")
            ok
        }.getOrElse { e ->
            Timber.w(e, "bindProcessToNetwork exception")
            false
        }
}
