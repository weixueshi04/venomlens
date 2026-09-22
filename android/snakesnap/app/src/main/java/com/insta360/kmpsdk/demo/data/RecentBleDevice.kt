package com.insta360.kmpsdk.demo.data

/**
 * 最近一次连接成功的设备记录，用于设置页"蓝牙唤醒"复用。
 *
 * [deviceName] 取完整 SN 的后 6 位，作为 SDK [com.arashivision.sdk.camera.api.CameraDevice.bleWakeUp] 的入参；
 * [sn] 同时保留完整 SN 以便去重与界面展示。
 */
data class RecentBleDevice(
    val deviceName: String,
    val cameraTypeKey: String,
    val sn: String,
    val lastConnectedAt: Long,
)
