package com.insta360.kmpsdk.demo.data

import android.content.Context
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.core.model.CameraType
import com.arashivision.sdk.camera.core.model.option.BatteryData
import com.arashivision.sdk.camera.core.model.option.StorageData
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.util.formatStorageSize
import com.insta360.kmpsdk.demo.util.formatLocalTime

fun CameraDevice.toDeviceInfo(context: Context): DeviceInfo {
    val storageList = system.getStorageDataList().getOrDefault(emptyList())
    val sdStorage = storageList.sdStorageData()
    val internalStorage = storageList.internalStorageData()
    val batteryData = system.getBatteryData().getOrDefault(
            BatteryData(
                false,
                0,
                0,
                BatteryData.Type.NONE,
                0,
            ),
        )
    return DeviceInfo(
        cameraType = system.getCameraType().getOrDefault(CameraType.UNKNOWN).displayName,
        sn = system.getSerialNumber().getOrDefault("-"),
        firmware = system.getFirmwareRevision().getOrDefault("-"),
        activated = system.getActivateTime().getOrDefault(0L).formatLocalTime(),
        sdTotal = sdStorage.total.formatStorageSize(),
        sdRemaining = sdStorage.free.formatStorageSize(),
        sdStatus = sdStorage.state.toString(context),
        sdAvailable = sdStorage.state == StorageData.State.PASS,
        internalTotal = internalStorage.total.formatStorageSize(),
        internalRemaining = internalStorage.free.formatStorageSize(),
        internalStatus = internalStorage.state.toString(context),
        internalAvailable = internalStorage.state == StorageData.State.PASS,
        batteryLevel = batteryData.level.toString() + "%",
        chargingStatus = context.getString(
            if (batteryData.isCharging) R.string.charging else R.string.not_charging,
        ),
        cameraTime = system.getMediaTime().getOrDefault(0L).toString(),
    )
}

fun DeviceInfo.withBattery(context: Context, battery: BatteryData): DeviceInfo =
    copy(
        batteryLevel = "${battery.level}%",
        chargingStatus =
            context.getString(
                if (battery.isCharging) R.string.charging else R.string.not_charging,
            ),
    )

fun DeviceInfo.withStorageList(context: Context, storageList: List<StorageData>): DeviceInfo {
    val sdStorage = storageList.sdStorageData()
    val internalStorage = storageList.internalStorageData()
    return copy(
        sdTotal = sdStorage.total.formatStorageSize(),
        sdRemaining = sdStorage.free.formatStorageSize(),
        sdStatus = sdStorage.state.toString(context),
        sdAvailable = sdStorage.state == StorageData.State.PASS,
        internalTotal = internalStorage.total.formatStorageSize(),
        internalRemaining = internalStorage.free.formatStorageSize(),
        internalStatus = internalStorage.state.toString(context),
        internalAvailable = internalStorage.state == StorageData.State.PASS,
    )
}

/** SD 卡：CAMERA / SD；机内：INNER；READER 闪存伴侣忽略。 */
fun List<StorageData>.sdStorageData(): StorageData =
    firstOrNull {
        it.fileLocation == StorageData.FileLocation.SD ||
            it.fileLocation == StorageData.FileLocation.CAMERA
    } ?: defaultStorageData(StorageData.FileLocation.SD)

fun List<StorageData>.internalStorageData(): StorageData =
    firstOrNull { it.fileLocation == StorageData.FileLocation.INNER }
        ?: defaultStorageData(StorageData.FileLocation.INNER)

private fun defaultStorageData(fileLocation: StorageData.FileLocation): StorageData =
    StorageData(
        StorageData.State.OTHER_ERROR,
        0L,
        0L,
        fileLocation,
        0L,
    )

fun StorageData.State.toString(context: Context): String {
    return when (this) {
        StorageData.State.PASS -> context.getString(R.string.sd_status_available)
        StorageData.State.NO_SPACE -> context.getString(R.string.sd_status_not_present)
        StorageData.State.INVALID_FORMAT,
        StorageData.State.WP_CARD,
        StorageData.State.OTHER_ERROR,
        StorageData.State.NO_CARD -> context.getString(R.string.sd_status_not_available)
    }
}
