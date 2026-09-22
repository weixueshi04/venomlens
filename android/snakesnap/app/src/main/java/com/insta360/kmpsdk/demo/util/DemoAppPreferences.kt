package com.insta360.kmpsdk.demo.util

import android.content.Context
import android.content.SharedPreferences
import com.arashivision.sdk.common.log.LogLevel
import com.arashivision.sdk.common.log.Logger
import com.insta360.kmpsdk.demo.data.RecentBleDevice

/**
 * Demo 全局 [SharedPreferences]：仅通过本对象提供的 read/persist 方法读写，不对外暴露 [SharedPreferences] 实例。
 */
object DemoAppPreferences {

    const val FILE_NAME = "demo_app_prefs"

    /** 最近连接设备列表最多保留条数 */
    const val MAX_RECENT_DEVICES = 10

    // 单条记录字段分隔；4 个字段均不会出现这两个字符
    private const val FIELD_SEP = "|"
    private const val ENTRY_SEP = "\n"

    object Keys {
        const val LOGCAT_DUMP_ENABLED = "logcat_dump_enabled"
        const val SDK_LOG_LEVEL = "sdk_log_level"
        const val RECENT_BLE_DEVICES = "recent_ble_devices"
    }

    private fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)


    fun readLogLevel(context: Context): LogLevel {
        val prefs = get(context)
        val name = prefs.getString(DemoAppPreferences.Keys.SDK_LOG_LEVEL, null)
        return if (name != null) {
            LogLevel.valueOf(name)
        } else {
            Logger.getLogLevel()
        }
    }

    fun persistLogLevel(context: Context, level: LogLevel) {
        get(context).edit()
            .putString(DemoAppPreferences.Keys.SDK_LOG_LEVEL, level.name).apply()
    }

    fun readLogcatDumpEnabled(context: Context): Boolean {
        val prefs = get(context)
        return prefs.getBoolean(DemoAppPreferences.Keys.LOGCAT_DUMP_ENABLED, true)
    }

    fun persistLogcatDumpEnabled(context: Context, enabled: Boolean) {
        get(context).edit()
            .putBoolean(DemoAppPreferences.Keys.LOGCAT_DUMP_ENABLED, enabled)
            .apply()
    }

    fun readRecentBleDevices(context: Context): List<RecentBleDevice> {
        val raw = get(context).getString(Keys.RECENT_BLE_DEVICES, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(ENTRY_SEP).mapNotNull { line -> parseEntry(line) }
    }

    /** 按 SN 去重并插入到首位，超过上限截尾。 */
    fun prependRecentBleDevice(context: Context, device: RecentBleDevice) {
        val existing = readRecentBleDevices(context)
        val merged = buildList {
            add(device)
            existing.forEach { if (it.sn != device.sn) add(it) }
        }.take(MAX_RECENT_DEVICES)
        val serialized = merged.joinToString(ENTRY_SEP) { it.serialize() }
        get(context).edit().putString(Keys.RECENT_BLE_DEVICES, serialized).apply()
    }

    private fun RecentBleDevice.serialize(): String =
        listOf(deviceName, cameraTypeKey, sn, lastConnectedAt.toString()).joinToString(FIELD_SEP)

    private fun parseEntry(line: String): RecentBleDevice? {
        if (line.isBlank()) return null
        val parts = line.split(FIELD_SEP)
        if (parts.size != 4) return null
        val time = parts[3].toLongOrNull() ?: return null
        val deviceName = parts[0]
        val cameraTypeKey = parts[1]
        val sn = parts[2]
        if (deviceName.isBlank() || cameraTypeKey.isBlank() || sn.isBlank()) return null
        return RecentBleDevice(
            deviceName = deviceName,
            cameraTypeKey = cameraTypeKey,
            sn = sn,
            lastConnectedAt = time,
        )
    }
}
