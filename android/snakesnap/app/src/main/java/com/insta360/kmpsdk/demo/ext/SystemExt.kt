package com.insta360.kmpsdk.demo.ext

import android.app.Service
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.VibrationEffect
import android.os.Vibrator
import com.insta360.kmpsdk.demo.DemoApplication

val connectedWiFiSsid: String
    get() {
        val wifiManager = DemoApplication.instance.getSystemService(Context.WIFI_SERVICE) as WifiManager
        var ssid = wifiManager.connectionInfo.ssid
        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        return ssid
    }


fun vibrate(secMill: Long, amplitude: Int) {
    val vibrator = DemoApplication.instance.getSystemService(Service.VIBRATOR_SERVICE) as Vibrator
    vibrator.vibrate(VibrationEffect.createOneShot(secMill, amplitude))
}

val connectivityManager: ConnectivityManager
    get() = DemoApplication.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager


val wifiManager: WifiManager
    get() = DemoApplication.instance.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager