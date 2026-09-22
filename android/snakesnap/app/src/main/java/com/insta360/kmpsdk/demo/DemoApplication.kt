package com.insta360.kmpsdk.demo

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.arashivision.inskmp.editsdk.manager.INSKMPEditSDKMgr
import com.insta360.kmpsdk.demo.util.DemoLogcatDumper
import timber.log.Timber

class DemoApplication : Application() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var instance: DemoApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.plant(Timber.DebugTree())
        // 尽早开始录制系统日志：不依赖 SDK 初始化与运行时权限，覆盖冷启动阶段
        DemoLogcatDumper.autoStart(this)
        // 添加网络监听器监控网络状态，方便调试
        startNetworkListener()

        INSKMPEditSDKMgr.enableDebug(true)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }

    fun startNetworkListener() {
        val connManager: ConnectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

        val mNetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Timber.d("onAvailable ==> %s", network)
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Timber.d("onLost ==> %s", network)
                }
            }
        connManager.registerNetworkCallback(request, mNetworkCallback)
    }
}
