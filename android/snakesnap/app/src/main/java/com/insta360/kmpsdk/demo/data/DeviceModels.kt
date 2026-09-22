package com.insta360.kmpsdk.demo.data

data class DeviceInfo(
    val cameraType: String = "-",
    val sn: String = "-",
    val firmware: String = "-",
    val activated: String = "-",
    val sdTotal: String = "-",
    val sdRemaining: String = "—",
    val sdStatus: String = "-",
    val sdAvailable: Boolean = false,
    val internalTotal: String = "-",
    val internalRemaining: String = "—",
    val internalStatus: String = "-",
    val internalAvailable: Boolean = false,
    val batteryLevel: String = "-",
    val chargingStatus: String = "-",
    val cameraTime: String = "—",
)

enum class GalleryTypeFilter { All, Photo, Video }
enum class GalleryLocationFilter { Camera, Local }
